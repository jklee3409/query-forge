package io.queryforge.backend.rag.model;

public enum QueryStrategy {
    RAW_ONLY,
    SYNTHETIC_SELECTIVE_REWRITE,
    ANCHOR_AWARE_REWRITE,
    AGENTIC_MULTI_QUERY
}
