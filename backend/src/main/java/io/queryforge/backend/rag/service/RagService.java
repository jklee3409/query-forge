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
import io.queryforge.backend.rag.repository.RagRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RagService {

    private static final Pattern SEARCH_TOKEN_PATTERN = Pattern.compile("[@A-Za-z0-9_./:$-]{2,}|\\p{InHangulSyllables}{2,}");
    private static final Pattern TECHNICAL_TOKEN_PATTERN = Pattern.compile("[@A-Za-z_][A-Za-z0-9_./:$-]{1,}");

    private final RagRepository repository;
    private final DomainScopedRetrievalService domainScopedRetrievalService;
    private final RagRetrievalExecutionService ragRetrievalExecutionService;
    private final HashEmbeddingService embeddingService;
    private final CohereRerankService cohereRerankService;
    private final RewriteCandidateService rewriteCandidateService;
    private final ChatAnswerService chatAnswerService;
    private final ChatRuntimeConfigService chatRuntimeConfigService;
    private final QueryStrategyRouter queryStrategyRouter;
    private final AgenticRetrievalService agenticRetrievalService;
    private final ObjectMapper objectMapper;

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public RagDtos.AskResponse ask(RagDtos.AskRequest request) {
        String rawQuery = normalizedText(request.query());
        if (rawQuery.isBlank()) {
            throw new IllegalArgumentException("query must not be blank");
        }
        if (request.domainId() == null) {
            throw new IllegalArgumentException("domainId is required for chat");
        }
        ChatRuntimeDtos.ChatRuntimeConfigResponse config = chatRuntimeConfigService.getConfig(request.domainId());
        ChatRuntimeDtos.ChatDomainReadinessResponse readiness = chatRuntimeConfigService.getReadiness(request.domainId());
        if (!readiness.activeConfigPresent()) {
            throw new IllegalArgumentException("active chat_runtime_config is missing for domain: " + config.displayName());
        }
        if (!config.enabled()) {
            throw new IllegalArgumentException("chat is disabled for domain: " + config.displayName());
        }
        String mode = normalizedMode(config.mode());
        String rewriteQueryProfile = normalizedRewriteProfile(config.rewriteQueryProfile());
        long routeStarted = System.nanoTime();
        QueryRouteDecision routeDecision = queryStrategyRouter.route(routeContext(
                rawQuery,
                config,
                readiness,
                mode,
                rewriteQueryProfile,
                config.rewriteAnchorInjectionEnabled(),
                false,
                false,
                null
        ));
        long routeLatency = elapsedMs(routeStarted);
        routeDecision = routeDecision.withLatency(routeLatency);
        if (!routeDecision.routerEnabled() && !"raw_only".equals(mode) && !readiness.readyForRewrite()) {
            throw new IllegalArgumentException(String.join("; ", readiness.blockingReasons()));
        }
        int retrievalTopK = normalizedPositive(config.retrievalTopK(), 10);
        int rerankTopN = normalizedPositive(config.rerankTopN(), 5);
        int memoryTopN = normalizedPositive(config.memoryTopN(), 5);
        int candidateCount = Math.min(normalizedPositive(config.rewriteCandidateCount(), 2), 2);
        double threshold = config.rewriteThreshold();
        String gatingPreset = normalizedPreset(config.gatingPreset());
        boolean useSessionContext = config.useSessionContext() || "selective_rewrite_with_session".equals(mode);
        JsonNode sessionContextSnapshot = useSessionContext ? nullSafeJson(request.sessionContext()) : objectMapper.createObjectNode();
        DomainScopedRetrievalService.RetrievalRuntime retrievalRuntime =
                domainScopedRetrievalService.retrievalRuntime(config);
        AgenticSettings agenticSettings = agenticSettings(config.metadata());
        ObjectNode runtimeMetadata = runtimeMetadata(config);
        runtimeMetadata.set("router", routerMetadata(routeDecision));
        if (agenticSettings.enabled()) {
            runtimeMetadata.set("agentic_retrieval", agenticSettingsMetadata(agenticSettings, retrievalTopK));
        }
        boolean rawOnlyRoute = routeDecision.routerEnabled() && routeDecision.strategy() == QueryStrategy.RAW_ONLY;

        Instant started = Instant.now();
        long stageStart = System.nanoTime();
        UUID onlineQueryId = repository.createOnlineQuery(
                config.domainId(),
                request.sessionId(),
                rawQuery,
                sessionContextSnapshot,
                mode,
                threshold,
                runtimeMetadata
        );
        long createQueryLatency = elapsedMs(stageStart);

        stageStart = System.nanoTime();
        String rawEmbeddingLiteral = domainScopedRetrievalService.embeddingLiteral(rawQuery, retrievalRuntime);
        String memoryPreset = switch (mode) {
            case "memory_only_ungated" -> "ungated";
            case "memory_only_gated" -> gatingPreset;
            default -> gatingPreset;
        };
        List<RagRepository.MemoryCandidate> memoryCandidates;
        if (rawOnlyRoute) {
            memoryCandidates = List.of();
        } else {
            memoryCandidates = domainScopedRetrievalService.findMemoryCandidates(
                    rawQuery,
                    rawEmbeddingLiteral,
                    memoryTopN,
                    memoryPreset,
                    config.domainId(),
                    config.generationStrategies(),
                    config.sourceGatingRunIds(),
                    config.sourceGatingBatchIds(),
                    retrievalRuntime
            );
        }
        JsonNode memoryTopNJson = objectMapper.valueToTree(memoryCandidates);
        long memoryLatency = elapsedMs(stageStart);

        if (agenticSettings.enabled()) {
            return askAgentic(
                    request,
                    rawQuery,
                    config,
                    mode,
                    rewriteQueryProfile,
                    routeDecision,
                    retrievalTopK,
                    rerankTopN,
                    memoryTopN,
                    candidateCount,
                    threshold,
                    gatingPreset,
                    useSessionContext,
                    sessionContextSnapshot,
                    retrievalRuntime,
                    onlineQueryId,
                    memoryCandidates,
                    memoryTopNJson,
                    createQueryLatency,
                    memoryLatency,
                    started,
                    agenticSettings,
                    readiness
            );
        }

        List<RagRepository.RetrievalDoc> rawRetrievedLocal;
        List<RagRepository.RetrievalDoc> rawRetrieved;
        double rawDense = memoryCandidates.isEmpty() ? 0.0 : memoryCandidates.getFirst().similarity();
        double rawConfidence;
        long rawRetrievalLatency;
        String rawRetrieverName;
        JsonNode rawRetrievalMetadata;
        if (rawOnlyRoute) {
            RagRetrievalExecutionService.RawOnlyExecutionResult rawOnlyExecution =
                    ragRetrievalExecutionService.executeRawOnly(new RagRetrievalExecutionService.RawOnlyExecutionRequest(
                            rawQuery,
                            rawEmbeddingLiteral,
                            retrievalTopK,
                            rerankTopN,
                            config.domainId(),
                            retrievalRuntime
                    ));
            rawRetrievedLocal = rawOnlyExecution.localRetrievedDocs();
            rawRetrieved = rawOnlyExecution.rerankedDocs();
            rawConfidence = rawOnlyExecution.rawRetrievalConfidence();
            rawRetrievalLatency = rawOnlyExecution.latencyMs();
            rawRetrieverName = rawOnlyExecution.retrieverName();
            rawRetrievalMetadata = rawOnlyExecution.retrievalMetadata();
        } else {
            stageStart = System.nanoTime();
            rawRetrievedLocal = domainScopedRetrievalService.retrieveChunks(
                    rawQuery,
                    rawEmbeddingLiteral,
                    retrievalTopK,
                    config.domainId(),
                    retrievalRuntime
            );
            rawRetrieved = cohereRerankService.rerank(rawQuery, rawRetrievedLocal, rerankTopN);
            rawConfidence = confidence(rawRetrieved, rawDense);
            rawRetrievalLatency = elapsedMs(stageStart);
            rawRetrieverName = retrievalRuntime.retrieverName();
            rawRetrievalMetadata = retrievalMetadata(retrievalRuntime);
        }
        repository.insertRetrievalResults(
                onlineQueryId,
                null,
                "raw",
                rawRetrievedLocal,
                mode,
                rawRetrieverName,
                rawRetrievalMetadata
        );

        if (routeDecision.routerEnabled() && !rawOnlyRoute) {
            routeStarted = System.nanoTime();
            QueryRouteDecision refinedRouteDecision = queryStrategyRouter.route(routeContext(
                    rawQuery,
                    config,
                    readiness,
                    mode,
                    rewriteQueryProfile,
                    config.rewriteAnchorInjectionEnabled(),
                    true,
                    !memoryCandidates.isEmpty(),
                    rawConfidence
            ));
            routeLatency += elapsedMs(routeStarted);
            routeDecision = refinedRouteDecision.withLatency(routeLatency);
            rawOnlyRoute = routeDecision.strategy() == QueryStrategy.RAW_ONLY;
        }

        stageStart = System.nanoTime();
        List<GeneratedCandidate> scoredCandidates = new ArrayList<>();
        if (!rawOnlyRoute) {
            List<RagRetrievalExecutionService.ExecutedRewriteCandidate> executedCandidates = null;
            if (usesSelectiveRewriteExecutionService(mode, routeDecision)) {
                RagRetrievalExecutionService.SelectiveRewriteExecutionResult selectiveExecution =
                        ragRetrievalExecutionService.executeSelectiveRewrite(new RagRetrievalExecutionService.SelectiveRewriteExecutionRequest(
                                rawQuery,
                                sessionContextSnapshot,
                                memoryCandidates,
                                candidateCount,
                                routeDecision.rewriteQueryProfile(),
                                domainContext(config),
                                retrievalTopK,
                                rerankTopN,
                                config.domainId(),
                                retrievalRuntime,
                                rawDense
                        ));
                executedCandidates = selectiveExecution.candidates();
            } else if (usesAnchorAwareRewriteExecutionService(routeDecision)) {
                RagRetrievalExecutionService.AnchorAwareRewriteExecutionResult anchorAwareExecution =
                        ragRetrievalExecutionService.executeAnchorAwareRewrite(new RagRetrievalExecutionService.AnchorAwareRewriteExecutionRequest(
                                rawQuery,
                                sessionContextSnapshot,
                                memoryCandidates,
                                candidateCount,
                                routeDecision.rewriteQueryProfile(),
                                domainContext(config),
                                retrievalTopK,
                                rerankTopN,
                                config.domainId(),
                                retrievalRuntime,
                                rawDense
                        ));
                executedCandidates = anchorAwareExecution.candidates();
            }
            if (executedCandidates != null) {
                for (int index = 0; index < executedCandidates.size(); index++) {
                    RagRetrievalExecutionService.ExecutedRewriteCandidate executed = executedCandidates.get(index);
                    int candidateIndex = executed.index() > 0 ? executed.index() : index + 1;
                    UUID candidateId = repository.createRewriteCandidate(
                            onlineQueryId,
                            candidateIndex,
                            executed.label(),
                            executed.query(),
                            memorySourceIds(memoryCandidates),
                            objectMapper.valueToTree(executed.rerankedDocs()),
                            executed.confidence(),
                            scoreBreakdown(executed.rerankedDocs(), memoryCandidates)
                    );
                    repository.insertRetrievalResults(
                            onlineQueryId,
                            candidateId,
                            "rewrite_candidate",
                            executed.localRetrievedDocs(),
                            mode,
                            executed.retrieverName(),
                            executed.retrievalMetadata()
                    );
                    scoredCandidates.add(new GeneratedCandidate(
                            executed.label(),
                            executed.query(),
                            executed.rerankedDocs(),
                            executed.confidence(),
                            candidateId
                    ));
                }
            } else {
                List<RewriteCandidateService.CandidateTemplate> generatedCandidates = rewriteCandidateService.buildCandidates(
                        rawQuery,
                        sessionContextSnapshot,
                        memoryCandidates,
                        candidateCount,
                        routeDecision.rewriteQueryProfile(),
                        routeDecision.anchorInjectionEnabled(),
                        domainContext(config)
                );
                for (int index = 0; index < generatedCandidates.size(); index++) {
                    RewriteCandidateService.CandidateTemplate generated = generatedCandidates.get(index);
                    String embeddingLiteral = domainScopedRetrievalService.embeddingLiteral(generated.query(), retrievalRuntime);
                    List<RagRepository.RetrievalDoc> candidateRetrievedLocal = domainScopedRetrievalService.retrieveChunks(
                            generated.query(),
                            embeddingLiteral,
                            retrievalTopK,
                            config.domainId(),
                            retrievalRuntime
                    );
                    List<RagRepository.RetrievalDoc> candidateRetrieved = cohereRerankService.rerank(
                            generated.query(),
                            candidateRetrievedLocal,
                            rerankTopN
                    );
                    double confidence = confidence(candidateRetrieved, rawDense);
                    UUID candidateId = repository.createRewriteCandidate(
                            onlineQueryId,
                            index + 1,
                            generated.label(),
                            generated.query(),
                            memorySourceIds(memoryCandidates),
                            objectMapper.valueToTree(candidateRetrieved),
                            confidence,
                            scoreBreakdown(candidateRetrieved, memoryCandidates)
                    );
                    repository.insertRetrievalResults(
                            onlineQueryId,
                            candidateId,
                            "rewrite_candidate",
                            candidateRetrievedLocal,
                            mode,
                            retrievalRuntime.retrieverName(),
                            retrievalMetadata(retrievalRuntime)
                    );
                    scoredCandidates.add(new GeneratedCandidate(generated.label(), generated.query(), candidateRetrieved, confidence, candidateId));
                }
            }
        }
        long candidateLatency = elapsedMs(stageStart);

        stageStart = System.nanoTime();
        Decision decision = rawOnlyRoute
                ? routerRawOnlyDecision(rawQuery, rawRetrieved, routeDecision)
                : decide(
                mode,
                rawQuery,
                rawConfidence,
                rawRetrieved,
                memoryCandidates,
                scoredCandidates,
                threshold,
                config.domainId(),
                retrievalRuntime
        );
        if (decision.selectedCandidateId() != null) {
            for (GeneratedCandidate candidate : scoredCandidates) {
                boolean adopted = candidate.rewriteCandidateId().equals(decision.selectedCandidateId());
                repository.markRewriteCandidateAdopted(
                        candidate.rewriteCandidateId(),
                        adopted,
                        adopted ? null : decision.rejectedReason()
                );
            }
        } else {
            for (GeneratedCandidate candidate : scoredCandidates) {
                repository.markRewriteCandidateAdopted(candidate.rewriteCandidateId(), false, decision.rejectedReason());
            }
        }
        long decisionLatency = elapsedMs(stageStart);

        stageStart = System.nanoTime();
        List<RagRepository.RetrievalDoc> reranked = decision.finalRetrieved();
        repository.insertRerankResults(onlineQueryId, decision.selectedCandidateId(), reranked, cohereRerankService.modelName());
        long rerankLatency = elapsedMs(stageStart);

        stageStart = System.nanoTime();
        AnswerDraft answerDraft = buildAnswer(
                rawQuery,
                decision.finalQuery(),
                config.displayName(),
                reranked
        );
        repository.insertAnswer(
                onlineQueryId,
                answerDraft.answerText(),
                objectMapper.valueToTree(answerDraft.citedDocumentIds()),
                objectMapper.valueToTree(answerDraft.citedChunkIds()),
                answerDraft.modelName(),
                objectMapper.createObjectNode().put("source", "llm-chat-answer")
        );
        long answerLatency = elapsedMs(stageStart);

        Map<String, Long> latencyBreakdown = Map.of(
                "queryRouterMs", routeLatency,
                "createOnlineQueryMs", createQueryLatency,
                "memoryLookupMs", memoryLatency,
                "rawRetrievalMs", rawRetrievalLatency,
                "candidateGenerationMs", candidateLatency,
                "rewriteDecisionMs", decisionLatency,
                "rerankMs", rerankLatency,
                "answerGenerationMs", answerLatency,
                "totalMs", java.time.Duration.between(started, Instant.now()).toMillis()
        );
        repository.upsertOnlineQueryDecision(
                onlineQueryId,
                decision.finalQuery(),
                decision.rewriteApplied(),
                memoryTopNJson,
                rawConfidence,
                decision.selectedCandidateId(),
                decision.selectedReason(),
                decision.rejectedReason(),
                objectMapper.valueToTree(latencyBreakdown)
        );
        repository.mergeOnlineQueryMetadata(onlineQueryId, routerMetadataEnvelope(routeDecision));

        double selectedConfidence = confidence(decision.finalRetrieved(), rawDense);
        boolean gatingApplied = !"raw_only".equals(mode) && !"memory_only_ungated".equals(mode);
        boolean selectiveRewrite = mode.startsWith("selective_rewrite");
        UUID rewriteLogId = repository.createOnlineRewriteLog(
                onlineQueryId,
                null,
                rawQuery,
                decision.finalQuery(),
                mode,
                generationMethodCodes(memoryCandidates),
                generationBatchIds(memoryCandidates),
                gatingApplied,
                gatingPreset,
                decision.rewriteApplied(),
                selectiveRewrite,
                useSessionContext,
                rawConfidence,
                selectedConfidence,
                selectedConfidence - rawConfidence,
                decision.selectedReason(),
                decision.rejectedReason(),
                rewriteLogMetadata(
                        config,
                        rewriteQueryProfile,
                        retrievalTopK,
                        rerankTopN,
                        memoryTopN,
                        candidateCount,
                        threshold,
                        latencyBreakdown,
                        retrievalRuntime,
                        routeDecision
                )
        );

        for (int index = 0; index < memoryCandidates.size(); index++) {
            RagRepository.MemoryCandidate memoryCandidate = memoryCandidates.get(index);
            repository.insertMemoryRetrievalLog(
                    rewriteLogId,
                    onlineQueryId,
                    index + 1,
                    memoryCandidate,
                    objectMapper.valueToTree(Map.of(
                            "gating_preset", gatingPreset,
                            "generation_batch_id", memoryCandidate.generationBatchId() == null ? "" : memoryCandidate.generationBatchId().toString(),
                            "source_gate_run_id", memoryCandidate.sourceGateRunId() == null ? "" : memoryCandidate.sourceGateRunId(),
                            "source_gating_batch_id", memoryCandidate.sourceGatingBatchId() == null ? "" : memoryCandidate.sourceGatingBatchId()
                    ))
            );
        }

        for (int index = 0; index < scoredCandidates.size(); index++) {
            GeneratedCandidate candidate = scoredCandidates.get(index);
            boolean selected = decision.selectedCandidateId() != null && decision.selectedCandidateId().equals(candidate.rewriteCandidateId());
            repository.insertRewriteCandidateLog(
                    rewriteLogId,
                    onlineQueryId,
                    candidate.rewriteCandidateId(),
                    index + 1,
                    candidate.label(),
                    candidate.query(),
                    candidate.confidence(),
                    selected,
                    selected ? null : decision.rejectedReason(),
                    objectMapper.valueToTree(candidate.retrieved()),
                    scoreBreakdown(candidate.retrieved(), memoryCandidates),
                    objectMapper.valueToTree(Map.of(
                            "mode", mode,
                            "selected_reason", decision.selectedReason()
                    ))
            );
        }

        return new RagDtos.AskResponse(
                onlineQueryId,
                answerDraft.answerText(),
                decision.finalQuery(),
                rawQuery,
                decision.rewriteApplied(),
                toRewriteDtos(scoredCandidates, decision.selectedCandidateId()),
                toScoredDocs(decision.finalRetrieved()),
                toScoredDocs(reranked),
                memoryTopNJson,
                answerDraft.modelName(),
                answerDraft.citedDocumentIds(),
                answerDraft.citedChunkIds(),
                config,
                latencyBreakdown,
                null
        );
    }

    private RagDtos.AskResponse askAgentic(
            RagDtos.AskRequest request,
            String rawQuery,
            ChatRuntimeDtos.ChatRuntimeConfigResponse config,
            String mode,
            String rewriteQueryProfile,
            QueryRouteDecision routeDecision,
            int retrievalTopK,
            int rerankTopN,
            int memoryTopN,
            int candidateCount,
            double threshold,
            String gatingPreset,
            boolean useSessionContext,
            JsonNode sessionContextSnapshot,
            DomainScopedRetrievalService.RetrievalRuntime retrievalRuntime,
            UUID onlineQueryId,
            List<RagRepository.MemoryCandidate> plannerMemoryCandidates,
            JsonNode memoryTopNJson,
            long createQueryLatency,
            long memoryLatency,
            Instant started,
            AgenticSettings agenticSettings,
            ChatRuntimeDtos.ChatDomainReadinessResponse readiness
    ) {
        long stageStart = System.nanoTime();
        AgenticRetrievalService.AgenticExecutionResult agenticResult = agenticRetrievalService.execute(
                new AgenticRetrievalService.AgenticExecutionRequest(
                        rawQuery,
                        onlineQueryId,
                        config,
                        readiness,
                        sessionContextSnapshot,
                        plannerMemoryCandidates,
                        mode,
                        rewriteQueryProfile,
                        switch (mode) {
                            case "memory_only_ungated" -> "ungated";
                            case "memory_only_gated" -> gatingPreset;
                            default -> gatingPreset;
                        },
                        retrievalTopK,
                        rerankTopN,
                        memoryTopN,
                        candidateCount,
                        threshold,
                        agenticSettings.maxSubqueries(),
                        agenticSettings.rrfK(),
                        retrievalTopK
                )
        );
        long agenticRetrievalLatency = elapsedMs(stageStart);

        stageStart = System.nanoTime();
        List<RagRepository.RetrievalDoc> mergedDocs = agenticResult.mergedDocs();
        repository.insertRerankResults(onlineQueryId, null, mergedDocs, "agentic-rrf");
        long rerankLatency = elapsedMs(stageStart);

        stageStart = System.nanoTime();
        AnswerDraft answerDraft = buildAnswer(
                rawQuery,
                rawQuery,
                config.displayName(),
                mergedDocs
        );
        ObjectNode answerMetadata = objectMapper.createObjectNode();
        answerMetadata.put("source", "llm-chat-answer");
        answerMetadata.put("agentic_multi_query", true);
        repository.insertAnswer(
                onlineQueryId,
                answerDraft.answerText(),
                objectMapper.valueToTree(answerDraft.citedDocumentIds()),
                objectMapper.valueToTree(answerDraft.citedChunkIds()),
                answerDraft.modelName(),
                answerMetadata
        );
        long answerLatency = elapsedMs(stageStart);

        Map<String, Long> latencyBreakdown = new LinkedHashMap<>();
        latencyBreakdown.put("queryRouterMs", routeDecision.metadata().get("latencyMs") instanceof Number number ? number.longValue() : 0L);
        latencyBreakdown.put("createOnlineQueryMs", createQueryLatency);
        latencyBreakdown.put("memoryLookupMs", memoryLatency);
        latencyBreakdown.put("agenticPlanningMs", agenticResult.planningLatencyMs());
        latencyBreakdown.put("agenticRetrievalMs", agenticRetrievalLatency);
        latencyBreakdown.put("rerankMs", rerankLatency);
        latencyBreakdown.put("answerGenerationMs", answerLatency);
        latencyBreakdown.put("totalMs", java.time.Duration.between(started, Instant.now()).toMillis());

        double denseHint = plannerMemoryCandidates.isEmpty() ? 0.0d : plannerMemoryCandidates.getFirst().similarity();
        double selectedConfidence = confidence(mergedDocs, denseHint);
        repository.upsertOnlineQueryDecision(
                onlineQueryId,
                rawQuery,
                agenticResult.rewriteApplied(),
                memoryTopNJson,
                selectedConfidence,
                null,
                agenticResult.selectedReason(),
                agenticResult.rejectedReason(),
                objectMapper.valueToTree(latencyBreakdown)
        );
        ObjectNode agenticEnvelope = agenticMetadataEnvelope(agenticResult, agenticSettings, retrievalTopK, routeDecision);
        repository.mergeOnlineQueryMetadata(onlineQueryId, agenticEnvelope);

        boolean gatingApplied = !"raw_only".equals(mode) && !"memory_only_ungated".equals(mode);
        boolean selectiveRewrite = mode.startsWith("selective_rewrite");
        ObjectNode rewriteMetadata = rewriteLogMetadata(
                config,
                rewriteQueryProfile,
                retrievalTopK,
                rerankTopN,
                memoryTopN,
                candidateCount,
                threshold,
                latencyBreakdown,
                retrievalRuntime,
                routeDecision
        );
        rewriteMetadata.set("agentic_retrieval", agenticEnvelope);
        UUID rewriteLogId = repository.createOnlineRewriteLog(
                onlineQueryId,
                null,
                rawQuery,
                rawQuery,
                mode,
                generationMethodCodes(plannerMemoryCandidates),
                generationBatchIds(plannerMemoryCandidates),
                gatingApplied,
                gatingPreset,
                agenticResult.rewriteApplied(),
                selectiveRewrite,
                useSessionContext,
                selectedConfidence,
                selectedConfidence,
                0.0d,
                agenticResult.selectedReason(),
                agenticResult.rejectedReason(),
                rewriteMetadata
        );

        for (int index = 0; index < plannerMemoryCandidates.size(); index++) {
            RagRepository.MemoryCandidate memoryCandidate = plannerMemoryCandidates.get(index);
            repository.insertMemoryRetrievalLog(
                    rewriteLogId,
                    onlineQueryId,
                    index + 1,
                    memoryCandidate,
                    objectMapper.valueToTree(Map.of(
                            "gating_preset", gatingPreset,
                            "generation_batch_id", memoryCandidate.generationBatchId() == null ? "" : memoryCandidate.generationBatchId().toString(),
                            "source_gate_run_id", memoryCandidate.sourceGateRunId() == null ? "" : memoryCandidate.sourceGateRunId(),
                            "source_gating_batch_id", memoryCandidate.sourceGatingBatchId() == null ? "" : memoryCandidate.sourceGatingBatchId(),
                            "agentic_role", "planner_memory_hint"
                    ))
            );
        }

        List<AgenticRetrievalService.PersistedRewriteCandidate> persistedCandidates = agenticResult.persistedCandidates();
        for (int index = 0; index < persistedCandidates.size(); index++) {
            AgenticRetrievalService.PersistedRewriteCandidate candidate = persistedCandidates.get(index);
            ObjectNode candidateMetadata = objectMapper.createObjectNode();
            candidateMetadata.put("mode", mode);
            candidateMetadata.put("selected_reason", agenticResult.selectedReason());
            candidateMetadata.put("agentic_multi_query", true);
            repository.insertRewriteCandidateLog(
                    rewriteLogId,
                    onlineQueryId,
                    candidate.rewriteCandidateId(),
                    index + 1,
                    candidate.label(),
                    candidate.query(),
                    candidate.confidence(),
                    candidate.selected(),
                    candidate.selected() ? null : candidate.rejectedReason(),
                    objectMapper.valueToTree(candidate.retrieved()),
                    candidate.scoreBreakdown(),
                    candidateMetadata
            );
        }

        RagDtos.AgenticRetrievalMetadata responseMetadata = toAgenticMetadata(agenticResult, agenticSettings, retrievalTopK);
        return new RagDtos.AskResponse(
                onlineQueryId,
                answerDraft.answerText(),
                rawQuery,
                rawQuery,
                agenticResult.rewriteApplied(),
                toAgenticRewriteDtos(persistedCandidates),
                toScoredDocs(mergedDocs),
                toScoredDocs(mergedDocs),
                memoryTopNJson,
                answerDraft.modelName(),
                answerDraft.citedDocumentIds(),
                answerDraft.citedChunkIds(),
                config,
                latencyBreakdown,
                responseMetadata
        );
    }

    public RagDtos.RewritePreviewResponse previewRewrite(RagDtos.RewritePreviewRequest request) {
        String rawQuery = normalizedText(request.rawQuery());
        if (rawQuery.isBlank()) {
            throw new IllegalArgumentException("rawQuery must not be blank");
        }
        int memoryTopN = normalizedPositive(request.memoryTopN(), 5);
        int candidateCount = Math.min(normalizedPositive(request.candidateCount(), 2), 2);
        String gatingPreset = request.gatingPreset() == null || request.gatingPreset().isBlank()
                ? "full_gating"
                : request.gatingPreset();

        String queryEmbedding = embeddingService.toHalfvecLiteral(embeddingService.embed(rawQuery));
        List<RagRepository.MemoryCandidate> memories = repository.findMemoryTopN(
                queryEmbedding,
                memoryTopN,
                gatingPreset,
                null,
                List.of(),
                List.of(),
                List.of()
        );
        List<RewriteCandidateService.CandidateTemplate> candidates = rewriteCandidateService.buildCandidates(
                rawQuery,
                nullSafeJson(request.sessionContext()),
                memories,
                candidateCount,
                "compact_anchor",
                false,
                objectMapper.createObjectNode()
        );
        List<RagDtos.RewriteCandidateDto> previewDtos = new ArrayList<>();
        for (RewriteCandidateService.CandidateTemplate candidate : candidates) {
            String candidateEmbedding = embeddingService.toHalfvecLiteral(embeddingService.embed(candidate.query()));
            List<RagRepository.RetrievalDoc> retrievedLocal = repository.findTopChunksByEmbedding(candidateEmbedding, 20);
            List<RagRepository.RetrievalDoc> retrieved = cohereRerankService.rerank(candidate.query(), retrievedLocal, 5);
            double confidence = confidence(retrieved, memories.isEmpty() ? 0.0 : memories.getFirst().similarity());
            previewDtos.add(
                    new RagDtos.RewriteCandidateDto(
                            null,
                            candidate.label(),
                            candidate.query(),
                            confidence,
                            false,
                            null,
                            objectMapper.valueToTree(retrieved),
                            scoreBreakdown(retrieved, memories)
                    )
            );
        }
        return new RagDtos.RewritePreviewResponse(
                rawQuery,
                objectMapper.valueToTree(memories),
                previewDtos
        );
    }

    public RagDtos.QueryTraceResponse getQueryTrace(UUID onlineQueryId) {
        RagRepository.OnlineQueryRow onlineQuery = repository.findOnlineQuery(onlineQueryId)
                .orElseThrow(() -> new IllegalArgumentException("online query was not found: " + onlineQueryId));
        return new RagDtos.QueryTraceResponse(
                onlineQuery.onlineQueryId(),
                onlineQuery.rawQuery(),
                onlineQuery.finalQueryUsed(),
                onlineQuery.rewriteApplied(),
                onlineQuery.rewriteStrategy(),
                onlineQuery.sessionContextSnapshot(),
                onlineQuery.memoryTopN(),
                onlineQuery.rawScore(),
                onlineQuery.selectedRewriteCandidateId(),
                onlineQuery.selectedReason(),
                onlineQuery.rejectedReason(),
                onlineQuery.threshold(),
                onlineQuery.latencyBreakdown(),
                repository.findRewriteCandidates(onlineQueryId).stream()
                        .map(candidate -> new RagDtos.RewriteCandidateDto(
                                candidate.rewriteCandidateId(),
                                candidate.candidateLabel(),
                                candidate.candidateQuery(),
                                candidate.confidenceScore() != null ? candidate.confidenceScore() : 0.0,
                                candidate.adopted() != null && candidate.adopted(),
                                candidate.rejectedReason(),
                                candidate.retrievalTopKDocs(),
                                candidate.scoreBreakdown()
                        ))
                        .toList(),
                repository.findRetrievalResults(onlineQueryId),
                repository.findRerankResults(onlineQueryId),
                repository.findAnswer(onlineQueryId)
        );
    }

    public RagDtos.ExperimentSummaryResponse getExperimentSummary(UUID experimentRunId) {
        RagRepository.ExperimentRunSummary summary = repository.findExperimentRunSummary(experimentRunId)
                .orElseThrow(() -> new IllegalArgumentException("experiment run was not found: " + experimentRunId));
        return new RagDtos.ExperimentSummaryResponse(
                summary.experimentRunId(),
                summary.experimentKey(),
                summary.status(),
                summary.startedAt(),
                summary.finishedAt(),
                summary.parameters(),
                summary.metrics(),
                summary.notes()
        );
    }

    public List<RagRepository.ExperimentRunSummary> listRecentExperimentRuns(int limit) {
        return repository.listRecentExperimentRuns(limit);
    }

    public RagDtos.EvalReportResponse readEvalReport(String reportType) {
        String normalized = reportType == null ? "retrieval" : reportType.toLowerCase(Locale.ROOT);
        List<Path> reportRoots = List.of(
                Path.of("data/reports"),
                Path.of("../data/reports"),
                Path.of("../../data/reports")
        );
        try {
            String prefix = normalized.startsWith("answer") ? "answer_summary_" : "retrieval_summary_";
            Optional<Path> latest = Optional.empty();
            for (Path reportDir : reportRoots) {
                if (!Files.exists(reportDir)) {
                    continue;
                }
                Optional<Path> candidate;
                try (Stream<Path> paths = Files.list(reportDir)) {
                    candidate = paths
                            .filter(path -> path.getFileName().toString().startsWith(prefix))
                            .filter(path -> path.getFileName().toString().endsWith(".json"))
                            .max(Comparator.comparingLong(path -> path.toFile().lastModified()));
                }
                if (candidate.isPresent()) {
                    if (latest.isEmpty() || candidate.get().toFile().lastModified() > latest.get().toFile().lastModified()) {
                        latest = candidate;
                    }
                }
            }
            if (latest.isEmpty()) {
                return new RagDtos.EvalReportResponse(normalized, objectMapper.createObjectNode());
            }
            JsonNode payload = objectMapper.readTree(Files.readString(latest.get()));
            return new RagDtos.EvalReportResponse(normalized, payload);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to read eval report.", exception);
        }
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public RagDtos.ReindexResponse reindex(RagDtos.ReindexRequest request) {
        boolean reindexChunks = request == null || request.reindexChunks() == null || request.reindexChunks();
        boolean reindexMemory = request == null || request.reindexMemory() == null || request.reindexMemory();
        int chunkUpdates = 0;
        int memoryUpdates = 0;
        String model = "hash-embedding-v1";

        if (reindexChunks) {
            for (RagRepository.ChunkSource chunk : repository.findAllChunksForEmbedding()) {
                String literal = embeddingService.toHalfvecLiteral(embeddingService.embed(chunk.chunkText()));
                repository.upsertChunkEmbedding(chunk.chunkId(), literal, model);
                chunkUpdates++;
            }
        }
        if (reindexMemory) {
            for (RagRepository.MemorySource memory : repository.findAllMemorySources()) {
                String literal = embeddingService.toHalfvecLiteral(embeddingService.embed(memory.queryText()));
                repository.updateMemoryEmbedding(memory.memoryId(), literal, model);
                memoryUpdates++;
            }
        }
        return new RagDtos.ReindexResponse(chunkUpdates, memoryUpdates, model);
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
                        scoreBreakdown(candidate.retrieved(), List.of())
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

    private List<RagDtos.RewriteCandidateDto> toAgenticRewriteDtos(
            List<AgenticRetrievalService.PersistedRewriteCandidate> candidates
    ) {
        return candidates.stream()
                .map(candidate -> new RagDtos.RewriteCandidateDto(
                        candidate.rewriteCandidateId(),
                        candidate.label(),
                        candidate.query(),
                        candidate.confidence(),
                        candidate.selected(),
                        candidate.selected() ? null : candidate.rejectedReason(),
                        objectMapper.valueToTree(candidate.retrieved()),
                        candidate.scoreBreakdown()
                ))
                .toList();
    }

    private RagDtos.AgenticRetrievalMetadata toAgenticMetadata(
            AgenticRetrievalService.AgenticExecutionResult result,
            AgenticSettings settings,
            int finalTopK
    ) {
        ObjectNode metadata = objectMapper.createObjectNode();
        metadata.set("execution", nullSafeJson(result.metadata()));
        metadata.put("fallback_plan", result.plan().fallbackApplied());
        return new RagDtos.AgenticRetrievalMetadata(
                result.plan(),
                result.traces(),
                settings.mergeStrategy(),
                settings.rrfK(),
                finalTopK,
                toScoredDocs(result.mergedDocs()),
                metadata
        );
    }

    private ObjectNode agenticMetadataEnvelope(
            AgenticRetrievalService.AgenticExecutionResult result,
            AgenticSettings settings,
            int finalTopK,
            QueryRouteDecision routeDecision
    ) {
        ObjectNode node = objectMapper.createObjectNode();
        node.set("agentic_plan", objectMapper.valueToTree(result.plan()));
        node.set("subquery_traces", objectMapper.valueToTree(result.traces()));
        node.put("merge_strategy", settings.mergeStrategy());
        node.put("max_subqueries", settings.maxSubqueries());
        node.put("rrf_k", settings.rrfK());
        node.put("final_top_k", finalTopK);
        node.set("merged_docs", objectMapper.valueToTree(toScoredDocs(result.mergedDocs())));
        node.set("agentic_execution", nullSafeJson(result.metadata()));
        node.set("router", routerMetadata(routeDecision));
        return node;
    }

    private AgenticSettings agenticSettings(JsonNode metadata) {
        boolean enabled = booleanMetadata(metadata, "agenticMultiQueryEnabled", "agentic_multi_query_enabled", false);
        int maxSubqueries = intMetadata(metadata, "maxSubqueries", "max_subqueries", 3);
        int rrfK = intMetadata(metadata, "rrfK", "rrf_k", 60);
        String mergeStrategy = stringMetadata(metadata, "agenticMergeStrategy", "agentic_merge_strategy", "RRF");
        String normalizedMergeStrategy = mergeStrategy == null || mergeStrategy.isBlank()
                ? "RRF"
                : mergeStrategy.trim().toUpperCase(Locale.ROOT);
        if (!"RRF".equals(normalizedMergeStrategy)) {
            normalizedMergeStrategy = "RRF";
        }
        return new AgenticSettings(
                enabled,
                Math.max(1, Math.min(maxSubqueries, 4)),
                normalizedMergeStrategy,
                Math.max(1, rrfK)
        );
    }

    private ObjectNode agenticSettingsMetadata(AgenticSettings settings, int finalTopK) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("agenticMultiQueryEnabled", settings.enabled());
        node.put("maxSubqueries", settings.maxSubqueries());
        node.put("agenticMergeStrategy", settings.mergeStrategy());
        node.put("rrfK", settings.rrfK());
        node.put("finalTopK", finalTopK);
        return node;
    }

    private boolean booleanMetadata(JsonNode metadata, String primaryKey, String aliasKey, boolean fallback) {
        JsonNode value = metadataValue(metadata, primaryKey, aliasKey);
        return value == null || value.isMissingNode() || value.isNull() ? fallback : value.asBoolean(fallback);
    }

    private int intMetadata(JsonNode metadata, String primaryKey, String aliasKey, int fallback) {
        JsonNode value = metadataValue(metadata, primaryKey, aliasKey);
        return value == null || value.isMissingNode() || value.isNull() ? fallback : value.asInt(fallback);
    }

    private String stringMetadata(JsonNode metadata, String primaryKey, String aliasKey, String fallback) {
        JsonNode value = metadataValue(metadata, primaryKey, aliasKey);
        if (value == null || value.isMissingNode() || value.isNull()) {
            return fallback;
        }
        String raw = value.asText("");
        return raw.isBlank() ? fallback : raw;
    }

    private JsonNode metadataValue(JsonNode metadata, String primaryKey, String aliasKey) {
        if (metadata == null || !metadata.isObject()) {
            return null;
        }
        JsonNode value = metadata.path(primaryKey);
        if (!value.isMissingNode()) {
            return value;
        }
        return metadata.path(aliasKey);
    }

    private ObjectNode retrievalMetadata(DomainScopedRetrievalService.RetrievalRuntime runtime) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("retrieval_backend", runtime.retrievalBackend());
        node.put("dense_embedding_model", runtime.denseEmbeddingModel());
        node.put("retriever_mode", runtime.retrieverMode());
        node.put("retriever_candidate_pool_k", runtime.candidatePoolK());
        ObjectNode weights = node.putObject("retriever_fusion_weights");
        weights.put("dense", runtime.denseWeight());
        weights.put("bm25", runtime.bm25Weight());
        weights.put("technical", runtime.technicalWeight());
        return node;
    }

    private Decision routerRawOnlyDecision(
            String rawQuery,
            List<RagRepository.RetrievalDoc> rawRetrieved,
            QueryRouteDecision routeDecision
    ) {
        String rejectedReason = routeDecision.fallbackApplied()
                ? routeDecision.fallbackReason()
                : "query_router_strategy=" + routeDecision.strategy().name().toLowerCase(Locale.ROOT);
        return new Decision(
                rawQuery,
                false,
                rawRetrieved,
                null,
                routeDecision.reason(),
                rejectedReason
        );
    }

    private boolean usesSelectiveRewriteExecutionService(String mode, QueryRouteDecision routeDecision) {
        boolean forcedNonAnchorSelective = mode.startsWith("selective_rewrite")
                && !routeDecision.routerEnabled()
                && !routeDecision.anchorInjectionEnabled();
        boolean routerSelectedNonAnchorSelective =
                routeDecision.strategy() == QueryStrategy.SYNTHETIC_SELECTIVE_REWRITE
                        && !routeDecision.anchorInjectionEnabled();
        return forcedNonAnchorSelective || routerSelectedNonAnchorSelective;
    }

    private boolean usesAnchorAwareRewriteExecutionService(QueryRouteDecision routeDecision) {
        return routeDecision.strategy() == QueryStrategy.ANCHOR_AWARE_REWRITE
                || routeDecision.anchorInjectionEnabled();
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
            DomainScopedRetrievalService.RetrievalRuntime retrievalRuntime
    ) {
        if ("raw_only".equals(mode)) {
            return new Decision(rawQuery, false, rawRetrieved, null, "raw_only", "mode=raw_only");
        }
        if ("memory_only_ungated".equals(mode) || "memory_only_gated".equals(mode)) {
            if (memoryCandidates.isEmpty()) {
                return new Decision(rawQuery, false, rawRetrieved, null, "memory_empty", "no memory candidate");
            }
            String memoryQuery = memoryCandidates.getFirst().queryText();
            String embedding = domainScopedRetrievalService.embeddingLiteral(memoryQuery, retrievalRuntime);
            List<RagRepository.RetrievalDoc> retrievedLocal = domainScopedRetrievalService.retrieveChunks(
                    memoryQuery,
                    embedding,
                    Math.max(20, rawRetrieved.size()),
                    domainId,
                    retrievalRuntime
            );
            List<RagRepository.RetrievalDoc> retrieved = cohereRerankService.rerank(memoryQuery, retrievedLocal, rawRetrieved.size());
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

    private double confidence(List<RagRepository.RetrievalDoc> docs, double denseScore) {
        if (docs.isEmpty()) {
            return 0.0;
        }
        double r1 = normalizeScore(docs.getFirst().score());
        int topN = Math.min(3, docs.size());
        double r3 = 0.0;
        for (int index = 0; index < topN; index++) {
            r3 += normalizeScore(docs.get(index).score());
        }
        r3 /= topN;
        double dense = normalizeScore(denseScore);
        return (0.6 * r1) + (0.3 * r3) + (0.1 * dense);
    }

    private JsonNode scoreBreakdown(List<RagRepository.RetrievalDoc> retrieved, List<RagRepository.MemoryCandidate> memories) {
        ObjectNode node = objectMapper.createObjectNode();
        double dense = memories.isEmpty() ? 0.0 : memories.getFirst().similarity();
        node.put("r1", retrieved.isEmpty() ? 0.0 : normalizeScore(retrieved.getFirst().score()));
        node.put(
                "r3",
                retrieved.isEmpty()
                        ? 0.0
                        : retrieved.stream().limit(3).mapToDouble(item -> normalizeScore(item.score())).average().orElse(0.0)
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

    private JsonNode generationMethodCodes(List<RagRepository.MemoryCandidate> memories) {
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (RagRepository.MemoryCandidate memory : memories) {
            if (memory.generationStrategy() != null && !memory.generationStrategy().isBlank()) {
                unique.add(memory.generationStrategy().trim().toUpperCase(Locale.ROOT));
            }
        }
        ArrayNode array = objectMapper.createArrayNode();
        unique.forEach(array::add);
        return array;
    }

    private JsonNode generationBatchIds(List<RagRepository.MemoryCandidate> memories) {
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (RagRepository.MemoryCandidate memory : memories) {
            if (memory.generationBatchId() != null) {
                unique.add(memory.generationBatchId().toString());
            }
        }
        ArrayNode array = objectMapper.createArrayNode();
        unique.forEach(array::add);
        return array;
    }

    private ObjectNode runtimeMetadata(ChatRuntimeDtos.ChatRuntimeConfigResponse config) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("domain_id", config.domainId().toString());
        node.put("domain_key", config.domainKey());
        node.put("domain_display_name", config.displayName());
        node.put("mode", config.mode());
        node.put("gating_preset", config.gatingPreset());
        node.set("generation_strategies", objectMapper.valueToTree(config.generationStrategies()));
        putNullableUuid(node, "source_gating_batch_id", config.sourceGatingBatchId());
        putNullableUuid(node, "source_gating_run_id", config.sourceGatingRunId());
        node.set("source_gating_batch_ids", objectMapper.valueToTree(config.sourceGatingBatchIds()));
        node.set("source_gating_run_ids", objectMapper.valueToTree(config.sourceGatingRunIds()));
        node.put("rewrite_query_profile", config.rewriteQueryProfile());
        node.put("rewrite_anchor_injection_enabled", config.rewriteAnchorInjectionEnabled());
        node.put("use_session_context", config.useSessionContext());
        node.put("retrieval_backend", config.retrievalBackend());
        node.put("dense_embedding_model", config.denseEmbeddingModel());
        node.put("retriever_mode", config.retrieverMode());
        node.put("retriever_candidate_pool_k", config.retrieverCandidatePoolK());
        ObjectNode weights = node.putObject("retriever_fusion_weights");
        weights.put("dense", config.retrieverDenseWeight());
        weights.put("bm25", config.retrieverBm25Weight());
        weights.put("technical", config.retrieverTechnicalWeight());
        return node;
    }

    private ObjectNode rewriteLogMetadata(
            ChatRuntimeDtos.ChatRuntimeConfigResponse config,
            String rewriteQueryProfile,
            int retrievalTopK,
            int rerankTopN,
            int memoryTopN,
            int candidateCount,
            double threshold,
            Map<String, Long> latencyBreakdown,
            DomainScopedRetrievalService.RetrievalRuntime retrievalRuntime,
            QueryRouteDecision routeDecision
    ) {
        ObjectNode node = runtimeMetadata(config);
        node.put("rewrite_query_profile", rewriteQueryProfile);
        node.put("retrieval_top_k", retrievalTopK);
        node.put("rerank_top_n", rerankTopN);
        node.put("memory_top_n", memoryTopN);
        node.put("rewrite_candidate_count", candidateCount);
        node.put("rewrite_threshold", threshold);
        node.set("retrieval_runtime", retrievalMetadata(retrievalRuntime));
        node.set("latency_breakdown", objectMapper.valueToTree(latencyBreakdown));
        node.set("router", routerMetadata(routeDecision));
        return node;
    }

    private ObjectNode routerMetadataEnvelope(QueryRouteDecision routeDecision) {
        ObjectNode node = objectMapper.createObjectNode();
        node.set("router", routerMetadata(routeDecision));
        return node;
    }

    private ObjectNode routerMetadata(QueryRouteDecision routeDecision) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("enabled", routeDecision.routerEnabled());
        node.put("strategy", routeDecision.strategy().name());
        node.put("reason", routeDecision.reason());
        node.put("fallbackAllowed", routeDecision.fallbackAllowed());
        node.put("fallbackApplied", routeDecision.fallbackApplied());
        putNullableString(node, "fallbackReason", routeDecision.fallbackReason());
        node.put("effectiveMode", routeDecision.effectiveMode());
        node.put("rewriteQueryProfile", routeDecision.rewriteQueryProfile());
        node.put("anchorInjectionEnabled", routeDecision.anchorInjectionEnabled());
        JsonNode metadata = objectMapper.valueToTree(routeDecision.metadata());
        if (metadata.has("latencyMs")) {
            node.put("latencyMs", metadata.path("latencyMs").asLong());
        }
        node.set("metadata", metadata);
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
                rawRetrievalConfidence
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

    private void putNullableUuid(ObjectNode node, String fieldName, UUID value) {
        if (value == null) {
            node.putNull(fieldName);
        } else {
            node.put(fieldName, value.toString());
        }
    }

    private void putNullableString(ObjectNode node, String fieldName, String value) {
        if (value == null) {
            node.putNull(fieldName);
        } else {
            node.put(fieldName, value);
        }
    }

    private AnswerDraft buildAnswer(
            String rawQuery,
            String finalQuery,
            String domainDisplayName,
            List<RagRepository.RetrievalDoc> reranked
    ) {
        ChatAnswerService.GeneratedAnswer generated = chatAnswerService.generateAnswer(
                rawQuery,
                finalQuery,
                domainDisplayName,
                reranked
        );
        return new AnswerDraft(
                generated.answerText(),
                generated.citedDocumentIds(),
                generated.citedChunkIds(),
                generated.modelName()
        );
    }

    private AnswerDraft buildAnswer(List<RagRepository.RetrievalDoc> reranked) {
        StringBuilder builder = new StringBuilder();
        Set<String> docs = new HashSet<>();
        Set<String> chunks = new HashSet<>();
        int used = 0;
        for (RagRepository.RetrievalDoc row : reranked) {
            if (used >= 2) {
                break;
            }
            String snippet = preview(row.chunkText());
            if (!snippet.isBlank()) {
                if (builder.length() > 0) {
                    builder.append(" ");
                }
                builder.append(snippet);
                docs.add(row.documentId());
                chunks.add(row.chunkId());
                used++;
            }
        }
        if (builder.length() == 0) {
            builder.append("관련 문서를 찾았지만 질문에 직접 대응하는 근거를 충분히 찾지 못했습니다.");
        }
        return new AnswerDraft(
                builder.toString(),
                List.copyOf(docs),
                List.copyOf(chunks),
                "extractive-answer-simulated"
        );
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

    private String normalizedMode(String mode) {
        if (mode == null || mode.isBlank()) {
            return "selective_rewrite";
        }
        return mode.trim().toLowerCase(Locale.ROOT);
    }

    private String normalizedPreset(String preset) {
        if (preset == null || preset.isBlank()) {
            return "full_gating";
        }
        return preset.trim().toLowerCase(Locale.ROOT);
    }

    private String normalizedRewriteProfile(String profile) {
        if (profile == null || profile.isBlank()) {
            return "compact_anchor";
        }
        String normalized = profile.trim().toLowerCase(Locale.ROOT);
        return "detailed_intent".equals(normalized) ? normalized : "compact_anchor";
    }

    private int normalizedPositive(Integer value, int fallback) {
        if (value == null || value <= 0) {
            return fallback;
        }
        return value;
    }

    private String normalizedText(String value) {
        if (value == null) {
            return "";
        }
        return value.trim();
    }

    private JsonNode nullSafeJson(JsonNode node) {
        return node != null ? node : objectMapper.createObjectNode();
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

    private double normalizeScore(double score) {
        return Math.max(0.0, Math.min(1.0, (score + 1.0) / 2.0));
    }

    private record GeneratedCandidate(
            String label,
            String query,
            List<RagRepository.RetrievalDoc> retrieved,
            double confidence,
            UUID rewriteCandidateId
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

    private record AgenticSettings(
            boolean enabled,
            int maxSubqueries,
            String mergeStrategy,
            int rrfK
    ) {
    }

    private record AnswerDraft(
            String answerText,
            List<String> citedDocumentIds,
            List<String> citedChunkIds,
            String modelName
    ) {
    }
}
