package io.queryforge.backend.rag.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.queryforge.backend.rag.model.ChatRuntimeDtos;
import io.queryforge.backend.rag.model.QueryRouteContext;
import io.queryforge.backend.rag.model.QueryRouteDecision;
import io.queryforge.backend.rag.model.QueryStrategy;
import io.queryforge.backend.rag.model.RagDtos;
import io.queryforge.backend.rag.model.RagPersistPolicy;
import io.queryforge.backend.rag.repository.RagRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
@RequiredArgsConstructor
public class AgenticRetrievalService {

    private static final Pattern SEARCH_TOKEN_PATTERN = Pattern.compile("[@A-Za-z0-9_./:$-]{2,}|\\p{InHangulSyllables}{2,}");
    private static final Pattern TECHNICAL_TOKEN_PATTERN = Pattern.compile("[@A-Za-z_][A-Za-z0-9_./:$-]{1,}");

    private final RagRepository repository;
    private final RagTracePersistenceService ragTracePersistenceService;
    private final HashEmbeddingService embeddingService;
    private final DenseEmbeddingService denseEmbeddingService;
    private final CohereRerankService cohereRerankService;
    private final RewriteCandidateService rewriteCandidateService;
    private final QueryStrategyRouter queryStrategyRouter;
    private final AgenticQueryPlannerService plannerService;
    private final SearchResultMerger searchResultMerger;
    private final ObjectMapper objectMapper;

    public AgenticExecutionResult execute(AgenticExecutionRequest request) {
        long started = System.nanoTime();
        RetrievalRuntime retrievalRuntime = retrievalRuntime(request.config());
        long planningStarted = System.nanoTime();
        RagDtos.AgenticQueryPlan plan = plannerService.plan(
                request.rawQuery(),
                request.config(),
                domainContext(request.config()),
                request.plannerMemoryHints(),
                request.maxSubqueries()
        );
        long planningLatency = elapsedMs(planningStarted);

        List<SubqueryExecution> executions = new ArrayList<>();
        List<List<RagRepository.RetrievalDoc>> resultSets = new ArrayList<>();
        List<PersistedRewriteCandidate> persistedCandidates = new ArrayList<>();
        for (RagDtos.AgenticSubquery subquery : plan.subqueries()) {
            SubqueryExecution execution = executeSubquery(request, subquery, retrievalRuntime);
            executions.add(execution);
            resultSets.add(execution.decision().finalRetrieved());
            persistedCandidates.addAll(execution.persistedCandidates());
        }

        List<RagRepository.RetrievalDoc> mergedDocs = searchResultMerger.mergeRrf(
                resultSets,
                request.finalTopK(),
                request.rrfK()
        );
        boolean rewriteApplied = executions.stream().anyMatch(execution -> execution.decision().rewriteApplied());
        ObjectNode metadata = objectMapper.createObjectNode();
        metadata.put("planning_latency_ms", planningLatency);
        metadata.put("execution_latency_ms", elapsedMs(started));
        metadata.put("subquery_count", plan.subqueries().size());
        metadata.put("fallback_plan", plan.fallbackApplied());
        return new AgenticExecutionResult(
                plan,
                executions.stream().map(SubqueryExecution::trace).toList(),
                persistedCandidates,
                mergedDocs,
                rewriteApplied,
                "agentic_multi_query_rrf",
                null,
                planningLatency,
                elapsedMs(started),
                metadata
        );
    }

