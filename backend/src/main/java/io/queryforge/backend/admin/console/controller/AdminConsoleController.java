package io.queryforge.backend.admin.console.controller;

import io.queryforge.backend.admin.console.model.AdminConsoleDtos;
import io.queryforge.backend.admin.console.service.AdminConsoleService;
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
public class AdminConsoleController {

    private final AdminConsoleService service;

    @GetMapping("/synthetic/methods")
    public List<AdminConsoleDtos.SyntheticGenerationMethod> syntheticMethods() {
        return service.listGenerationMethods();
    }

    @GetMapping("/synthetic/batches")
    public List<AdminConsoleDtos.SyntheticGenerationBatchRow> syntheticBatches(
            @RequestParam(name = "limit", required = false) Integer limit
    ) {
        return service.listGenerationBatches(limit);
    }

    @PostMapping("/synthetic/batches/run")
    public AdminConsoleDtos.SyntheticGenerationBatchRow runSyntheticBatch(
            @RequestBody AdminConsoleDtos.SyntheticBatchRunRequest request
    ) {
        return service.runSyntheticGeneration(request);
    }

    @GetMapping("/synthetic/queries")
    public List<AdminConsoleDtos.SyntheticQueryRow> syntheticQueries(
            @RequestParam(name = "method_code", required = false) String methodCode,
            @RequestParam(name = "batch_id", required = false) UUID batchId,
            @RequestParam(name = "query_type", required = false) String queryType,
            @RequestParam(name = "gated", required = false) Boolean gated,
            @RequestParam(name = "limit", required = false) Integer limit,
            @RequestParam(name = "offset", required = false) Integer offset
    ) {
        return service.listSyntheticQueries(methodCode, batchId, queryType, gated, limit, offset);
    }

    @GetMapping("/synthetic/queries/{queryId}")
    public AdminConsoleDtos.SyntheticQueryDetailResponse syntheticQueryDetail(@PathVariable String queryId) {
        return service.getSyntheticQueryDetail(queryId);
    }

    @GetMapping("/synthetic/stats")
    public AdminConsoleDtos.SyntheticStatsResponse syntheticStats(
            @RequestParam(name = "method_code", required = false) String methodCode,
            @RequestParam(name = "batch_id", required = false) UUID batchId
    ) {
        return service.getSyntheticStats(methodCode, batchId);
    }

    @GetMapping("/dashboard/stats")
    public AdminConsoleDtos.AdminDashboardStats dashboardStats() {
        return service.getDashboardStats();
    }

    @GetMapping("/gating/batches")
    public List<AdminConsoleDtos.GatingBatchRow> gatingBatches(
            @RequestParam(name = "limit", required = false) Integer limit
    ) {
        return service.listGatingBatches(limit);
    }

    @PostMapping("/gating/batches/run")
    public AdminConsoleDtos.GatingBatchRow runGatingBatch(
            @RequestBody AdminConsoleDtos.GatingBatchRunRequest request
    ) {
        return service.runGating(request);
    }

    @GetMapping("/gating/batches/{gatingBatchId}/funnel")
    public AdminConsoleDtos.GatingFunnelResponse gatingFunnel(
            @PathVariable UUID gatingBatchId,
            @RequestParam(name = "method_code", required = false) String methodCode
    ) {
        return service.getGatingFunnel(gatingBatchId, methodCode);
    }

    @GetMapping("/gating/batches/{gatingBatchId}/results")
    public List<AdminConsoleDtos.GatingResultRow> gatingResults(
            @PathVariable UUID gatingBatchId,
            @RequestParam(name = "method_code", required = false) String methodCode,
            @RequestParam(name = "pass_stage", required = false) String passStage,
            @RequestParam(name = "query_type", required = false) String queryType,
            @RequestParam(name = "limit", required = false) Integer limit,
            @RequestParam(name = "offset", required = false) Integer offset
    ) {
        return service.listGatingResults(gatingBatchId, methodCode, passStage, queryType, limit, offset);
    }

