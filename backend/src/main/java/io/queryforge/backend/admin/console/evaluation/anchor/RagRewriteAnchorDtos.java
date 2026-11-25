package io.queryforge.backend.admin.console.evaluation.anchor;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.UUID;

public final class RagRewriteAnchorDtos {

    private RagRewriteAnchorDtos() {
    }

    public record RagRewriteAnchorEvalRow(
            UUID id,
            UUID ragTestRunId,
            UUID ragTestResultDetailId,
            String sampleId,
            UUID datasetItemId,
            String mode,
            String originalQuery,
            String finalRewriteQuery,
            Boolean rewriteApplied,
            Integer sourceMemoryIndex,
            String anchorText,
            String normalizedAnchorText,
            String canonicalAnchorText,
            String anchorSource,
            JsonNode sourceTags,
            Boolean appearsInRawQuery,
            Boolean appearsInFinalRewrite,
            Boolean appearsInExpectedChunk,
            Boolean appearsInExpectedDoc,
            Boolean appearsInRetrievedChunk,
            Boolean groundedByExpectedChunk,
            Boolean groundedByExpectedDoc,
            Boolean groundedByRetrievedChunk,
            Boolean groundedByMemory,
            Boolean groundedByGlossary,
            Double groundingScore,
            Double intentRelevanceScore,
            Double driftRiskScore,
            Double overallAnchorScore,
            String label,
            String evidenceSummary,
            JsonNode expectedChunkIds,
            JsonNode expectedDocIds,
            JsonNode retrievedChunkIds,
            UUID sourceMemoryEntryId,
            String sourceMemoryQueryId,
            Instant createdAt
    ) {
    }

    public record AnchorEvaluationCase(
            UUID detailId,
            UUID ragTestRunId,
            String sampleId,
            String rawQuery,
            String rewriteQuery,
            Boolean rewriteApplied,
            JsonNode memoryCandidates,
            JsonNode metricContribution
    ) {
    }
}
