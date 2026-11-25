package io.queryforge.backend.admin.domain.source.dto;

import java.time.Instant;

public final class DomainSourceDtos {

    private DomainSourceDtos() {
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

    public record DomainSourceAttachRequest(
            String sourceId,
            String sourceRole,
            Boolean active
    ) {
    }
}
