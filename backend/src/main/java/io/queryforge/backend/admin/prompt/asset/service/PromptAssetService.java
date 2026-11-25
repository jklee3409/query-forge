package io.queryforge.backend.admin.prompt.asset.service;

import io.queryforge.backend.admin.prompt.asset.dto.PromptAssetDtos;
import io.queryforge.backend.admin.prompt.asset.repository.PromptAssetRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class PromptAssetService {

    private final PromptAssetRepository repository;

    public List<PromptAssetDtos.PromptAssetRow> listAssets(String family, Boolean activeOnly) {
        return repository.findAssets(family, activeOnly == null || activeOnly);
    }

    public PromptAssetDtos.PromptAssetDetail getAsset(UUID promptAssetId) {
        PromptAssetDtos.PromptAssetDetail detail = repository.findAsset(promptAssetId)
                .orElseThrow(() -> new IllegalArgumentException("prompt asset not found: " + promptAssetId));
        if (detail.contentBody() != null || !"file".equals(detail.asset().storageBackend())) {
            return detail;
        }
        return new PromptAssetDtos.PromptAssetDetail(detail.asset(), readFileBackedPrompt(detail.asset().contentPath()));
    }

    @Transactional
    public PromptAssetDtos.PromptAssetDetail createRevision(
            UUID promptAssetId,
            PromptAssetDtos.PromptRevisionRequest request
    ) {
        validateRevisionRequest(request);
        UUID revisionId = repository.createRevision(promptAssetId, request);
        return getAsset(revisionId);
    }

    @Transactional
    public PromptAssetDtos.PromptAssetDetail updateAsset(
            UUID promptAssetId,
            PromptAssetDtos.PromptAssetUpdateRequest request
    ) {
        if (request.contentBody() != null && request.contentBody().isBlank()) {
            throw new IllegalArgumentException("contentBody must not be blank");
        }
        repository.updateAsset(promptAssetId, request);
        return getAsset(promptAssetId);
    }

    @Transactional
    public PromptAssetDtos.PromptAssetDetail deactivateAsset(UUID promptAssetId) {
        repository.deactivateAsset(promptAssetId);
        return getAsset(promptAssetId);
    }

    private void validateRevisionRequest(PromptAssetDtos.PromptRevisionRequest request) {
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

    private String readFileBackedPrompt(String contentPath) {
        if (contentPath == null || contentPath.isBlank()) {
            return null;
        }
        List<Path> candidates = List.of(
                Path.of(contentPath),
                Path.of("..").resolve(contentPath),
                Path.of("..").resolve("..").resolve(contentPath)
        );
        for (Path candidate : candidates) {
            if (Files.isRegularFile(candidate)) {
                try {
                    return Files.readString(candidate);
                } catch (Exception ignored) {
                    return null;
                }
            }
        }
        return null;
    }
}
