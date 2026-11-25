package io.queryforge.backend.admin.console.evaluation.dataset;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.UUID;

public final class EvalDatasetDtos {

    private EvalDatasetDtos() {
    }

    public record EvalDatasetRow(
            UUID datasetId,
            String datasetKey,
            String datasetName,
            String version,
            String queryLanguage,
            String metadataStrategyProfile,
            int totalItems,
            JsonNode categoryDistribution,
            JsonNode singleMultiDistribution,
            Instant createdAt
    ) {
    }

    public record EvalDatasetItemRow(
            UUID datasetId,
            String sampleId,
            String split,
            String queryCategory,
            String singleOrMultiChunk,
            String userQueryKo,
            String userQueryEn,
            String queryLanguage,
            JsonNode dialogContext,
            String targetMethod,
            JsonNode evaluationFocus
    ) {
    }
}
