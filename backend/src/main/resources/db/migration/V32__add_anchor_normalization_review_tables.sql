CREATE TABLE IF NOT EXISTS anchor_normalization_run (
    run_id UUID PRIMARY KEY,
    run_name TEXT NOT NULL,
    status TEXT NOT NULL DEFAULT 'pending_review',
    source_scope_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    report_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    summary_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    anchor_mapping_version TEXT NOT NULL DEFAULT 'anchor-map-v1',
    anchor_normalization_version TEXT NOT NULL DEFAULT 'anchor-normalize-v1',
    canonical_anchor_runtime_schema_version TEXT NOT NULL DEFAULT 'canonical-anchor-runtime-v1',
    created_by TEXT NOT NULL DEFAULT 'admin-ui',
    reviewed_by TEXT,
    review_note TEXT,
    applied_update_count INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    reviewed_at TIMESTAMPTZ,
    applied_at TIMESTAMPTZ,
    CHECK (status IN ('pending_review', 'approved', 'rejected'))
);

CREATE TABLE IF NOT EXISTS anchor_normalization_candidate (
    candidate_id UUID PRIMARY KEY,
    run_id UUID NOT NULL REFERENCES anchor_normalization_run (run_id) ON DELETE CASCADE,
    term_id UUID NOT NULL REFERENCES corpus_glossary_terms (term_id) ON DELETE RESTRICT,
    term_type TEXT NOT NULL,
    current_canonical_form TEXT NOT NULL,
    current_normalized_form TEXT NOT NULL,
    proposed_canonical_form TEXT NOT NULL,
    proposed_normalized_form TEXT NOT NULL,
    resolution_status TEXT NOT NULL,
    change_required BOOLEAN NOT NULL DEFAULT FALSE,
    conflict_term_id UUID REFERENCES corpus_glossary_terms (term_id) ON DELETE RESTRICT,
    metadata_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    applied_at TIMESTAMPTZ,
    CHECK (resolution_status IN ('unchanged', 'would_update', 'conflict', 'invalid'))
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_anchor_normalization_candidate_run_term
    ON anchor_normalization_candidate (run_id, term_id);

CREATE INDEX IF NOT EXISTS idx_anchor_normalization_run_status_created
    ON anchor_normalization_run (status, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_anchor_normalization_candidate_run_status
    ON anchor_normalization_candidate (run_id, resolution_status);

COMMENT ON TABLE anchor_normalization_run IS
    'Dry-run and manual review history for deterministic anchor canonical_form/normalized_form normalization.';

COMMENT ON TABLE anchor_normalization_candidate IS
    'Per-anchor dry-run proposal. Approval only updates corpus_glossary_terms canonical_form and normalized_form for safe candidates.';
