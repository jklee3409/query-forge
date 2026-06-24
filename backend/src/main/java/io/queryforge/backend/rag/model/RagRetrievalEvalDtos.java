package io.queryforge.backend.rag.model;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

public final class RagRetrievalEvalDtos {

    private RagRetrievalEvalDtos() {
    }

    public record RagRetrievalEvalRequest(
            UUID domainId,
            String query,
            String forcedMode,
            Integer topK,
            RagPersistPolicy persistPolicy,
            Boolean answerGeneration,
            Boolean includeTrace,
            Boolean includeScores,
            Boolean includeMetadata,
            JsonNode runtimeConfig
    ) {
        public RagRetrievalEvalRequest {
            forcedMode = normalizeMode(forcedMode);
            persistPolicy = persistPolicy == null ? RagPersistPolicy.NONE : persistPolicy;
            answerGeneration = answerGeneration != null && answerGeneration;
            includeTrace = includeTrace != null && includeTrace;
            includeScores = includeScores == null || includeScores;
            includeMetadata = includeMetadata != null && includeMetadata;
        }

        public RagRetrievalEvalRequest(
                UUID domainId,
                String query,
                String forcedMode,
                Integer topK,
                RagPersistPolicy persistPolicy,
                Boolean answerGeneration,
                Boolean includeTrace,
                Boolean includeScores,
                Boolean includeMetadata
        ) {
            this(
                    domainId,
                    query,
                    forcedMode,
                    topK,
                    persistPolicy,
                    answerGeneration,
                    includeTrace,
                    includeScores,
                    includeMetadata,
                    null
            );
        }
    }

    public record RagRetrievalEvalResponse(
            UUID domainId,
            String query,
            String finalQuery,
            String forcedMode,
            String selectedMode,
            List<String> retrievedChunkIds,
            List<RagRetrievalEvalDoc> retrievedDocs,
            RagEvalTrace trace,
            RagLlmCallCount llmCallCount,
            Long latencyMs,
            boolean persisted,
            RagPersistPolicy persistPolicy,
            List<String> warnings
    ) {
        public RagRetrievalEvalResponse {
            retrievedChunkIds = retrievedChunkIds == null ? List.of() : List.copyOf(retrievedChunkIds);
            retrievedDocs = retrievedDocs == null ? List.of() : List.copyOf(retrievedDocs);
            llmCallCount = llmCallCount == null ? RagLlmCallCount.zero() : llmCallCount;
            persistPolicy = persistPolicy == null ? RagPersistPolicy.NONE : persistPolicy;
            warnings = warnings == null ? List.of() : List.copyOf(warnings);
        }
    }

    public record RagRetrievalEvalDoc(
            String chunkId,
            String documentId,
            String title,
            String contentPreview,
            Double score,
            int rank
    ) {
    }

    public record RagEvalTrace(
            String routeDecision,
            JsonNode retrievalTrace
    ) {
    }

    private static String normalizeMode(String value) {
        if (value == null || value.isBlank()) {
            return "strategy_router";
        }
        return value.trim().toLowerCase(Locale.ROOT).replace("-", "_");
    }
}
