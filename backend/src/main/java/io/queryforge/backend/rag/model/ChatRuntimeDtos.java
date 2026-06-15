package io.queryforge.backend.rag.model;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class ChatRuntimeDtos {

    private ChatRuntimeDtos() {
    }

    public record ChatDomainOption(
            UUID domainId,
            String domainKey,
            String displayName,
            String sourceLanguage,
            boolean enabled
    ) {
    }

    public record ChatRuntimeConfigResponse(
            UUID domainId,
            String domainKey,
            String displayName,
            String sourceLanguage,
            boolean enabled,
            String mode,
            List<String> generationStrategies,
            String gatingPreset,
            UUID sourceGatingBatchId,
            UUID sourceGatingRunId,
            List<UUID> sourceGatingBatchIds,
            List<UUID> sourceGatingRunIds,
            String rewriteQueryProfile,
            boolean rewriteAnchorInjectionEnabled,
            boolean useSessionContext,
            String retrievalBackend,
            String denseEmbeddingModel,
            String retrieverMode,
            int retrieverCandidatePoolK,
            double retrieverDenseWeight,
            double retrieverBm25Weight,
            double retrieverTechnicalWeight,
            int retrievalTopK,
            int rerankTopN,
            int memoryTopN,
            int rewriteCandidateCount,
            double rewriteThreshold,
            String rewriteFailurePolicy,
            JsonNode metadata,
            Instant updatedAt,
            boolean readyForRewrite,
            String readinessMessage
    ) {
    }

    public record ChatRuntimeConfigRequest(
            UUID domainId,
            Boolean enabled,
            String mode,
            List<String> generationStrategies,
            String gatingPreset,
            UUID sourceGatingBatchId,
            List<UUID> sourceGatingBatchIds,
            String rewriteQueryProfile,
            Boolean rewriteAnchorInjectionEnabled,
            Boolean useSessionContext,
            String retrievalBackend,
            String denseEmbeddingModel,
            String retrieverMode,
            Integer retrieverCandidatePoolK,
            Double retrieverDenseWeight,
            Double retrieverBm25Weight,
            Double retrieverTechnicalWeight,
            Integer retrievalTopK,
            Integer rerankTopN,
            Integer memoryTopN,
            Integer rewriteCandidateCount,
            Double rewriteThreshold,
            String rewriteFailurePolicy,
            JsonNode metadata,
            String updatedBy
    ) {
    }

    public record ApplyChatConfigFromRagRunRequest(
            UUID ragTestRunId,
            String updatedBy
    ) {
    }

    public record ChatRuntimeConfigProvenanceRow(
            UUID provenanceId,
            UUID domainId,
            String domainKey,
            String displayName,
            String changeSource,
            UUID sourceRagTestRunId,
            String sourceRagTestRunLabel,
            JsonNode sourceConfig,
            JsonNode previousConfig,
            JsonNode appliedConfig,
            JsonNode diff,
            String updatedBy,
            Instant createdAt
    ) {
    }

    public record ChatDomainReadinessResponse(
            UUID domainId,
            String domainKey,
            String displayName,
            String sourceLanguage,
            boolean activeConfigPresent,
            boolean configEnabled,
            String mode,
            boolean rewriteBackedMode,
            List<String> generationStrategies,
            String gatingPreset,
            SnapshotReadiness snapshot,
            ChunkEmbeddingReadiness chunkEmbeddings,
            long memoryCount,
            long acceptedGatedQueryCount,
            PromptBindingReadiness promptBinding,
            RetrievalReadiness retrieval,
            boolean readyForRewrite,
            List<String> blockingReasons,
            Instant checkedAt
    ) {
    }

    public record SnapshotReadiness(
            UUID selectedSourceGatingBatchId,
            UUID configSourceGatingRunId,
            List<UUID> selectedSourceGatingBatchIds,
            List<UUID> configSourceGatingRunIds,
            int selectedSnapshotCount,
            UUID snapshotSourceGatingRunId,
            boolean selectedSnapshotPresent,
            String status,
            UUID snapshotDomainId,
            String gatingPreset,
            String methodCode,
            boolean sourceGatingRunPresent,
            boolean sourceGatingRunMatchesConfig,
            boolean domainMismatch,
            boolean gatingPresetMismatch,
            boolean generationStrategyMismatch
    ) {
    }

    public record ChunkEmbeddingReadiness(
            boolean required,
            String embeddingModel,
            long domainChunkCount,
            long materializedChunkCount,
            long missingChunkCount,
            boolean ready,
            Instant latestUpdatedAt
    ) {
    }

    public record PromptBindingReadiness(
            String bindingKey,
            boolean active,
            UUID activePromptAssetId,
            String activePromptName,
            String activePromptVersion,
            String activeContentHash
    ) {
    }

    public record RetrievalReadiness(
            String retrievalBackend,
            String denseEmbeddingModel,
            String retrieverMode,
            int candidatePoolK,
            double denseWeight,
            double bm25Weight,
            double technicalWeight
    ) {
    }
}
