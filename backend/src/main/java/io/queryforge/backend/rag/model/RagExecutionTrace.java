package io.queryforge.backend.rag.model;

import com.fasterxml.jackson.databind.JsonNode;

public record RagExecutionTrace(
        String originalQuery,
        String finalQuery,
        ForcedRetrievalMode forcedMode,
        String routeDecision,
        JsonNode memoryTrace,
        JsonNode rewriteTrace,
        JsonNode anchorTrace,
        JsonNode agenticTrace,
        JsonNode retrievalTrace,
        JsonNode rerankTrace,
        JsonNode fallbackTrace,
        long latencyMs,
        JsonNode metadata
) {
}
