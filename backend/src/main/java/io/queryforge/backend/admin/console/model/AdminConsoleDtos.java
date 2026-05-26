package io.queryforge.backend.admin.console.model;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.List;
import java.util.Map;
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

    public record GatingBatchRunRequest(
            String gatingPreset,
            UUID domainId,
            UUID generationBatchId,
            List<UUID> generationBatchIds,
            String methodCode,
            List<String> methodCodes,
            String llmModel,
            GatingRunConfig config,
            String createdBy
    ) {
    }

    public record GatingRunConfig(
            GatingStageFlags stageFlags,
            GatingRuleConfig ruleConfig,
            GatingWeightsConfig gatingWeights,
            GatingUtilityScoreConfig utilityScoreWeights,
            GatingThresholdConfig thresholds,
            RetrieverConfigRequest retrieverConfig
    ) {
    }

    public record RetrieverConfigRequest(
            String retrieverMode,
            String denseEmbeddingModel,
            Boolean denseEmbeddingRequired,
            Boolean denseFallbackEnabled,
            Boolean rerankEnabled,
            Integer candidatePoolK,
            Double denseWeight,
            Double bm25Weight,
            Double technicalWeight
    ) {
    }

    public record GatingStageFlags(
            Boolean enableRuleFilter,
            Boolean enableLlmSelfEval,
            Boolean enableRetrievalUtility,
            Boolean enableDiversity
    ) {
    }

    public record GatingRuleConfig(
            Integer minLengthShort,
            Integer maxLengthShort,
            Integer minLengthLong,
            Integer maxLengthLong,
            Integer minTokens,
            Integer maxTokens,
            Double minKoreanRatio
    ) {
    }

    public record GatingWeightsConfig(
            Double llmWeight,
            Double utilityWeight,
            Double diversityWeight
    ) {
    }

    public record GatingUtilityScoreConfig(
            Double targetTop1Score,
            Double targetTop3Score,
            Double targetTop5Score,
            Double targetTop10Score,
            Double sameDocTop3Score,
            Double sameDocTop5Score,
            Double outsideTop5Score,
            Double multiPartialBonus,
            Double multiFullBonus
    ) {
    }

    public record GatingThresholdConfig(
            Double utilityThreshold,
            Double diversityThresholdSameChunk,
            Double diversityThresholdSameDoc,
            Double finalScoreThreshold
    ) {
    }

    public record GatingBatchRow(
            UUID gatingBatchId,
            String gatingPreset,
            String retrieverMode,
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
            Integer targetQueryCount,
            Double estimatedSecondsPerQuery,
            Long estimatedRemainingSeconds,
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
            String queryLanguage,
            String metadataStrategyProfile,
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
            String userQueryEn,
            String queryLanguage,
            JsonNode dialogContext,
            String targetMethod,
            JsonNode evaluationFocus
    ) {
    }

    public record RagTestRunRequest(
            UUID datasetId,
            UUID domainId,
            String evalQueryLanguage,
            String runName,
            List<String> methodCodes,
            Boolean syntheticFreeBaseline,
            List<UUID> generationBatchIds,
            UUID sourceGatingBatchId,
            Boolean stageCutoffEnabled,
            String stageCutoffLevel,
            Map<String, UUID> comparisonGatingBatchIds,
            Boolean officialRun,
            String officialComparisonType,
            Boolean gatingApplied,
            String gatingPreset,
            Boolean rewriteEnabled,
            Boolean selectiveRewrite,
            Boolean useSessionContext,
            String rewriteRetrievalStrategy,
            Boolean rewriteAnchorInjectionEnabled,
            Boolean multiSourceAnchorExpansionEnabled,
            String rewriteFailurePolicy,
            String llmModel,
            Integer topK,
            Double threshold,
            String retrievalBackend,
            Integer retrievalTopK,
            Integer rerankTopN,
            RetrieverConfigRequest retrieverConfig,
            String createdBy
    ) {
    }

    public record RuntimeOptionsResponse(
            List<String> llmModels,
            String defaultLlmModel,
            List<String> denseEmbeddingModels,
            String defaultDenseEmbeddingModel,
            List<String> retrievalBackends,
            String defaultRetrievalBackend,
            List<String> retrieverModes,
            List<String> rewriteFailurePolicies,
            List<RuntimeOption> llmProviderOptions,
            List<RuntimeOption> llmModelOptions,
            List<RuntimeOption> denseEmbeddingModelOptions,
            List<RuntimeOption> retrievalBackendOptions,
            List<RuntimeOption> retrieverModeOptions,
            List<RuntimeOption> rewriteFailurePolicyOptions,
            Map<String, RuntimeParameterRange> defaultParameterRanges,
            String defaultRetrieverMode,
            JsonNode retrieverModeDefaults
    ) {
    }

    public record ChunkEmbeddingMaterializationRequest(
            String embeddingModel,
            String createdBy
    ) {
    }

    public record ChunkEmbeddingMaterializationStatusResponse(
            String embeddingModel,
            String vectorStore,
            long totalChunkCount,
            long materializedChunkCount,
            long missingChunkCount,
            boolean ready,
            Instant latestUpdatedAt
    ) {
    }

    public record RuntimeOption(
            String code,
            String label,
            String provider,
            String status,
            String availability,
            String reason,
            Boolean defaultSelected
    ) {
    }

    public record RuntimeParameterRange(
            Double min,
            Double max,
            Double defaultValue
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
            Boolean stageCutoffEnabled,
            String stageCutoffLevel,
            Boolean rewriteEnabled,
            Boolean selectiveRewrite,
            Boolean useSessionContext,
            Boolean rewriteAnchorInjectionEnabled,
            Boolean multiSourceAnchorExpansionEnabled,
            Integer retrievalTopK,
            Double threshold,
            Integer totalStageCount,
            Integer completedStageCount,
            Double estimatedSecondsPerStage,
            Long estimatedRemainingSeconds,
            Instant startedAt,
            Instant finishedAt,
            JsonNode metricsJson
    ) {
        public RagTestRunRow withMetricsJson(JsonNode nextMetricsJson) {
            return new RagTestRunRow(
                    ragTestRunId,
                    runLabel,
                    status,
                    datasetId,
                    datasetName,
                    generationMethodCodes,
                    generationBatchIds,
                    gatingApplied,
                    gatingPreset,
                    stageCutoffEnabled,
                    stageCutoffLevel,
                    rewriteEnabled,
                    selectiveRewrite,
                    useSessionContext,
                    rewriteAnchorInjectionEnabled,
                    multiSourceAnchorExpansionEnabled,
                    retrievalTopK,
                    threshold,
                    totalStageCount,
                    completedStageCount,
                    estimatedSecondsPerStage,
                    estimatedRemainingSeconds,
                    startedAt,
                    finishedAt,
                    nextMetricsJson
            );
        }
    }

    public record RagTestRunDetail(
            RagTestRunRow run,
            JsonNode summary,
            JsonNode anchorSummary,
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
            Boolean hitTarget,
            List<RagRewriteAnchorEvalRow> anchorEvaluations
    ) {
        public RagTestResultDetailRow withAnchorEvaluations(List<RagRewriteAnchorEvalRow> nextAnchorEvaluations) {
            return new RagTestResultDetailRow(
                    detailId,
                    ragTestRunId,
                    sampleId,
                    queryCategory,
                    rawQuery,
                    rewriteQuery,
                    rewriteApplied,
                    memoryCandidates,
                    rewriteCandidates,
                    retrievedChunks,
                    metricContribution,
                    hitTarget,
                    nextAnchorEvaluations
            );
        }
    }

    public record RagRewriteAnchorEvalRow(
            UUID id,
            UUID ragTestRunId,
            UUID ragTestResultDetailId,
            String sampleId,
            UUID datasetItemId,
            String mode,
            String originalQuery,
            String finalRewriteQuery,
            Boolean rewriteApplied,
            Integer sourceMemoryIndex,
            String anchorText,
            String normalizedAnchorText,
            String canonicalAnchorText,
            String anchorSource,
            JsonNode sourceTags,
            Boolean appearsInRawQuery,
            Boolean appearsInFinalRewrite,
            Boolean appearsInExpectedChunk,
            Boolean appearsInExpectedDoc,
            Boolean appearsInRetrievedChunk,
            Boolean groundedByExpectedChunk,
            Boolean groundedByExpectedDoc,
            Boolean groundedByRetrievedChunk,
            Boolean groundedByMemory,
            Boolean groundedByGlossary,
            Double groundingScore,
            Double intentRelevanceScore,
            Double driftRiskScore,
            Double overallAnchorScore,
            String label,
            String evidenceSummary,
            JsonNode expectedChunkIds,
            JsonNode expectedDocIds,
            JsonNode retrievedChunkIds,
            UUID sourceMemoryEntryId,
            String sourceMemoryQueryId,
            Instant createdAt
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
