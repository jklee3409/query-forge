package io.queryforge.backend.rag.experiment;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.UUID;

public final class RagExperimentDtos {

    private RagExperimentDtos() {
    }

    public record ExperimentSummaryResponse(
            UUID experimentRunId,
            String experimentKey,
            String status,
            Instant startedAt,
            Instant finishedAt,
            JsonNode parameters,
            JsonNode metrics,
            String notes
    ) {
    }

    public record ExperimentCommandRequest(
            String command,
            String experiment
    ) {
    }

    public record ExperimentCommandResponse(
            String command,
            String experiment,
            int exitCode,
            JsonNode summary,
            String stdout,
            String stderr
    ) {
    }
}
