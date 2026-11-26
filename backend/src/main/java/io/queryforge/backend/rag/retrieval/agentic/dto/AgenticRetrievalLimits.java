package io.queryforge.backend.rag.retrieval.agentic.dto;

public record AgenticRetrievalLimits(int maxSubqueries, int rrfK) {

    public static final int DEFAULT_CHAT_MAX_SUBQUERIES = 3;
    public static final int DEFAULT_EVAL_MAX_SUBQUERIES = 4;
    public static final int DEFAULT_RRF_K = 60;
    private static final int MAX_SUBQUERIES = 4;
    private static final int MAX_REGISTERED_RRF_K = 500;

    public AgenticRetrievalLimits {
        maxSubqueries = Math.clamp(maxSubqueries, 1, MAX_SUBQUERIES);
        rrfK = Math.max(1, rrfK);
    }

    public static AgenticRetrievalLimits forRegistration(int maxSubqueries, int rrfK) {
        return new AgenticRetrievalLimits(maxSubqueries, Math.min(rrfK, MAX_REGISTERED_RRF_K));
    }
}
