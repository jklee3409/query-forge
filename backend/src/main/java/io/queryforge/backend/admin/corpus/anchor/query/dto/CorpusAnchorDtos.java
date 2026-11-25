package io.queryforge.backend.admin.corpus.anchor.query.dto;

import java.time.Instant;
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

}
