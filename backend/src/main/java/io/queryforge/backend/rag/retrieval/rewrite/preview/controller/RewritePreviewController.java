package io.queryforge.backend.rag.retrieval.rewrite.preview.controller;

import io.queryforge.backend.rag.retrieval.rewrite.preview.dto.RewritePreviewDtos;
import io.queryforge.backend.rag.retrieval.rewrite.preview.service.RewritePreviewService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class RewritePreviewController {

    private final RewritePreviewService previewService;

    @PostMapping("/rewrite/preview")
    public RewritePreviewDtos.RewritePreviewResponse previewRewrite(@RequestBody RewritePreviewDtos.RewritePreviewRequest request) {
        return previewService.previewRewrite(request);
    }
}
