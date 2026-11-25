package io.queryforge.backend.rag.embedding.reindex.dto;

import java.util.UUID;

public final class RagReindexDtos {

    private RagReindexDtos() {
    }

    public record ReindexRequest(
            Boolean reindexChunks,
            Boolean reindexMemory
    ) {
    }

    public record ReindexResponse(
            int chunkEmbeddingsUpdated,
            int memoryEmbeddingsUpdated,
            String embeddingModel
    ) {
    }

    public record ChunkSource(String chunkId, String documentId, String chunkText) {
    }

    public record MemorySource(UUID memoryId, String queryText) {
    }
}
