package io.queryforge.backend.admin.domain.model;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class DomainAdminDtos {

    private DomainAdminDtos() {
    }

    public record DomainSummary(
            UUID domainId,
            String domainKey,
            String displayName,
            String description,
            String primaryLanguage,
            String sourceLanguage,
            String status,
            JsonNode metadata,
            long sourceCount,
            long activeDocumentCount,
            long activeChunkCount,
            long generationBatchCount,
            long evalDatasetCount,
            long ragTestRunCount,
            Instant createdAt,
            Instant updatedAt
    ) {
    }

    public record DomainDetail(
            DomainSummary domain,
            List<DomainSource> sources,
            List<DomainMethodPolicy> methodPolicies
    ) {
    }

    public record DomainSource(
            String sourceId,
            String sourceType,
            String productName,
            String sourceName,
            boolean enabled,
            String sourceRole,
            boolean active,
            long activeDocumentCount,
            long activeChunkCount,
            Instant createdAt
    ) {
    }

    public record DomainMethodPolicy(
            String methodCode,
            String methodName,
            boolean methodActive,
            boolean enabled,
            String defaultQueryLanguage,
            JsonNode metadata,
            Instant createdAt
    ) {
    }

    public record DomainCreateRequest(
            String domainKey,
            String displayName,
            String description,
            String primaryLanguage,
            String sourceLanguage,
            JsonNode metadata,
            String createdBy
    ) {
    }

    public record DomainUpdateRequest(
            String displayName,
            String description,
            String primaryLanguage,
            String sourceLanguage,
            String status,
            JsonNode metadata
    ) {
    }

    public record DomainSourceAttachRequest(
            String sourceId,
            String sourceRole,
            Boolean active
    ) {
    }

    public record DomainDashboardSummary(
            UUID domainId,
            String domainKey,
            String displayName,
            long sourceCount,
            long activeDocumentCount,
            long activeChunkCount,
            long glossaryTermCount,
            long syntheticRawCount,
            long gatedQueryCount,
            long memoryEntryCount,
            long evalDatasetCount,
            long ragTestRunCount,
            String latestRagStatus,
            Instant latestRagCreatedAt
    ) {
    }
}
