package io.queryforge.backend.rag.runtime.promotion.controller;

import io.queryforge.backend.rag.runtime.configuration.dto.ChatConfigDtos;
import io.queryforge.backend.rag.runtime.promotion.dto.ChatConfigPromotionDtos;
import io.queryforge.backend.rag.runtime.promotion.service.ChatConfigPromotionService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class ChatConfigPromotionController {

    private final ChatConfigPromotionService service;

    @PostMapping("/api/admin/chat/config/apply-rag-run")
    public ChatConfigDtos.ChatRuntimeConfigResponse applyRagRunToChatConfig(
            @RequestBody ChatConfigPromotionDtos.ApplyChatConfigFromRagRunRequest request
    ) {
        return service.applyRagRunConfig(request);
    }
}
