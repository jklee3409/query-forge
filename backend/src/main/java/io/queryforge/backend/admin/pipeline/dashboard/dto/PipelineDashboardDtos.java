package io.queryforge.backend.admin.pipeline.dashboard.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class PipelineDashboardDtos {

    private PipelineDashboardDtos() {
    }

    public record DashboardStats(
            long sourceCount,
            long activeDocumentCount,
            long activeChunkCount,
            long glossaryTermCount,
            long duplicateUrlSkippedCount,
            long sameHashSkippedCount,
            long unchangedSkippedCount,
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
