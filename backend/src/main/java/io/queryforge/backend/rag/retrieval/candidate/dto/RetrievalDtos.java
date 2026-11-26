package io.queryforge.backend.rag.retrieval.candidate.dto;

import java.util.Locale;

public final class RetrievalDtos {

    public static final String DEFAULT_DENSE_EMBEDDING_MODEL = "intfloat/multilingual-e5-small";
    public static final double DEFAULT_DENSE_WEIGHT = 0.60d;
    public static final double DEFAULT_BM25_WEIGHT = 0.32d;
    public static final double DEFAULT_TECHNICAL_WEIGHT = 0.08d;

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
        public static int validateCandidatePoolK(int value, String fieldName) {
            if (value < 1 || value > 500) {
                throw new IllegalArgumentException(fieldName + " must be between 1 and 500");
            }
            return value;
        }

        public static double validateFusionWeight(double value, String fieldName) {
            if (value < 0.0d || value > 1.0d) {
                throw new IllegalArgumentException(fieldName + " must be between 0.0 and 1.0");
            }
            return value;
        }

        public static String normalizeBackend(String value) {
            return normalizeOption(value, "local");
        }

        public static String normalizeMode(String value) {
            return normalizeOption(value, "hybrid");
        }

        public static String normalizeDenseModel(String value) {
            return value == null || value.isBlank() ? DEFAULT_DENSE_EMBEDDING_MODEL : value.trim();
        }

        private static String normalizeOption(String value, String fallback) {
            return value == null || value.isBlank()
                    ? fallback : value.trim().toLowerCase(Locale.ROOT).replace("-", "_");
        }

        public String retrieverName() {
            if ("db_ann".equals(retrievalBackend)) {
                return "db-ann:" + retrieverMode + ":" + denseEmbeddingModel;
            }
            return "local:" + retrieverMode + ":hash-embedding-v1";
        }
    }
}
