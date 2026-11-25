package io.queryforge.backend.rag.runtime.configuration.model;

import java.util.List;

public record RagExecutionSettings(
        int retrievalTopK,
        int rerankTopN,
        int memoryTopN,
        int rewriteCandidateCount,
        double rewriteThreshold
) {
    public static final Parameter RETRIEVAL_TOP_K = new Parameter("retrieval_top_k", "retrievalTopK", 1, 100, 10);
    public static final Parameter RERANK_TOP_N = new Parameter("rerank_top_n", "rerankTopN", 1, 100, 5);
    public static final Parameter MEMORY_TOP_N = new Parameter("memory_top_n", "memoryTopN", 1, 50, 5);
    public static final Parameter REWRITE_CANDIDATE_COUNT =
            new Parameter("rewrite_candidate_count", "rewriteCandidateCount", 1, 2, 2);
    public static final Parameter REWRITE_THRESHOLD =
            new Parameter("rewrite_threshold", "rewriteThreshold", 0, 1, 0.05d);
    public static final List<Parameter> PARAMETERS = List.of(
            RETRIEVAL_TOP_K, RERANK_TOP_N, MEMORY_TOP_N, REWRITE_CANDIDATE_COUNT, REWRITE_THRESHOLD
    );

    public RagExecutionSettings {
        RETRIEVAL_TOP_K.validate(retrievalTopK);
        RERANK_TOP_N.validate(rerankTopN);
        MEMORY_TOP_N.validate(memoryTopN);
        REWRITE_CANDIDATE_COUNT.validate(rewriteCandidateCount);
        REWRITE_THRESHOLD.validate(rewriteThreshold);
    }

    public RagExecutionSettings withOverrides(Overrides overrides) {
        return new RagExecutionSettings(
                valueOrDefault(overrides.retrievalTopK(), retrievalTopK),
                valueOrDefault(overrides.rerankTopN(), rerankTopN),
                valueOrDefault(overrides.memoryTopN(), memoryTopN),
                valueOrDefault(overrides.rewriteCandidateCount(), rewriteCandidateCount),
                valueOrDefault(overrides.rewriteThreshold(), rewriteThreshold)
        );
    }

    private static <T> T valueOrDefault(T value, T fallback) {
        return value == null ? fallback : value;
    }

    public record Overrides(
            Integer retrievalTopK,
            Integer rerankTopN,
            Integer memoryTopN,
            Integer rewriteCandidateCount,
            Double rewriteThreshold
    ) {
    }

    public record Parameter(String key, String fieldName, double min, double max, double defaultValue) {
        public void validate(double value) {
            validate(value, min, max);
        }

        public void validate(double value, double lowerBound, double upperBound) {
            if (!Double.isFinite(value) || value < lowerBound || value > upperBound) {
                throw new IllegalArgumentException(fieldName + " must be between "
                        + formatBound(lowerBound) + " and " + formatBound(upperBound));
            }
        }

        private String formatBound(double bound) {
            return bound == Math.rint(bound) && defaultValue == Math.rint(defaultValue)
                    ? Long.toString((long) bound)
                    : Double.toString(bound);
        }
    }
}
