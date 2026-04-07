package io.queryforge.backend.admin.pipeline.controller;

import io.queryforge.backend.admin.corpus.model.CorpusAdminDtos;
import io.queryforge.backend.admin.pipeline.model.PipelineAdminDtos;
import io.queryforge.backend.admin.pipeline.service.PipelineAdminService;
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
@RequestMapping("/api/admin/pipeline")
@RequiredArgsConstructor
public class PipelineAdminController {

    private final PipelineAdminService service;

    @GetMapping("/dashboard")
    public PipelineAdminDtos.DashboardStats dashboard() {
        return service.getDashboardStats();
    }

    @PostMapping("/collect")
    public PipelineAdminDtos.PipelineRunActionResponse collect(
            @RequestBody(required = false) PipelineAdminDtos.PipelineRunRequest request
    ) {
        return service.startCollect(defaultRequest(request));
    }

    @PostMapping("/normalize")
    public PipelineAdminDtos.PipelineRunActionResponse normalize(
            @RequestBody(required = false) PipelineAdminDtos.PipelineRunRequest request
    ) {
        return service.startNormalize(defaultRequest(request));
    }

    @PostMapping("/chunk")
    public PipelineAdminDtos.PipelineRunActionResponse chunk(
            @RequestBody(required = false) PipelineAdminDtos.PipelineRunRequest request
    ) {
        return service.startChunk(defaultRequest(request));
    }

    @PostMapping("/glossary")
    public PipelineAdminDtos.PipelineRunActionResponse glossary(
            @RequestBody(required = false) PipelineAdminDtos.PipelineRunRequest request
    ) {
        return service.startGlossary(defaultRequest(request));
    }

    @PostMapping("/import")
    public PipelineAdminDtos.PipelineRunActionResponse importCorpus(
            @RequestBody(required = false) PipelineAdminDtos.PipelineRunRequest request
    ) {
        return service.startImport(defaultRequest(request));
    }

    @PostMapping("/full-ingest")
    public PipelineAdminDtos.PipelineRunActionResponse fullIngest(
            @RequestBody(required = false) PipelineAdminDtos.PipelineRunRequest request
    ) {
        return service.startFullIngest(defaultRequest(request));
    }

    @PostMapping("/runs/{runId}/retry")
    public PipelineAdminDtos.PipelineRunActionResponse retry(@PathVariable UUID runId) {
        return service.retryRun(runId);
    }

    @PostMapping("/runs/{runId}/cancel")
    public PipelineAdminDtos.PipelineRunActionResponse cancel(@PathVariable UUID runId) {
        return service.cancelRun(runId);
    }

    @GetMapping("/runs")
    public List<CorpusAdminDtos.RunSummary> listRuns(
            @RequestParam(name = "run_id", required = false) UUID runId,
            @RequestParam(name = "run_status", required = false) String runStatus,
            @RequestParam(name = "run_type", required = false) String runType,
            @RequestParam(name = "limit", required = false) Integer limit,
            @RequestParam(name = "offset", required = false) Integer offset
    ) {
        return service.listRuns(runId, runStatus, runType, limit, offset);
    }

    @GetMapping("/runs/{runId}")
    public CorpusAdminDtos.RunDetail getRun(@PathVariable UUID runId) {
        return service.getRun(runId);
    }

    @GetMapping("/runs/{runId}/steps")
    public List<CorpusAdminDtos.RunStep> getRunSteps(@PathVariable UUID runId) {
        return service.getRunSteps(runId);
    }

    @GetMapping("/runs/{runId}/logs")
    public PipelineAdminDtos.PipelineRunLogsResponse getRunLogs(@PathVariable UUID runId) {
        return service.getRunLogs(runId);
    }

    private PipelineAdminDtos.PipelineRunRequest defaultRequest(PipelineAdminDtos.PipelineRunRequest request) {
        if (request != null) {
            return request;
        }
        return new PipelineAdminDtos.PipelineRunRequest(
                List.of(),
                List.of(),
                false,
                null,
                "api",
                null,
                null
        );
    }
}
