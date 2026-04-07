package io.queryforge.backend.admin.pipeline.model;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Builder;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class PipelineAdminDtos {

    private PipelineAdminDtos() {
    }

    @Builder
    public record PipelineRunRequest(
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

    public record DashboardStats(
            long sourceCount,
            long activeDocumentCount,
            long activeChunkCount,
            long glossaryTermCount,
            long recentRunSuccessCount,
            long recentRunFailureCount,
            List<ProductDocumentStat> productDocumentStats,
            List<RecentRunStat> recentRuns,
            List<FailedStepStat> failedSteps
    ) {
    }

    public record ProductDocumentStat(
            String productName,
            long documentCount
    ) {
    }

    public record RecentRunStat(
            UUID runId,
            String runType,
            String runStatus,
            Instant startedAt,
            Instant finishedAt,
            String createdBy
    ) {
    }

    public record FailedStepStat(
            UUID runId,
            UUID stepId,
            String stepName,
            String errorMessage,
            Instant finishedAt
    ) {
    }
}
