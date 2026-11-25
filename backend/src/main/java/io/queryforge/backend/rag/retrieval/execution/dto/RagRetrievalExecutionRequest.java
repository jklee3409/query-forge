package io.queryforge.backend.rag.retrieval.execution.dto;

import com.fasterxml.jackson.databind.JsonNode;
import io.queryforge.backend.rag.retrieval.execution.model.ForcedRetrievalMode;
import io.queryforge.backend.rag.retrieval.execution.model.RagPersistPolicy;

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
