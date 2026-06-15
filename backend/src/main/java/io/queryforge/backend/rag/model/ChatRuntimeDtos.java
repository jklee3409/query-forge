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
            String rewriteQueryProfile,
            boolean rewriteAnchorInjectionEnabled,
            boolean useSessionContext,
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
            String rewriteQueryProfile,
            Boolean rewriteAnchorInjectionEnabled,
            Boolean useSessionContext,
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
}
