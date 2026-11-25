package io.queryforge.backend.rag.trace.write.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import io.queryforge.backend.rag.retrieval.candidate.model.RetrievalDoc;
import io.queryforge.backend.rag.retrieval.execution.dto.RagRetrievalExecutionResult;
import io.queryforge.backend.rag.retrieval.execution.dto.RetrievalExecutionDtos;
import io.queryforge.backend.rag.retrieval.execution.model.ForcedRetrievalMode;
import io.queryforge.backend.rag.retrieval.execution.model.RagPersistPolicy;
import io.queryforge.backend.rag.retrieval.memory.model.MemoryCandidate;

import java.util.List;
import java.util.UUID;

public final class RagTraceWriteDtos {

    private RagTraceWriteDtos() {
    }

    public record RagTracePersistenceRequest(
            RagPersistPolicy persistPolicy,
            String source,
            String evalRunId,
            String sampleId,
            ForcedRetrievalMode forcedMode,
            UUID onlineQueryId,
            RagRetrievalExecutionResult executionResult
    ) {
        public RagTracePersistenceRequest {
            persistPolicy = persistPolicy == null ? RagPersistPolicy.NONE : persistPolicy;
        }
    }

    public record OnlineQueryDecisionPersistenceRequest(
            RagPersistPolicy persistPolicy,
            UUID onlineQueryId,
            RetrievalExecutionDtos.NonAgenticExecutionKind executionKind,
            String rawQuery,
            String finalQueryUsed,
            boolean rewriteApplied,
            JsonNode memoryTopN,
            Double rawScore,
            UUID selectedRewriteCandidateId,
            int finalRetrievedDocsCount,
            String selectedReason,
            String rejectedReason,
            JsonNode latencyBreakdown
    ) {
        public OnlineQueryDecisionPersistenceRequest {
            persistPolicy = persistPolicy == null ? RagPersistPolicy.NONE : persistPolicy;
            rawQuery = rawQuery == null ? "" : rawQuery;
            finalQueryUsed = finalQueryUsed == null ? rawQuery : finalQueryUsed;
            memoryTopN = memoryTopN == null ? JsonNodeFactory.instance.arrayNode() : memoryTopN;
            finalRetrievedDocsCount = Math.max(0, finalRetrievedDocsCount);
            latencyBreakdown = latencyBreakdown == null ? JsonNodeFactory.instance.objectNode() : latencyBreakdown;
        }
    }

    public record OnlineQueryMetadataMergePersistenceRequest(
            RagPersistPolicy persistPolicy,
            UUID onlineQueryId,
            RetrievalExecutionDtos.NonAgenticExecutionKind executionKind,
            JsonNode metadata,
            String sourceMarker
    ) {
        public OnlineQueryMetadataMergePersistenceRequest {
            persistPolicy = persistPolicy == null ? RagPersistPolicy.NONE : persistPolicy;
            metadata = metadata == null ? JsonNodeFactory.instance.objectNode() : metadata;
            sourceMarker = sourceMarker == null || sourceMarker.isBlank() ? null : sourceMarker;
        }
    }

    public record RawOnlyTracePersistenceRequest(
            RagPersistPolicy persistPolicy,
            UUID onlineQueryId,
            String rawQuery,
            String finalQuery,
            String mode,
            List<RetrievalDoc> rawRetrievedDocs,
            List<RetrievalDoc> rawRerankedDocs,
            JsonNode retrievalMetadata,
            String retrieverName,
            String rerankerModel,
            long latencyMs,
            RawOnlyTraceWriteScope writeScope
    ) {
        public RawOnlyTracePersistenceRequest {
            persistPolicy = persistPolicy == null ? RagPersistPolicy.NONE : persistPolicy;
            rawQuery = rawQuery == null ? "" : rawQuery;
            finalQuery = finalQuery == null ? rawQuery : finalQuery;
            mode = mode == null ? "raw_only" : mode;
            rawRetrievedDocs = rawRetrievedDocs == null ? List.of() : List.copyOf(rawRetrievedDocs);
            rawRerankedDocs = rawRerankedDocs == null ? List.of() : List.copyOf(rawRerankedDocs);
            retrievalMetadata = retrievalMetadata == null ? JsonNodeFactory.instance.objectNode() : retrievalMetadata;
            retrieverName = retrieverName == null ? "unknown" : retrieverName;
            rerankerModel = rerankerModel == null ? "unknown" : rerankerModel;
            writeScope = writeScope == null ? RawOnlyTraceWriteScope.RETRIEVAL : writeScope;
            latencyMs = Math.max(0L, latencyMs);
        }
    }

    public record RewriteCandidateTracePersistenceRequest(
            RagPersistPolicy persistPolicy,
            UUID onlineQueryId,
            UUID rewriteCandidateId,
            RetrievalExecutionDtos.NonAgenticExecutionKind executionKind,
            int candidateIndex,
            String candidateQuery,
            String candidateLabel,
            String mode,
            List<RetrievalDoc> retrievedDocs,
            List<RetrievalDoc> rerankedDocs,
            JsonNode retrievalMetadata,
            String retrieverName,
            String rerankerModel,
            long latencyMs,
            RewriteCandidateTraceWriteScope writeScope
    ) {
        public RewriteCandidateTracePersistenceRequest {
            persistPolicy = persistPolicy == null ? RagPersistPolicy.NONE : persistPolicy;
            candidateIndex = Math.max(0, candidateIndex);
            candidateQuery = candidateQuery == null ? "" : candidateQuery;
            candidateLabel = candidateLabel == null ? "" : candidateLabel;
            mode = mode == null ? "" : mode;
            retrievedDocs = retrievedDocs == null ? List.of() : List.copyOf(retrievedDocs);
            rerankedDocs = rerankedDocs == null ? List.of() : List.copyOf(rerankedDocs);
            retrievalMetadata = retrievalMetadata == null ? JsonNodeFactory.instance.objectNode() : retrievalMetadata;
            retrieverName = retrieverName == null ? "unknown" : retrieverName;
            rerankerModel = rerankerModel == null ? "unknown" : rerankerModel;
            latencyMs = Math.max(0L, latencyMs);
            writeScope = writeScope == null ? RewriteCandidateTraceWriteScope.CANDIDATE_RETRIEVAL : writeScope;
        }
    }

