package io.queryforge.backend.admin.corpus.run.controller;

import io.queryforge.backend.admin.corpus.run.dto.CorpusRunDtos;
import io.queryforge.backend.admin.corpus.run.service.CorpusRunService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/admin/corpus")
@RequiredArgsConstructor
public class CorpusRunController {

    private final CorpusRunService service;

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
        return service.getRunDetail(runId);
    }
}
