package io.queryforge.backend.admin.console.model;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class AdminConsoleDtos {

    private AdminConsoleDtos() {
    }

    public record PipelineOverviewResponse(
            boolean running,
            Instant lastRunStartedAt,
            Instant lastRunFinishedAt,
            String lastRunStatus,
            long collectedDocumentCount,
            long normalizedDocumentCount,
            long chunkCount,
            long glossaryCount,
            JsonNode sourceScopePreview
    ) {
    }

    public record ActionResponse(
            String status,
            String message,
            JsonNode payload
    ) {
    }

    public record PipelineRunCommandRequest(
            String runType,
            List<String> sourceIds,
            List<String> documentIds,
            Boolean dryRun,
            String createdBy,
            Integer limit
    ) {
    }

    public record PipelineRunSummary(
            UUID runId,
            String runType,
            String runStatus,
            Instant startedAt,
            Instant finishedAt,
            Long durationMs,
            long processedCount,
            boolean hasError,
            String errorMessage
    ) {
    }

    public record PipelineDocumentRow(
            String documentId,
            String sourceId,
            String productName,
            String versionLabel,
            String title,
            String canonicalUrl,
            long sectionCount,
            long chunkCount,
            boolean active,
            Instant updatedAt
    ) {
    }

    public record PipelineDocumentDetailResponse(
            JsonNode document,
            JsonNode rawVsCleaned,
            JsonNode chunks,
            JsonNode glossaryTerms
    ) {
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
            String versionName,
            String sourceDocumentVersion,
            String sourceId,
            String sourceDocumentId,
            Integer limitChunks,
            Double avgQueriesPerChunk,
            Integer maxTotalQueries,
            String llmModel,
            Integer llmRpm,
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

    public record GatingBatchRunRequest(
            String gatingPreset,
            UUID generationBatchId,
            String methodCode,
            Boolean enableRuleFilter,
            Boolean enableLlmSelfEval,
            Boolean enableRetrievalUtility,
            Boolean enableDiversity,
            Integer ruleMinLengthShort,
            Integer ruleMaxLengthShort,
            Integer ruleMinLengthLong,
            Integer ruleMaxLengthLong,
            Integer ruleMinTokens,
            Integer ruleMaxTokens,
            Double ruleMinKoreanRatio,
            Double llmWeight,
            Double utilityWeight,
            Double diversityWeight,
            Double utilityTargetTop1Score,
            Double utilityTargetTop3Score,
            Double utilityTargetTop5Score,
            Double utilitySameDocTop3Score,
            Double utilitySameDocTop5Score,
            Double utilityOutsideTop5Score,
            Double utilityMultiPartialBonus,
            Double utilityMultiFullBonus,
            Double utilityThreshold,
            Double diversityThresholdSameChunk,
            Double diversityThresholdSameDoc,
            Double finalScoreThreshold,
            String createdBy
    ) {
    }

    public record GatingBatchRow(
            UUID gatingBatchId,
            String gatingPreset,
            UUID generationBatchId,
            String methodCode,
            String methodName,
            UUID sourceGenerationRunId,
            UUID sourceGatingRunId,
            String status,
            Instant startedAt,
            Instant finishedAt,
            int processedCount,
            int acceptedCount,
            int rejectedCount,
            JsonNode rejectionSummary,
            JsonNode stageConfig
    ) {
    }

    public record GatingFunnelResponse(
            UUID gatingBatchId,
            String methodCode,
            String gatingPreset,
            int generatedTotal,
            int passedRule,
            int passedLlm,
            int passedUtility,
            int passedDiversity,
            int finalAccepted
    ) {
    }

    public record GatingResultRow(
            String syntheticQueryId,
            String queryText,
            String queryType,
            String generationStrategy,
            Boolean passedRule,
            Boolean passedLlm,
            Boolean passedUtility,
            Boolean passedDiversity,
            Double utilityScore,
            Double noveltyScore,
            Double finalScore,
            boolean finalDecision,
            String rejectedStage,
            String rejectedReason,
            JsonNode llmScores,
            JsonNode rejectionReasons
    ) {
    }

    public record EvalDatasetRow(
            UUID datasetId,
            String datasetKey,
            String datasetName,
            String version,
            int totalItems,
            JsonNode categoryDistribution,
            JsonNode singleMultiDistribution,
            Instant createdAt
    ) {
    }

    public record EvalDatasetItemRow(
            UUID datasetId,
            String sampleId,
            String split,
            String queryCategory,
            String singleOrMultiChunk,
            String userQueryKo,
            JsonNode dialogContext
    ) {
    }

    public record RagTestRunRequest(
            UUID datasetId,
            List<String> methodCodes,
            List<UUID> generationBatchIds,
            UUID sourceGatingBatchId,
            Boolean gatingApplied,
            String gatingPreset,
            Boolean rewriteEnabled,
            Boolean selectiveRewrite,
            Boolean useSessionContext,
            Integer topK,
            Double threshold,
            Integer retrievalTopK,
            Integer rerankTopN,
            String createdBy
    ) {
    }

    public record RagTestRunRow(
            UUID ragTestRunId,
            String runLabel,
            String status,
            UUID datasetId,
            String datasetName,
            JsonNode generationMethodCodes,
            JsonNode generationBatchIds,
            Boolean gatingApplied,
            String gatingPreset,
            Boolean rewriteEnabled,
            Boolean selectiveRewrite,
            Boolean useSessionContext,
            Integer retrievalTopK,
            Double threshold,
            Instant startedAt,
            Instant finishedAt,
            JsonNode metricsJson
    ) {
    }

    public record RagTestRunDetail(
            RagTestRunRow run,
            JsonNode summary,
            List<RagTestResultDetailRow> details
    ) {
    }

    public record RagTestResultDetailRow(
            UUID detailId,
            UUID ragTestRunId,
            String sampleId,
            String queryCategory,
            String rawQuery,
            String rewriteQuery,
            Boolean rewriteApplied,
            JsonNode memoryCandidates,
            JsonNode rewriteCandidates,
            JsonNode retrievedChunks,
            JsonNode metricContribution,
            Boolean hitTarget
    ) {
    }

    public record RagCompareResponse(
            UUID datasetId,
            List<RagTestRunRow> runs
    ) {
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

    public record AdminDashboardStats(
            long sourceCount,
            long activeDocumentCount,
            long chunkCount,
            long glossaryCount,
            long syntheticQueryCount,
            long gatedAcceptedCount,
            long memoryCount,
            long ragRunCount
    ) {
    }

    public record RewriteDebugRow(
            UUID rewriteLogId,
            UUID onlineQueryId,
            String rawQuery,
            String finalQuery,
            String rewriteStrategy,
            boolean rewriteApplied,
            String gatingPreset,
            Double rawConfidence,
            Double selectedConfidence,
            Double confidenceDelta,
            String decisionReason,
            String rejectionReason,
            Instant createdAt
    ) {
    }

    public record RewriteDebugDetail(
            RewriteDebugRow rewrite,
            JsonNode memoryRetrievals,
            JsonNode candidateLogs
    ) {
    }
}
