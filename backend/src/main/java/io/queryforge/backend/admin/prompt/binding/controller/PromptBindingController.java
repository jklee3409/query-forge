package io.queryforge.backend.admin.prompt.binding.controller;

import io.queryforge.backend.admin.prompt.binding.dto.PromptBindingDtos;
import io.queryforge.backend.admin.prompt.binding.service.PromptBindingService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class PromptBindingController {

    private final PromptBindingService service;

    @GetMapping("/prompt-bindings")
    public List<PromptBindingDtos.PromptBindingRow> listBindings(
            @RequestParam(name = "family", required = false) String family
    ) {
        return service.listBindings(family);
    }

    @GetMapping("/prompt-bindings/{bindingKey}")
    public PromptBindingDtos.PromptBindingRow getBinding(@PathVariable String bindingKey) {
        return service.getBinding(bindingKey);
    }

    @PatchMapping("/prompt-bindings/{bindingKey}")
    public PromptBindingDtos.PromptBindingRow updateBinding(
            @PathVariable String bindingKey,
            @RequestBody PromptBindingDtos.PromptBindingUpdateRequest request
    ) {
        return service.updateBinding(bindingKey, request);
    }

    @PostMapping("/prompt-bindings/{bindingKey}/validate")
    public PromptBindingDtos.PromptValidationResponse validateBindingPrompt(
            @PathVariable String bindingKey,
            @RequestBody PromptBindingDtos.PromptValidationRequest request
    ) {
        return service.validatePrompt(request);
    }
}
