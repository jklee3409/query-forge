package io.queryforge.backend.rag.runtime.snapshot.dto;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;

public final class GatingSnapshotDtos {

    private GatingSnapshotDtos() {
    }

    public static List<UUID> withLegacyId(List<UUID> ids, UUID legacyId) {
        if (ids != null && !ids.isEmpty()) {
            return ids;
        }
        return legacyId == null ? List.of() : List.of(legacyId);
    }

    public static List<UUID> distinctIds(List<UUID> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<UUID> distinct = new LinkedHashSet<>(ids);
        distinct.remove(null);
        return List.copyOf(distinct);
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
