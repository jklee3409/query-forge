package io.queryforge.backend.rag.chat.controller;

import io.queryforge.backend.rag.chat.dto.ChatDtos;
import io.queryforge.backend.rag.chat.service.RagService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class RagController {

    private final RagService ragService;

    @PostMapping("/chat/ask")
    public ChatDtos.AskResponse ask(@RequestBody ChatDtos.AskRequest request) {
        return ragService.ask(request);
    }
}
