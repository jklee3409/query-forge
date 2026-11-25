package io.queryforge.backend.admin.corpus.run;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class CorpusRunDtos {

    private CorpusRunDtos() {
    }

    public record RunSummary(
            UUID runId,
            UUID domainId,
            String runType,
            String runStatus,
            String triggerType,
            JsonNode sourceScope,
            JsonNode configSnapshot,
            Instant startedAt,
            Instant finishedAt,
            Long durationMs,
            JsonNode summaryJson,
            String errorMessage,
            String createdBy,
            Instant createdAt,
            Instant cancelRequestedAt,
            Instant updatedAt
    ) {
    }

    public record RunStep(
            UUID stepId,
            String stepName,
            int stepOrder,
            String stepStatus,
            String inputArtifactPath,
            String outputArtifactPath,
            String commandLine,
            JsonNode metricsJson,
            Instant startedAt,
            Instant finishedAt,
            String errorMessage,
            String stdoutLogPath,
            String stderrLogPath,
            String stdoutExcerpt,
            String stderrExcerpt,
            Instant updatedAt
        ) {
    }

    public record RunDetail(
            RunSummary run,
            List<RunStep> steps
    ) {
    }
}