    private SubqueryExecution executeSubquery(
            AgenticExecutionRequest request,
            RagDtos.AgenticSubquery subquery,
            RetrievalRuntime retrievalRuntime
    ) {
        long started = System.nanoTime();
        String query = normalizeWhitespace(subquery.query());
        long routeStarted = System.nanoTime();
        QueryRouteDecision routeDecision = queryStrategyRouter.route(routeContext(
                query,
                request.config(),
                request.readiness(),
                request.mode(),
                request.rewriteQueryProfile(),
                request.config().rewriteAnchorInjectionEnabled(),
                false,
                false,
                null
        ));
        long routeLatency = elapsedMs(routeStarted);
        routeDecision = routeDecision.withLatency(routeLatency);
        boolean rawOnlyRoute = routeDecision.routerEnabled() && routeDecision.strategy() == QueryStrategy.RAW_ONLY;

        String queryEmbedding = embeddingLiteral(query, retrievalRuntime);
        List<RagRepository.MemoryCandidate> memories = rawOnlyRoute
                ? List.of()
                : findMemoryCandidates(
                query,
                queryEmbedding,
                request.memoryTopN(),
                request.memoryPreset(),
                request.config().domainId(),
                request.config().generationStrategies(),
                request.config().sourceGatingRunIds(),
                request.config().sourceGatingBatchIds(),
                retrievalRuntime
        );

        long retrievalStarted = System.nanoTime();
        List<RagRepository.RetrievalDoc> rawRetrievedLocal = retrieveChunks(
                query,
                queryEmbedding,
                request.retrievalTopK(),
                request.config().domainId(),
                retrievalRuntime
        );
        long rawRetrievalLatency = elapsedMs(retrievalStarted);
        persistSubqueryRetrievalTrace(
                request,
                subquery,
                query,
                retrievalRuntime,
                null,
                rawRetrievedLocal,
                rawRetrievalLatency,
                "raw",
                RagTracePersistenceService.AgenticSubqueryRetrievalTraceWriteScope.SUBQUERY_RAW_RETRIEVAL
        );
        List<RagRepository.RetrievalDoc> rawRetrieved = cohereRerankService.rerank(
                query,
                rawRetrievedLocal,
                request.rerankTopN()
        );
        double rawDense = memories.isEmpty() ? 0.0d : memories.getFirst().similarity();
        double rawConfidence = confidence(rawRetrieved, rawDense);

        if (routeDecision.routerEnabled() && !rawOnlyRoute) {
            routeStarted = System.nanoTime();
            QueryRouteDecision refined = queryStrategyRouter.route(routeContext(
                    query,
                    request.config(),
                    request.readiness(),
                    request.mode(),
                    request.rewriteQueryProfile(),
                    request.config().rewriteAnchorInjectionEnabled(),
                    true,
                    !memories.isEmpty(),
                    rawConfidence
            ));
            routeLatency += elapsedMs(routeStarted);
            routeDecision = refined.withLatency(routeLatency);
            rawOnlyRoute = routeDecision.strategy() == QueryStrategy.RAW_ONLY;
        }

        List<GeneratedCandidate> generatedCandidates = new ArrayList<>();
        List<PersistedRewriteCandidate> persistedCandidates = new ArrayList<>();
        if (!rawOnlyRoute) {
            List<RewriteCandidateService.CandidateTemplate> templates = rewriteCandidateService.buildCandidates(
                    query,
                    request.sessionContext(),
                    memories,
                    request.candidateCount(),
                    routeDecision.rewriteQueryProfile(),
                    routeDecision.anchorInjectionEnabled(),
                    domainContext(request.config())
            );
            for (int index = 0; index < templates.size(); index++) {
                RewriteCandidateService.CandidateTemplate template = templates.get(index);
                String candidateEmbedding = embeddingLiteral(template.query(), retrievalRuntime);
                retrievalStarted = System.nanoTime();
                List<RagRepository.RetrievalDoc> candidateRetrievedLocal = retrieveChunks(
                        template.query(),
                        candidateEmbedding,
                        request.retrievalTopK(),
                        request.config().domainId(),
                        retrievalRuntime
                );
                long candidateRetrievalLatency = elapsedMs(retrievalStarted);
                List<RagRepository.RetrievalDoc> candidateRetrieved = cohereRerankService.rerank(
                        template.query(),
                        candidateRetrievedLocal,
                        request.rerankTopN()
                );
                double candidateConfidence = confidence(candidateRetrieved, rawDense);
                int candidateRank = subquery.index() * 10 + index + 1;
                String candidateLabel = subqueryLabel(subquery, template.label());
                JsonNode candidateScoreBreakdown = scoreBreakdown(candidateRetrieved, memories);
                UUID candidateId = ragTracePersistenceService.createAgenticRewriteCandidateTrace(
                        new RagTracePersistenceService.AgenticRewriteCandidateTracePersistenceRequest(
                                RagPersistPolicy.ONLINE_QUERY,
                                request.onlineQueryId(),
                                RagTracePersistenceService.AgenticRetrievalExecutionKind.AGENTIC_MULTI_QUERY,
                                subquery.index(),
                                query,
                                candidateRank,
                                candidateLabel,
                                template.query(),
                                agenticCandidateMetadata(subquery, template.label()),
                                memorySourceIds(memories),
                                objectMapper.valueToTree(candidateRetrieved),
                                candidateConfidence,
                                candidateScoreBreakdown
                        )
                ).rewriteCandidateId();
                persistSubqueryRetrievalTrace(
                        request,
                        subquery,
                        query,
                        retrievalRuntime,
                        candidateId,
                        candidateRetrievedLocal,
                        candidateRetrievalLatency,
                        "rewrite_candidate",
                        RagTracePersistenceService.AgenticSubqueryRetrievalTraceWriteScope.SUBQUERY_CANDIDATE_RETRIEVAL
                );
                generatedCandidates.add(new GeneratedCandidate(
                        candidateLabel,
                        template.query(),
                        candidateRetrieved,
                        candidateConfidence,
                        candidateId,
                        candidateScoreBreakdown
                ));
            }
        }

        Decision decision = rawOnlyRoute
                ? routerRawOnlyDecision(query, rawRetrieved, routeDecision)
                : decide(
                request.mode(),
                query,
                rawConfidence,
                rawRetrieved,
                memories,
                generatedCandidates,
                request.threshold(),
                request.config().domainId(),
                retrievalRuntime
        );
        for (GeneratedCandidate candidate : generatedCandidates) {
            boolean selected = decision.selectedCandidateId() != null
                    && decision.selectedCandidateId().equals(candidate.rewriteCandidateId());
            ragTracePersistenceService.markAgenticRewriteCandidateAdopted(
                    new RagTracePersistenceService.AgenticRewriteCandidateAdoptionPersistenceRequest(
                            RagPersistPolicy.ONLINE_QUERY,
                            request.onlineQueryId(),
                            candidate.rewriteCandidateId(),
                            RagTracePersistenceService.AgenticRetrievalExecutionKind.AGENTIC_MULTI_QUERY,
                            subquery.index(),
                            selected,
                            selected ? null : decision.rejectedReason()
                    )
            );
            persistedCandidates.add(new PersistedRewriteCandidate(
                    candidate.rewriteCandidateId(),
                    candidate.label(),
                    candidate.query(),
                    candidate.retrieved(),
                    candidate.confidence(),
                    selected,
                    selected ? null : decision.rejectedReason(),
                    candidate.scoreBreakdown()
            ));
        }

        RagDtos.SubqueryRetrievalTrace trace = new RagDtos.SubqueryRetrievalTrace(
                subquery.index(),
                query,
                decision.finalQuery(),
                decision.rewriteApplied(),
                routeDecision.strategy().name(),
                decision.selectedReason(),
                decision.rejectedReason(),
                toScoredDocs(decision.finalRetrieved()),
                toRewriteDtos(generatedCandidates, decision.selectedCandidateId()),
                objectMapper.valueToTree(memories),
                elapsedMs(started),
                traceMetadata(routeDecision, rawConfidence, decision, memories.size())
        );
        return new SubqueryExecution(trace, decision, persistedCandidates);
    }

