package io.queryforge.backend.rag.trace.write.agentic;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import io.queryforge.backend.rag.retrieval.candidate.RetrievalDoc;
import io.queryforge.backend.rag.retrieval.execution.RagPersistPolicy;
import io.queryforge.backend.rag.retrieval.memory.MemoryCandidate;
import io.queryforge.backend.rag.trace.write.RagTraceRepository;
import io.queryforge.backend.rag.trace.write.TraceWriteResults.OnlineRewriteLogPersistenceResult;
import io.queryforge.backend.rag.trace.write.TraceWriteResults.RagTracePersistenceResult;
import io.queryforge.backend.rag.trace.write.TraceWriteResults.RewriteCandidatePersistenceResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AgenticTracePersistenceService {

    private final RagTraceRepository traceRepository;

    public RagTracePersistenceResult persistAgenticSubqueryRetrievalTrace(
            AgenticSubqueryRetrievalTracePersistenceRequest request
    ) {
        if (request == null) {
            throw new IllegalArgumentException("request is required");
        }
        return switch (request.persistPolicy()) {
            case NONE -> new RagTracePersistenceResult(
                    RagPersistPolicy.NONE,
                    request.onlineQueryId(),
                    false,
                    "skipped_none"
            );
            case TRACE_ONLY -> throw new UnsupportedOperationException(
                    "TRACE_ONLY persistence is not implemented for Phase 6A agentic subquery retrieval trace"
            );
            case ONLINE_QUERY -> persistAgenticSubqueryRetrievalOnlineQueryTrace(request);
        };
    }

    public RewriteCandidatePersistenceResult createAgenticRewriteCandidateTrace(
            AgenticRewriteCandidateTracePersistenceRequest request
    ) {
        if (request == null) {
            throw new IllegalArgumentException("request is required");
        }
        return switch (request.persistPolicy()) {
            case NONE -> new RewriteCandidatePersistenceResult(
                    RagPersistPolicy.NONE,
                    request.onlineQueryId(),
                    null,
                    false,
                    "skipped_none"
            );
            case TRACE_ONLY -> throw new UnsupportedOperationException(
                    "TRACE_ONLY persistence is not implemented for Phase 6B agentic rewrite candidate persistence"
            );
            case ONLINE_QUERY -> createAgenticRewriteCandidateOnlineQueryTrace(request);
        };
    }

    public RewriteCandidatePersistenceResult markAgenticRewriteCandidateAdopted(
            AgenticRewriteCandidateAdoptionPersistenceRequest request
    ) {
        if (request == null) {
            throw new IllegalArgumentException("request is required");
        }
        return switch (request.persistPolicy()) {
            case NONE -> new RewriteCandidatePersistenceResult(
                    RagPersistPolicy.NONE,
                    request.onlineQueryId(),
                    request.rewriteCandidateId(),
                    false,
                    "skipped_none"
            );
            case TRACE_ONLY -> throw new UnsupportedOperationException(
                    "TRACE_ONLY persistence is not implemented for Phase 6B agentic rewrite candidate persistence"
            );
            case ONLINE_QUERY -> markAgenticRewriteCandidateAdoptedOnlineQueryTrace(request);
        };
    }

    public RagTracePersistenceResult persistAgenticFinalRerankTrace(
            AgenticFinalRerankTracePersistenceRequest request
    ) {
        if (request == null) {
            throw new IllegalArgumentException("request is required");
        }
        return switch (request.persistPolicy()) {
            case NONE -> new RagTracePersistenceResult(
                    RagPersistPolicy.NONE,
                    request.onlineQueryId(),
                    false,
                    "skipped_none"
            );
            case TRACE_ONLY -> throw new UnsupportedOperationException(
                    "TRACE_ONLY persistence is not implemented for Phase 6C agentic final RRF rerank trace"
            );
            case ONLINE_QUERY -> persistAgenticFinalRerankOnlineQueryTrace(request);
        };
    }

    public OnlineRewriteLogPersistenceResult createAgenticOnlineRewriteLogTrace(
            AgenticOnlineRewriteLogPersistenceRequest request
    ) {
        if (request == null) {
            throw new IllegalArgumentException("request is required");
        }
        return switch (request.persistPolicy()) {
            case NONE -> new OnlineRewriteLogPersistenceResult(
                    RagPersistPolicy.NONE,
                    request.onlineQueryId(),
                    null,
                    false,
                    "skipped_none"
            );
            case TRACE_ONLY -> throw new UnsupportedOperationException(
                    "TRACE_ONLY persistence is not implemented for Phase 6D agentic rewrite log persistence"
            );
            case ONLINE_QUERY -> createAgenticOnlineRewriteLogOnlineQueryTrace(request);
        };
    }

    public RagTracePersistenceResult insertAgenticMemoryRetrievalTrace(
            AgenticMemoryRetrievalLogPersistenceRequest request
    ) {
        if (request == null) {
            throw new IllegalArgumentException("request is required");
        }
        return switch (request.persistPolicy()) {
            case NONE -> new RagTracePersistenceResult(
                    RagPersistPolicy.NONE,
                    request.onlineQueryId(),
                    false,
                    "skipped_none"
            );
            case TRACE_ONLY -> throw new UnsupportedOperationException(
                    "TRACE_ONLY persistence is not implemented for Phase 6D agentic memory retrieval log persistence"
            );
            case ONLINE_QUERY -> insertAgenticMemoryRetrievalOnlineQueryTrace(request);
        };
    }

    public RagTracePersistenceResult insertAgenticRewriteCandidateTrace(
            AgenticRewriteCandidateLogPersistenceRequest request
    ) {
        if (request == null) {
            throw new IllegalArgumentException("request is required");
        }
        return switch (request.persistPolicy()) {
            case NONE -> new RagTracePersistenceResult(
                    RagPersistPolicy.NONE,
                    request.onlineQueryId(),
                    false,
                    "skipped_none"
            );
            case TRACE_ONLY -> throw new UnsupportedOperationException(
                    "TRACE_ONLY persistence is not implemented for Phase 6D agentic rewrite candidate log persistence"
            );
            case ONLINE_QUERY -> insertAgenticRewriteCandidateOnlineQueryTrace(request);
        };
    }

    public RagTracePersistenceResult persistAgenticOnlineQueryDecision(
            AgenticOnlineQueryDecisionPersistenceRequest request
    ) {
        if (request == null) {
            throw new IllegalArgumentException("request is required");
        }
        return switch (request.persistPolicy()) {
            case NONE -> new RagTracePersistenceResult(
                    RagPersistPolicy.NONE,
                    request.onlineQueryId(),
                    false,
                    "skipped_none"
            );
            case TRACE_ONLY -> throw new UnsupportedOperationException(
                    "TRACE_ONLY persistence is not implemented for Phase 6E agentic online query decision persistence"
            );
            case ONLINE_QUERY -> persistAgenticOnlineQueryDecisionTrace(request);
        };
    }

    public RagTracePersistenceResult mergeAgenticOnlineQueryMetadata(
            AgenticOnlineQueryMetadataMergePersistenceRequest request
    ) {
        if (request == null) {
            throw new IllegalArgumentException("request is required");
        }
        return switch (request.persistPolicy()) {
            case NONE -> new RagTracePersistenceResult(
                    RagPersistPolicy.NONE,
                    request.onlineQueryId(),
                    false,
                    "skipped_none"
            );
            case TRACE_ONLY -> throw new UnsupportedOperationException(
                    "TRACE_ONLY persistence is not implemented for Phase 6E agentic online query metadata persistence"
            );
            case ONLINE_QUERY -> mergeAgenticOnlineQueryMetadataTrace(request);
        };
    }

    private RagTracePersistenceResult persistAgenticSubqueryRetrievalOnlineQueryTrace(
            AgenticSubqueryRetrievalTracePersistenceRequest request
    ) {
        if (request.onlineQueryId() == null) {
            throw new IllegalArgumentException("onlineQueryId is required for ONLINE_QUERY agentic subquery retrieval trace persistence");
        }
        if (request.executionKind() == null) {
            throw new IllegalArgumentException("executionKind is required for ONLINE_QUERY agentic subquery retrieval trace persistence");
        }
        if (request.mode().isBlank()) {
            throw new IllegalArgumentException("mode is required for ONLINE_QUERY agentic subquery retrieval trace persistence");
        }
        switch (request.writeScope()) {
            case SUBQUERY_RAW_RETRIEVAL -> {
                if (request.rewriteCandidateId() != null) {
                    throw new IllegalArgumentException("rewriteCandidateId must be null for agentic subquery raw retrieval trace persistence");
                }
                traceRepository.insertRetrievalResults(
                        request.onlineQueryId(),
                        null,
                        request.writeScope().repositoryStage,
                        request.retrievedDocs(),
                        request.mode(),
                        request.retrieverName(),
                        request.retrievalMetadata()
                );
            }
            case SUBQUERY_CANDIDATE_RETRIEVAL -> {
                if (request.rewriteCandidateId() == null) {
                    throw new IllegalArgumentException("rewriteCandidateId is required for agentic subquery candidate retrieval trace persistence");
                }
                traceRepository.insertRetrievalResults(
                        request.onlineQueryId(),
                        request.rewriteCandidateId(),
                        request.writeScope().repositoryStage,
                        request.retrievedDocs(),
                        request.mode(),
                        request.retrieverName(),
                        request.retrievalMetadata()
                );
            }
        }
        return new RagTracePersistenceResult(
                RagPersistPolicy.ONLINE_QUERY,
                request.onlineQueryId(),
                true,
                "persisted_" + request.executionKind().name().toLowerCase(Locale.ROOT)
                        + "_" + request.writeScope().statusSuffix
        );
    }

    private RagTracePersistenceResult persistAgenticFinalRerankOnlineQueryTrace(
            AgenticFinalRerankTracePersistenceRequest request
    ) {
        if (request.onlineQueryId() == null) {
            throw new IllegalArgumentException("onlineQueryId is required for ONLINE_QUERY agentic final RRF rerank trace persistence");
        }
        validateAgenticExecutionKind(request.executionKind(), "agentic final RRF rerank trace persistence");
        if (!"agentic-rrf".equals(request.resultScope())) {
            throw new IllegalArgumentException("agentic final RRF rerank trace persistence requires resultScope=agentic-rrf");
        }
        if (!"agentic-rrf".equals(request.rerankerModel())) {
            throw new IllegalArgumentException("agentic final RRF rerank trace persistence requires rerankerModel=agentic-rrf");
        }
        traceRepository.insertRerankResults(
                request.onlineQueryId(),
                null,
                request.finalMergedDocs(),
                request.rerankerModel()
        );
        return new RagTracePersistenceResult(
                RagPersistPolicy.ONLINE_QUERY,
                request.onlineQueryId(),
                true,
                "persisted_" + request.executionKind().name().toLowerCase(Locale.ROOT) + "_final_rrf_rerank"
        );
    }

    private OnlineRewriteLogPersistenceResult createAgenticOnlineRewriteLogOnlineQueryTrace(
            AgenticOnlineRewriteLogPersistenceRequest request
    ) {
        if (request.onlineQueryId() == null) {
            throw new IllegalArgumentException("onlineQueryId is required for ONLINE_QUERY agentic rewrite log persistence");
        }
        validateAgenticExecutionKind(request.executionKind(), "agentic rewrite log persistence");
        UUID rewriteLogId = traceRepository.createOnlineRewriteLog(
                request.onlineQueryId(),
                request.runId(),
                request.rawQuery(),
                request.finalQuery(),
                request.rewriteStrategy(),
                request.generationMethodCodes(),
                request.generationBatchIds(),
                request.gatingApplied(),
                request.gatingPreset(),
                request.rewriteApplied(),
                request.selectiveRewrite(),
                request.useSessionContext(),
                request.rawConfidence(),
                request.selectedConfidence(),
                request.confidenceDelta(),
                request.decisionReason(),
                request.rejectionReason(),
                request.metadata()
        );
        return new OnlineRewriteLogPersistenceResult(
                RagPersistPolicy.ONLINE_QUERY,
                request.onlineQueryId(),
                rewriteLogId,
                true,
                "persisted_" + request.executionKind().name().toLowerCase(Locale.ROOT) + "_rewrite_log"
        );
    }

    private RagTracePersistenceResult insertAgenticMemoryRetrievalOnlineQueryTrace(
            AgenticMemoryRetrievalLogPersistenceRequest request
    ) {
        if (request.onlineQueryId() == null) {
            throw new IllegalArgumentException("onlineQueryId is required for ONLINE_QUERY agentic memory retrieval log persistence");
        }
        if (request.rewriteLogId() == null) {
            throw new IllegalArgumentException("rewriteLogId is required for ONLINE_QUERY agentic memory retrieval log persistence");
        }
        if (request.candidate() == null) {
            throw new IllegalArgumentException("candidate is required for ONLINE_QUERY agentic memory retrieval log persistence");
        }
        validateAgenticExecutionKind(request.executionKind(), "agentic memory retrieval log persistence");
        traceRepository.insertMemoryRetrievalLog(
                request.rewriteLogId(),
                request.onlineQueryId(),
                request.retrievalRank(),
                request.candidate(),
                request.metadata()
        );
        return new RagTracePersistenceResult(
                RagPersistPolicy.ONLINE_QUERY,
                request.onlineQueryId(),
                true,
                "persisted_" + request.executionKind().name().toLowerCase(Locale.ROOT) + "_memory_retrieval_log"
        );
    }

    private RagTracePersistenceResult insertAgenticRewriteCandidateOnlineQueryTrace(
            AgenticRewriteCandidateLogPersistenceRequest request
    ) {
        if (request.onlineQueryId() == null) {
            throw new IllegalArgumentException("onlineQueryId is required for ONLINE_QUERY agentic rewrite candidate log persistence");
        }
        if (request.rewriteLogId() == null) {
            throw new IllegalArgumentException("rewriteLogId is required for ONLINE_QUERY agentic rewrite candidate log persistence");
        }
        if (request.rewriteCandidateId() == null) {
            throw new IllegalArgumentException("rewriteCandidateId is required for ONLINE_QUERY agentic rewrite candidate log persistence");
        }
        validateAgenticExecutionKind(request.executionKind(), "agentic rewrite candidate log persistence");
        traceRepository.insertRewriteCandidateLog(
                request.rewriteLogId(),
                request.onlineQueryId(),
                request.rewriteCandidateId(),
                request.candidateRank(),
                request.candidateLabel(),
                request.candidateQuery(),
                request.confidenceScore(),
                request.selected(),
                request.rejectionReason(),
                request.retrievalTopKDocs(),
                request.scoreBreakdown(),
                request.metadata()
        );
        return new RagTracePersistenceResult(
                RagPersistPolicy.ONLINE_QUERY,
                request.onlineQueryId(),
                true,
                "persisted_" + request.executionKind().name().toLowerCase(Locale.ROOT) + "_rewrite_candidate_log"
        );
    }

    private RagTracePersistenceResult persistAgenticOnlineQueryDecisionTrace(
            AgenticOnlineQueryDecisionPersistenceRequest request
    ) {
        if (request.onlineQueryId() == null) {
            throw new IllegalArgumentException("onlineQueryId is required for ONLINE_QUERY agentic decision persistence");
        }
        validateAgenticExecutionKind(request.executionKind(), "agentic online query decision persistence");
        traceRepository.upsertOnlineQueryDecision(
                request.onlineQueryId(),
                request.finalQueryUsed(),
                request.rewriteApplied(),
                request.memoryTopN(),
                request.selectedConfidence(),
                request.selectedRewriteCandidateId(),
                request.selectedReason(),
                request.rejectedReason(),
                request.latencyBreakdown()
        );
        return new RagTracePersistenceResult(
                RagPersistPolicy.ONLINE_QUERY,
                request.onlineQueryId(),
                true,
                "persisted_" + request.executionKind().name().toLowerCase(Locale.ROOT) + "_online_query_decision"
        );
    }

    private RagTracePersistenceResult mergeAgenticOnlineQueryMetadataTrace(
            AgenticOnlineQueryMetadataMergePersistenceRequest request
    ) {
        if (request.onlineQueryId() == null) {
            throw new IllegalArgumentException("onlineQueryId is required for ONLINE_QUERY agentic metadata merge persistence");
        }
        validateAgenticExecutionKind(request.executionKind(), "agentic online query metadata merge persistence");
        traceRepository.mergeOnlineQueryMetadata(request.onlineQueryId(), request.metadata());
        return new RagTracePersistenceResult(
                RagPersistPolicy.ONLINE_QUERY,
                request.onlineQueryId(),
                true,
                "persisted_" + request.executionKind().name().toLowerCase(Locale.ROOT) + "_online_query_metadata"
        );
    }

    private RewriteCandidatePersistenceResult createAgenticRewriteCandidateOnlineQueryTrace(
            AgenticRewriteCandidateTracePersistenceRequest request
    ) {
        if (request.onlineQueryId() == null) {
            throw new IllegalArgumentException("onlineQueryId is required for ONLINE_QUERY agentic rewrite candidate creation persistence");
        }
        validateAgenticExecutionKind(request.executionKind(), "agentic rewrite candidate creation persistence");
        UUID rewriteCandidateId = traceRepository.createRewriteCandidate(
                request.onlineQueryId(),
                request.candidateIndex(),
                request.candidateLabel(),
                request.candidateQuery(),
                request.memorySourceIds(),
                request.retrievalTopKDocs(),
                request.confidenceScore(),
                request.scoreBreakdown()
        );
        return new RewriteCandidatePersistenceResult(
                RagPersistPolicy.ONLINE_QUERY,
                request.onlineQueryId(),
                rewriteCandidateId,
                true,
                "persisted_" + request.executionKind().name().toLowerCase(Locale.ROOT) + "_candidate_root"
        );
    }

    private RewriteCandidatePersistenceResult markAgenticRewriteCandidateAdoptedOnlineQueryTrace(
            AgenticRewriteCandidateAdoptionPersistenceRequest request
    ) {
        if (request.onlineQueryId() == null) {
            throw new IllegalArgumentException("onlineQueryId is required for ONLINE_QUERY agentic rewrite candidate adoption persistence");
        }
        if (request.rewriteCandidateId() == null) {
            throw new IllegalArgumentException("rewriteCandidateId is required for ONLINE_QUERY agentic rewrite candidate adoption persistence");
        }
        validateAgenticExecutionKind(request.executionKind(), "agentic rewrite candidate adoption persistence");
        traceRepository.markRewriteCandidateAdopted(
                request.rewriteCandidateId(),
                request.adopted(),
                request.rejectedReason()
        );
        return new RewriteCandidatePersistenceResult(
                RagPersistPolicy.ONLINE_QUERY,
                request.onlineQueryId(),
                request.rewriteCandidateId(),
                true,
                "persisted_" + request.executionKind().name().toLowerCase(Locale.ROOT) + "_candidate_adoption"
        );
    }

    private void validateAgenticExecutionKind(
            AgenticRetrievalExecutionKind executionKind,
            String context
    ) {
        if (executionKind == null) {
            throw new IllegalArgumentException("executionKind is required for ONLINE_QUERY " + context);
        }
    }

    public record AgenticSubqueryRetrievalTracePersistenceRequest(
            RagPersistPolicy persistPolicy,
            UUID onlineQueryId,
            AgenticRetrievalExecutionKind executionKind,
            int subqueryIndex,
            String subqueryText,
            String mode,
            UUID rewriteCandidateId,
            List<RetrievalDoc> retrievedDocs,
            JsonNode retrievalMetadata,
            String retrieverName,
            long latencyMs,
            AgenticSubqueryRetrievalTraceWriteScope writeScope
    ) {
        public AgenticSubqueryRetrievalTracePersistenceRequest {
            persistPolicy = persistPolicy == null ? RagPersistPolicy.NONE : persistPolicy;
            subqueryIndex = Math.max(0, subqueryIndex);
            subqueryText = subqueryText == null ? "" : subqueryText;
            mode = mode == null ? "" : mode;
            retrievedDocs = retrievedDocs == null ? List.of() : List.copyOf(retrievedDocs);
            retrievalMetadata = retrievalMetadata == null ? JsonNodeFactory.instance.objectNode() : retrievalMetadata;
            retrieverName = retrieverName == null ? "unknown" : retrieverName;
            latencyMs = Math.max(0L, latencyMs);
            writeScope = writeScope == null
                    ? AgenticSubqueryRetrievalTraceWriteScope.SUBQUERY_RAW_RETRIEVAL
                    : writeScope;
        }
    }

    public record AgenticRewriteCandidateTracePersistenceRequest(
            RagPersistPolicy persistPolicy,
            UUID onlineQueryId,
            AgenticRetrievalExecutionKind executionKind,
            int subqueryIndex,
            String subqueryText,
            int candidateIndex,
            String candidateLabel,
            String candidateQuery,
            JsonNode candidateMetadata,
            JsonNode memorySourceIds,
            JsonNode retrievalTopKDocs,
            double confidenceScore,
            JsonNode scoreBreakdown
    ) {
        public AgenticRewriteCandidateTracePersistenceRequest {
            persistPolicy = persistPolicy == null ? RagPersistPolicy.NONE : persistPolicy;
            subqueryIndex = Math.max(0, subqueryIndex);
            subqueryText = subqueryText == null ? "" : subqueryText;
            candidateIndex = Math.max(0, candidateIndex);
            candidateLabel = candidateLabel == null ? "" : candidateLabel;
            candidateQuery = candidateQuery == null ? "" : candidateQuery;
            candidateMetadata = candidateMetadata == null ? JsonNodeFactory.instance.objectNode() : candidateMetadata;
            memorySourceIds = memorySourceIds == null ? JsonNodeFactory.instance.arrayNode() : memorySourceIds;
            retrievalTopKDocs = retrievalTopKDocs == null ? JsonNodeFactory.instance.arrayNode() : retrievalTopKDocs;
            scoreBreakdown = scoreBreakdown == null ? JsonNodeFactory.instance.objectNode() : scoreBreakdown;
        }
    }

    public record AgenticRewriteCandidateAdoptionPersistenceRequest(
            RagPersistPolicy persistPolicy,
            UUID onlineQueryId,
            UUID rewriteCandidateId,
            AgenticRetrievalExecutionKind executionKind,
            int subqueryIndex,
            boolean adopted,
            String rejectedReason
    ) {
        public AgenticRewriteCandidateAdoptionPersistenceRequest {
            persistPolicy = persistPolicy == null ? RagPersistPolicy.NONE : persistPolicy;
            subqueryIndex = Math.max(0, subqueryIndex);
        }
    }

    public record AgenticFinalRerankTracePersistenceRequest(
            RagPersistPolicy persistPolicy,
            UUID onlineQueryId,
            AgenticRetrievalExecutionKind executionKind,
            List<RetrievalDoc> finalMergedDocs,
            String resultScope,
            String rerankerModel,
            long latencyMs
    ) {
        public AgenticFinalRerankTracePersistenceRequest {
            persistPolicy = persistPolicy == null ? RagPersistPolicy.NONE : persistPolicy;
            finalMergedDocs = finalMergedDocs == null ? List.of() : List.copyOf(finalMergedDocs);
            resultScope = resultScope == null || resultScope.isBlank() ? "agentic-rrf" : resultScope;
            rerankerModel = rerankerModel == null || rerankerModel.isBlank() ? resultScope : rerankerModel;
            latencyMs = Math.max(0L, latencyMs);
        }
    }

    public record AgenticOnlineRewriteLogPersistenceRequest(
            RagPersistPolicy persistPolicy,
            UUID onlineQueryId,
            UUID runId,
            AgenticRetrievalExecutionKind executionKind,
            String rawQuery,
            String finalQuery,
            String rewriteStrategy,
            JsonNode generationMethodCodes,
            JsonNode generationBatchIds,
            boolean gatingApplied,
            String gatingPreset,
            boolean rewriteApplied,
            Boolean selectiveRewrite,
            Boolean useSessionContext,
            Double rawConfidence,
            Double selectedConfidence,
            Double confidenceDelta,
            String decisionReason,
            String rejectionReason,
            JsonNode metadata
    ) {
        public AgenticOnlineRewriteLogPersistenceRequest {
            persistPolicy = persistPolicy == null ? RagPersistPolicy.NONE : persistPolicy;
            rawQuery = rawQuery == null ? "" : rawQuery;
            finalQuery = finalQuery == null ? rawQuery : finalQuery;
            rewriteStrategy = rewriteStrategy == null ? "" : rewriteStrategy;
            generationMethodCodes = generationMethodCodes == null ? JsonNodeFactory.instance.arrayNode() : generationMethodCodes;
            generationBatchIds = generationBatchIds == null ? JsonNodeFactory.instance.arrayNode() : generationBatchIds;
            gatingPreset = gatingPreset == null ? "" : gatingPreset;
            metadata = metadata == null ? JsonNodeFactory.instance.objectNode() : metadata;
        }
    }

    public record AgenticMemoryRetrievalLogPersistenceRequest(
            RagPersistPolicy persistPolicy,
            UUID onlineQueryId,
            UUID rewriteLogId,
            AgenticRetrievalExecutionKind executionKind,
            int retrievalRank,
            MemoryCandidate candidate,
            JsonNode metadata
    ) {
        public AgenticMemoryRetrievalLogPersistenceRequest {
            persistPolicy = persistPolicy == null ? RagPersistPolicy.NONE : persistPolicy;
            retrievalRank = Math.max(0, retrievalRank);
            metadata = metadata == null ? JsonNodeFactory.instance.objectNode() : metadata;
        }
    }

    public record AgenticRewriteCandidateLogPersistenceRequest(
            RagPersistPolicy persistPolicy,
            UUID onlineQueryId,
            UUID rewriteLogId,
            UUID rewriteCandidateId,
            AgenticRetrievalExecutionKind executionKind,
            int candidateRank,
            String candidateLabel,
            String candidateQuery,
            Double confidenceScore,
            boolean selected,
            String rejectionReason,
            JsonNode retrievalTopKDocs,
            JsonNode scoreBreakdown,
            JsonNode metadata
    ) {
        public AgenticRewriteCandidateLogPersistenceRequest {
            persistPolicy = persistPolicy == null ? RagPersistPolicy.NONE : persistPolicy;
            candidateRank = Math.max(0, candidateRank);
            candidateLabel = candidateLabel == null ? "" : candidateLabel;
            candidateQuery = candidateQuery == null ? "" : candidateQuery;
            retrievalTopKDocs = retrievalTopKDocs == null ? JsonNodeFactory.instance.arrayNode() : retrievalTopKDocs;
            scoreBreakdown = scoreBreakdown == null ? JsonNodeFactory.instance.objectNode() : scoreBreakdown;
            metadata = metadata == null ? JsonNodeFactory.instance.objectNode() : metadata;
        }
    }

    public record AgenticOnlineQueryDecisionPersistenceRequest(
            RagPersistPolicy persistPolicy,
            UUID onlineQueryId,
            AgenticRetrievalExecutionKind executionKind,
            String rawQuery,
            String finalQueryUsed,
            boolean rewriteApplied,
            JsonNode memoryTopN,
            Double selectedConfidence,
            UUID selectedRewriteCandidateId,
            int finalRetrievedDocsCount,
            String selectedReason,
            String rejectedReason,
            JsonNode latencyBreakdown
    ) {
        public AgenticOnlineQueryDecisionPersistenceRequest {
            persistPolicy = persistPolicy == null ? RagPersistPolicy.NONE : persistPolicy;
            rawQuery = rawQuery == null ? "" : rawQuery;
            finalQueryUsed = finalQueryUsed == null ? rawQuery : finalQueryUsed;
            memoryTopN = memoryTopN == null ? JsonNodeFactory.instance.arrayNode() : memoryTopN;
            finalRetrievedDocsCount = Math.max(0, finalRetrievedDocsCount);
            latencyBreakdown = latencyBreakdown == null ? JsonNodeFactory.instance.objectNode() : latencyBreakdown;
        }
    }

    public record AgenticOnlineQueryMetadataMergePersistenceRequest(
            RagPersistPolicy persistPolicy,
            UUID onlineQueryId,
            AgenticRetrievalExecutionKind executionKind,
            JsonNode metadata,
            String sourceMarker
    ) {
        public AgenticOnlineQueryMetadataMergePersistenceRequest {
            persistPolicy = persistPolicy == null ? RagPersistPolicy.NONE : persistPolicy;
            metadata = metadata == null ? JsonNodeFactory.instance.objectNode() : metadata;
            sourceMarker = sourceMarker == null || sourceMarker.isBlank() ? null : sourceMarker;
        }
    }

    public enum AgenticRetrievalExecutionKind {
        AGENTIC_MULTI_QUERY
    }

    public enum AgenticSubqueryRetrievalTraceWriteScope {
        SUBQUERY_RAW_RETRIEVAL("subquery_raw_retrieval", "raw"),
        SUBQUERY_CANDIDATE_RETRIEVAL("subquery_candidate_retrieval", "rewrite_candidate");

        private final String statusSuffix;
        private final String repositoryStage;

        AgenticSubqueryRetrievalTraceWriteScope(String statusSuffix, String repositoryStage) {
            this.statusSuffix = statusSuffix;
            this.repositoryStage = repositoryStage;
        }
    }
}
