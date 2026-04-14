CREATE TABLE IF NOT EXISTS rag_eval_experiment_record (
    record_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    rag_test_run_id UUID NOT NULL UNIQUE REFERENCES rag_test_run (rag_test_run_id) ON DELETE CASCADE,
    snapshot_id TEXT NOT NULL,
    generation_strategy JSONB NOT NULL DEFAULT '[]'::jsonb,
    gating_config JSONB NOT NULL DEFAULT '{}'::jsonb,
    memory_size INTEGER NOT NULL DEFAULT 0,
    retrieval_config JSONB NOT NULL DEFAULT '{}'::jsonb,
    rewrite_config JSONB NOT NULL DEFAULT '{}'::jsonb,
    dataset_version TEXT,
    run_timestamp TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    metrics JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_rag_eval_experiment_record_run_timestamp
    ON rag_eval_experiment_record (run_timestamp DESC);
