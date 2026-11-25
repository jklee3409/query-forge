package io.queryforge.backend.rag.runtime.readiness;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequiredArgsConstructor
public class ChatReadinessController {

    private final ChatReadinessService service;

    @GetMapping("/api/chat/readiness")
    public ChatReadinessDtos.ChatDomainReadinessResponse chatReadiness(
            @RequestParam(name = "domain_id") UUID domainId
    ) {
        return service.getReadiness(domainId);
    }

    @GetMapping("/api/admin/chat/readiness")
    public ChatReadinessDtos.ChatDomainReadinessResponse adminChatReadiness(
            @RequestParam(name = "domain_id") UUID domainId
    ) {
        return service.getReadiness(domainId);
    }
}
