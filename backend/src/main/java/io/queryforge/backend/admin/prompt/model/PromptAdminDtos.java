package io.queryforge.backend.admin.prompt.model;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class PromptAdminDtos {

    private PromptAdminDtos() {
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

    public record PromptBindingRow(
            String bindingKey,
            String promptFamily,
            UUID activePromptAssetId,
            String activePromptName,
            String activePromptVersion,
            String activeContentHash,
            JsonNode fallbackPromptAssetIds,
            String description,
            JsonNode metadata,
            String updatedBy,
            Instant updatedAt
    ) {
    }

    public record PromptBindingUpdateRequest(
            UUID activePromptAssetId,
            List<UUID> fallbackPromptAssetIds,
            String description,
            JsonNode metadata,
            String updatedBy
    ) {
    }

    public record PromptValidationRequest(
            String contentBody
    ) {
    }

    public record PromptValidationResponse(
            boolean valid,
            List<String> warnings,
            List<String> errors
    ) {
    }
}
