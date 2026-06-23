package io.queryforge.backend.rag.model;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.UUID;

public record RagRetrievalExecutionRequest(
        UUID domainId,
        String query,
        ForcedRetrievalMode forcedMode,
        RagPersistPolicy persistPolicy,
        Integer topK,
        Boolean includeTrace,
        String evalRunId,
        String sampleId,
        JsonNode metadata
) {
    public RagRetrievalExecutionRequest {
        if (persistPolicy == null) {
            persistPolicy = RagPersistPolicy.NONE;
        }
    }
}
