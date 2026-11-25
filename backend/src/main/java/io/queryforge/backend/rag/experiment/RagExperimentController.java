package io.queryforge.backend.rag.experiment;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class RagExperimentController {

    private final RagExperimentQueryService experimentQueryService;
    private final ExperimentPipelineService experimentPipelineService;

    @GetMapping("/experiments/{runId}/summary")
    public RagExperimentDtos.ExperimentSummaryResponse experimentSummary(@PathVariable UUID runId) {
        return experimentQueryService.getExperimentSummary(runId);
    }

    @PostMapping("/admin/experiments/run")
    public RagExperimentDtos.ExperimentCommandResponse runExperimentCommand(
            @RequestBody RagExperimentDtos.ExperimentCommandRequest request
    ) {
        return experimentPipelineService.run(request);
    }
}
