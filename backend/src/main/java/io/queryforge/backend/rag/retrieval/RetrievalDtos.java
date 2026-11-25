package io.queryforge.backend.rag.retrieval;

public final class RetrievalDtos {

    private RetrievalDtos() {
    }

    public record ScoredDocumentDto(
            String documentId,
            String chunkId,
            String chunkTextPreview,
            double score
    ) {
    }
}
