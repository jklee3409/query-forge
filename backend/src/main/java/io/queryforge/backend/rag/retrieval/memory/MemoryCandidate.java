package io.queryforge.backend.rag.retrieval.memory;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.UUID;

public record MemoryCandidate(
        UUID memoryId,
        String queryText,
        String targetDocId,
        JsonNode targetChunkIds,
        JsonNode glossaryTerms,
        JsonNode metadata,
        double similarity,
        String generationStrategy,
        UUID generationBatchId,
        UUID domainId,
        String sourceGatedQueryId,
        String sourceGateRunId,
        String sourceGatingBatchId
) {
}
