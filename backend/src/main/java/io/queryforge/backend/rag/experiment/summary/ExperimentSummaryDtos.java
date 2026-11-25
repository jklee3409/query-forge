package io.queryforge.backend.rag.experiment.summary;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.UUID;

public final class ExperimentSummaryDtos {

    private ExperimentSummaryDtos() {
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
}
