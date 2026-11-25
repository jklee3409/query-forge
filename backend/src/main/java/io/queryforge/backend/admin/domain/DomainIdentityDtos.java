package io.queryforge.backend.admin.domain;

import com.fasterxml.jackson.databind.JsonNode;
import io.queryforge.backend.admin.domain.source.DomainSourceDtos.DomainMethodPolicy;
import io.queryforge.backend.admin.domain.source.DomainSourceDtos.DomainSource;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class DomainIdentityDtos {

    private DomainIdentityDtos() {
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
}
