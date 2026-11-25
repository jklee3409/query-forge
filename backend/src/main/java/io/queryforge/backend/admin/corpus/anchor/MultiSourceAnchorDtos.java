package io.queryforge.backend.admin.corpus.anchor;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Builder;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class MultiSourceAnchorDtos {

    private MultiSourceAnchorDtos() {
    }

    @Builder
    public record MultiSourceAnchorBuildRequest(
            String runName,
            String relationVersion,
            List<String> relationTypes,
            Double minRelationScore,
            Integer maxRelationsPerAnchor,
            String createdBy
    ) {
    }

    public record MultiSourceAnchorBuildRunSummary(
            UUID runId,
            String runName,
            String relationVersion,
            String mappingVersion,
            String normalizationVersion,
            String canonicalAnchorRuntimeSchemaVersion,
            String status,
            JsonNode relationTypeAllowlist,
            double minRelationScore,
            Integer maxRelationsPerAnchor,
            int candidateAnchorCount,
            int relationCount,
            int evidenceCount,
            JsonNode summaryJson,
            String createdBy,
            String errorMessage,
            Instant createdAt,
            Instant updatedAt,
            Instant finishedAt
    ) {
    }
}