    @GetMapping("/rag/datasets")
    public List<AdminConsoleDtos.EvalDatasetRow> ragDatasets() {
        return service.listEvalDatasets();
    }

    @GetMapping("/rag/datasets/{datasetId}/items")
    public List<AdminConsoleDtos.EvalDatasetItemRow> ragDatasetItems(
            @PathVariable UUID datasetId,
            @RequestParam(name = "limit", required = false) Integer limit,
            @RequestParam(name = "offset", required = false) Integer offset
    ) {
        return service.listEvalDatasetItems(datasetId, limit, offset);
    }

    @GetMapping("/rag/tests")
    public List<AdminConsoleDtos.RagTestRunRow> ragTests(
            @RequestParam(name = "limit", required = false) Integer limit
    ) {
        return service.listRagTestRuns(limit);
    }

    @PostMapping("/rag/tests/run")
    public AdminConsoleDtos.RagTestRunRow runRagTest(
            @RequestBody AdminConsoleDtos.RagTestRunRequest request
    ) {
        return service.runRagTest(request);
    }

    @GetMapping("/rag/tests/{runId}")
    public AdminConsoleDtos.RagTestRunDetail ragTestDetail(
            @PathVariable UUID runId,
            @RequestParam(name = "detail_limit", required = false) Integer detailLimit
    ) {
        return service.getRagTestRunDetail(runId, detailLimit);
    }

    @DeleteMapping("/rag/tests/{runId}")
    public void deleteRagTest(@PathVariable UUID runId) {
        service.deleteRagTestRun(runId);
    }

    @GetMapping("/rag/compare")
    public AdminConsoleDtos.RagCompareResponse ragCompare(@RequestParam("dataset_id") UUID datasetId) {
        return service.compareRagRuns(datasetId);
    }

    @GetMapping("/rewrite/logs")
    public List<AdminConsoleDtos.RewriteDebugRow> rewriteLogs(
            @RequestParam(name = "limit", required = false) Integer limit,
            @RequestParam(name = "offset", required = false) Integer offset
    ) {
        return service.listRewriteDebugRows(limit, offset);
    }

    @GetMapping("/rewrite/logs/{rewriteLogId}")
    public AdminConsoleDtos.RewriteDebugDetail rewriteLogDetail(@PathVariable UUID rewriteLogId) {
        return service.getRewriteDebugDetail(rewriteLogId);
    }

    @GetMapping("/llm-jobs")
    public List<AdminConsoleDtos.LlmJobRow> llmJobs(
            @RequestParam(name = "limit", required = false) Integer limit
    ) {
        return service.listLlmJobs(limit);
    }

    @GetMapping("/llm-jobs/{jobId}")
    public AdminConsoleDtos.LlmJobRow llmJob(@PathVariable UUID jobId) {
        return service.getLlmJob(jobId);
    }

    @GetMapping("/llm-jobs/{jobId}/items")
    public List<AdminConsoleDtos.LlmJobItemRow> llmJobItems(@PathVariable UUID jobId) {
        return service.listLlmJobItems(jobId);
    }

    @PostMapping("/llm-jobs/{jobId}/pause")
    public void pauseLlmJob(@PathVariable UUID jobId) {
        service.pauseLlmJob(jobId);
    }

    @PostMapping("/llm-jobs/{jobId}/resume")
    public void resumeLlmJob(@PathVariable UUID jobId) {
        service.resumeLlmJob(jobId);
    }

    @PostMapping("/llm-jobs/{jobId}/cancel")
    public void cancelLlmJob(@PathVariable UUID jobId) {
        service.cancelLlmJob(jobId);
    }

    @PostMapping("/llm-jobs/{jobId}/retry")
    public void retryLlmJob(@PathVariable UUID jobId) {
        service.retryLlmJob(jobId);
    }
}
