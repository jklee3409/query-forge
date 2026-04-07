package io.queryforge.backend.rag.model;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class RagDtos {

    private RagDtos() {
    }

    public record AskRequest(
            String query,
            String sessionId,
            JsonNode sessionContext,
            String mode,
            Integer retrievalTopK,
            Integer rerankTopN,
            Integer memoryTopN,
            Integer rewriteCandidateCount,
            Double rewriteThreshold,
            String gatingPreset
    ) {
    }

    public record RewritePreviewRequest(
            String rawQuery,
            JsonNode sessionContext,
            Integer memoryTopN,
            Integer candidateCount,
            String gatingPreset
    ) {
    }

    public record RewriteCandidateDto(
            UUID rewriteCandidateId,
            String label,
            String candidateQuery,
            double confidenceScore,
            boolean adopted,
            String rejectedReason,
            JsonNode retrievalTopKDocs,
            JsonNode scoreBreakdown
    ) {
    }

    public record ScoredDocumentDto(
            String documentId,
            String chunkId,
            String chunkTextPreview,
            double score
    ) {
    }

    public record AskResponse(
            UUID onlineQueryId,
            String answer,
            String finalQueryUsed,
            String rawQuery,
            boolean rewriteApplied,
            List<RewriteCandidateDto> rewriteCandidates,
            List<ScoredDocumentDto> retrievedDocs,
            List<ScoredDocumentDto> rerankedDocs,
            JsonNode memoryTopN,
            Map<String, Long> latencyBreakdown
    ) {
    }

    public record RewritePreviewResponse(
            String rawQuery,
            JsonNode memoryTopN,
            List<RewriteCandidateDto> rewriteCandidates
    ) {
    }

    public record QueryTraceResponse(
            UUID onlineQueryId,
            String rawQuery,
            String finalQueryUsed,
            Boolean rewriteApplied,
            String rewriteStrategy,
            JsonNode sessionContextSnapshot,
            JsonNode memoryTopN,
            Double rawScore,
            UUID selectedRewriteCandidateId,
            String selectedReason,
            String rejectedReason,
            Double threshold,
            JsonNode latencyBreakdown,
            List<RewriteCandidateDto> rewriteCandidates,
            JsonNode retrievalResults,
            JsonNode rerankResults,
            JsonNode answer
    ) {
    }

    public record ExperimentSummaryResponse(
            UUID experimentRunId,
            String experimentKey,
            String status,
            Instant startedAt,
            Instant finishedAt,
            JsonNode parameters,
            JsonNode metrics,
            String notes
    ) {
    }

    public record EvalReportResponse(
            String reportType,
            JsonNode payload
    ) {
    }

    public record ReindexRequest(
            Boolean reindexChunks,
            Boolean reindexMemory
    ) {
    }

    public record ReindexResponse(
            int chunkEmbeddingsUpdated,
            int memoryEmbeddingsUpdated,
            String embeddingModel
    ) {
    }

    public record ExperimentCommandRequest(
            String command,
            String experiment
    ) {
    }

    public record ExperimentCommandResponse(
            String command,
            String experiment,
            int exitCode,
            JsonNode summary,
            String stdout,
            String stderr
    ) {
    }
}
