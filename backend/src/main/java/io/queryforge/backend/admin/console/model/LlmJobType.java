package io.queryforge.backend.admin.console.model;

import java.util.List;

public enum LlmJobType {
    GENERATE_EN_SUMMARY,
    TRANSLATE_CHUNK_TO_KO,
    GENERATE_KO_SUMMARY,
    GENERATE_SYNTHETIC_QUERY,
    RUN_LLM_SELF_EVAL,
    GENERATE_REWRITE_CANDIDATES,
    RUN_RAG_TEST,
    MATERIALIZE_CHUNK_EMBEDDINGS;

    public static List<String> dbValues() {
        return List.of(
                GENERATE_EN_SUMMARY.name(),
                TRANSLATE_CHUNK_TO_KO.name(),
                GENERATE_KO_SUMMARY.name(),
                GENERATE_SYNTHETIC_QUERY.name(),
                RUN_LLM_SELF_EVAL.name(),
                GENERATE_REWRITE_CANDIDATES.name(),
                RUN_RAG_TEST.name(),
                MATERIALIZE_CHUNK_EMBEDDINGS.name()
        );
    }
}
