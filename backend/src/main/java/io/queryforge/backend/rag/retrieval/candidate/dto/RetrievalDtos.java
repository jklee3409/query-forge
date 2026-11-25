package io.queryforge.backend.rag.retrieval.candidate.dto;

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

    public record RetrievalRuntime(
            String retrievalBackend,
            String denseEmbeddingModel,
            String retrieverMode,
            int candidatePoolK,
            double denseWeight,
            double bm25Weight,
            double technicalWeight
    ) {
        public String retrieverName() {
            if ("db_ann".equals(retrievalBackend)) {
                return "db-ann:" + retrieverMode + ":" + denseEmbeddingModel;
            }
            return "local:" + retrieverMode + ":hash-embedding-v1";
        }
    }
}
