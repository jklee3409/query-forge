package io.queryforge.backend.admin.prompt.binding.dto;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class PromptBindingDtos {

    private PromptBindingDtos() {
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
