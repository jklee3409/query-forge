package io.queryforge.backend.admin.corpus.anchor.extraction.dto;

import lombok.Builder;

import java.util.List;

public final class AnchorExtractionDtos {

    private AnchorExtractionDtos() {
    }

    @Builder
    public record AnchorExtractRequest(
            List<String> documentIds,
            List<String> chunkIds
    ) {
    }

    public record AnchorExtractResponse(
            int targetChunkCount,
            int deletedEvidenceCount,
            int insertedEvidenceCount,
            int updatedTermCount,
            int deactivatedTermCount,
            int remappedSyntheticQueryCount,
            int remappedLinkCount
    ) {
    }

    public record AnchorChunkRow(
            String chunkId,
            String documentId,
            String chunkText,
            String productName
    ) {
    }

    public record AnchorCandidate(
            String documentId,
            String chunkId,
            String termType,
            String canonicalForm,
            String normalizedForm,
            String matchedText
    ) {
    }

    public record TermRefreshSummary(
            int updatedTermCount,
            int deactivatedTermCount
    ) {
    }

    public record AnchorRemapSummary(
            int remappedSyntheticQueryCount,
            int remappedLinkCount
    ) {
    }
}
