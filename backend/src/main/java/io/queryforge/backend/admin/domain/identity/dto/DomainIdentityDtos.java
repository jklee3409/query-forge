package io.queryforge.backend.admin.domain.identity.dto;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
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
