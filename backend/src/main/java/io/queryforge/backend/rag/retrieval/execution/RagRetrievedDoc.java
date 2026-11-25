package io.queryforge.backend.rag.retrieval.execution;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.UUID;

public record RagRetrievedDoc(
        String chunkId,
        String documentId,
        UUID domainId,
        String title,
        String url,
        String snippet,
        double score,
        int rank,
        String retriever,
        JsonNode metadata
) {
}
