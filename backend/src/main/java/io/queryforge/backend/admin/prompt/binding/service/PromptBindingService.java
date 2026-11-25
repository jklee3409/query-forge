package io.queryforge.backend.admin.prompt.binding.service;

import io.queryforge.backend.admin.prompt.binding.dto.PromptBindingDtos;
import io.queryforge.backend.admin.prompt.binding.repository.PromptBindingRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class PromptBindingService {

    private final PromptBindingRepository repository;

    public List<PromptBindingDtos.PromptBindingRow> listBindings(String family) {
        return repository.findBindings(family);
    }

    public PromptBindingDtos.PromptBindingRow getBinding(String bindingKey) {
        return repository.findBinding(bindingKey)
                .orElseThrow(() -> new IllegalArgumentException("prompt binding not found: " + bindingKey));
    }

    @Transactional
    public PromptBindingDtos.PromptBindingRow updateBinding(
            String bindingKey,
            PromptBindingDtos.PromptBindingUpdateRequest request
    ) {
        repository.updateBinding(bindingKey, request);
        return getBinding(bindingKey);
    }

    public PromptBindingDtos.PromptValidationResponse validatePrompt(PromptBindingDtos.PromptValidationRequest request) {
        List<String> warnings = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        String content = request == null ? null : request.contentBody();
        if (content == null || content.isBlank()) {
            errors.add("contentBody is required");
            return new PromptBindingDtos.PromptValidationResponse(false, warnings, errors);
        }
        String trimmed = content.trim();
        if (!trimmed.startsWith("---")) {
            warnings.add("front matter is missing");
        }
        if (!content.contains("id:")) {
            warnings.add("front matter id is missing");
        }
        if (!content.contains("family:")) {
            warnings.add("front matter family is missing");
        }
        if (!content.contains("version:")) {
            warnings.add("front matter version is missing");
        }
        if (!content.toLowerCase().contains("output")) {
            warnings.add("output contract is not explicitly visible");
        }
        return new PromptBindingDtos.PromptValidationResponse(errors.isEmpty(), warnings, errors);
    }
}
