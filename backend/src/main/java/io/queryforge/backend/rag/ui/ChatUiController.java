package io.queryforge.backend.rag.ui;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class ChatUiController {

    @GetMapping("/")
    public String chat(Model model) {
        model.addAttribute("pageTitle", "Query Forge Chat");
        return "chat/index";
    }
}

