package io.queryforge.backend.rag.runtime.configuration;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
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
    public List<ChatConfigDtos.ChatDomainOption> chatDomains() {
        return service.listChatDomains();
    }

    @GetMapping("/api/chat/config")
    public ChatConfigDtos.ChatRuntimeConfigResponse chatConfig(
            @RequestParam(name = "domain_id") UUID domainId
    ) {
        return service.getConfig(domainId);
    }

    @GetMapping("/api/admin/chat/config")
    public ChatConfigDtos.ChatRuntimeConfigResponse adminChatConfig(
            @RequestParam(name = "domain_id") UUID domainId
    ) {
        return service.getConfig(domainId);
    }

    @GetMapping("/api/admin/chat/config/provenance")
    public List<ChatConfigDtos.ChatRuntimeConfigProvenanceRow> adminChatConfigProvenance(
            @RequestParam(name = "domain_id") UUID domainId,
            @RequestParam(name = "limit", required = false) Integer limit
    ) {
        return service.listConfigProvenance(domainId, limit);
    }

    @PutMapping("/api/admin/chat/config")
    public ChatConfigDtos.ChatRuntimeConfigResponse updateAdminChatConfig(
            @RequestBody ChatConfigDtos.ChatRuntimeConfigRequest request
    ) {
        return service.updateConfig(request);
    }
}
