package io.queryforge.backend.admin.console.evaluation.anchor;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/admin/console")
@RequiredArgsConstructor
public class RagRewriteAnchorController {

    private final RagRewriteAnchorRepository repository;

    @GetMapping("/rag/tests/{runId}/anchors/summary")
    public Object ragTestAnchorSummary(@PathVariable UUID runId) {
        return repository.findRagAnchorSummary(runId);
    }

    @GetMapping("/rag/tests/details/{detailId}/anchors")
    public List<RagRewriteAnchorDtos.RagRewriteAnchorEvalRow> ragTestDetailAnchors(@PathVariable UUID detailId) {
        return repository.findRagRewriteAnchorEvalByDetailId(detailId);
    }
}
