package io.queryforge.backend.admin.console.jobs;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.UUID;

public final class LlmJobDtos {

    private LlmJobDtos() {
    }

    public record LlmJobRow(
            UUID jobId,
            String jobType,
            String jobStatus,
            Integer priority,
            UUID generationBatchId,
            UUID gatingBatchId,
            UUID ragTestRunId,
            String experimentName,
            String commandName,
            JsonNode commandArgs,
            Integer totalItems,
            Integer processedItems,
            Double progressPct,
            Double estimatedSecondsPerUnit,
            Long estimatedRemainingSeconds,
            Integer retryCount,
            Integer maxRetries,
            Instant nextRunAt,
            Instant startedAt,
            Instant finishedAt,
            String errorMessage,
            JsonNode resultJson,
            Instant createdAt
    ) {
    }

    public record LlmJobItemRow(
            UUID jobItemId,
            UUID jobId,
            Integer itemOrder,
            String itemType,
            String itemStatus,
            Integer retryCount,
            Integer maxRetries,
            JsonNode payloadJson,
            JsonNode checkpointJson,
            JsonNode resultJson,
            String errorMessage,
            Instant startedAt,
            Instant finishedAt,
            Instant createdAt
    ) {
    }
}
