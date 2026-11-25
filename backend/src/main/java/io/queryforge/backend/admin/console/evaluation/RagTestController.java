package io.queryforge.backend.admin.console.evaluation;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
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
@RequestMapping("/api/admin/console")
@RequiredArgsConstructor
public class RagTestController {

    private final RagTestService ragTestService;
    private final RagTestHistoryService ragTestHistoryService;

    @GetMapping("/rag/tests")
    public List<RagTestDtos.RagTestRunRow> ragTests(
            @RequestParam(name = "limit", required = false) Integer limit,
            @RequestParam(name = "domain_id", required = false) UUID domainId
    ) {
        return ragTestHistoryService.listRagTestRuns(limit, domainId);
    }

    @PostMapping("/rag/tests/run")
    public RagTestDtos.RagTestRunRow runRagTest(
            @RequestBody RagTestDtos.RagTestRunRequest request
    ) {
        return ragTestService.runRagTest(request);
    }

    @GetMapping("/rag/tests/{runId}")
    public RagTestDtos.RagTestRunDetail ragTestDetail(
            @PathVariable UUID runId,
            @RequestParam(name = "detail_limit", required = false) Integer detailLimit
    ) {
        return ragTestHistoryService.getRagTestRunDetail(runId, detailLimit);
    }

    @GetMapping("/rag/tests/{runId}/anchors/summary")
    public Object ragTestAnchorSummary(@PathVariable UUID runId) {
        return ragTestHistoryService.getRagAnchorSummary(runId);
    }

    @GetMapping("/rag/tests/details/{detailId}/anchors")
    public List<RagTestDtos.RagRewriteAnchorEvalRow> ragTestDetailAnchors(@PathVariable UUID detailId) {
        return ragTestHistoryService.listRagDetailAnchorEvaluations(detailId);
    }

    @DeleteMapping("/rag/tests/{runId}")
    public void deleteRagTest(@PathVariable UUID runId) {
        ragTestHistoryService.deleteRagTestRun(runId);
    }

    @GetMapping("/rag/compare")
    public RagTestDtos.RagCompareResponse ragCompare(@RequestParam("dataset_id") UUID datasetId) {
        return ragTestHistoryService.compareRagRuns(datasetId);
    }
}
