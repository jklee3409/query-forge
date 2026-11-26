package io.queryforge.backend.rag.chat.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.queryforge.backend.rag.chat.dto.ChatDtos;
import io.queryforge.backend.rag.chat.service.ChatResponseAssembler.AgenticSettings;
import io.queryforge.backend.rag.chat.service.ChatResponseAssembler.AnswerDraft;
import io.queryforge.backend.rag.retrieval.agentic.dto.AgenticExecutionDtos.AgenticExecutionRequest;
import io.queryforge.backend.rag.retrieval.candidate.dto.RetrievalDtos;
import io.queryforge.backend.rag.retrieval.candidate.model.RetrievalDoc;
import io.queryforge.backend.rag.retrieval.candidate.service.DomainScopedRetrievalService;
import io.queryforge.backend.rag.retrieval.execution.dto.RetrievalExecutionDtos.ExecutedRewriteCandidate;
import io.queryforge.backend.rag.retrieval.execution.dto.RetrievalExecutionDtos.NonAgenticExecutionKind;
import io.queryforge.backend.rag.retrieval.execution.dto.RetrievalExecutionDtos.RawRetrievalRequest;
import io.queryforge.backend.rag.retrieval.execution.dto.RetrievalExecutionDtos.RetrievalMaterial;
import io.queryforge.backend.rag.retrieval.execution.dto.RetrievalExecutionDtos.RewriteExecutionRequest;
import io.queryforge.backend.rag.retrieval.execution.dto.RetrievalExecutionDtos.RewriteExecutionResult;
import io.queryforge.backend.rag.retrieval.execution.model.RagPersistPolicy;
import io.queryforge.backend.rag.retrieval.execution.service.RagRetrievalExecutionService;
import io.queryforge.backend.rag.retrieval.memory.model.MemoryCandidate;
import io.queryforge.backend.rag.retrieval.rewrite.dto.RewriteDtos;
import io.queryforge.backend.rag.retrieval.rewrite.policy.RewriteDecisionPolicy;
import io.queryforge.backend.rag.retrieval.rewrite.policy.RewriteDecisionPolicy.Decision;
import io.queryforge.backend.rag.retrieval.rewrite.service.RewriteDomainContext;
import io.queryforge.backend.rag.retrieval.routing.dto.QueryRouteContext;
import io.queryforge.backend.rag.retrieval.routing.service.QueryRouteContexts;
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
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

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
        ChatReadinessDtos.ChatDomainReadinessResponse readiness = chatReadinessService.evaluateReadiness(config);
        if (!readiness.activeConfigPresent()) {
            throw new IllegalArgumentException("active chat_runtime_config is missing for domain: " + config.displayName());
        }
        if (!config.enabled()) {
            throw new IllegalArgumentException("chat is disabled for domain: " + config.displayName());
        }
        String mode = normalizedMode(config.mode());
        String rewriteQueryProfile = normalizedRewriteProfile(config.rewriteQueryProfile());
        long routeStarted = System.nanoTime();
        QueryRouteContext routingContext = QueryRouteContexts.initialRouteContext(
                rawQuery, config, readiness, mode, rewriteQueryProfile, false
        );
        QueryRouteDecision routeDecision = queryStrategyRouter.route(routingContext);
        long routeLatency = responses.elapsedMs(routeStarted);
        routeDecision = routeDecision.withLatency(routeLatency);
        if (!routeDecision.routerEnabled() && !"raw_only".equals(mode) && !readiness.readyForRewrite()) {
            throw new IllegalArgumentException(String.join("; ", readiness.blockingReasons()));
        }
        RagExecutionSettings settings = config.executionSettings();
        int retrievalTopK = settings.retrievalTopK();
        int rerankTopN = settings.rerankTopN();
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
        String memoryPreset = "memory_only_ungated".equals(mode) ? "ungated" : gatingPreset;
        List<MemoryCandidate> memoryCandidates;
        if (rawOnlyRoute) {
            memoryCandidates = List.of();
        } else {
            memoryCandidates = domainScopedRetrievalService.findMemoryCandidates(
                    rawQuery,
                    rawEmbeddingLiteral,
                    settings.memoryTopN(),
                    memoryPreset,
                    config,
                    retrievalRuntime
            );
        }
        long memoryLatency = responses.elapsedMs(stageStart);
        boolean memoryAvailable = !memoryCandidates.isEmpty();

        if (routeDecision.routerEnabled() && routeDecision.strategy() == QueryStrategy.AGENTIC_MULTI_QUERY) {
            routeStarted = System.nanoTime();
            QueryRouteDecision memoryAwareAgenticDecision =
                    queryStrategyRouter.route(routingContext.withRetrievalEvidence(memoryAvailable, null));
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
            AgenticExecutionRequest agenticRequest = new AgenticExecutionRequest(
                    rawQuery,
                    onlineQueryId,
                    config,
                    readiness,
                    sessionContextSnapshot,
                    memoryCandidates,
                    mode,
                    rewriteQueryProfile,
                    memoryPreset,
                    settings,
                    agenticSettings.maxSubqueries(),
                    agenticSettings.rrfK(),
                    retrievalTopK,
                    RagPersistPolicy.ONLINE_QUERY
            );
            return agenticChatService.askAgentic(
                    agenticRequest,
                    routeDecision,
                    gatingPreset,
                    useSessionContext,
                    retrievalRuntime,
                    createQueryLatency,
                    memoryLatency,
                    started,
                    agenticSettings
            );
        }

        JsonNode memoryTopNJson = objectMapper.valueToTree(memoryCandidates);
        double rawDense = memoryAvailable ? memoryCandidates.getFirst().similarity() : 0.0d;
        RetrievalMaterial rawRetrieval = ragRetrievalExecutionService.executeRawRetrieval(
                new RawRetrievalRequest(rawQuery, rawEmbeddingLiteral, retrievalTopK, rerankTopN,
                        config.domainId(), retrievalRuntime),
                rawDense
        );
        double rawConfidence = rawRetrieval.confidence();
        long rawRetrievalLatency = rawRetrieval.latencyMs();
        boolean rawOnlyTracePersistence = useRawOnlyTracePersistence(mode, rawOnlyRoute);
        if (rawOnlyTracePersistence) {
            ragTracePersistenceService.persistRawOnlyTrace(new RagTraceWriteDtos.RawOnlyTracePersistenceRequest(
                    RagPersistPolicy.ONLINE_QUERY,
                    onlineQueryId,
                    rawQuery,
                    rawQuery,
                    mode,
                    rawRetrieval.retrievedDocs(),
                    rawRetrieval.rerankedDocs(),
                    rawRetrieval.retrievalMetadata(),
                    rawRetrieval.retrieverName(),
                    null,
                    rawRetrievalLatency,
                    RagTraceWriteDtos.RawOnlyTraceWriteScope.RETRIEVAL
            ));
        } else {
            traceRepository.insertRetrievalResults(
                    onlineQueryId,
                    null,
                    "raw",
                    rawRetrieval.retrievedDocs(),
                    mode,
                    rawRetrieval.retrieverName(),
                    rawRetrieval.retrievalMetadata()
            );
        }

        if (routeDecision.routerEnabled() && !rawOnlyRoute) {
            routeStarted = System.nanoTime();
            QueryRouteDecision refinedRouteDecision =
                    queryStrategyRouter.route(routingContext.withRetrievalEvidence(memoryAvailable, rawConfidence));
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
                    settings.rewriteCandidateCount(),
                    routeDecision.rewriteQueryProfile(),
                    RewriteDomainContext.create(objectMapper, config),
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
        RetrievalMaterial memoryRetrieval = null;
        if (!rawOnlyRoute && memoryAvailable && RewriteDecisionPolicy.usesMemoryQuery(mode)) {
            memoryRetrieval = ragRetrievalExecutionService.executeMemoryRetrieval(
                    memoryCandidates.getFirst(), rawRetrieval.rerankedDocs().size(), config.domainId(), retrievalRuntime
            );
        }
        Decision decision = rawOnlyRoute
                ? RewriteDecisionPolicy.routerRawOnly(rawRetrieval, routeDecision)
                : RewriteDecisionPolicy.decide(mode, rawRetrieval, scoredCandidates, threshold, memoryRetrieval);
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
                    rawRetrieval.retrievedDocs(),
                    reranked,
                    rawRetrieval.retrievalMetadata(),
                    rawRetrieval.retrieverName(),
                    rawRetrieval.rerankerModel(),
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
                            rawRetrieval.rerankerModel(),
                            0L,
                            RagTraceWriteDtos.RewriteCandidateTraceWriteScope.CANDIDATE_RERANK
                    )
            );
        } else {
            traceRepository.insertRerankResults(onlineQueryId, null, reranked, rawRetrieval.rerankerModel());
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
                settings,
                latencyBreakdown,
                retrievalRuntime,
                routeDecision
        );
        ragTracePersistenceService.persistOnlineRewriteLogs(
                new RagTraceWriteDtos.OnlineRewriteLogPersistenceRequest(
                        RagPersistPolicy.ONLINE_QUERY,
                        onlineQueryId,
                        null,
                        decisionPersistenceKind,
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
                ),
                memoryCandidates,
                scoredCandidates,
                candidateIds,
                selectedCandidate == null ? null : selectedCandidate.index()
        );

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

}
