package io.queryforge.backend.admin.corpus.anchor.extraction;

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
}
