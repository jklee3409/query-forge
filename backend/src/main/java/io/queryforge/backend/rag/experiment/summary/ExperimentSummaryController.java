package io.queryforge.backend.rag.experiment.summary;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class ExperimentSummaryController {

    private final RagExperimentQueryService experimentQueryService;

    @GetMapping("/experiments/{runId}/summary")
    public ExperimentSummaryDtos.ExperimentSummaryResponse experimentSummary(@PathVariable UUID runId) {
        return experimentQueryService.getExperimentSummary(runId);
    }

}
