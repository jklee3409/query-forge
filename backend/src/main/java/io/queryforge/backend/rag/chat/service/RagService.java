package io.queryforge.backend.rag.chat.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.queryforge.backend.rag.chat.dto.ChatDtos;
import io.queryforge.backend.rag.chat.service.ChatResponseAssembler.AgenticSettings;
import io.queryforge.backend.rag.chat.service.ChatResponseAssembler.AnswerDraft;
import io.queryforge.backend.rag.retrieval.candidate.dto.RetrievalDtos;
import io.queryforge.backend.rag.retrieval.candidate.model.RetrievalDoc;
import io.queryforge.backend.rag.retrieval.candidate.service.DomainScopedRetrievalService;
import io.queryforge.backend.rag.retrieval.execution.dto.RetrievalExecutionDtos;
import io.queryforge.backend.rag.retrieval.execution.dto.RetrievalExecutionDtos.ExecutedRewriteCandidate;
import io.queryforge.backend.rag.retrieval.execution.dto.RetrievalExecutionDtos.NonAgenticExecutionKind;
import io.queryforge.backend.rag.retrieval.execution.dto.RetrievalExecutionDtos.RewriteExecutionRequest;
import io.queryforge.backend.rag.retrieval.execution.dto.RetrievalExecutionDtos.RewriteExecutionResult;
import io.queryforge.backend.rag.retrieval.execution.model.RagPersistPolicy;
import io.queryforge.backend.rag.retrieval.execution.service.RagRetrievalExecutionService;
import io.queryforge.backend.rag.retrieval.memory.model.MemoryCandidate;
import io.queryforge.backend.rag.retrieval.rerank.service.CohereRerankService;
import io.queryforge.backend.rag.retrieval.rewrite.dto.RewriteDtos;
import io.queryforge.backend.rag.retrieval.routing.dto.QueryRouteDecision;
import io.queryforge.backend.rag.retrieval.routing.model.QueryStrategy;
import io.queryforge.backend.rag.retrieval.routing.service.QueryStrategyRouter;
import io.queryforge.backend.rag.runtime.configuration.dto.ChatConfigDtos;
import io.queryforge.backend.rag.runtime.configuration.model.RagExecutionSettings;
import io.queryforge.backend.rag.runtime.configuration.service.ChatRuntimeConfigService;
import io.queryforge.backend.rag.runtime.readiness.dto.ChatReadinessDtos;
import io.queryforge.backend.rag.runtime.readiness.service.ChatReadinessService;
import io.queryforge.backend.rag.trace.write.dto.RagTraceWriteDtos;
import io.queryforge.backend.rag.trace.write.dto.TraceWriteResults;
import io.queryforge.backend.rag.trace.write.repository.RagTraceRepository;
import io.queryforge.backend.rag.trace.write.service.RagTracePersistenceService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static io.queryforge.backend.rag.chat.routing.service.ChatRequestRouting.routeContext;
import static io.queryforge.backend.rag.retrieval.rewrite.service.RewriteScoring.confidence;
import static io.queryforge.backend.rag.retrieval.rewrite.service.RewriteScoring.scoreBreakdown;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RagService {

    private final ChatResponseAssembler responses;
    private final AgenticChatService agenticChatService;
    private final RagTraceRepository traceRepository;
    private final DomainScopedRetrievalService domainScopedRetrievalService;
    private final RagRetrievalExecutionService ragRetrievalExecutionService;
    private final RagTracePersistenceService ragTracePersistenceService;
    private final CohereRerankService cohereRerankService;
    private final ChatRuntimeConfigService chatRuntimeConfigService;
    private final ChatReadinessService chatReadinessService;
    private final QueryStrategyRouter queryStrategyRouter;
    private final ObjectMapper objectMapper;

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public ChatDtos.AskResponse ask(ChatDtos.AskRequest request) {
        String rawQuery = normalizedText(request.query());
        if (rawQuery.isBlank()) {
            throw new IllegalArgumentException("query must not be blank");
        }
        if (request.domainId() == null) {
            throw new IllegalArgumentException("domainId is required for chat");
        }
        ChatConfigDtos.ChatRuntimeConfigResponse config = chatRuntimeConfigService.getConfig(request.domainId());
        ChatReadinessDtos.ChatDomainReadinessResponse readiness = chatReadinessService.getReadiness(request.domainId());
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
        long routeLatency = responses.elapsedMs(routeStarted);
        routeDecision = routeDecision.withLatency(routeLatency);
        if (!routeDecision.routerEnabled() && !"raw_only".equals(mode) && !readiness.readyForRewrite()) {
            throw new IllegalArgumentException(String.join("; ", readiness.blockingReasons()));
        }
        RagExecutionSettings settings = config.executionSettings();
        int retrievalTopK = settings.retrievalTopK();
        int rerankTopN = settings.rerankTopN();
        int memoryTopN = settings.memoryTopN();
        int candidateCount = settings.rewriteCandidateCount();
        double threshold = settings.rewriteThreshold();
        String gatingPreset = normalizedPreset(config.gatingPreset());
        boolean useSessionContext = config.useSessionContext() || "selective_rewrite_with_session".equals(mode);
        JsonNode sessionContextSnapshot = useSessionContext ? responses.nullSafeJson(request.sessionContext()) : objectMapper.createObjectNode();
        RetrievalDtos.RetrievalRuntime retrievalRuntime =
                domainScopedRetrievalService.retrievalRuntime(config);
        AgenticSettings configuredAgenticSettings = responses.agenticSettings(config.metadata());
        ObjectNode runtimeMetadata = responses.runtimeMetadata(config);
        runtimeMetadata.set("router", responses.routerMetadata(routeDecision));
        if (configuredAgenticSettings.enabled()) {
            runtimeMetadata.set("agentic_retrieval", responses.agenticSettingsMetadata(configuredAgenticSettings, retrievalTopK));
        }
        boolean rawOnlyRoute = routeDecision.routerEnabled() && routeDecision.strategy() == QueryStrategy.RAW_ONLY;

        Instant started = Instant.now();
        long stageStart = System.nanoTime();
        UUID onlineQueryId = traceRepository.createOnlineQuery(
                config.domainId(),
                request.sessionId(),
                rawQuery,
                sessionContextSnapshot,
                mode,
                threshold,
                runtimeMetadata
        );
        long createQueryLatency = responses.elapsedMs(stageStart);

        stageStart = System.nanoTime();
        String rawEmbeddingLiteral = domainScopedRetrievalService.embeddingLiteral(rawQuery, retrievalRuntime);
        String memoryPreset = switch (mode) {
            case "memory_only_ungated" -> "ungated";
            case "memory_only_gated" -> gatingPreset;
            default -> gatingPreset;
        };
        List<MemoryCandidate> memoryCandidates;
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
        long memoryLatency = responses.elapsedMs(stageStart);

        if (routeDecision.routerEnabled() && routeDecision.strategy() == QueryStrategy.AGENTIC_MULTI_QUERY) {
            routeStarted = System.nanoTime();
            QueryRouteDecision memoryAwareAgenticDecision = queryStrategyRouter.route(routeContext(
                    rawQuery,
                    config,
                    readiness,
                    mode,
                    rewriteQueryProfile,
                    config.rewriteAnchorInjectionEnabled(),
                    true,
                    !memoryCandidates.isEmpty(),
                    null
            ));
            routeLatency += responses.elapsedMs(routeStarted);
            routeDecision = memoryAwareAgenticDecision.withLatency(routeLatency);
            rawOnlyRoute = routeDecision.routerEnabled() && routeDecision.strategy() == QueryStrategy.RAW_ONLY;
        }

        boolean routerSelectedAgentic = routeDecision.routerEnabled()
                && routeDecision.strategy() == QueryStrategy.AGENTIC_MULTI_QUERY;
        boolean forcedAgentic = "agentic_multi_query".equals(mode);
        if (forcedAgentic || routerSelectedAgentic) {
            AgenticSettings agenticSettings = new AgenticSettings(
                    true,
                    configuredAgenticSettings.maxSubqueries(),
                    configuredAgenticSettings.mergeStrategy(),
                    configuredAgenticSettings.rrfK()
            );
            return agenticChatService.askAgentic(
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

        List<RetrievalDoc> rawRetrievedLocal;
        List<RetrievalDoc> rawRetrieved;
        double rawDense = memoryCandidates.isEmpty() ? 0.0 : memoryCandidates.getFirst().similarity();
        double rawConfidence;
        long rawRetrievalLatency;
        String rawRetrieverName;
        JsonNode rawRetrievalMetadata;
        if (rawOnlyRoute) {
            RetrievalExecutionDtos.RawOnlyExecutionResult rawOnlyExecution =
                    ragRetrievalExecutionService.executeRawOnly(new RetrievalExecutionDtos.RawOnlyExecutionRequest(
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
            rawRetrievalLatency = responses.elapsedMs(stageStart);
            rawRetrieverName = retrievalRuntime.retrieverName();
            rawRetrievalMetadata = responses.retrievalMetadata(retrievalRuntime);
        }
        boolean rawOnlyTracePersistence = useRawOnlyTracePersistence(mode, rawOnlyRoute);
        if (rawOnlyTracePersistence) {
            ragTracePersistenceService.persistRawOnlyTrace(new RagTraceWriteDtos.RawOnlyTracePersistenceRequest(
                    RagPersistPolicy.ONLINE_QUERY,
                    onlineQueryId,
                    rawQuery,
                    rawQuery,
                    mode,
                    rawRetrievedLocal,
                    rawRetrieved,
                    rawRetrievalMetadata,
                    rawRetrieverName,
                    null,
                    rawRetrievalLatency,
                    RagTraceWriteDtos.RawOnlyTraceWriteScope.RETRIEVAL
            ));
        } else {
            traceRepository.insertRetrievalResults(
                    onlineQueryId,
                    null,
                    "raw",
                    rawRetrievedLocal,
                    mode,
                    rawRetrieverName,
                    rawRetrievalMetadata
            );
        }

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
            routeLatency += responses.elapsedMs(routeStarted);
            routeDecision = refinedRouteDecision.withLatency(routeLatency);
            rawOnlyRoute = routeDecision.strategy() == QueryStrategy.RAW_ONLY;
        }

        stageStart = System.nanoTime();
        List<ExecutedRewriteCandidate> scoredCandidates = new ArrayList<>();
        Map<Integer, UUID> candidateIds = new HashMap<>();
        NonAgenticExecutionKind rewriteTraceKind = null;
        if (!rawOnlyRoute) {
            boolean selectiveExecution = isSelectiveRewriteExecution(mode, routeDecision);
            boolean anchorAwareExecution = !selectiveExecution && isAnchorAwareRewriteExecution(routeDecision);
            RewriteExecutionRequest executionRequest = new RewriteExecutionRequest(
                    rawQuery,
                    sessionContextSnapshot,
                    memoryCandidates,
                    candidateCount,
                    routeDecision.rewriteQueryProfile(),
                    responses.domainContext(config),
                    retrievalTopK,
                    rerankTopN,
                    config.domainId(),
                    retrievalRuntime,
                    rawDense,
                    anchorAwareExecution
            );
            if (selectiveExecution || anchorAwareExecution) {
                RewriteExecutionResult execution = ragRetrievalExecutionService.executeRewrite(executionRequest);
                rewriteTraceKind = execution.executionKind();
                scoredCandidates.addAll(execution.candidates());
                for (ExecutedRewriteCandidate candidate : scoredCandidates) {
                    UUID candidateId = persistRewriteCandidate(candidate, onlineQueryId, mode, memoryCandidates, rewriteTraceKind);
                    candidateIds.put(candidate.index(), candidateId);
                }
            } else {
                // This path records each completed candidate before executing the next.
                List<RewriteDtos.CandidateTemplate> templates =
                        ragRetrievalExecutionService.generateRewriteCandidates(executionRequest);
                for (int index = 0; index < templates.size(); index++) {
                    ExecutedRewriteCandidate candidate = ragRetrievalExecutionService.executeCandidate(
                            executionRequest, index + 1, templates.get(index)
                    );
                    UUID candidateId = persistRewriteCandidate(candidate, onlineQueryId, mode, memoryCandidates, null);
                    candidateIds.put(candidate.index(), candidateId);
                    scoredCandidates.add(candidate);
                }
            }
        }
        long candidateLatency = responses.elapsedMs(stageStart);

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
        ExecutedRewriteCandidate selectedCandidate = decision.selectedCandidate();
        UUID selectedCandidateId = selectedCandidate == null ? null : candidateIds.get(selectedCandidate.index());
        for (ExecutedRewriteCandidate candidate : scoredCandidates) {
            UUID candidateId = candidateIds.get(candidate.index());
            boolean adopted = selectedCandidate != null && selectedCandidate.index() == candidate.index();
            String rejectedReason = adopted ? null : decision.rejectedReason();
            if (rewriteTraceKind != null) {
                ragTracePersistenceService.markRewriteCandidateAdopted(
                        new RagTraceWriteDtos.RewriteCandidateAdoptionPersistenceRequest(
                                RagPersistPolicy.ONLINE_QUERY,
                                onlineQueryId,
                                candidateId,
                                rewriteTraceKind,
                                adopted,
                                rejectedReason
                        )
                );
            } else {
                traceRepository.markRewriteCandidateAdopted(candidateId, adopted, rejectedReason);
            }
        }
        long decisionLatency = responses.elapsedMs(stageStart);

        stageStart = System.nanoTime();
        List<RetrievalDoc> reranked = decision.finalRetrieved();
        if (rawOnlyTracePersistence) {
            ragTracePersistenceService.persistRawOnlyTrace(new RagTraceWriteDtos.RawOnlyTracePersistenceRequest(
                    RagPersistPolicy.ONLINE_QUERY,
                    onlineQueryId,
                    rawQuery,
                    decision.finalQuery(),
                    mode,
                    rawRetrievedLocal,
                    reranked,
                    rawRetrievalMetadata,
                    rawRetrieverName,
                    cohereRerankService.modelName(),
                    rawRetrievalLatency,
                    RagTraceWriteDtos.RawOnlyTraceWriteScope.RERANK
            ));
        } else if (selectedCandidate != null) {
            ragTracePersistenceService.persistRewriteCandidateTrace(
                    new RagTraceWriteDtos.RewriteCandidateTracePersistenceRequest(
                            RagPersistPolicy.ONLINE_QUERY,
                            onlineQueryId,
                            selectedCandidateId,
                            rewriteCandidateTraceExecutionKind(routeDecision),
                            selectedCandidate.index(),
                            selectedCandidate.query(),
                            selectedCandidate.label(),
                            mode,
                            List.of(),
                            reranked,
                            objectMapper.createObjectNode(),
                            null,
                            cohereRerankService.modelName(),
                            0L,
                            RagTraceWriteDtos.RewriteCandidateTraceWriteScope.CANDIDATE_RERANK
                    )
            );
        } else {
            traceRepository.insertRerankResults(onlineQueryId, null, reranked, cohereRerankService.modelName());
        }
        long rerankLatency = responses.elapsedMs(stageStart);

        stageStart = System.nanoTime();
        AnswerDraft answerDraft = responses.buildAnswer(
                rawQuery,
                decision.finalQuery(),
                config.displayName(),
                reranked
        );
        traceRepository.insertAnswer(
                onlineQueryId,
                answerDraft.answerText(),
                objectMapper.valueToTree(answerDraft.citedDocumentIds()),
                objectMapper.valueToTree(answerDraft.citedChunkIds()),
                answerDraft.modelName(),
                objectMapper.createObjectNode().put("source", "llm-chat-answer")
        );
        long answerLatency = responses.elapsedMs(stageStart);

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
        NonAgenticExecutionKind decisionPersistenceKind =
                onlineQueryDecisionPersistenceKind(mode, routeDecision, rawOnlyRoute);
        ragTracePersistenceService.persistOnlineQueryDecision(
                new RagTraceWriteDtos.OnlineQueryDecisionPersistenceRequest(
                        RagPersistPolicy.ONLINE_QUERY,
                        onlineQueryId,
                        decisionPersistenceKind,
                        rawQuery,
                        decision.finalQuery(),
                        decision.rewriteApplied(),
                        memoryTopNJson,
                        rawConfidence,
                        selectedCandidateId,
                        decision.finalRetrieved().size(),
                        decision.selectedReason(),
                        decision.rejectedReason(),
                        objectMapper.valueToTree(latencyBreakdown)
                )
        );
        ragTracePersistenceService.mergeOnlineQueryMetadata(
                new RagTraceWriteDtos.OnlineQueryMetadataMergePersistenceRequest(
                        RagPersistPolicy.ONLINE_QUERY,
                        onlineQueryId,
                        decisionPersistenceKind,
                        responses.routerMetadataEnvelope(routeDecision),
                        "router"
                )
        );

        double selectedConfidence = confidence(decision.finalRetrieved(), rawDense);
        boolean gatingApplied = !"raw_only".equals(mode) && !"memory_only_ungated".equals(mode);
        boolean selectiveRewrite = mode.startsWith("selective_rewrite");
        JsonNode rewriteMetadata = responses.rewriteLogMetadata(
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
        UUID rewriteLogId;
        if (rewriteTraceKind != null) {
            TraceWriteResults.OnlineRewriteLogPersistenceResult rewriteLogPersistence =
                    ragTracePersistenceService.createOnlineRewriteLogTrace(
                            new RagTraceWriteDtos.OnlineRewriteLogPersistenceRequest(
                                    RagPersistPolicy.ONLINE_QUERY,
                                    onlineQueryId,
                                    null,
                                    rewriteTraceKind,
                                    rawQuery,
                                    decision.finalQuery(),
                                    mode,
                                    responses.generationMethodCodes(memoryCandidates),
                                    responses.generationBatchIds(memoryCandidates),
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
                                    rewriteMetadata
                            )
                    );
            rewriteLogId = rewriteLogPersistence.rewriteLogId();
        } else {
            rewriteLogId = traceRepository.createOnlineRewriteLog(
                    onlineQueryId,
                    null,
                    rawQuery,
                    decision.finalQuery(),
                    mode,
                    responses.generationMethodCodes(memoryCandidates),
                    responses.generationBatchIds(memoryCandidates),
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
                    rewriteMetadata
            );
        }

        for (int index = 0; index < memoryCandidates.size(); index++) {
            MemoryCandidate memoryCandidate = memoryCandidates.get(index);
            JsonNode memoryMetadata = objectMapper.valueToTree(Map.of(
                    "gating_preset", gatingPreset,
                    "generation_batch_id", memoryCandidate.generationBatchId() == null ? "" : memoryCandidate.generationBatchId().toString(),
                    "source_gate_run_id", memoryCandidate.sourceGateRunId() == null ? "" : memoryCandidate.sourceGateRunId(),
                    "source_gating_batch_id", memoryCandidate.sourceGatingBatchId() == null ? "" : memoryCandidate.sourceGatingBatchId()
            ));
            if (rewriteTraceKind != null) {
                ragTracePersistenceService.insertMemoryRetrievalTrace(
                        new RagTraceWriteDtos.MemoryRetrievalLogPersistenceRequest(
                                RagPersistPolicy.ONLINE_QUERY,
                                onlineQueryId,
                                rewriteLogId,
                                rewriteTraceKind,
                                index + 1,
                                memoryCandidate,
                                memoryMetadata
                        )
                );
            } else {
                traceRepository.insertMemoryRetrievalLog(
                        rewriteLogId,
                        onlineQueryId,
                        index + 1,
                        memoryCandidate,
                        memoryMetadata
                );
            }
        }

        for (ExecutedRewriteCandidate candidate : scoredCandidates) {
            UUID candidateId = candidateIds.get(candidate.index());
            boolean selected = selectedCandidate != null && selectedCandidate.index() == candidate.index();
            JsonNode candidateMetadata = objectMapper.valueToTree(Map.of(
                    "mode", mode,
                    "selected_reason", decision.selectedReason()
            ));
            if (rewriteTraceKind != null) {
                ragTracePersistenceService.insertRewriteCandidateTrace(
                        new RagTraceWriteDtos.RewriteCandidateLogPersistenceRequest(
                                RagPersistPolicy.ONLINE_QUERY,
                                onlineQueryId,
                                rewriteLogId,
                                candidateId,
                                rewriteTraceKind,
                                candidate.index(),
                                candidate.label(),
                                candidate.query(),
                                candidate.confidence(),
                                selected,
                                selected ? null : decision.rejectedReason(),
                                objectMapper.valueToTree(candidate.rerankedDocs()),
                                scoreBreakdown(objectMapper, candidate.rerankedDocs(), memoryCandidates),
                                candidateMetadata
                        )
                );
            } else {
                traceRepository.insertRewriteCandidateLog(
                        rewriteLogId,
                        onlineQueryId,
                        candidateId,
                        candidate.index(),
                        candidate.label(),
                        candidate.query(),
                        candidate.confidence(),
                        selected,
                        selected ? null : decision.rejectedReason(),
                        objectMapper.valueToTree(candidate.rerankedDocs()),
                        scoreBreakdown(objectMapper, candidate.rerankedDocs(), memoryCandidates),
                        candidateMetadata
                );
            }
        }

        return new ChatDtos.AskResponse(
                onlineQueryId,
                answerDraft.answerText(),
                decision.finalQuery(),
                rawQuery,
                decision.rewriteApplied(),
                toRewriteDtos(scoredCandidates, candidateIds, selectedCandidate),
                responses.toScoredDocs(decision.finalRetrieved()),
                responses.toScoredDocs(reranked),
                memoryTopNJson,
                answerDraft.modelName(),
                answerDraft.citedDocumentIds(),
                answerDraft.citedChunkIds(),
                config,
                latencyBreakdown,
                null
        );
    }

    private UUID persistRewriteCandidate(
            ExecutedRewriteCandidate candidate,
            UUID onlineQueryId,
            String mode,
            List<MemoryCandidate> memoryCandidates,
            NonAgenticExecutionKind executionKind
    ) {
        JsonNode memorySourceIds = responses.memorySourceIds(memoryCandidates);
        JsonNode rerankedDocs = objectMapper.valueToTree(candidate.rerankedDocs());
        JsonNode scores = scoreBreakdown(objectMapper, candidate.rerankedDocs(), memoryCandidates);
        if (executionKind == null) {
            UUID candidateId = traceRepository.createRewriteCandidate(
                    onlineQueryId, candidate.index(), candidate.label(), candidate.query(),
                    memorySourceIds, rerankedDocs, candidate.confidence(), scores
            );
            traceRepository.insertRetrievalResults(
                    onlineQueryId, candidateId, "rewrite_candidate", candidate.localRetrievedDocs(),
                    mode, candidate.retrieverName(), candidate.retrievalMetadata()
            );
            return candidateId;
        }
        UUID candidateId = ragTracePersistenceService.createRewriteCandidateTrace(
                new RagTraceWriteDtos.CreateRewriteCandidateTracePersistenceRequest(
                        RagPersistPolicy.ONLINE_QUERY,
                        onlineQueryId,
                        executionKind,
                        candidate.index(),
                        candidate.label(),
                        candidate.query(),
                        candidate.metadata(),
                        memorySourceIds,
                        rerankedDocs,
                        candidate.confidence(),
                        scores
                )
        ).rewriteCandidateId();
        ragTracePersistenceService.persistRewriteCandidateTrace(
                new RagTraceWriteDtos.RewriteCandidateTracePersistenceRequest(
                        RagPersistPolicy.ONLINE_QUERY,
                        onlineQueryId,
                        candidateId,
                        executionKind,
                        candidate.index(),
                        candidate.query(),
                        candidate.label(),
                        mode,
                        candidate.localRetrievedDocs(),
                        candidate.rerankedDocs(),
                        candidate.retrievalMetadata(),
                        candidate.retrieverName(),
                        candidate.rerankerModel(),
                        candidate.latencyMs(),
                        RagTraceWriteDtos.RewriteCandidateTraceWriteScope.CANDIDATE_RETRIEVAL
                )
        );
        return candidateId;
    }

    private List<RewriteDtos.RewriteCandidateDto> toRewriteDtos(
            List<ExecutedRewriteCandidate> candidates,
            Map<Integer, UUID> candidateIds,
            ExecutedRewriteCandidate selectedCandidate
    ) {
        return candidates.stream()
                .map(candidate -> {
                    boolean adopted = selectedCandidate != null && selectedCandidate.index() == candidate.index();
                    return new RewriteDtos.RewriteCandidateDto(
                            candidateIds.get(candidate.index()),
                            candidate.label(),
                            candidate.query(),
                            candidate.confidence(),
                            adopted,
                            selectedCandidate != null && !adopted ? "not_selected" : null,
                            objectMapper.valueToTree(candidate.rerankedDocs()),
                            scoreBreakdown(objectMapper, candidate.rerankedDocs(), List.of())
                    );
                })
                .toList();
    }

    private Decision routerRawOnlyDecision(
            String rawQuery,
            List<RetrievalDoc> rawRetrieved,
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

    private boolean isSelectiveRewriteExecution(String mode, QueryRouteDecision routeDecision) {
        boolean forcedNonAnchorSelective = mode.startsWith("selective_rewrite")
                && !routeDecision.routerEnabled()
                && !routeDecision.anchorInjectionEnabled();
        boolean routerSelectedNonAnchorSelective =
                routeDecision.strategy() == QueryStrategy.SYNTHETIC_SELECTIVE_REWRITE
                        && !routeDecision.anchorInjectionEnabled();
        return forcedNonAnchorSelective || routerSelectedNonAnchorSelective;
    }

    private boolean useRawOnlyTracePersistence(String mode, boolean rawOnlyRoute) {
        return rawOnlyRoute && "raw_only".equals(mode);
    }

    private NonAgenticExecutionKind onlineQueryDecisionPersistenceKind(
            String mode,
            QueryRouteDecision routeDecision,
            boolean rawOnlyRoute
    ) {
        if (rawOnlyRoute || "raw_only".equals(mode)) {
            return NonAgenticExecutionKind.RAW_ONLY;
        }
        return rewriteCandidateTraceExecutionKind(routeDecision);
    }

    private boolean isAnchorAwareRewriteExecution(QueryRouteDecision routeDecision) {
        return routeDecision.strategy() == QueryStrategy.ANCHOR_AWARE_REWRITE
                || routeDecision.anchorInjectionEnabled();
    }

    private NonAgenticExecutionKind rewriteCandidateTraceExecutionKind(
            QueryRouteDecision routeDecision
    ) {
        return isAnchorAwareRewriteExecution(routeDecision)
                ? NonAgenticExecutionKind.ANCHOR_AWARE_REWRITE
                : NonAgenticExecutionKind.SELECTIVE_REWRITE;
    }

    private Decision decide(
            String mode,
            String rawQuery,
            double rawConfidence,
            List<RetrievalDoc> rawRetrieved,
            List<MemoryCandidate> memoryCandidates,
            List<ExecutedRewriteCandidate> candidates,
            double threshold,
            UUID domainId,
            RetrievalDtos.RetrievalRuntime retrievalRuntime
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
            List<RetrievalDoc> retrievedLocal = domainScopedRetrievalService.retrieveChunks(
                    memoryQuery,
                    embedding,
                    Math.max(20, rawRetrieved.size()),
                    domainId,
                    retrievalRuntime
            );
            List<RetrievalDoc> retrieved = cohereRerankService.rerank(memoryQuery, retrievedLocal, rawRetrieved.size());
            return new Decision(memoryQuery, true, retrieved, null, "memory_only", null);
        }

        ExecutedRewriteCandidate best = candidates.stream()
                .max(Comparator.comparingDouble(ExecutedRewriteCandidate::confidence))
                .orElse(null);
        if (best == null) {
            return new Decision(rawQuery, false, rawRetrieved, null, "no_candidate", "rewrite candidate missing");
        }

        if ("rewrite_always".equals(mode)) {
            return new Decision(best.query(), true, best.rerankedDocs(), best, "forced", null);
        }

        double delta = best.confidence() - rawConfidence;
        if (delta >= threshold) {
            return new Decision(best.query(), true, best.rerankedDocs(), best, "delta_above_threshold", null);
        }
        return new Decision(rawQuery, false, rawRetrieved, null, "delta_below_threshold", "best-candidate delta below threshold");
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

    private String normalizedText(String value) {
        if (value == null) {
            return "";
        }
        return value.trim();
    }

    private record Decision(
            String finalQuery,
            boolean rewriteApplied,
            List<RetrievalDoc> finalRetrieved,
            ExecutedRewriteCandidate selectedCandidate,
            String selectedReason,
            String rejectedReason
    ) {
    }
}
