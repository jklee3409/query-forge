package io.queryforge.backend.admin.console.dashboard.dto;

public final class ConsoleDashboardDtos {

    private ConsoleDashboardDtos() {
    }

    public record AdminDashboardStats(
            long sourceCount,
            long activeDocumentCount,
            long chunkCount,
            long glossaryCount,
            long syntheticQueryCount,
            long gatedAcceptedCount,
            long memoryCount,
            long ragRunCount
    ) {
    }
}
