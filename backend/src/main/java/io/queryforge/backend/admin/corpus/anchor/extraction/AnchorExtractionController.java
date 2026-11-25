package io.queryforge.backend.admin.corpus.anchor.extraction;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/corpus")
@RequiredArgsConstructor
public class AnchorExtractionController {

    private final AnchorExtractionService service;

    @PostMapping("/anchors/extract")
    public AnchorExtractionDtos.AnchorExtractResponse extractAnchors(
            @RequestBody AnchorExtractionDtos.AnchorExtractRequest request
    ) {
        return service.extractAnchors(request);
    }
}
