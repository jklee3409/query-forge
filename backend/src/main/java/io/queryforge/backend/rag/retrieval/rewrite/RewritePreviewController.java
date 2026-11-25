package io.queryforge.backend.rag.retrieval.rewrite;

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
    public RewriteDtos.RewritePreviewResponse previewRewrite(@RequestBody RewriteDtos.RewritePreviewRequest request) {
        return previewService.previewRewrite(request);
    }
}
