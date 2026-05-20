package io.queryforge.backend.admin.prompt.controller;

import io.queryforge.backend.admin.prompt.model.PromptAdminDtos;
import io.queryforge.backend.admin.prompt.service.PromptAdminService;
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
public class PromptAdminController {

    private final PromptAdminService service;

    @GetMapping("/prompt-assets")
    public List<PromptAdminDtos.PromptAssetRow> listAssets(
            @RequestParam(name = "family", required = false) String family,
            @RequestParam(name = "active_only", required = false) Boolean activeOnly
    ) {
        return service.listAssets(family, activeOnly);
    }

    @GetMapping("/prompt-assets/{promptAssetId}")
    public PromptAdminDtos.PromptAssetDetail getAsset(@PathVariable UUID promptAssetId) {
        return service.getAsset(promptAssetId);
    }

    @PostMapping("/prompt-assets/{promptAssetId}/revisions")
    public PromptAdminDtos.PromptAssetDetail createRevision(
            @PathVariable UUID promptAssetId,
            @RequestBody PromptAdminDtos.PromptRevisionRequest request
    ) {
        return service.createRevision(promptAssetId, request);
    }

    @PatchMapping("/prompt-assets/{promptAssetId}")
    public PromptAdminDtos.PromptAssetDetail updateAsset(
            @PathVariable UUID promptAssetId,
            @RequestBody PromptAdminDtos.PromptAssetUpdateRequest request
    ) {
        return service.updateAsset(promptAssetId, request);
    }

    @PostMapping("/prompt-assets/{promptAssetId}/deactivate")
    public PromptAdminDtos.PromptAssetDetail deactivateAsset(@PathVariable UUID promptAssetId) {
        return service.deactivateAsset(promptAssetId);
    }

    @GetMapping("/prompt-bindings")
    public List<PromptAdminDtos.PromptBindingRow> listBindings(
            @RequestParam(name = "family", required = false) String family
    ) {
        return service.listBindings(family);
    }

    @GetMapping("/prompt-bindings/{bindingKey}")
    public PromptAdminDtos.PromptBindingRow getBinding(@PathVariable String bindingKey) {
        return service.getBinding(bindingKey);
    }

    @PatchMapping("/prompt-bindings/{bindingKey}")
    public PromptAdminDtos.PromptBindingRow updateBinding(
            @PathVariable String bindingKey,
            @RequestBody PromptAdminDtos.PromptBindingUpdateRequest request
    ) {
        return service.updateBinding(bindingKey, request);
    }

    @PostMapping("/prompt-bindings/{bindingKey}/validate")
    public PromptAdminDtos.PromptValidationResponse validateBindingPrompt(
            @PathVariable String bindingKey,
            @RequestBody PromptAdminDtos.PromptValidationRequest request
    ) {
        return service.validatePrompt(request);
    }
}
