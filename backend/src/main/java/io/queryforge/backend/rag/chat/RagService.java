package io.queryforge.backend.rag.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.queryforge.backend.rag.chat.ChatResponseAssembler.AgenticSettings;
import io.queryforge.backend.rag.chat.ChatResponseAssembler.AnswerDraft;
import io.queryforge.backend.rag.retrieval.candidate.DomainScopedRetrievalService;
import io.queryforge.backend.rag.retrieval.candidate.RetrievalDoc;
import io.queryforge.backend.rag.retrieval.execution.RagPersistPolicy;
import io.queryforge.backend.rag.retrieval.execution.RagRetrievalExecutionService;
import io.queryforge.backend.rag.retrieval.memory.MemoryCandidate;
import io.queryforge.backend.rag.retrieval.rerank.CohereRerankService;
import io.queryforge.backend.rag.retrieval.rewrite.RewriteCandidateService;
import io.queryforge.backend.rag.retrieval.rewrite.RewriteDtos;
import io.queryforge.backend.rag.retrieval.routing.QueryRouteDecision;
import io.queryforge.backend.rag.retrieval.routing.QueryStrategy;
import io.queryforge.backend.rag.retrieval.routing.QueryStrategyRouter;
import io.queryforge.backend.rag.runtime.configuration.ChatConfigDtos;
import io.queryforge.backend.rag.runtime.configuration.ChatRuntimeConfigService;
import io.queryforge.backend.rag.runtime.readiness.ChatReadinessDtos;
import io.queryforge.backend.rag.runtime.readiness.ChatReadinessService;
import io.queryforge.backend.rag.trace.write.RagTracePersistenceService;
import io.queryforge.backend.rag.trace.write.RagTraceRepository;
import io.queryforge.backend.rag.trace.write.TraceWriteResults;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static io.queryforge.backend.rag.chat.routing.ChatRequestRouting.routeContext;
import static io.queryforge.backend.rag.retrieval.rewrite.RewriteScoring.confidence;
import static io.queryforge.backend.rag.retrieval.rewrite.RewriteScoring.scoreBreakdown;

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
    private final RewriteCandidateService rewriteCandidateService;
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
        int retrievalTopK = normalizedPositive(config.retrievalTopK(), 10);
        int rerankTopN = normalizedPositive(config.rerankTopN(), 5);
        int memoryTopN = normalizedPositive(config.memoryTopN(), 5);
        int candidateCount = Math.min(normalizedPositive(config.rewriteCandidateCount(), 2), 2);
        double threshold = config.rewriteThreshold();
        String gatingPreset = normalizedPreset(config.gatingPreset());
        boolean useSessionContext = config.useSessionContext() || "selective_rewrite_with_session".equals(mode);
        JsonNode sessionContextSnapshot = useSessionContext ? responses.nullSafeJson(request.sessionContext()) : objectMapper.createObjectNode();
        DomainScopedRetrievalService.RetrievalRuntime retrievalRuntime =
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
            rawRetrievalLatency = responses.elapsedMs(stageStart);
            rawRetrieverName = retrievalRuntime.retrieverName();
            rawRetrievalMetadata = responses.retrievalMetadata(retrievalRuntime);
        }
        boolean rawOnlyTracePersistence = useRawOnlyTracePersistence(mode, rawOnlyRoute);
        if (rawOnlyTracePersistence) {
            ragTracePersistenceService.persistRawOnlyTrace(new RagTracePersistenceService.RawOnlyTracePersistenceRequest(
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
                    RagTracePersistenceService.RawOnlyTraceWriteScope.RETRIEVAL
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
        List<GeneratedCandidate> scoredCandidates = new ArrayList<>();
        RagRetrievalExecutionService.NonAgenticExecutionKind candidateRootAdoptionPersistenceKind = null;
        if (!rawOnlyRoute) {
            List<RagRetrievalExecutionService.ExecutedRewriteCandidate> executedCandidates = null;
            RagRetrievalExecutionService.NonAgenticExecutionKind executedCandidateKind = null;
            if (usesSelectiveRewriteExecutionService(mode, routeDecision)) {
                RagRetrievalExecutionService.SelectiveRewriteExecutionResult selectiveExecution =
                        ragRetrievalExecutionService.executeSelectiveRewrite(new RagRetrievalExecutionService.SelectiveRewriteExecutionRequest(
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
                                rawDense
                        ));
                executedCandidates = selectiveExecution.candidates();
                executedCandidateKind = selectiveExecution.executionKind();
            } else if (usesAnchorAwareRewriteExecutionService(routeDecision)) {
                RagRetrievalExecutionService.AnchorAwareRewriteExecutionResult anchorAwareExecution =
                        ragRetrievalExecutionService.executeAnchorAwareRewrite(new RagRetrievalExecutionService.AnchorAwareRewriteExecutionRequest(
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
                                rawDense
                        ));
                executedCandidates = anchorAwareExecution.candidates();
                executedCandidateKind = anchorAwareExecution.executionKind();
            }
            if (executedCandidates != null) {
                candidateRootAdoptionPersistenceKind = executedCandidateKind;
                for (int index = 0; index < executedCandidates.size(); index++) {
                    RagRetrievalExecutionService.ExecutedRewriteCandidate executed = executedCandidates.get(index);
                    int candidateIndex = executed.index() > 0 ? executed.index() : index + 1;
                    TraceWriteResults.RewriteCandidatePersistenceResult candidatePersistence =
                            ragTracePersistenceService.createRewriteCandidateTrace(
                                    new RagTracePersistenceService.CreateRewriteCandidateTracePersistenceRequest(
                                            RagPersistPolicy.ONLINE_QUERY,
                                            onlineQueryId,
                                            executedCandidateKind,
                                            candidateIndex,
                                            executed.label(),
                                            executed.query(),
                                            executed.metadata(),
                                            responses.memorySourceIds(memoryCandidates),
                                            objectMapper.valueToTree(executed.rerankedDocs()),
                                            executed.confidence(),
                                            scoreBreakdown(objectMapper, executed.rerankedDocs(), memoryCandidates)
                                    )
                            );
                    UUID candidateId = candidatePersistence.rewriteCandidateId();
                    ragTracePersistenceService.persistRewriteCandidateTrace(
                            new RagTracePersistenceService.RewriteCandidateTracePersistenceRequest(
                                    RagPersistPolicy.ONLINE_QUERY,
                                    onlineQueryId,
                                    candidateId,
                                    executedCandidateKind,
                                    candidateIndex,
                                    executed.query(),
                                    executed.label(),
                                    mode,
                                    executed.localRetrievedDocs(),
                                    executed.rerankedDocs(),
                                    executed.retrievalMetadata(),
                                    executed.retrieverName(),
                                    executed.rerankerModel(),
                                    executed.latencyMs(),
                                    RagTracePersistenceService.RewriteCandidateTraceWriteScope.CANDIDATE_RETRIEVAL
                            )
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
                        responses.domainContext(config)
                );
                for (int index = 0; index < generatedCandidates.size(); index++) {
                    RewriteCandidateService.CandidateTemplate generated = generatedCandidates.get(index);
                    String embeddingLiteral = domainScopedRetrievalService.embeddingLiteral(generated.query(), retrievalRuntime);
                    List<RetrievalDoc> candidateRetrievedLocal = domainScopedRetrievalService.retrieveChunks(
                            generated.query(),
                            embeddingLiteral,
                            retrievalTopK,
                            config.domainId(),
                            retrievalRuntime
                    );
                    List<RetrievalDoc> candidateRetrieved = cohereRerankService.rerank(
                            generated.query(),
                            candidateRetrievedLocal,
                            rerankTopN
                    );
                    double confidence = confidence(candidateRetrieved, rawDense);
                    UUID candidateId = traceRepository.createRewriteCandidate(
                            onlineQueryId,
                            index + 1,
                            generated.label(),
                            generated.query(),
                            responses.memorySourceIds(memoryCandidates),
                            objectMapper.valueToTree(candidateRetrieved),
                            confidence,
                            scoreBreakdown(objectMapper, candidateRetrieved, memoryCandidates)
                    );
                    traceRepository.insertRetrievalResults(
                            onlineQueryId,
                            candidateId,
                            "rewrite_candidate",
                            candidateRetrievedLocal,
                            mode,
                            retrievalRuntime.retrieverName(),
                            responses.retrievalMetadata(retrievalRuntime)
                    );
                    scoredCandidates.add(new GeneratedCandidate(generated.label(), generated.query(), candidateRetrieved, confidence, candidateId));
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
        if (decision.selectedCandidateId() != null) {
            for (GeneratedCandidate candidate : scoredCandidates) {
                boolean adopted = candidate.rewriteCandidateId().equals(decision.selectedCandidateId());
                if (candidateRootAdoptionPersistenceKind != null) {
                    ragTracePersistenceService.markRewriteCandidateAdopted(
                            new RagTracePersistenceService.RewriteCandidateAdoptionPersistenceRequest(
                                    RagPersistPolicy.ONLINE_QUERY,
                                    onlineQueryId,
                                    candidate.rewriteCandidateId(),
                                    candidateRootAdoptionPersistenceKind,
                                    adopted,
                                    adopted ? null : decision.rejectedReason()
                            )
                    );
                } else {
                    traceRepository.markRewriteCandidateAdopted(
                            candidate.rewriteCandidateId(),
                            adopted,
                            adopted ? null : decision.rejectedReason()
                    );
                }
            }
        } else {
            for (GeneratedCandidate candidate : scoredCandidates) {
                if (candidateRootAdoptionPersistenceKind != null) {
                    ragTracePersistenceService.markRewriteCandidateAdopted(
                            new RagTracePersistenceService.RewriteCandidateAdoptionPersistenceRequest(
                                    RagPersistPolicy.ONLINE_QUERY,
                                    onlineQueryId,
                                    candidate.rewriteCandidateId(),
                                    candidateRootAdoptionPersistenceKind,
                                    false,
                                    decision.rejectedReason()
                            )
                    );
                } else {
                    traceRepository.markRewriteCandidateAdopted(candidate.rewriteCandidateId(), false, decision.rejectedReason());
                }
            }
        }
        long decisionLatency = responses.elapsedMs(stageStart);

        stageStart = System.nanoTime();
        List<RetrievalDoc> reranked = decision.finalRetrieved();
        if (rawOnlyTracePersistence) {
            ragTracePersistenceService.persistRawOnlyTrace(new RagTracePersistenceService.RawOnlyTracePersistenceRequest(
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
                    RagTracePersistenceService.RawOnlyTraceWriteScope.RERANK
            ));
        } else {
            GeneratedCandidate selectedGeneratedCandidate = null;
            int selectedGeneratedCandidateIndex = 0;
            if (decision.selectedCandidateId() != null) {
                for (int index = 0; index < scoredCandidates.size(); index++) {
                    GeneratedCandidate candidate = scoredCandidates.get(index);
                    if (decision.selectedCandidateId().equals(candidate.rewriteCandidateId())) {
                        selectedGeneratedCandidate = candidate;
                        selectedGeneratedCandidateIndex = index + 1;
                        break;
                    }
                }
            }
            if (selectedGeneratedCandidate != null) {
                ragTracePersistenceService.persistRewriteCandidateTrace(
                        new RagTracePersistenceService.RewriteCandidateTracePersistenceRequest(
                                RagPersistPolicy.ONLINE_QUERY,
                                onlineQueryId,
                                decision.selectedCandidateId(),
                                rewriteCandidateTraceExecutionKind(routeDecision),
                                selectedGeneratedCandidateIndex,
                                selectedGeneratedCandidate.query(),
                                selectedGeneratedCandidate.label(),
                                mode,
                                List.of(),
                                reranked,
                                objectMapper.createObjectNode(),
                                null,
                                cohereRerankService.modelName(),
                                0L,
                                RagTracePersistenceService.RewriteCandidateTraceWriteScope.CANDIDATE_RERANK
                        )
                );
            } else {
                traceRepository.insertRerankResults(onlineQueryId, decision.selectedCandidateId(), reranked, cohereRerankService.modelName());
            }
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
        RagRetrievalExecutionService.NonAgenticExecutionKind decisionPersistenceKind =
                onlineQueryDecisionPersistenceKind(mode, routeDecision, rawOnlyRoute);
        ragTracePersistenceService.persistOnlineQueryDecision(
                new RagTracePersistenceService.OnlineQueryDecisionPersistenceRequest(
                        RagPersistPolicy.ONLINE_QUERY,
                        onlineQueryId,
                        decisionPersistenceKind,
                        rawQuery,
                        decision.finalQuery(),
                        decision.rewriteApplied(),
                        memoryTopNJson,
                        rawConfidence,
                        decision.selectedCandidateId(),
                        decision.finalRetrieved().size(),
                        decision.selectedReason(),
                        decision.rejectedReason(),
                        objectMapper.valueToTree(latencyBreakdown)
                )
        );
        ragTracePersistenceService.mergeOnlineQueryMetadata(
                new RagTracePersistenceService.OnlineQueryMetadataMergePersistenceRequest(
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
        if (candidateRootAdoptionPersistenceKind != null) {
            TraceWriteResults.OnlineRewriteLogPersistenceResult rewriteLogPersistence =
                    ragTracePersistenceService.createOnlineRewriteLogTrace(
                            new RagTracePersistenceService.OnlineRewriteLogPersistenceRequest(
                                    RagPersistPolicy.ONLINE_QUERY,
                                    onlineQueryId,
                                    null,
                                    candidateRootAdoptionPersistenceKind,
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
            if (candidateRootAdoptionPersistenceKind != null) {
                ragTracePersistenceService.insertMemoryRetrievalTrace(
                        new RagTracePersistenceService.MemoryRetrievalLogPersistenceRequest(
                                RagPersistPolicy.ONLINE_QUERY,
                                onlineQueryId,
                                rewriteLogId,
                                candidateRootAdoptionPersistenceKind,
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

        for (int index = 0; index < scoredCandidates.size(); index++) {
            GeneratedCandidate candidate = scoredCandidates.get(index);
            boolean selected = decision.selectedCandidateId() != null && decision.selectedCandidateId().equals(candidate.rewriteCandidateId());
            JsonNode candidateMetadata = objectMapper.valueToTree(Map.of(
                    "mode", mode,
                    "selected_reason", decision.selectedReason()
            ));
            if (candidateRootAdoptionPersistenceKind != null) {
                ragTracePersistenceService.insertRewriteCandidateTrace(
                        new RagTracePersistenceService.RewriteCandidateLogPersistenceRequest(
                                RagPersistPolicy.ONLINE_QUERY,
                                onlineQueryId,
                                rewriteLogId,
                                candidate.rewriteCandidateId(),
                                candidateRootAdoptionPersistenceKind,
                                index + 1,
                                candidate.label(),
                                candidate.query(),
                                candidate.confidence(),
                                selected,
                                selected ? null : decision.rejectedReason(),
                                objectMapper.valueToTree(candidate.retrieved()),
                                scoreBreakdown(objectMapper, candidate.retrieved(), memoryCandidates),
                                candidateMetadata
                        )
                );
            } else {
                traceRepository.insertRewriteCandidateLog(
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
                        scoreBreakdown(objectMapper, candidate.retrieved(), memoryCandidates),
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
                toRewriteDtos(scoredCandidates, decision.selectedCandidateId()),
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

    private List<RewriteDtos.RewriteCandidateDto> toRewriteDtos(
            List<GeneratedCandidate> candidates,
            UUID selectedCandidateId
    ) {
        return candidates.stream()
                .map(candidate -> new RewriteDtos.RewriteCandidateDto(
                        candidate.rewriteCandidateId(),
                        candidate.label(),
                        candidate.query(),
                        candidate.confidence(),
                        selectedCandidateId != null && selectedCandidateId.equals(candidate.rewriteCandidateId()),
                        selectedCandidateId != null && !selectedCandidateId.equals(candidate.rewriteCandidateId())
                                ? "not_selected"
                                : null,
                        objectMapper.valueToTree(candidate.retrieved()),
                        scoreBreakdown(objectMapper, candidate.retrieved(), List.of())
                ))
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

    private boolean usesSelectiveRewriteExecutionService(String mode, QueryRouteDecision routeDecision) {
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

    private RagRetrievalExecutionService.NonAgenticExecutionKind onlineQueryDecisionPersistenceKind(
            String mode,
            QueryRouteDecision routeDecision,
            boolean rawOnlyRoute
    ) {
        if (rawOnlyRoute || "raw_only".equals(mode)) {
            return RagRetrievalExecutionService.NonAgenticExecutionKind.RAW_ONLY;
        }
        return rewriteCandidateTraceExecutionKind(routeDecision);
    }

    private boolean usesAnchorAwareRewriteExecutionService(QueryRouteDecision routeDecision) {
        return routeDecision.strategy() == QueryStrategy.ANCHOR_AWARE_REWRITE
                || routeDecision.anchorInjectionEnabled();
    }

    private RagRetrievalExecutionService.NonAgenticExecutionKind rewriteCandidateTraceExecutionKind(
            QueryRouteDecision routeDecision
    ) {
        return usesAnchorAwareRewriteExecutionService(routeDecision)
                ? RagRetrievalExecutionService.NonAgenticExecutionKind.ANCHOR_AWARE_REWRITE
                : RagRetrievalExecutionService.NonAgenticExecutionKind.SELECTIVE_REWRITE;
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

    private record GeneratedCandidate(
            String label,
            String query,
            List<RetrievalDoc> retrieved,
            double confidence,
            UUID rewriteCandidateId
    ) {
    }

    private record Decision(
            String finalQuery,
            boolean rewriteApplied,
            List<RetrievalDoc> finalRetrieved,
            UUID selectedCandidateId,
            String selectedReason,
            String rejectedReason
    ) {
    }
}
