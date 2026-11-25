package io.queryforge.backend.rag.retrieval.agentic.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.queryforge.backend.rag.retrieval.agentic.dto.AgenticExecutionDtos.AgenticExecutionRequest;
import io.queryforge.backend.rag.retrieval.agentic.dto.AgenticExecutionDtos.AgenticExecutionResult;
import io.queryforge.backend.rag.retrieval.agentic.dto.AgenticExecutionDtos.PersistedRewriteCandidate;
import io.queryforge.backend.rag.retrieval.agentic.dto.AgenticRetrievalDtos;
import io.queryforge.backend.rag.retrieval.agentic.fusion.service.SearchResultMerger;
import io.queryforge.backend.rag.retrieval.agentic.planning.dto.AgenticPlanningDtos;
import io.queryforge.backend.rag.retrieval.agentic.planning.service.AgenticQueryPlannerService;
import io.queryforge.backend.rag.retrieval.candidate.dto.RetrievalDtos.RetrievalRuntime;
import io.queryforge.backend.rag.retrieval.candidate.dto.RetrievalDtos;
import io.queryforge.backend.rag.retrieval.candidate.model.RetrievalDoc;
import io.queryforge.backend.rag.retrieval.candidate.service.DomainScopedRetrievalService;
import io.queryforge.backend.rag.retrieval.execution.model.RagPersistPolicy;
import io.queryforge.backend.rag.retrieval.memory.model.MemoryCandidate;
import io.queryforge.backend.rag.retrieval.rerank.service.CohereRerankService;
import io.queryforge.backend.rag.retrieval.rewrite.dto.RewriteDtos;
import io.queryforge.backend.rag.retrieval.rewrite.service.RewriteCandidateService;
import io.queryforge.backend.rag.retrieval.routing.dto.QueryRouteContext;
import io.queryforge.backend.rag.retrieval.routing.dto.QueryRouteDecision;
import io.queryforge.backend.rag.retrieval.routing.model.QueryStrategy;
import io.queryforge.backend.rag.retrieval.routing.service.QueryStrategyRouter;
import io.queryforge.backend.rag.runtime.configuration.dto.ChatConfigDtos;
import io.queryforge.backend.rag.runtime.readiness.dto.ChatReadinessDtos;
import io.queryforge.backend.rag.trace.write.agentic.dto.AgenticTraceWriteDtos;
import io.queryforge.backend.rag.trace.write.agentic.service.AgenticTracePersistenceService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
@RequiredArgsConstructor
public class AgenticRetrievalService {

    private static final Pattern SEARCH_TOKEN_PATTERN = Pattern.compile("[@A-Za-z0-9_./:$-]{2,}|\\p{InHangulSyllables}{2,}");
    private static final Pattern TECHNICAL_TOKEN_PATTERN = Pattern.compile("[@A-Za-z_][A-Za-z0-9_./:$-]{1,}");
    private final DomainScopedRetrievalService domainScopedRetrievalService;
    private final AgenticTracePersistenceService agenticTracePersistenceService;
    private final CohereRerankService cohereRerankService;
    private final RewriteCandidateService rewriteCandidateService;
    private final QueryStrategyRouter queryStrategyRouter;
    private final AgenticQueryPlannerService plannerService;
    private final SearchResultMerger searchResultMerger;
    private final ObjectMapper objectMapper;

