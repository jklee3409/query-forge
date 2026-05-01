CREATE TABLE IF NOT EXISTS anchor_eval_run (
    run_id UUID PRIMARY KEY,
    run_name TEXT NOT NULL,
    status TEXT NOT NULL,
    product_name TEXT,
    source_id TEXT,
    sample_size INT NOT NULL,
    candidate_limit INT NOT NULL,
    created_by TEXT NOT NULL,
    summary_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS anchor_eval_sample (
    sample_id UUID PRIMARY KEY,
    run_id UUID NOT NULL REFERENCES anchor_eval_run(run_id) ON DELETE CASCADE,
    document_id TEXT NOT NULL,
    chunk_id TEXT NOT NULL,
    chunk_text TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS anchor_eval_candidate (
    candidate_id UUID PRIMARY KEY,
    sample_id UUID NOT NULL REFERENCES anchor_eval_sample(sample_id) ON DELETE CASCADE,
    term_id UUID,
    canonical_form TEXT NOT NULL,
    term_type TEXT,
    score DOUBLE PRECISION NOT NULL DEFAULT 0,
    rank_index INT NOT NULL,
    is_selected BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS anchor_eval_label (
    label_id UUID PRIMARY KEY,
    run_id UUID NOT NULL REFERENCES anchor_eval_run(run_id) ON DELETE CASCADE,
    candidate_id UUID NOT NULL REFERENCES anchor_eval_candidate(candidate_id) ON DELETE CASCADE,
    label_value TEXT NOT NULL,
    confidence DOUBLE PRECISION,
    note TEXT,
    labeled_by TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE(run_id, candidate_id)
);

CREATE INDEX IF NOT EXISTS idx_anchor_eval_sample_run_id ON anchor_eval_sample(run_id);
CREATE INDEX IF NOT EXISTS idx_anchor_eval_candidate_sample_id ON anchor_eval_candidate(sample_id);
CREATE INDEX IF NOT EXISTS idx_anchor_eval_label_run_id ON anchor_eval_label(run_id);
