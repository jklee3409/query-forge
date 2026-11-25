package io.queryforge.backend.rag.trace.query.dto;

import com.fasterxml.jackson.databind.JsonNode;
import io.queryforge.backend.rag.retrieval.rewrite.dto.RewriteDtos.RewriteCandidateDto;

import java.util.List;
import java.util.UUID;

public final class RagTraceDtos {

    private RagTraceDtos() {
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

    public record RewriteCandidateRow(
            UUID rewriteCandidateId,
            int candidateRank,
            String candidateLabel,
            String candidateQuery,
            JsonNode retrievalTopKDocs,
            Double confidenceScore,
            Boolean adopted,
            String rejectedReason,
            JsonNode scoreBreakdown
    ) {
    }

    public record OnlineQueryRow(
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
            JsonNode latencyBreakdown
    ) {
    }
}
