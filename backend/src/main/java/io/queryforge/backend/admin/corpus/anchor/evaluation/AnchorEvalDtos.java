package io.queryforge.backend.admin.corpus.anchor.evaluation;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Builder;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class AnchorEvalDtos {

    private AnchorEvalDtos() {
    }

    @Builder
    public record AnchorEvalRunCreateRequest(
            String runName,
            String productName,
            String sourceId,
            List<String> documentIds,
            List<String> chunkIds,
            Integer sampleSize,
            Integer candidateLimit,
            String createdBy
    ) {
    }

    public record AnchorEvalRunSummary(
            UUID runId,
            String runName,
            String status,
            String productName,
            String sourceId,
            int sampleSize,
            int candidateLimit,
            String createdBy,
            JsonNode summaryJson,
            Instant createdAt,
            Instant updatedAt
    ) {
    }

    public record AnchorEvalCandidateDto(
            UUID candidateId,
            UUID termId,
            String canonicalForm,
            String termType,
            double score,
            int rankIndex,
            String labelValue,
            Double labelConfidence,
            String labelNote
    ) {
    }

    public record AnchorEvalSampleDto(
            UUID sampleId,
            String documentId,
            String chunkId,
            String chunkText,
            List<AnchorEvalCandidateDto> candidates
    ) {
    }

    public record AnchorEvalRunDetail(
            AnchorEvalRunSummary run,
            List<AnchorEvalSampleDto> samples
    ) {
    }

    @Builder
    public record AnchorEvalLabelRequest(
            UUID candidateId,
            String labelValue,
            Double confidence,
            String note,
            String labeledBy
    ) {
    }
}
