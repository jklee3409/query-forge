package io.queryforge.backend.admin.pipeline.execution.controller;

import io.queryforge.backend.admin.corpus.run.dto.CorpusRunDtos;
import io.queryforge.backend.admin.pipeline.execution.dto.PipelineExecutionDtos;
import io.queryforge.backend.admin.pipeline.execution.service.PipelineAdminService;
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

    @PostMapping("/collect")
    public PipelineExecutionDtos.PipelineRunActionResponse collect(
            @RequestBody(required = false) PipelineExecutionDtos.PipelineRunRequest request
    ) {
        return service.startCollect(defaultRequest(request));
    }

    @PostMapping("/normalize")
    public PipelineExecutionDtos.PipelineRunActionResponse normalize(
            @RequestBody(required = false) PipelineExecutionDtos.PipelineRunRequest request
    ) {
        return service.startNormalize(defaultRequest(request));
    }

    @PostMapping("/chunk")
    public PipelineExecutionDtos.PipelineRunActionResponse chunk(
            @RequestBody(required = false) PipelineExecutionDtos.PipelineRunRequest request
    ) {
        return service.startChunk(defaultRequest(request));
    }

    @PostMapping("/glossary")
    public PipelineExecutionDtos.PipelineRunActionResponse glossary(
            @RequestBody(required = false) PipelineExecutionDtos.PipelineRunRequest request
    ) {
        return service.startGlossary(defaultRequest(request));
    }

    @PostMapping("/import")
    public PipelineExecutionDtos.PipelineRunActionResponse importCorpus(
            @RequestBody(required = false) PipelineExecutionDtos.PipelineRunRequest request
    ) {
        return service.startImport(defaultRequest(request));
    }

    @PostMapping("/full-ingest")
    public PipelineExecutionDtos.PipelineRunActionResponse fullIngest(
            @RequestBody(required = false) PipelineExecutionDtos.PipelineRunRequest request
    ) {
        return service.startFullIngest(defaultRequest(request));
    }

    @PostMapping("/runs/{runId}/retry")
    public PipelineExecutionDtos.PipelineRunActionResponse retry(@PathVariable UUID runId) {
        return service.retryRun(runId);
    }

    @PostMapping("/runs/{runId}/cancel")
    public PipelineExecutionDtos.PipelineRunActionResponse cancel(@PathVariable UUID runId) {
        return service.cancelRun(runId);
    }

    @GetMapping("/runs")
    public List<CorpusRunDtos.RunSummary> listRuns(
            @RequestParam(name = "run_id", required = false) UUID runId,
            @RequestParam(name = "run_status", required = false) String runStatus,
            @RequestParam(name = "run_type", required = false) String runType,
            @RequestParam(name = "domain_id", required = false) UUID domainId,
            @RequestParam(name = "limit", required = false) Integer limit,
            @RequestParam(name = "offset", required = false) Integer offset
    ) {
        return service.listRuns(runId, runStatus, runType, domainId, limit, offset);
    }

    @GetMapping("/runs/{runId}")
    public CorpusRunDtos.RunDetail getRun(@PathVariable UUID runId) {
        return service.getRun(runId);
    }

    @GetMapping("/runs/{runId}/steps")
    public List<CorpusRunDtos.RunStep> getRunSteps(@PathVariable UUID runId) {
        return service.getRunSteps(runId);
    }

    @GetMapping("/runs/{runId}/logs")
    public PipelineExecutionDtos.PipelineRunLogsResponse getRunLogs(@PathVariable UUID runId) {
        return service.getRunLogs(runId);
    }

    private PipelineExecutionDtos.PipelineRunRequest defaultRequest(PipelineExecutionDtos.PipelineRunRequest request) {
        if (request != null) {
            return request;
        }
        return new PipelineExecutionDtos.PipelineRunRequest(
                null,
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
