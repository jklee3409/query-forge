package io.queryforge.backend.admin.pipeline.artifact.dto;

import java.util.List;

public final class DocumentArtifactDtos {

    private DocumentArtifactDtos() {
    }

    public record PersistResult(
            List<String> documentIds,
            int discoveredDocumentCount,
            int persistedDocumentCount
    ) {
        public static PersistResult empty() {
            return new PersistResult(List.of(), 0, 0);
        }
    }
}
