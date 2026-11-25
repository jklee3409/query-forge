package io.queryforge.backend.admin.console.gating;

import com.fasterxml.jackson.databind.JsonNode;
import io.queryforge.backend.admin.console.runtime.AdminRuntimeOptionsDtos.RetrieverConfigRequest;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class GatingDtos {

    private GatingDtos() {
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
}
