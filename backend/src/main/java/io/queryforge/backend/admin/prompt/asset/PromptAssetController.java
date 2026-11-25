package io.queryforge.backend.admin.prompt.asset;

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
import java.util.UUID;

@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class PromptAssetController {

    private final PromptAssetService service;

    @GetMapping("/prompt-assets")
    public List<PromptAssetDtos.PromptAssetRow> listAssets(
            @RequestParam(name = "family", required = false) String family,
            @RequestParam(name = "active_only", required = false) Boolean activeOnly
    ) {
        return service.listAssets(family, activeOnly);
    }

    @GetMapping("/prompt-assets/{promptAssetId}")
    public PromptAssetDtos.PromptAssetDetail getAsset(@PathVariable UUID promptAssetId) {
        return service.getAsset(promptAssetId);
    }

    @PostMapping("/prompt-assets/{promptAssetId}/revisions")
    public PromptAssetDtos.PromptAssetDetail createRevision(
            @PathVariable UUID promptAssetId,
            @RequestBody PromptAssetDtos.PromptRevisionRequest request
    ) {
        return service.createRevision(promptAssetId, request);
    }

    @PatchMapping("/prompt-assets/{promptAssetId}")
    public PromptAssetDtos.PromptAssetDetail updateAsset(
            @PathVariable UUID promptAssetId,
            @RequestBody PromptAssetDtos.PromptAssetUpdateRequest request
    ) {
        return service.updateAsset(promptAssetId, request);
    }

    @PostMapping("/prompt-assets/{promptAssetId}/deactivate")
    public PromptAssetDtos.PromptAssetDetail deactivateAsset(@PathVariable UUID promptAssetId) {
        return service.deactivateAsset(promptAssetId);
    }
}
