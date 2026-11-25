package io.queryforge.backend.rag.retrieval.rewrite;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.UUID;

public final class RewriteDtos {

    private RewriteDtos() {
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

    public record RewritePreviewResponse(
            String rawQuery,
            JsonNode memoryTopN,
            List<RewriteCandidateDto> rewriteCandidates
    ) {
    }
}
