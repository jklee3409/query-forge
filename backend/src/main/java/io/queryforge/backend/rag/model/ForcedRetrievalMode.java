package io.queryforge.backend.rag.model;

public enum ForcedRetrievalMode {
    RAW_ONLY,
    SELECTIVE_REWRITE,
    ANCHOR_AWARE_REWRITE,
    AGENTIC_MULTI_QUERY,
    STRATEGY_ROUTER
}