    public AgenticExecutionResult execute(AgenticExecutionRequest request) {
        long started = System.nanoTime();
        RetrievalRuntime retrievalRuntime = domainScopedRetrievalService.retrievalRuntime(request.config());
        long planningStarted = System.nanoTime();
        AgenticPlanningDtos.AgenticQueryPlan plan = plannerService.plan(
                request.rawQuery(),
                request.config(),
                domainContext(request.config()),
                request.plannerMemoryHints(),
                request.maxSubqueries()
        );
        long planningLatency = elapsedMs(planningStarted);

        List<SubqueryExecution> executions = new ArrayList<>();
        List<List<RetrievalDoc>> resultSets = new ArrayList<>();
        List<PersistedRewriteCandidate> persistedCandidates = new ArrayList<>();
        for (AgenticPlanningDtos.AgenticSubquery subquery : plan.subqueries()) {
            SubqueryExecution execution = executeSubquery(request, subquery, retrievalRuntime);
            executions.add(execution);
            resultSets.add(execution.decision().finalRetrieved());
            persistedCandidates.addAll(execution.persistedCandidates());
        }

        List<RetrievalDoc> mergedDocs = searchResultMerger.mergeRrf(
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
            AgenticPlanningDtos.AgenticSubquery subquery,
            RetrievalRuntime retrievalRuntime
    ) {
        long started = System.nanoTime();
        RagPersistPolicy persistPolicy = request.persistPolicy();
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

        String queryEmbedding = domainScopedRetrievalService.embeddingLiteral(query, retrievalRuntime);
        List<MemoryCandidate> memories = rawOnlyRoute
                ? List.of()
                : domainScopedRetrievalService.findMemoryCandidates(
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
        List<RetrievalDoc> rawRetrievedLocal = domainScopedRetrievalService.retrieveChunks(
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
                AgenticTraceWriteDtos.AgenticSubqueryRetrievalTraceWriteScope.SUBQUERY_RAW_RETRIEVAL
        );
        List<RetrievalDoc> rawRetrieved = cohereRerankService.rerank(
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
            List<RewriteDtos.CandidateTemplate> templates = rewriteCandidateService.buildCandidates(
                    query,
                    request.sessionContext(),
                    memories,
                    request.candidateCount(),
                    routeDecision.rewriteQueryProfile(),
                    routeDecision.anchorInjectionEnabled(),
                    domainContext(request.config())
            );
            for (int index = 0; index < templates.size(); index++) {
                RewriteDtos.CandidateTemplate template = templates.get(index);
                String candidateEmbedding = domainScopedRetrievalService.embeddingLiteral(template.query(), retrievalRuntime);
                retrievalStarted = System.nanoTime();
                List<RetrievalDoc> candidateRetrievedLocal = domainScopedRetrievalService.retrieveChunks(
                        template.query(),
                        candidateEmbedding,
                        request.retrievalTopK(),
                        request.config().domainId(),
                        retrievalRuntime
                );
                long candidateRetrievalLatency = elapsedMs(retrievalStarted);
                List<RetrievalDoc> candidateRetrieved = cohereRerankService.rerank(
                        template.query(),
                        candidateRetrievedLocal,
                        request.rerankTopN()
                );
                double candidateConfidence = confidence(candidateRetrieved, rawDense);
                int candidateRank = subquery.index() * 10 + index + 1;
                String candidateLabel = subqueryLabel(subquery, template.label());
                JsonNode candidateScoreBreakdown = scoreBreakdown(candidateRetrieved, memories);
                UUID candidateId = agenticTracePersistenceService.createAgenticRewriteCandidateTrace(
                        new AgenticTraceWriteDtos.AgenticRewriteCandidateTracePersistenceRequest(
                                persistPolicy,
                                request.onlineQueryId(),
                                AgenticTraceWriteDtos.AgenticRetrievalExecutionKind.AGENTIC_MULTI_QUERY,
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
                        AgenticTraceWriteDtos.AgenticSubqueryRetrievalTraceWriteScope.SUBQUERY_CANDIDATE_RETRIEVAL
                );
                generatedCandidates.add(new GeneratedCandidate(
                        candidateKey(subquery.index(), candidateRank, candidateLabel),
                        candidateRank,
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
            boolean selected = isSelectedCandidate(decision, candidate);
            agenticTracePersistenceService.markAgenticRewriteCandidateAdopted(
                    new AgenticTraceWriteDtos.AgenticRewriteCandidateAdoptionPersistenceRequest(
                            persistPolicy,
                            request.onlineQueryId(),
                            candidate.rewriteCandidateId(),
                            AgenticTraceWriteDtos.AgenticRetrievalExecutionKind.AGENTIC_MULTI_QUERY,
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

        AgenticRetrievalDtos.SubqueryRetrievalTrace trace = new AgenticRetrievalDtos.SubqueryRetrievalTrace(
                subquery.index(),
                query,
                decision.finalQuery(),
                decision.rewriteApplied(),
                routeDecision.strategy().name(),
                decision.selectedReason(),
                decision.rejectedReason(),
                toScoredDocs(decision.finalRetrieved()),
                toRewriteDtos(generatedCandidates, decision),
                objectMapper.valueToTree(memories),
                elapsedMs(started),
                traceMetadata(routeDecision, rawConfidence, decision, memories.size())
        );
        return new SubqueryExecution(trace, decision, persistedCandidates);
    }

    private void persistSubqueryRetrievalTrace(
            AgenticExecutionRequest request,
            AgenticPlanningDtos.AgenticSubquery subquery,
            String subqueryText,
            RetrievalRuntime retrievalRuntime,
            UUID rewriteCandidateId,
            List<RetrievalDoc> retrievedDocs,
            long latencyMs,
            String phase,
            AgenticTraceWriteDtos.AgenticSubqueryRetrievalTraceWriteScope writeScope
    ) {
        agenticTracePersistenceService.persistAgenticSubqueryRetrievalTrace(
                new AgenticTraceWriteDtos.AgenticSubqueryRetrievalTracePersistenceRequest(
                        request.persistPolicy(),
                        request.onlineQueryId(),
                        AgenticTraceWriteDtos.AgenticRetrievalExecutionKind.AGENTIC_MULTI_QUERY,
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
            List<RetrievalDoc> rawRetrieved,
            List<MemoryCandidate> memoryCandidates,
            List<GeneratedCandidate> candidates,
            double threshold,
            UUID domainId,
            RetrievalRuntime retrievalRuntime
    ) {
        if ("raw_only".equals(mode)) {
            return new Decision(rawQuery, false, rawRetrieved, null, null, "raw_only", "mode=raw_only");
        }
        if ("memory_only_ungated".equals(mode) || "memory_only_gated".equals(mode)) {
            if (memoryCandidates.isEmpty()) {
                return new Decision(rawQuery, false, rawRetrieved, null, null, "memory_empty", "no memory candidate");
            }
            String memoryQuery = memoryCandidates.getFirst().queryText();
            String embedding = domainScopedRetrievalService.embeddingLiteral(memoryQuery, retrievalRuntime);
            List<RetrievalDoc> retrievedLocal = domainScopedRetrievalService.retrieveChunks(
                    memoryQuery,
                    embedding,
                    Math.max(20, rawRetrieved.size()),
                    domainId,
                    retrievalRuntime
            );
            List<RetrievalDoc> retrieved = cohereRerankService.rerank(
                    memoryQuery,
                    retrievedLocal,
                    rawRetrieved.size()
            );
            return new Decision(memoryQuery, true, retrieved, null, null, "memory_only", null);
        }

        GeneratedCandidate best = candidates.stream()
                .max(Comparator.comparingDouble(GeneratedCandidate::confidence))
                .orElse(null);
        if (best == null) {
            return new Decision(rawQuery, false, rawRetrieved, null, null, "no_candidate", "rewrite candidate missing");
        }
        if ("rewrite_always".equals(mode)) {
            return new Decision(
                    best.query(),
                    true,
                    best.retrieved(),
                    best.rewriteCandidateId(),
                    best.candidateKey(),
                    "forced",
                    null
            );
        }
        double delta = best.confidence() - rawConfidence;
        if (delta >= threshold) {
            return new Decision(
                    best.query(),
                    true,
                    best.retrieved(),
                    best.rewriteCandidateId(),
                    best.candidateKey(),
                    "delta_above_threshold",
                    null
            );
        }
        return new Decision(rawQuery, false, rawRetrieved, null, null, "delta_below_threshold", "best-candidate delta below threshold");
    }

    private Decision routerRawOnlyDecision(
            String rawQuery,
            List<RetrievalDoc> rawRetrieved,
            QueryRouteDecision routeDecision
    ) {
        String rejectedReason = routeDecision.fallbackApplied()
                ? routeDecision.fallbackReason()
                : "query_router_strategy=" + routeDecision.strategy().name().toLowerCase(Locale.ROOT);
        return new Decision(rawQuery, false, rawRetrieved, null, null, routeDecision.reason(), rejectedReason);
    }

    private String subqueryLabel(AgenticPlanningDtos.AgenticSubquery subquery, String label) {
        String suffix = label == null || label.isBlank() ? "candidate" : label.trim();
        return "subquery_" + subquery.index() + "_" + suffix;
    }

    private String candidateKey(int subqueryIndex, int candidateRank, String candidateLabel) {
        return subqueryIndex + ":" + candidateRank + ":" + (candidateLabel == null ? "" : candidateLabel);
    }

    private boolean isSelectedCandidate(Decision decision, GeneratedCandidate candidate) {
        if (decision.selectedCandidateId() != null && candidate.rewriteCandidateId() != null) {
            return decision.selectedCandidateId().equals(candidate.rewriteCandidateId());
        }
        return decision.selectedCandidateKey() != null
                && decision.selectedCandidateKey().equals(candidate.candidateKey());
    }

    private ObjectNode agenticCandidateMetadata(AgenticPlanningDtos.AgenticSubquery subquery, String templateLabel) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("agentic_multi_query", true);
        node.put("subquery_index", subquery.index());
        node.put("subquery", subquery.query());
        node.put("subquery_intent", subquery.intent());
        node.put("candidate_template_label", templateLabel == null ? "" : templateLabel);
        return node;
    }

    private ObjectNode retrievalMetadata(
            RetrievalRuntime runtime,
            AgenticPlanningDtos.AgenticSubquery subquery,
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
        if (decision.selectedCandidateKey() != null) {
            node.put("selected_candidate_key", decision.selectedCandidateKey());
        }
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
            ChatConfigDtos.ChatRuntimeConfigResponse config,
            ChatReadinessDtos.ChatDomainReadinessResponse readiness,
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

    private ObjectNode domainContext(ChatConfigDtos.ChatRuntimeConfigResponse config) {
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

    private JsonNode scoreBreakdown(List<RetrievalDoc> retrieved, List<MemoryCandidate> memories) {
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

    private JsonNode memorySourceIds(List<MemoryCandidate> memories) {
        ArrayNode array = objectMapper.createArrayNode();
        for (MemoryCandidate memory : memories) {
            array.add(memory.memoryId().toString());
        }
        return array;
    }

    private List<RewriteDtos.RewriteCandidateDto> toRewriteDtos(List<GeneratedCandidate> candidates, Decision decision) {
        return candidates.stream()
                .map(candidate -> new RewriteDtos.RewriteCandidateDto(
                        candidate.rewriteCandidateId(),
                        candidate.label(),
                        candidate.query(),
                        candidate.confidence(),
                        isSelectedCandidate(decision, candidate),
                        decision.selectedCandidateKey() != null && !isSelectedCandidate(decision, candidate)
                                ? "not_selected"
                                : null,
                        objectMapper.valueToTree(candidate.retrieved()),
                        candidate.scoreBreakdown()
                ))
                .toList();
    }

    private List<RetrievalDtos.ScoredDocumentDto> toScoredDocs(List<RetrievalDoc> docs) {
        return docs.stream()
                .map(doc -> new RetrievalDtos.ScoredDocumentDto(
                        doc.documentId(),
                        doc.chunkId(),
                        preview(doc.chunkText()),
                        doc.score()
                ))
                .toList();
    }

    private double confidence(List<RetrievalDoc> docs, double denseScore) {
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

    private record SubqueryExecution(
            AgenticRetrievalDtos.SubqueryRetrievalTrace trace,
            Decision decision,
            List<PersistedRewriteCandidate> persistedCandidates
    ) {
    }

    private record GeneratedCandidate(
            String candidateKey,
            int candidateRank,
            String label,
            String query,
            List<RetrievalDoc> retrieved,
            double confidence,
            UUID rewriteCandidateId,
            JsonNode scoreBreakdown
    ) {
    }

    private record Decision(
            String finalQuery,
            boolean rewriteApplied,
            List<RetrievalDoc> finalRetrieved,
            UUID selectedCandidateId,
            String selectedCandidateKey,
            String selectedReason,
            String rejectedReason
    ) {
    }
}
