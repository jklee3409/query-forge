package io.queryforge.backend.rag.chat;

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