    private void persistSubqueryRetrievalTrace(
            AgenticExecutionRequest request,
            RagDtos.AgenticSubquery subquery,
            String subqueryText,
            RetrievalRuntime retrievalRuntime,
            UUID rewriteCandidateId,
            List<RagRepository.RetrievalDoc> retrievedDocs,
            long latencyMs,
            String phase,
            RagTracePersistenceService.AgenticSubqueryRetrievalTraceWriteScope writeScope
    ) {
        ragTracePersistenceService.persistAgenticSubqueryRetrievalTrace(
                new RagTracePersistenceService.AgenticSubqueryRetrievalTracePersistenceRequest(
                        RagPersistPolicy.ONLINE_QUERY,
                        request.onlineQueryId(),
                        RagTracePersistenceService.AgenticRetrievalExecutionKind.AGENTIC_MULTI_QUERY,
                        subquery.index(),
                        subqueryText,
                        request.mode(),
                        rewriteCandidateId,
                        retrievedDocs,
                        retrievalMetadata(retrievalRuntime, subquery, phase),
                        retrievalRuntime.retrieverName(),
                        latencyMs,
                        writeScope
                )
        );
    }

    private Decision decide(
            String mode,
            String rawQuery,
            double rawConfidence,
            List<RagRepository.RetrievalDoc> rawRetrieved,
            List<RagRepository.MemoryCandidate> memoryCandidates,
            List<GeneratedCandidate> candidates,
            double threshold,
            UUID domainId,
            RetrievalRuntime retrievalRuntime
    ) {
        if ("raw_only".equals(mode)) {
            return new Decision(rawQuery, false, rawRetrieved, null, "raw_only", "mode=raw_only");
        }
        if ("memory_only_ungated".equals(mode) || "memory_only_gated".equals(mode)) {
            if (memoryCandidates.isEmpty()) {
                return new Decision(rawQuery, false, rawRetrieved, null, "memory_empty", "no memory candidate");
            }
            String memoryQuery = memoryCandidates.getFirst().queryText();
            String embedding = embeddingLiteral(memoryQuery, retrievalRuntime);
            List<RagRepository.RetrievalDoc> retrievedLocal = retrieveChunks(
                    memoryQuery,
                    embedding,
                    Math.max(20, rawRetrieved.size()),
                    domainId,
                    retrievalRuntime
            );
            List<RagRepository.RetrievalDoc> retrieved = cohereRerankService.rerank(
                    memoryQuery,
                    retrievedLocal,
                    rawRetrieved.size()
            );
            return new Decision(memoryQuery, true, retrieved, null, "memory_only", null);
        }

        GeneratedCandidate best = candidates.stream()
                .max(Comparator.comparingDouble(GeneratedCandidate::confidence))
                .orElse(null);
        if (best == null) {
            return new Decision(rawQuery, false, rawRetrieved, null, "no_candidate", "rewrite candidate missing");
        }
        if ("rewrite_always".equals(mode)) {
            return new Decision(best.query(), true, best.retrieved(), best.rewriteCandidateId(), "forced", null);
        }
        double delta = best.confidence() - rawConfidence;
        if (delta >= threshold) {
            return new Decision(best.query(), true, best.retrieved(), best.rewriteCandidateId(), "delta_above_threshold", null);
        }
        return new Decision(rawQuery, false, rawRetrieved, null, "delta_below_threshold", "best-candidate delta below threshold");
    }

