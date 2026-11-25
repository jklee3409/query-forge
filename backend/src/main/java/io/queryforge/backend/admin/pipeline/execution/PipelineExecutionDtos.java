package io.queryforge.backend.admin.pipeline.execution;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Builder;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class PipelineExecutionDtos {

    private PipelineExecutionDtos() {
    }

    @Builder
    public record PipelineRunRequest(
            UUID domainId,
            List<String> sourceIds,
            List<String> documentIds,
            Boolean dryRun,
            String createdBy,
            String triggerType,
            Integer limit,
            JsonNode options
    ) {
    }

    @Builder
    public record PipelineRunActionResponse(
            UUID runId,
            String runType,
            String runStatus,
            String message
    ) {
    }

    public record StepLogDto(
            UUID stepId,
            String stepName,
            String stdoutLogPath,
            String stderrLogPath,
            String stdout,
            String stderr,
            Instant updatedAt
    ) {
    }

    public record PipelineRunLogsResponse(
            UUID runId,
            List<StepLogDto> steps
    ) {
    }
}
