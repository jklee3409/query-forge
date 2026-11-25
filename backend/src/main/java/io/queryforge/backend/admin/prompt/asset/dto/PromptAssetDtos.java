package io.queryforge.backend.admin.prompt.asset.dto;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.UUID;

public final class PromptAssetDtos {

    private PromptAssetDtos() {
    }

    public record PromptAssetRow(
            UUID promptAssetId,
            String promptFamily,
            String promptName,
            String version,
            String contentPath,
            String contentHash,
            boolean active,
            String storageBackend,
            boolean hasContentBody,
            UUID parentPromptAssetId,
            JsonNode metadata,
            String updatedBy,
            Instant createdAt,
            Instant updatedAt
    ) {
    }

    public record PromptAssetDetail(
            PromptAssetRow asset,
            String contentBody
    ) {
    }

    public record PromptRevisionRequest(
            String version,
            String contentBody,
            JsonNode metadata,
            String updatedBy
    ) {
    }

    public record PromptAssetUpdateRequest(
            Boolean active,
            String contentBody,
            JsonNode metadata,
            String updatedBy
    ) {
    }
}
