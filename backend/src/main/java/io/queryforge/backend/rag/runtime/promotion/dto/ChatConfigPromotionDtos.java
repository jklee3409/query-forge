package io.queryforge.backend.rag.runtime.promotion.dto;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.UUID;

public final class ChatConfigPromotionDtos {

    private ChatConfigPromotionDtos() {
    }

    public record ApplyChatConfigFromRagRunRequest(
            UUID ragTestRunId,
            String updatedBy
    ) {
    }

    public record RagRunApplySnapshot(
            UUID ragTestRunId,
            UUID domainId,
            String status,
            JsonNode generationMethodCodes,
            String gatingPreset,
            Boolean rewriteEnabled,
            Boolean selectiveRewrite,
            Boolean useSessionContext,
            Boolean rewriteAnchorInjectionEnabled,
            Integer retrievalTopK,
            Integer rerankTopN,
            Double threshold,
            JsonNode configJson
    ) {
    }
}
