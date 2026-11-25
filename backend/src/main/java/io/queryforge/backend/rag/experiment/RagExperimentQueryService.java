package io.queryforge.backend.rag.experiment;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RagExperimentQueryService {

    private final RagExperimentRepository experimentRepository;

    public RagExperimentDtos.ExperimentSummaryResponse getExperimentSummary(UUID experimentRunId) {
        RagExperimentRepository.ExperimentRunSummary summary = experimentRepository.findExperimentRunSummary(experimentRunId)
                .orElseThrow(() -> new IllegalArgumentException("experiment run was not found: " + experimentRunId));
        return new RagExperimentDtos.ExperimentSummaryResponse(
                summary.experimentRunId(),
                summary.experimentKey(),
                summary.status(),
                summary.startedAt(),
                summary.finishedAt(),
                summary.parameters(),
                summary.metrics(),
                summary.notes()
        );
    }

    public List<RagExperimentRepository.ExperimentRunSummary> listRecentExperimentRuns(int limit) {
        return experimentRepository.listRecentExperimentRuns(limit);
    }
}
