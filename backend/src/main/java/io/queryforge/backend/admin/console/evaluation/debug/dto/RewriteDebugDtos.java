package io.queryforge.backend.admin.console.evaluation.debug.dto;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.UUID;

public final class RewriteDebugDtos {

    private RewriteDebugDtos() {
    }

    public record RewriteDebugRow(
            UUID rewriteLogId,
            UUID onlineQueryId,
            String rawQuery,
            String finalQuery,
            String rewriteStrategy,
            boolean rewriteApplied,
            String gatingPreset,
            Double rawConfidence,
            Double selectedConfidence,
            Double confidenceDelta,
            String decisionReason,
            String rejectionReason,
            Instant createdAt
    ) {
    }

    public record RewriteDebugDetail(
            RewriteDebugRow rewrite,
            JsonNode memoryRetrievals,
            JsonNode candidateLogs
    ) {
    }
}
