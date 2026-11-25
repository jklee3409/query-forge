package io.queryforge.backend.admin.corpus.anchor.normalization.dto;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Builder;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class AnchorNormalizationDtos {

    private AnchorNormalizationDtos() {
    }

    @Builder
    public record AnchorNormalizationRunCreateRequest(
            String runName,
            String documentId,
            String chunkId,
            String keyword,
            Boolean activeOnly,
            Integer limit,
            String createdBy
    ) {
    }

    @Builder
    public record AnchorNormalizationReviewRequest(
            String reviewedBy,
            String note
    ) {
    }

    @Builder
    public record AnchorNormalizationCandidateReviewRequest(
            String decision,
            String reviewedBy,
            String note
    ) {
    }

    @Builder
    public record AnchorNormalizationCandidateDecision(
            UUID candidateId,
            String decision,
            String note
    ) {
    }

    @Builder
    public record AnchorNormalizationCandidateReviewBatchRequest(
            List<AnchorNormalizationCandidateDecision> decisions,
            String reviewedBy,
            String note
    ) {
    }

    public record AnchorNormalizationRunSummary(
            UUID runId,
            String runName,
            String status,
            int candidateCount,
            int changedCount,
            int unchangedCount,
            int conflictCount,
            int invalidCount,
            int appliedUpdateCount,
            int reviewApprovedCount,
            int reviewSkippedCount,
            int reviewPendingCount,
            String createdBy,
            String reviewedBy,
            JsonNode sourceScopeJson,
            JsonNode summaryJson,
            Instant createdAt,
            Instant updatedAt,
            Instant reviewedAt,
            Instant appliedAt
    ) {
    }

    public record AnchorNormalizationCandidateDto(
            UUID candidateId,
            UUID termId,
            String termType,
            String currentCanonicalForm,
            String currentNormalizedForm,
            String proposedCanonicalForm,
            String proposedNormalizedForm,
            String resolutionStatus,
            boolean changeRequired,
            UUID conflictTermId,
            String reviewDecision,
            String reviewedBy,
            String reviewNote,
            JsonNode metadataJson,
            Instant reviewedAt,
            Instant appliedAt
    ) {
    }

    public record AnchorNormalizationRunDetail(
            AnchorNormalizationRunSummary run,
            List<AnchorNormalizationCandidateDto> candidates
    ) {
    }

    public record TargetAnchor(
            UUID termId,
            String canonicalForm,
            String normalizedForm,
            String termType
    ) {
    }

    public record CandidateDraft(
            UUID candidateId,
            UUID runId,
            UUID termId,
            String termType,
            String currentCanonicalForm,
            String currentNormalizedForm,
            String proposedCanonicalForm,
            String proposedNormalizedForm,
            String resolutionStatus,
            boolean changeRequired,
            UUID conflictTermId,
            JsonNode metadataJson
    ) {
    }
}
