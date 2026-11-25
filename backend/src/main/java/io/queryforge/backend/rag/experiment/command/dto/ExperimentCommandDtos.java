package io.queryforge.backend.rag.experiment.command.dto;

import com.fasterxml.jackson.databind.JsonNode;

public final class ExperimentCommandDtos {

    private ExperimentCommandDtos() {
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
