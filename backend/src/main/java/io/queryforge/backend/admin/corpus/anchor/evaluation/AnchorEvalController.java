package io.queryforge.backend.admin.corpus.anchor.evaluation;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/admin/corpus")
@RequiredArgsConstructor
public class AnchorEvalController {

    private final AnchorEvalService service;

    @PostMapping("/anchors/eval/runs")
    public AnchorEvalDtos.AnchorEvalRunSummary createAnchorEvalRun(
            @RequestBody AnchorEvalDtos.AnchorEvalRunCreateRequest request
    ) {
        return service.createAnchorEvalRun(request);
    }

    @GetMapping("/anchors/eval/runs")
    public List<AnchorEvalDtos.AnchorEvalRunSummary> listAnchorEvalRuns(
            @RequestParam(name = "limit", required = false) Integer limit,
            @RequestParam(name = "offset", required = false) Integer offset
    ) {
        return service.listAnchorEvalRuns(limit, offset);
    }

    @GetMapping("/anchors/eval/runs/{runId}")
    public AnchorEvalDtos.AnchorEvalRunDetail getAnchorEvalRun(@PathVariable UUID runId) {
        return service.getAnchorEvalRun(runId);
    }

    @PostMapping("/anchors/eval/runs/{runId}/labels")
    public AnchorEvalDtos.AnchorEvalRunSummary upsertAnchorEvalLabel(
            @PathVariable UUID runId,
            @RequestBody AnchorEvalDtos.AnchorEvalLabelRequest request
    ) {
        return service.upsertAnchorEvalLabel(runId, request);
    }

    @PostMapping("/anchors/eval/runs/{runId}/recompute")
    public AnchorEvalDtos.AnchorEvalRunSummary recomputeAnchorEvalSummary(@PathVariable UUID runId) {
        return service.recomputeAnchorEvalSummary(runId);
    }
}
