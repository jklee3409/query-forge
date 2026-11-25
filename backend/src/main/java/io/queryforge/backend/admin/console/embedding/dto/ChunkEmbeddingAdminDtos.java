package io.queryforge.backend.admin.console.embedding.dto;

import java.time.Instant;

public final class ChunkEmbeddingAdminDtos {

    private ChunkEmbeddingAdminDtos() {
    }

    public record ChunkEmbeddingMaterializationRequest(
            String embeddingModel,
            String createdBy
    ) {
    }

    public record ChunkEmbeddingMaterializationStatusResponse(
            String embeddingModel,
            String vectorStore,
            long totalChunkCount,
            long materializedChunkCount,
            long missingChunkCount,
            boolean ready,
            Instant latestUpdatedAt
    ) {
    }
}
