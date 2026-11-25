package io.queryforge.backend.rag.trace.write.agentic.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import io.queryforge.backend.rag.retrieval.candidate.model.RetrievalDoc;
import io.queryforge.backend.rag.retrieval.execution.model.RagPersistPolicy;
import io.queryforge.backend.rag.retrieval.memory.model.MemoryCandidate;

import java.util.List;
import java.util.UUID;

public final class AgenticTraceWriteDtos {

    private AgenticTraceWriteDtos() {
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

        public String statusSuffix() {
            return statusSuffix;
        }

        public String repositoryStage() {
            return repositoryStage;
        }
    }
}
