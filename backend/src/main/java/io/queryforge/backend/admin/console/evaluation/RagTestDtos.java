package io.queryforge.backend.admin.console.evaluation;

import com.fasterxml.jackson.databind.JsonNode;
import io.queryforge.backend.admin.console.runtime.AdminRuntimeOptionsDtos.RetrieverConfigRequest;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class RagTestDtos {

    private RagTestDtos() {
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
            Boolean routerEnabled,
            String forcedRetrievalMode,
            Boolean agenticMultiQueryEnabled,
            String rewriteRetrievalStrategy,
            Boolean rewriteAnchorInjectionEnabled,
            Boolean multiSourceAnchorExpansionEnabled,
            String rewriteQueryProfile,
            String rewriteFailurePolicy,
            String llmModel,
            String rewriteLlmModel,
            Integer topK,
            Double threshold,
            String retrievalBackend,
            Integer retrievalTopK,
            Integer rerankTopN,
            Integer memoryTopN,
            Integer rewriteCandidateCount,
            Integer agenticMaxSubqueries,
            Integer agenticRrfK,
            RetrieverConfigRequest retrieverConfig,
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
}
