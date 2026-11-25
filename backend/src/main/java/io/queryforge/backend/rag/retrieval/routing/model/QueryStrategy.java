package io.queryforge.backend.rag.retrieval.routing.model;

public enum QueryStrategy {
    RAW_ONLY,
    SYNTHETIC_SELECTIVE_REWRITE,
    ANCHOR_AWARE_REWRITE,
    AGENTIC_MULTI_QUERY
}
