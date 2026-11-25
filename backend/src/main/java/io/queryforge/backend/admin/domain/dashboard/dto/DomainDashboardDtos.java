package io.queryforge.backend.admin.domain.dashboard.dto;

import java.time.Instant;
import java.util.UUID;

public final class DomainDashboardDtos {

    private DomainDashboardDtos() {
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
