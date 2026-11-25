package io.queryforge.backend.rag.runtime.snapshot.dto;

import java.util.UUID;

public final class GatingSnapshotDtos {

    private GatingSnapshotDtos() {
    }

    public record GatingSnapshot(
            UUID gatingBatchId,
            UUID sourceGatingRunId,
            UUID domainId,
            String gatingPreset,
            String methodCode,
            String status
    ) {
    }
}
