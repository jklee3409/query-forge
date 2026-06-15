CREATE TABLE IF NOT EXISTS chat_runtime_config_provenance (
    provenance_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    domain_id UUID NOT NULL REFERENCES tech_doc_domain(domain_id) ON DELETE CASCADE,
    change_source TEXT NOT NULL DEFAULT 'manual',
    source_rag_test_run_id UUID REFERENCES rag_test_run(rag_test_run_id) ON DELETE SET NULL,
    source_config_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    previous_config_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    applied_config_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    diff_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    updated_by TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CHECK (change_source IN ('manual', 'apply_rag_run', 'migration', 'system'))
);

CREATE INDEX IF NOT EXISTS idx_chat_runtime_config_provenance_domain
    ON chat_runtime_config_provenance (domain_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_chat_runtime_config_provenance_rag_run
    ON chat_runtime_config_provenance (source_rag_test_run_id, created_at DESC);

COMMENT ON TABLE chat_runtime_config_provenance IS
    'Append-only audit trail for per-domain chat runtime config changes.';

COMMENT ON COLUMN chat_runtime_config_provenance.source_rag_test_run_id IS
    'Admin RAG test run that supplied the config when change_source=apply_rag_run.';
