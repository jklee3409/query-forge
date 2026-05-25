CREATE TABLE IF NOT EXISTS rag_rewrite_anchor_eval (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    rag_test_run_id UUID NOT NULL REFERENCES rag_test_run (rag_test_run_id) ON DELETE CASCADE,
    rag_test_result_detail_id UUID NOT NULL REFERENCES rag_test_result_detail (detail_id) ON DELETE CASCADE,
    sample_id TEXT NOT NULL REFERENCES eval_samples (sample_id) ON DELETE CASCADE,
    dataset_item_id UUID REFERENCES eval_dataset_item (dataset_item_id) ON DELETE SET NULL,
    mode TEXT,
    original_query TEXT NOT NULL,
    final_rewrite_query TEXT NOT NULL,
    rewrite_applied BOOLEAN NOT NULL DEFAULT TRUE,
    source_memory_index INTEGER,
    anchor_text TEXT NOT NULL,
    normalized_anchor_text TEXT NOT NULL,
    canonical_anchor_text TEXT,
    anchor_source TEXT NOT NULL,
    source_tags JSONB NOT NULL DEFAULT '[]'::jsonb,
    appears_in_raw_query BOOLEAN NOT NULL DEFAULT FALSE,
    appears_in_final_rewrite BOOLEAN NOT NULL DEFAULT FALSE,
    appears_in_expected_chunk BOOLEAN NOT NULL DEFAULT FALSE,
    appears_in_expected_doc BOOLEAN NOT NULL DEFAULT FALSE,
    appears_in_retrieved_chunk BOOLEAN NOT NULL DEFAULT FALSE,
    grounded_by_expected_chunk BOOLEAN NOT NULL DEFAULT FALSE,
    grounded_by_expected_doc BOOLEAN NOT NULL DEFAULT FALSE,
    grounded_by_retrieved_chunk BOOLEAN NOT NULL DEFAULT FALSE,
    grounded_by_memory BOOLEAN NOT NULL DEFAULT FALSE,
    grounded_by_glossary BOOLEAN NOT NULL DEFAULT FALSE,
    grounding_score DOUBLE PRECISION,
    intent_relevance_score DOUBLE PRECISION,
    drift_risk_score DOUBLE PRECISION,
    overall_anchor_score DOUBLE PRECISION,
    label TEXT NOT NULL DEFAULT 'unknown',
    evidence_summary TEXT,
    expected_chunk_ids JSONB NOT NULL DEFAULT '[]'::jsonb,
    expected_doc_ids JSONB NOT NULL DEFAULT '[]'::jsonb,
    retrieved_chunk_ids JSONB NOT NULL DEFAULT '[]'::jsonb,
    source_memory_entry_id UUID REFERENCES memory_entries (memory_id) ON DELETE SET NULL,
    source_memory_query_id TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_rag_rewrite_anchor_eval_source CHECK (
        anchor_source IN (
            'injected_candidate',
            'added_by_rewrite',
            'preserved_raw',
            'canonical',
            'memory',
            'glossary',
            'multi_source'
        )
    ),
    CONSTRAINT chk_rag_rewrite_anchor_eval_label CHECK (
        label IN ('useful', 'neutral', 'risky', 'unsupported', 'unknown')
    ),
    CONSTRAINT uq_rag_rewrite_anchor_eval_detail_anchor UNIQUE (
        rag_test_result_detail_id,
        normalized_anchor_text
    )
);

CREATE INDEX IF NOT EXISTS idx_rag_rewrite_anchor_eval_run
    ON rag_rewrite_anchor_eval (rag_test_run_id);

CREATE INDEX IF NOT EXISTS idx_rag_rewrite_anchor_eval_detail
    ON rag_rewrite_anchor_eval (rag_test_result_detail_id);

CREATE INDEX IF NOT EXISTS idx_rag_rewrite_anchor_eval_sample
    ON rag_rewrite_anchor_eval (sample_id);

CREATE INDEX IF NOT EXISTS idx_rag_rewrite_anchor_eval_label
    ON rag_rewrite_anchor_eval (label);

CREATE INDEX IF NOT EXISTS idx_rag_rewrite_anchor_eval_source
    ON rag_rewrite_anchor_eval (anchor_source);

CREATE INDEX IF NOT EXISTS idx_rag_rewrite_anchor_eval_normalized
    ON rag_rewrite_anchor_eval (normalized_anchor_text);

CREATE INDEX IF NOT EXISTS idx_rag_rewrite_anchor_eval_run_score
    ON rag_rewrite_anchor_eval (rag_test_run_id, overall_anchor_score DESC);