    private Decision routerRawOnlyDecision(
            String rawQuery,
            List<RagRepository.RetrievalDoc> rawRetrieved,
            QueryRouteDecision routeDecision
    ) {
        String rejectedReason = routeDecision.fallbackApplied()
                ? routeDecision.fallbackReason()
                : "query_router_strategy=" + routeDecision.strategy().name().toLowerCase(Locale.ROOT);
        return new Decision(rawQuery, false, rawRetrieved, null, routeDecision.reason(), rejectedReason);
    }

    private String subqueryLabel(RagDtos.AgenticSubquery subquery, String label) {
        String suffix = label == null || label.isBlank() ? "candidate" : label.trim();
        return "subquery_" + subquery.index() + "_" + suffix;
    }

    private ObjectNode agenticCandidateMetadata(RagDtos.AgenticSubquery subquery, String templateLabel) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("agentic_multi_query", true);
        node.put("subquery_index", subquery.index());
        node.put("subquery", subquery.query());
        node.put("subquery_intent", subquery.intent());
        node.put("candidate_template_label", templateLabel == null ? "" : templateLabel);
        return node;
    }

    private RetrievalRuntime retrievalRuntime(ChatRuntimeDtos.ChatRuntimeConfigResponse config) {
        String backend = config.retrievalBackend() == null || config.retrievalBackend().isBlank()
                ? "local"
                : config.retrievalBackend().trim().toLowerCase(Locale.ROOT).replace("-", "_");
        String mode = config.retrieverMode() == null || config.retrieverMode().isBlank()
                ? "hybrid"
                : config.retrieverMode().trim().toLowerCase(Locale.ROOT).replace("-", "_");
        String denseModel = config.denseEmbeddingModel() == null || config.denseEmbeddingModel().isBlank()
                ? "intfloat/multilingual-e5-small"
                : config.denseEmbeddingModel().trim();
        int candidatePoolK = Math.max(1, config.retrieverCandidatePoolK());
        double denseWeight = clampWeight(config.retrieverDenseWeight());
        double bm25Weight = clampWeight(config.retrieverBm25Weight());
        double technicalWeight = clampWeight(config.retrieverTechnicalWeight());
        if ("bm25_only".equals(mode)) {
            denseWeight = 0.0d;
            bm25Weight = 1.0d;
            technicalWeight = 0.0d;
        } else if ("dense_only".equals(mode)) {
            denseWeight = 1.0d;
            bm25Weight = 0.0d;
            technicalWeight = 0.0d;
        } else {
            double sum = denseWeight + bm25Weight + technicalWeight;
            if (sum <= 0.0d) {
                denseWeight = 0.60d;
                bm25Weight = 0.32d;
                technicalWeight = 0.08d;
                sum = denseWeight + bm25Weight + technicalWeight;
            }
            denseWeight = denseWeight / sum;
            bm25Weight = bm25Weight / sum;
            technicalWeight = technicalWeight / sum;
        }
        return new RetrievalRuntime(backend, denseModel, mode, candidatePoolK, denseWeight, bm25Weight, technicalWeight);
    }

    private String embeddingLiteral(String query, RetrievalRuntime runtime) {
        if ("db_ann".equals(runtime.retrievalBackend())) {
            return embeddingService.toHalfvecLiteral(denseEmbeddingService.embedQuery(
                    query,
                    runtime.retrieverMode(),
                    runtime.denseEmbeddingModel()
            ));
        }
        return embeddingService.toHalfvecLiteral(embeddingService.embed(query));
    }

    private List<RagRepository.RetrievalDoc> retrieveChunks(
            String query,
            String queryEmbeddingLiteral,
            int topK,
            UUID domainId,
            RetrievalRuntime runtime
    ) {
        int poolSize = Math.max(topK, runtime.candidatePoolK());
        List<RagRepository.RetrievalDoc> candidates = new ArrayList<>();
        List<String> patterns = searchPatterns(query);
        if ("db_ann".equals(runtime.retrievalBackend())) {
            if (!"bm25_only".equals(runtime.retrieverMode())) {
                candidates.addAll(repository.findDbAnnChunkDensePool(
                        queryEmbeddingLiteral,
                        runtime.denseEmbeddingModel(),
                        poolSize,
                        domainId
                ));
            }
            if (!"dense_only".equals(runtime.retrieverMode())) {
                candidates.addAll(repository.findDbAnnChunkTextPool(
                        queryEmbeddingLiteral,
                        runtime.denseEmbeddingModel(),
                        patterns,
                        poolSize,
                        domainId
                ));
            }
        } else {
            if (!"bm25_only".equals(runtime.retrieverMode())) {
                candidates.addAll(repository.findTopChunksByEmbedding(queryEmbeddingLiteral, poolSize, domainId));
            }
            if (!"dense_only".equals(runtime.retrieverMode())) {
                candidates.addAll(repository.findChunkTextPool(patterns, poolSize, domainId));
            }
        }
        if (candidates.isEmpty()) {
            return List.of();
        }
        return rankDocs(query, mergeDocs(candidates), topK, runtime);
    }

    private List<RagRepository.MemoryCandidate> findMemoryCandidates(
            String query,
            String queryEmbeddingLiteral,
            int topN,
            String gatingPreset,
            UUID domainId,
            List<String> generationStrategies,
            List<UUID> sourceGatingRunIds,
            List<UUID> sourceGatingBatchIds,
            RetrievalRuntime runtime
    ) {
        if (!"db_ann".equals(runtime.retrievalBackend())) {
            return repository.findMemoryTopN(
                    queryEmbeddingLiteral,
                    topN,
                    gatingPreset,
                    domainId,
                    generationStrategies,
                    sourceGatingRunIds,
                    sourceGatingBatchIds
            );
        }
        int poolSize = Math.max(topN, runtime.candidatePoolK());
        List<RagRepository.MemoryCandidate> candidates = new ArrayList<>();
        if (!"bm25_only".equals(runtime.retrieverMode())) {
            candidates.addAll(repository.findMemoryDensePool(
                    queryEmbeddingLiteral,
                    runtime.denseEmbeddingModel(),
                    poolSize,
                    gatingPreset,
                    domainId,
                    generationStrategies,
                    sourceGatingRunIds,
                    sourceGatingBatchIds
            ));
        }
        if (!"dense_only".equals(runtime.retrieverMode())) {
            candidates.addAll(repository.findMemoryTextPool(
                    queryEmbeddingLiteral,
                    runtime.denseEmbeddingModel(),
                    searchPatterns(query),
                    poolSize,
                    gatingPreset,
                    domainId,
                    generationStrategies,
                    sourceGatingRunIds,
                    sourceGatingBatchIds
            ));
        }
        return rankMemories(query, mergeMemories(candidates), topN, runtime);
    }

    private List<RagRepository.RetrievalDoc> mergeDocs(List<RagRepository.RetrievalDoc> docs) {
        Map<String, RagRepository.RetrievalDoc> merged = new LinkedHashMap<>();
        for (RagRepository.RetrievalDoc doc : docs) {
            RagRepository.RetrievalDoc current = merged.get(doc.chunkId());
            if (current == null || doc.score() > current.score()) {
                merged.put(doc.chunkId(), doc);
            }
        }
        return new ArrayList<>(merged.values());
    }

    private List<RagRepository.MemoryCandidate> mergeMemories(List<RagRepository.MemoryCandidate> memories) {
        Map<UUID, RagRepository.MemoryCandidate> merged = new LinkedHashMap<>();
        for (RagRepository.MemoryCandidate memory : memories) {
            RagRepository.MemoryCandidate current = merged.get(memory.memoryId());
            if (current == null || memory.similarity() > current.similarity()) {
                merged.put(memory.memoryId(), memory);
            }
        }
        return new ArrayList<>(merged.values());
    }

    private List<RagRepository.RetrievalDoc> rankDocs(
            String query,
            List<RagRepository.RetrievalDoc> candidates,
            int topK,
            RetrievalRuntime runtime
    ) {
        return candidates.stream()
                .map(doc -> new RagRepository.RetrievalDoc(
                        doc.documentId(),
                        doc.chunkId(),
                        doc.chunkText(),
                        fusedScore(query, doc.chunkText(), doc.score(), runtime)
                ))
                .sorted(Comparator
                        .comparingDouble(RagRepository.RetrievalDoc::score)
                        .reversed()
                        .thenComparing(RagRepository.RetrievalDoc::chunkId))
                .limit(Math.max(1, topK))
                .toList();
    }

    private List<RagRepository.MemoryCandidate> rankMemories(
            String query,
            List<RagRepository.MemoryCandidate> candidates,
            int topN,
            RetrievalRuntime runtime
    ) {
        return candidates.stream()
                .map(candidate -> withSimilarity(
                        candidate,
                        fusedScore(query, memoryLexicalText(candidate), candidate.similarity(), runtime)
                ))
                .sorted(Comparator
                        .comparingDouble(RagRepository.MemoryCandidate::similarity)
                        .reversed()
                        .thenComparing(memory -> memory.memoryId().toString()))
                .limit(Math.max(1, topN))
                .toList();
    }

    private double fusedScore(String query, String text, double denseScore, RetrievalRuntime runtime) {
        double dense = normalizeScore(denseScore);
        double lexical = lexicalOverlap(query, text);
        double technical = technicalOverlap(query, text);
        double combined = (runtime.denseWeight() * dense)
                + (runtime.bm25Weight() * lexical)
                + (runtime.technicalWeight() * technical);
        return Math.max(-1.0d, Math.min(1.0d, (combined * 2.0d) - 1.0d));
    }

    private RagRepository.MemoryCandidate withSimilarity(RagRepository.MemoryCandidate source, double similarity) {
        return new RagRepository.MemoryCandidate(
                source.memoryId(),
                source.queryText(),
                source.targetDocId(),
                source.targetChunkIds(),
                source.glossaryTerms(),
                source.metadata(),
                similarity,
                source.generationStrategy(),
                source.generationBatchId(),
                source.domainId(),
                source.sourceGatedQueryId(),
                source.sourceGateRunId(),
                source.sourceGatingBatchId()
        );
    }

    private String memoryLexicalText(RagRepository.MemoryCandidate candidate) {
        return String.join(
                " ",
                candidate.queryText() == null ? "" : candidate.queryText(),
                candidate.glossaryTerms() == null ? "" : candidate.glossaryTerms().toString(),
                candidate.metadata() == null ? "" : candidate.metadata().toString()
        );
    }

    private ObjectNode retrievalMetadata(
            RetrievalRuntime runtime,
            RagDtos.AgenticSubquery subquery,
            String phase
    ) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("retrieval_backend", runtime.retrievalBackend());
        node.put("dense_embedding_model", runtime.denseEmbeddingModel());
        node.put("retriever_mode", runtime.retrieverMode());
        node.put("retriever_candidate_pool_k", runtime.candidatePoolK());
        ObjectNode weights = node.putObject("retriever_fusion_weights");
        weights.put("dense", runtime.denseWeight());
        weights.put("bm25", runtime.bm25Weight());
        weights.put("technical", runtime.technicalWeight());
        node.put("agentic_multi_query", true);
        node.put("subquery_index", subquery.index());
        node.put("subquery", subquery.query());
        node.put("subquery_intent", subquery.intent());
        node.put("agentic_phase", phase);
        return node;
    }

    private ObjectNode traceMetadata(
            QueryRouteDecision routeDecision,
            double rawConfidence,
            Decision decision,
            int memoryCandidateCount
    ) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("raw_confidence", rawConfidence);
        node.put("memory_candidate_count", memoryCandidateCount);
        node.put("selected_candidate_id", decision.selectedCandidateId() == null ? "" : decision.selectedCandidateId().toString());
        node.put("router_enabled", routeDecision.routerEnabled());
        node.put("route_reason", routeDecision.reason());
        node.put("route_fallback_applied", routeDecision.fallbackApplied());
        if (routeDecision.fallbackReason() != null) {
            node.put("route_fallback_reason", routeDecision.fallbackReason());
        }
        node.set("route_metadata", objectMapper.valueToTree(routeDecision.metadata()));
        return node;
    }

    private QueryRouteContext routeContext(
            String rawQuery,
            ChatRuntimeDtos.ChatRuntimeConfigResponse config,
            ChatRuntimeDtos.ChatDomainReadinessResponse readiness,
            String mode,
            String rewriteQueryProfile,
            boolean anchorInjectionEnabled,
            boolean memoryCandidatesKnown,
            boolean memoryCandidatesAvailable,
            Double rawRetrievalConfidence
    ) {
        return new QueryRouteContext(
                rawQuery,
                config.domainId(),
                config,
                readiness,
                mode,
                rewriteQueryProfile,
                anchorInjectionEnabled,
                rawQuery == null ? 0 : rawQuery.length(),
                queryTokenCount(rawQuery),
                containsKorean(rawQuery),
                containsEnglish(rawQuery),
                containsTechnicalAnchor(rawQuery),
                memoryCandidatesKnown,
                memoryCandidatesAvailable,
                rawRetrievalConfidence,
                true
        );
    }

    private ObjectNode domainContext(ChatRuntimeDtos.ChatRuntimeConfigResponse config) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("current_technical_domain", config.displayName());
        node.put("domain_key", config.domainKey());
        node.put("source_language", config.sourceLanguage() == null ? "" : config.sourceLanguage());
        node.put(
                "rewrite_instruction",
                "Keep the query inside the " + config.displayName()
                        + " documentation domain and do not add anchors from other domains."
        );
        return node;
    }

    private JsonNode scoreBreakdown(List<RagRepository.RetrievalDoc> retrieved, List<RagRepository.MemoryCandidate> memories) {
        ObjectNode node = objectMapper.createObjectNode();
        double dense = memories.isEmpty() ? 0.0d : memories.getFirst().similarity();
        node.put("r1", retrieved.isEmpty() ? 0.0d : normalizeScore(retrieved.getFirst().score()));
        node.put(
                "r3",
                retrieved.isEmpty()
                        ? 0.0d
                        : retrieved.stream().limit(3).mapToDouble(item -> normalizeScore(item.score())).average().orElse(0.0d)
        );
        node.put("dense", normalizeScore(dense));
        return node;
    }

    private JsonNode memorySourceIds(List<RagRepository.MemoryCandidate> memories) {
        ArrayNode array = objectMapper.createArrayNode();
        for (RagRepository.MemoryCandidate memory : memories) {
            array.add(memory.memoryId().toString());
        }
        return array;
    }

    private List<RagDtos.RewriteCandidateDto> toRewriteDtos(
            List<GeneratedCandidate> candidates,
            UUID selectedCandidateId
    ) {
        return candidates.stream()
                .map(candidate -> new RagDtos.RewriteCandidateDto(
                        candidate.rewriteCandidateId(),
                        candidate.label(),
                        candidate.query(),
                        candidate.confidence(),
                        selectedCandidateId != null && selectedCandidateId.equals(candidate.rewriteCandidateId()),
                        selectedCandidateId != null && !selectedCandidateId.equals(candidate.rewriteCandidateId())
                                ? "not_selected"
                                : null,
                        objectMapper.valueToTree(candidate.retrieved()),
                        candidate.scoreBreakdown()
                ))
                .toList();
    }

    private List<RagDtos.ScoredDocumentDto> toScoredDocs(List<RagRepository.RetrievalDoc> docs) {
        return docs.stream()
                .map(doc -> new RagDtos.ScoredDocumentDto(
                        doc.documentId(),
                        doc.chunkId(),
                        preview(doc.chunkText()),
                        doc.score()
                ))
                .toList();
    }

    private double confidence(List<RagRepository.RetrievalDoc> docs, double denseScore) {
        if (docs.isEmpty()) {
            return 0.0d;
        }
        double r1 = normalizeScore(docs.getFirst().score());
        int topN = Math.min(3, docs.size());
        double r3 = 0.0d;
        for (int index = 0; index < topN; index++) {
            r3 += normalizeScore(docs.get(index).score());
        }
        r3 /= topN;
        double dense = normalizeScore(denseScore);
        return (0.6d * r1) + (0.3d * r3) + (0.1d * dense);
    }

    private List<String> searchPatterns(String query) {
        LinkedHashSet<String> tokens = new LinkedHashSet<>();
        Matcher matcher = SEARCH_TOKEN_PATTERN.matcher(query == null ? "" : query);
        while (matcher.find() && tokens.size() < 16) {
            String value = matcher.group().trim().toLowerCase(Locale.ROOT);
            if (value.length() >= 2) {
                tokens.add(value);
            }
        }
        return List.copyOf(tokens);
    }

    private double lexicalOverlap(String query, String text) {
        List<String> tokens = searchPatterns(query);
        if (tokens.isEmpty() || text == null || text.isBlank()) {
            return 0.0d;
        }
        String normalizedText = text.toLowerCase(Locale.ROOT);
        int hits = 0;
        for (String token : tokens) {
            if (normalizedText.contains(token)) {
                hits++;
            }
        }
        return (double) hits / tokens.size();
    }

    private double technicalOverlap(String query, String text) {
        if (query == null || query.isBlank() || text == null || text.isBlank()) {
            return 0.0d;
        }
        LinkedHashSet<String> tokens = new LinkedHashSet<>();
        Matcher matcher = TECHNICAL_TOKEN_PATTERN.matcher(query);
        while (matcher.find() && tokens.size() < 12) {
            tokens.add(matcher.group().toLowerCase(Locale.ROOT));
        }
        if (tokens.isEmpty()) {
            return 0.0d;
        }
        String normalizedText = text.toLowerCase(Locale.ROOT);
        int hits = 0;
        for (String token : tokens) {
            if (normalizedText.contains(token)) {
                hits++;
            }
        }
        return (double) hits / tokens.size();
    }

    private double clampWeight(double value) {
        return Math.max(0.0d, Math.min(1.0d, value));
    }

    private double normalizeScore(double score) {
        return Math.max(0.0d, Math.min(1.0d, (score + 1.0d) / 2.0d));
    }

    private int queryTokenCount(String query) {
        Matcher matcher = SEARCH_TOKEN_PATTERN.matcher(query == null ? "" : query);
        int count = 0;
        while (matcher.find()) {
            count++;
        }
        return count;
    }

    private boolean containsKorean(String query) {
        return query != null && query.codePoints().anyMatch(codePoint -> codePoint >= 0xAC00 && codePoint <= 0xD7A3);
    }

    private boolean containsEnglish(String query) {
        return query != null && query.codePoints().anyMatch(codePoint ->
                (codePoint >= 'A' && codePoint <= 'Z') || (codePoint >= 'a' && codePoint <= 'z'));
    }

    private boolean containsTechnicalAnchor(String query) {
        if (query == null || query.isBlank()) {
            return false;
        }
        Matcher matcher = TECHNICAL_TOKEN_PATTERN.matcher(query);
        while (matcher.find()) {
            String token = matcher.group();
            if (token.contains(".")
                    || token.contains("/")
                    || token.contains(":")
                    || token.contains("$")
                    || token.contains("-")
                    || token.matches(".*[A-Z].*[A-Z].*")
                    || token.matches(".*\\d.*")
                    || token.endsWith("Exception")
                    || token.endsWith("Error")) {
                return true;
            }
        }
        return false;
    }

    private String normalizeWhitespace(String value) {
        return value == null ? "" : value.trim().replaceAll("\\s+", " ");
    }

    private String preview(String text) {
        if (text == null) {
            return "";
        }
        String normalized = text.replaceAll("\\s+", " ").trim();
        if (normalized.length() <= 220) {
            return normalized;
        }
        return normalized.substring(0, 220) + "...";
    }

    private long elapsedMs(long startedNano) {
        return (System.nanoTime() - startedNano) / 1_000_000L;
    }

    public record AgenticExecutionRequest(
            String rawQuery,
            UUID onlineQueryId,
            ChatRuntimeDtos.ChatRuntimeConfigResponse config,
            ChatRuntimeDtos.ChatDomainReadinessResponse readiness,
            JsonNode sessionContext,
            List<RagRepository.MemoryCandidate> plannerMemoryHints,
            String mode,
            String rewriteQueryProfile,
            String memoryPreset,
            int retrievalTopK,
            int rerankTopN,
            int memoryTopN,
            int candidateCount,
            double threshold,
            int maxSubqueries,
            int rrfK,
            int finalTopK
    ) {
    }

    public record AgenticExecutionResult(
            RagDtos.AgenticQueryPlan plan,
            List<RagDtos.SubqueryRetrievalTrace> traces,
            List<PersistedRewriteCandidate> persistedCandidates,
            List<RagRepository.RetrievalDoc> mergedDocs,
            boolean rewriteApplied,
            String selectedReason,
            String rejectedReason,
            long planningLatencyMs,
            long totalLatencyMs,
            JsonNode metadata
    ) {
    }

    public record PersistedRewriteCandidate(
            UUID rewriteCandidateId,
            String label,
            String query,
            List<RagRepository.RetrievalDoc> retrieved,
            double confidence,
            boolean selected,
            String rejectedReason,
            JsonNode scoreBreakdown
    ) {
    }

    private record SubqueryExecution(
            RagDtos.SubqueryRetrievalTrace trace,
            Decision decision,
            List<PersistedRewriteCandidate> persistedCandidates
    ) {
    }

    private record GeneratedCandidate(
            String label,
            String query,
            List<RagRepository.RetrievalDoc> retrieved,
            double confidence,
            UUID rewriteCandidateId,
            JsonNode scoreBreakdown
    ) {
    }

    private record Decision(
            String finalQuery,
            boolean rewriteApplied,
            List<RagRepository.RetrievalDoc> finalRetrieved,
            UUID selectedCandidateId,
            String selectedReason,
            String rejectedReason
    ) {
    }

    private record RetrievalRuntime(
            String retrievalBackend,
            String denseEmbeddingModel,
            String retrieverMode,
            int candidatePoolK,
            double denseWeight,
            double bm25Weight,
            double technicalWeight
    ) {
        String retrieverName() {
            if ("db_ann".equals(retrievalBackend)) {
                return "db-ann:" + retrieverMode + ":" + denseEmbeddingModel;
            }
            return "local:" + retrieverMode + ":hash-embedding-v1";
        }
    }
}
