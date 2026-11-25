package io.queryforge.backend.rag.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.queryforge.backend.rag.chat.ChatResponseAssembler.AgenticSettings;
import io.queryforge.backend.rag.chat.ChatResponseAssembler.AnswerDraft;
import io.queryforge.backend.rag.retrieval.agentic.AgenticRetrievalDtos;
import io.queryforge.backend.rag.retrieval.agentic.AgenticRetrievalService;
import io.queryforge.backend.rag.retrieval.candidate.DomainScopedRetrievalService;
import io.queryforge.backend.rag.retrieval.candidate.RetrievalDoc;
import io.queryforge.backend.rag.retrieval.execution.RagPersistPolicy;
import io.queryforge.backend.rag.retrieval.memory.MemoryCandidate;
import io.queryforge.backend.rag.retrieval.routing.QueryRouteDecision;
import io.queryforge.backend.rag.runtime.configuration.ChatConfigDtos;
import io.queryforge.backend.rag.runtime.readiness.ChatReadinessDtos;
import io.queryforge.backend.rag.trace.write.RagTraceRepository;
import io.queryforge.backend.rag.trace.write.TraceWriteResults;
import io.queryforge.backend.rag.trace.write.agentic.AgenticTracePersistenceService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.queryforge.backend.rag.retrieval.rewrite.RewriteScoring.confidence;
import static io.queryforge.backend.rag.retrieval.rewrite.RewriteScoring.scoreBreakdown;

@Service
@RequiredArgsConstructor
class AgenticChatService {

    private final ChatResponseAssembler responses;
    private final RagTraceRepository traceRepository;
    private final AgenticTracePersistenceService agenticTracePersistenceService;
    private final AgenticRetrievalService agenticRetrievalService;
    private final ObjectMapper objectMapper;

