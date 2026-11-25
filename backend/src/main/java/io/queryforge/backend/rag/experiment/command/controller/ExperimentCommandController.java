package io.queryforge.backend.rag.experiment.command.controller;

import io.queryforge.backend.rag.experiment.command.dto.ExperimentCommandDtos;
import io.queryforge.backend.rag.experiment.command.service.ExperimentPipelineService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class ExperimentCommandController {

    private final ExperimentPipelineService experimentPipelineService;

    @PostMapping("/admin/experiments/run")
    public ExperimentCommandDtos.ExperimentCommandResponse runExperimentCommand(
            @RequestBody ExperimentCommandDtos.ExperimentCommandRequest request
    ) {
        return experimentPipelineService.run(request);
    }
}
