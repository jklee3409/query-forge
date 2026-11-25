package io.queryforge.backend.rag.runtime.readiness;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class ChatReadinessDtos {

    private ChatReadinessDtos() {
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
