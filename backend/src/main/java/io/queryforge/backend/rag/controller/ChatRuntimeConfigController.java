package io.queryforge.backend.rag.controller;

import io.queryforge.backend.rag.model.ChatRuntimeDtos;
import io.queryforge.backend.rag.service.ChatRuntimeConfigService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
public class ChatRuntimeConfigController {

    private final ChatRuntimeConfigService service;

    @GetMapping("/api/chat/domains")
    public List<ChatRuntimeDtos.ChatDomainOption> chatDomains() {
        return service.listChatDomains();
    }

    @GetMapping("/api/chat/config")
    public ChatRuntimeDtos.ChatRuntimeConfigResponse chatConfig(
            @RequestParam(name = "domain_id") UUID domainId
    ) {
        return service.getConfig(domainId);
    }

    @GetMapping("/api/admin/chat/config")
    public ChatRuntimeDtos.ChatRuntimeConfigResponse adminChatConfig(
            @RequestParam(name = "domain_id") UUID domainId
    ) {
        return service.getConfig(domainId);
    }

    @PutMapping("/api/admin/chat/config")
    public ChatRuntimeDtos.ChatRuntimeConfigResponse updateAdminChatConfig(
            @RequestBody ChatRuntimeDtos.ChatRuntimeConfigRequest request
    ) {
        return service.updateConfig(request);
    }

    @PostMapping("/api/admin/chat/config/apply-rag-run")
    public ChatRuntimeDtos.ChatRuntimeConfigResponse applyRagRunToChatConfig(
            @RequestBody ChatRuntimeDtos.ApplyChatConfigFromRagRunRequest request
    ) {
        return service.applyRagRunConfig(request);
    }
}
