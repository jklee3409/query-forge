package io.queryforge.backend.rag.retrieval.execution;

public record RagLlmCallCount(
        int rewriteCalls,
        int plannerCalls,
        int answerCalls,
        int totalCalls
) {
    public static RagLlmCallCount zero() {
        return new RagLlmCallCount(0, 0, 0, 0);
    }
}
