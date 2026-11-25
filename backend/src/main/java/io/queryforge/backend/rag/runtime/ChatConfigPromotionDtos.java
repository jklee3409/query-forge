package io.queryforge.backend.rag.runtime;

import java.util.UUID;

public final class ChatConfigPromotionDtos {

    private ChatConfigPromotionDtos() {
    }

    public record ApplyChatConfigFromRagRunRequest(
            UUID ragTestRunId,
            String updatedBy
    ) {
    }
}