    ChatDtos.AskResponse askAgentic(
            ChatDtos.AskRequest request,
            String rawQuery,
            ChatConfigDtos.ChatRuntimeConfigResponse config,
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
            List<MemoryCandidate> plannerMemoryCandidates,
            JsonNode memoryTopNJson,
            long createQueryLatency,
            long memoryLatency,
            Instant started,
            AgenticSettings agenticSettings,
            ChatReadinessDtos.ChatDomainReadinessResponse readiness
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
        long agenticRetrievalLatency = responses.elapsedMs(stageStart);

        stageStart = System.nanoTime();
        List<RetrievalDoc> mergedDocs = agenticResult.mergedDocs();
        agenticTracePersistenceService.persistAgenticFinalRerankTrace(
                new AgenticTracePersistenceService.AgenticFinalRerankTracePersistenceRequest(
                        RagPersistPolicy.ONLINE_QUERY,
                        onlineQueryId,
                        AgenticTracePersistenceService.AgenticRetrievalExecutionKind.AGENTIC_MULTI_QUERY,
                        mergedDocs,
                        "agentic-rrf",
                        "agentic-rrf",
                        0L
                )
        );
        long rerankLatency = responses.elapsedMs(stageStart);

        stageStart = System.nanoTime();
        AnswerDraft answerDraft = responses.buildAnswer(
                rawQuery,
                rawQuery,
                config.displayName(),
                mergedDocs
        );
        ObjectNode answerMetadata = objectMapper.createObjectNode();
        answerMetadata.put("source", "llm-chat-answer");
        answerMetadata.put("agentic_multi_query", true);
        traceRepository.insertAnswer(
                onlineQueryId,
                answerDraft.answerText(),
                objectMapper.valueToTree(answerDraft.citedDocumentIds()),
                objectMapper.valueToTree(answerDraft.citedChunkIds()),
                answerDraft.modelName(),
                answerMetadata
        );
        long answerLatency = responses.elapsedMs(stageStart);

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
        JsonNode latencyBreakdownJson = objectMapper.valueToTree(latencyBreakdown);
        agenticTracePersistenceService.persistAgenticOnlineQueryDecision(
                new AgenticTracePersistenceService.AgenticOnlineQueryDecisionPersistenceRequest(
                        RagPersistPolicy.ONLINE_QUERY,
                        onlineQueryId,
                        AgenticTracePersistenceService.AgenticRetrievalExecutionKind.AGENTIC_MULTI_QUERY,
                        rawQuery,
                        rawQuery,
                        agenticResult.rewriteApplied(),
                        memoryTopNJson,
                        selectedConfidence,
                        null,
                        mergedDocs.size(),
                        agenticResult.selectedReason(),
                        agenticResult.rejectedReason(),
                        latencyBreakdownJson
                )
        );
        ObjectNode agenticEnvelope = responses.agenticMetadataEnvelope(agenticResult, agenticSettings, retrievalTopK, routeDecision);
        agenticTracePersistenceService.mergeAgenticOnlineQueryMetadata(
                new AgenticTracePersistenceService.AgenticOnlineQueryMetadataMergePersistenceRequest(
                        RagPersistPolicy.ONLINE_QUERY,
                        onlineQueryId,
                        AgenticTracePersistenceService.AgenticRetrievalExecutionKind.AGENTIC_MULTI_QUERY,
                        agenticEnvelope,
                        "agentic_retrieval"
                )
        );

        boolean gatingApplied = !"raw_only".equals(mode) && !"memory_only_ungated".equals(mode);
        boolean selectiveRewrite = mode.startsWith("selective_rewrite");
        ObjectNode rewriteMetadata = responses.rewriteLogMetadata(
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
        TraceWriteResults.OnlineRewriteLogPersistenceResult rewriteLogPersistence =
                agenticTracePersistenceService.createAgenticOnlineRewriteLogTrace(
                        new AgenticTracePersistenceService.AgenticOnlineRewriteLogPersistenceRequest(
                                RagPersistPolicy.ONLINE_QUERY,
                                onlineQueryId,
                                null,
                                AgenticTracePersistenceService.AgenticRetrievalExecutionKind.AGENTIC_MULTI_QUERY,
                                rawQuery,
                                rawQuery,
                                mode,
                                responses.generationMethodCodes(plannerMemoryCandidates),
                                responses.generationBatchIds(plannerMemoryCandidates),
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
                        )
                );
        UUID rewriteLogId = rewriteLogPersistence.rewriteLogId();

        for (int index = 0; index < plannerMemoryCandidates.size(); index++) {
            MemoryCandidate memoryCandidate = plannerMemoryCandidates.get(index);
            agenticTracePersistenceService.insertAgenticMemoryRetrievalTrace(
                    new AgenticTracePersistenceService.AgenticMemoryRetrievalLogPersistenceRequest(
                            RagPersistPolicy.ONLINE_QUERY,
                            onlineQueryId,
                            rewriteLogId,
                            AgenticTracePersistenceService.AgenticRetrievalExecutionKind.AGENTIC_MULTI_QUERY,
                            index + 1,
                            memoryCandidate,
                            objectMapper.valueToTree(Map.of(
                                    "gating_preset", gatingPreset,
                                    "generation_batch_id", memoryCandidate.generationBatchId() == null ? "" : memoryCandidate.generationBatchId().toString(),
                                    "source_gate_run_id", memoryCandidate.sourceGateRunId() == null ? "" : memoryCandidate.sourceGateRunId(),
                                    "source_gating_batch_id", memoryCandidate.sourceGatingBatchId() == null ? "" : memoryCandidate.sourceGatingBatchId(),
                                    "agentic_role", "planner_memory_hint"
                            ))
                    )
            );
        }

        List<AgenticRetrievalService.PersistedRewriteCandidate> persistedCandidates = agenticResult.persistedCandidates();
        for (int index = 0; index < persistedCandidates.size(); index++) {
            AgenticRetrievalService.PersistedRewriteCandidate candidate = persistedCandidates.get(index);
            ObjectNode candidateMetadata = objectMapper.createObjectNode();
            candidateMetadata.put("mode", mode);
            candidateMetadata.put("selected_reason", agenticResult.selectedReason());
            candidateMetadata.put("agentic_multi_query", true);
            agenticTracePersistenceService.insertAgenticRewriteCandidateTrace(
                    new AgenticTracePersistenceService.AgenticRewriteCandidateLogPersistenceRequest(
                            RagPersistPolicy.ONLINE_QUERY,
                            onlineQueryId,
                            rewriteLogId,
                            candidate.rewriteCandidateId(),
                            AgenticTracePersistenceService.AgenticRetrievalExecutionKind.AGENTIC_MULTI_QUERY,
                            index + 1,
                            candidate.label(),
                            candidate.query(),
                            candidate.confidence(),
                            candidate.selected(),
                            candidate.selected() ? null : candidate.rejectedReason(),
                            objectMapper.valueToTree(candidate.retrieved()),
                            candidate.scoreBreakdown(),
                            candidateMetadata
                    )
            );
        }

        AgenticRetrievalDtos.AgenticRetrievalMetadata responseMetadata = responses.toAgenticMetadata(agenticResult, agenticSettings, retrievalTopK);
        return new ChatDtos.AskResponse(
                onlineQueryId,
                answerDraft.answerText(),
                rawQuery,
                rawQuery,
                agenticResult.rewriteApplied(),
                responses.toAgenticRewriteDtos(persistedCandidates),
                responses.toScoredDocs(mergedDocs),
                responses.toScoredDocs(mergedDocs),
                memoryTopNJson,
                answerDraft.modelName(),
                answerDraft.citedDocumentIds(),
                answerDraft.citedChunkIds(),
                config,
                latencyBreakdown,
                responseMetadata
        );
    }
}
