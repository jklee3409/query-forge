package io.queryforge.backend.admin.console.synthetic;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.UUID;

public final class SyntheticGenerationDtos {

    private SyntheticGenerationDtos() {
    }

    public record SyntheticGenerationMethod(
            UUID generationMethodId,
            String methodCode,
            String methodName,
            String description,
            boolean active,
            String promptTemplateVersion,
            String summaryStrategy,
            String translationStrategy,
            String queryLanguageStrategy,
            String terminologyPreservationRule,
            JsonNode metadata
    ) {
    }

    public record SyntheticBatchRunRequest(
            String methodCode,
            UUID domainId,
            String versionName,
            String sourceDocumentVersion,
            String sourceId,
            String sourceDocumentId,
            Integer limitChunks,
            Double avgQueriesPerChunk,
            Integer maxTotalQueries,
            Boolean randomChunkSampling,
            String llmModel,
            Integer llmRpm,
            String llmExecutionMode,
            String geminiBatchInputMode,
            String createdBy
    ) {
    }

    public record SyntheticGenerationBatchRow(
            UUID batchId,
            String methodCode,
            String methodName,
            String versionName,
            String sourceDocumentVersion,
            UUID sourceGenerationRunId,
            String status,
            Instant startedAt,
            Instant finishedAt,
            int totalGeneratedCount,
            Integer targetQueryCount,
            Double estimatedSecondsPerQuery,
            Long estimatedRemainingSeconds,
            String llmJobStatus,
            String llmJobItemStatus,
            Integer llmRetryCount,
            Integer llmMaxRetries,
            String createdBy,
            JsonNode metricsJson
    ) {
    }

    public record SyntheticQueryRow(
            String queryId,
            String queryText,
            String queryType,
            String generationMethod,
            UUID generationBatchId,
            String generationBatchVersion,
            String sourceChunkId,
            JsonNode targetChunkIds,
            Instant createdAt,
            boolean gated
    ) {
    }

    public record SyntheticQueryDetailResponse(
            String queryId,
            String queryText,
            String queryType,
            String generationMethod,
            UUID generationBatchId,
            String languageProfile,
            JsonNode sourceChunk,
            JsonNode sourceLinks,
            String sourceSummary,
            JsonNode glossaryTerms,
            JsonNode mappedAnchors,
            String promptTemplateVersion,
            String promptVersion,
            String promptHash,
            JsonNode rawOutput,
            JsonNode metadata
    ) {
    }

    public record SyntheticStatsResponse(
            JsonNode byMethod,
            JsonNode byBatch,
            JsonNode byQueryType,
            JsonNode byDocumentVersion
    ) {
    }
}
