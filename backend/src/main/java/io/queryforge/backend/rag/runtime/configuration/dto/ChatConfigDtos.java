package io.queryforge.backend.rag.runtime.configuration.dto;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class ChatConfigDtos {

    private ChatConfigDtos() {
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
            boolean routerEnabled,
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
            Boolean routerEnabled,
            JsonNode metadata,
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
}
