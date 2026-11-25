package io.queryforge.backend.admin.corpus.anchor;

import lombok.Builder;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class CorpusAnchorDtos {

    private CorpusAnchorDtos() {
    }

    public record AnchorSummary(
            UUID termId,
            String canonicalForm,
            String normalizedForm,
            String termType,
            boolean keepInEnglish,
            double sourceConfidence,
            int evidenceCount,
            int scopedEvidenceCount,
            String firstSeenDocumentId,
            String firstSeenChunkId,
            Instant updatedAt
    ) {
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
}