    public record CreateRewriteCandidateTracePersistenceRequest(
            RagPersistPolicy persistPolicy,
            UUID onlineQueryId,
            RetrievalExecutionDtos.NonAgenticExecutionKind executionKind,
            int candidateIndex,
            String candidateLabel,
            String candidateQuery,
            JsonNode candidateMetadata,
            JsonNode memorySourceIds,
            JsonNode retrievalTopKDocs,
            double confidenceScore,
            JsonNode scoreBreakdown
    ) {
        public CreateRewriteCandidateTracePersistenceRequest {
            persistPolicy = persistPolicy == null ? RagPersistPolicy.NONE : persistPolicy;
            candidateIndex = Math.max(0, candidateIndex);
            candidateLabel = candidateLabel == null ? "" : candidateLabel;
            candidateQuery = candidateQuery == null ? "" : candidateQuery;
            candidateMetadata = candidateMetadata == null ? JsonNodeFactory.instance.objectNode() : candidateMetadata;
            memorySourceIds = memorySourceIds == null ? JsonNodeFactory.instance.arrayNode() : memorySourceIds;
            retrievalTopKDocs = retrievalTopKDocs == null ? JsonNodeFactory.instance.arrayNode() : retrievalTopKDocs;
            scoreBreakdown = scoreBreakdown == null ? JsonNodeFactory.instance.objectNode() : scoreBreakdown;
        }
    }

    public record RewriteCandidateAdoptionPersistenceRequest(
            RagPersistPolicy persistPolicy,
            UUID onlineQueryId,
            UUID rewriteCandidateId,
            RetrievalExecutionDtos.NonAgenticExecutionKind executionKind,
            boolean adopted,
            String rejectedReason
    ) {
        public RewriteCandidateAdoptionPersistenceRequest {
            persistPolicy = persistPolicy == null ? RagPersistPolicy.NONE : persistPolicy;
        }
    }

    public record OnlineRewriteLogPersistenceRequest(
            RagPersistPolicy persistPolicy,
            UUID onlineQueryId,
            UUID runId,
            RetrievalExecutionDtos.NonAgenticExecutionKind executionKind,
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
        public OnlineRewriteLogPersistenceRequest {
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

    public record MemoryRetrievalLogPersistenceRequest(
            RagPersistPolicy persistPolicy,
            UUID onlineQueryId,
            UUID rewriteLogId,
            RetrievalExecutionDtos.NonAgenticExecutionKind executionKind,
            int retrievalRank,
            MemoryCandidate candidate,
            JsonNode metadata
    ) {
        public MemoryRetrievalLogPersistenceRequest {
            persistPolicy = persistPolicy == null ? RagPersistPolicy.NONE : persistPolicy;
            retrievalRank = Math.max(0, retrievalRank);
            metadata = metadata == null ? JsonNodeFactory.instance.objectNode() : metadata;
        }
    }

    public record RewriteCandidateLogPersistenceRequest(
            RagPersistPolicy persistPolicy,
            UUID onlineQueryId,
            UUID rewriteLogId,
            UUID rewriteCandidateId,
            RetrievalExecutionDtos.NonAgenticExecutionKind executionKind,
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
        public RewriteCandidateLogPersistenceRequest {
            persistPolicy = persistPolicy == null ? RagPersistPolicy.NONE : persistPolicy;
            candidateRank = Math.max(0, candidateRank);
            candidateLabel = candidateLabel == null ? "" : candidateLabel;
            candidateQuery = candidateQuery == null ? "" : candidateQuery;
            retrievalTopKDocs = retrievalTopKDocs == null ? JsonNodeFactory.instance.arrayNode() : retrievalTopKDocs;
            scoreBreakdown = scoreBreakdown == null ? JsonNodeFactory.instance.objectNode() : scoreBreakdown;
            metadata = metadata == null ? JsonNodeFactory.instance.objectNode() : metadata;
        }
    }

    public enum RawOnlyTraceWriteScope {
        RETRIEVAL("retrieval"),
        RERANK("rerank");

        private final String statusSuffix;

        RawOnlyTraceWriteScope(String statusSuffix) {
            this.statusSuffix = statusSuffix;
        }

        public String statusSuffix() {
            return statusSuffix;
        }
    }

    public enum RewriteCandidateTraceWriteScope {
        CANDIDATE_RETRIEVAL("candidate_retrieval"),
        CANDIDATE_RERANK("candidate_rerank");

        private final String statusSuffix;

        RewriteCandidateTraceWriteScope(String statusSuffix) {
            this.statusSuffix = statusSuffix;
        }

        public String statusSuffix() {
            return statusSuffix;
        }
    }
}
