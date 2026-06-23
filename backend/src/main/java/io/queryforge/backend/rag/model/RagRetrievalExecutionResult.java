package io.queryforge.backend.rag.model;

import java.util.List;

public record RagRetrievalExecutionResult(
        List<String> retrievedChunkIds,
        List<RagRetrievedDoc> retrievedDocs,
        String finalQuery,
        String originalQuery,
        ForcedRetrievalMode forcedMode,
        String selectedStrategy,
        RagExecutionTrace trace,
        RagLlmCallCount llmCallCount,
        long latencyMs,
        boolean fallbackApplied,
        String fallbackReason
) {
    public RagRetrievalExecutionResult {
        retrievedChunkIds = retrievedChunkIds == null ? List.of() : List.copyOf(retrievedChunkIds);
        retrievedDocs = retrievedDocs == null ? List.of() : List.copyOf(retrievedDocs);
        llmCallCount = llmCallCount == null ? RagLlmCallCount.zero() : llmCallCount;
    }
}
