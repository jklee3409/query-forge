package io.queryforge.backend.admin.prompt.service;

import io.queryforge.backend.admin.prompt.model.PromptAdminDtos;
import io.queryforge.backend.admin.prompt.repository.PromptAdminRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class PromptAdminService {

    private final PromptAdminRepository repository;

    public List<PromptAdminDtos.PromptAssetRow> listAssets(String family, Boolean activeOnly) {
        return repository.findAssets(family, activeOnly == null || activeOnly);
    }

    public PromptAdminDtos.PromptAssetDetail getAsset(UUID promptAssetId) {
        return repository.findAsset(promptAssetId)
                .orElseThrow(() -> new IllegalArgumentException("prompt asset not found: " + promptAssetId));
    }

    @Transactional
    public PromptAdminDtos.PromptAssetDetail createRevision(
            UUID promptAssetId,
            PromptAdminDtos.PromptRevisionRequest request
    ) {
        validateRevisionRequest(request);
        UUID revisionId = repository.createRevision(promptAssetId, request);
        return getAsset(revisionId);
    }

    @Transactional
    public PromptAdminDtos.PromptAssetDetail updateAsset(
            UUID promptAssetId,
            PromptAdminDtos.PromptAssetUpdateRequest request
    ) {
        if (request.contentBody() != null && request.contentBody().isBlank()) {
            throw new IllegalArgumentException("contentBody must not be blank");
        }
        repository.updateAsset(promptAssetId, request);
        return getAsset(promptAssetId);
    }

    @Transactional
    public PromptAdminDtos.PromptAssetDetail deactivateAsset(UUID promptAssetId) {
        repository.deactivateAsset(promptAssetId);
        return getAsset(promptAssetId);
    }

    public List<PromptAdminDtos.PromptBindingRow> listBindings(String family) {
        return repository.findBindings(family);
    }

    public PromptAdminDtos.PromptBindingRow getBinding(String bindingKey) {
        return repository.findBinding(bindingKey)
                .orElseThrow(() -> new IllegalArgumentException("prompt binding not found: " + bindingKey));
    }

    @Transactional
    public PromptAdminDtos.PromptBindingRow updateBinding(
            String bindingKey,
            PromptAdminDtos.PromptBindingUpdateRequest request
    ) {
        repository.updateBinding(bindingKey, request);
        return getBinding(bindingKey);
    }

    public PromptAdminDtos.PromptValidationResponse validatePrompt(PromptAdminDtos.PromptValidationRequest request) {
        List<String> warnings = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        String content = request == null ? null : request.contentBody();
        if (content == null || content.isBlank()) {
            errors.add("contentBody is required");
            return new PromptAdminDtos.PromptValidationResponse(false, warnings, errors);
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
        return new PromptAdminDtos.PromptValidationResponse(errors.isEmpty(), warnings, errors);
    }

    private void validateRevisionRequest(PromptAdminDtos.PromptRevisionRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request body is required");
        }
        if (request.version() == null || request.version().isBlank()) {
            throw new IllegalArgumentException("version is required");
        }
        if (request.contentBody() == null || request.contentBody().isBlank()) {
            throw new IllegalArgumentException("contentBody is required");
        }
    }
}
