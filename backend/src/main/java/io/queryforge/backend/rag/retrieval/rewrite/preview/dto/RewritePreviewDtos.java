package io.queryforge.backend.rag.retrieval.rewrite.preview.dto;

import com.fasterxml.jackson.databind.JsonNode;
import io.queryforge.backend.rag.retrieval.rewrite.dto.RewriteDtos.RewriteCandidateDto;

import java.util.List;

public final class RewritePreviewDtos {

    private RewritePreviewDtos() {
    }

    public record RewritePreviewRequest(
            String rawQuery,
            JsonNode sessionContext,
            Integer memoryTopN,
            Integer candidateCount,
            String gatingPreset
    ) {
    }

    public record RewritePreviewResponse(
            String rawQuery,
            JsonNode memoryTopN,
            List<RewriteCandidateDto> rewriteCandidates
    ) {
    }
}
