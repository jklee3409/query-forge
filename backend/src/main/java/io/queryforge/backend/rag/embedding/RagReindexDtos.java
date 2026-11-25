package io.queryforge.backend.rag.embedding;

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
}
