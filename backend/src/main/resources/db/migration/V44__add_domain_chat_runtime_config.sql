ALTER TABLE IF EXISTS online_queries
    ADD COLUMN IF NOT EXISTS domain_id UUID REFERENCES tech_doc_domain(domain_id);

CREATE INDEX IF NOT EXISTS idx_online_queries_domain
    ON online_queries (domain_id, created_at DESC);

CREATE TABLE IF NOT EXISTS chat_runtime_config (
    domain_id UUID PRIMARY KEY REFERENCES tech_doc_domain(domain_id) ON DELETE CASCADE,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    mode TEXT NOT NULL DEFAULT 'selective_rewrite',
    generation_strategies JSONB NOT NULL DEFAULT '[]'::jsonb,
    gating_preset TEXT NOT NULL DEFAULT 'full_gating',
    source_gating_batch_id UUID REFERENCES quality_gating_batch(gating_batch_id) ON DELETE SET NULL,
    source_gating_run_id UUID REFERENCES experiment_runs(experiment_run_id) ON DELETE SET NULL,
    rewrite_query_profile TEXT NOT NULL DEFAULT 'compact_anchor',
    rewrite_anchor_injection_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    use_session_context BOOLEAN NOT NULL DEFAULT FALSE,
    retrieval_top_k INTEGER NOT NULL DEFAULT 10,
    rerank_top_n INTEGER NOT NULL DEFAULT 5,
    memory_top_n INTEGER NOT NULL DEFAULT 5,
    rewrite_candidate_count INTEGER NOT NULL DEFAULT 2,
    rewrite_threshold DOUBLE PRECISION NOT NULL DEFAULT 0.05,
    rewrite_failure_policy TEXT NOT NULL DEFAULT 'heuristic_fallback',
    metadata_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    updated_by TEXT,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CHECK (mode IN ('raw_only', 'selective_rewrite', 'selective_rewrite_with_session', 'rewrite_always', 'memory_only_gated', 'memory_only_ungated')),
    CHECK (gating_preset IN ('ungated', 'rule_only', 'rule_plus_llm', 'full_gating')),
    CHECK (rewrite_query_profile IN ('compact_anchor', 'detailed_intent')),
    CHECK (rewrite_failure_policy IN ('fail_run', 'skip_to_raw', 'heuristic_fallback')),
    CHECK (retrieval_top_k BETWEEN 1 AND 100),
    CHECK (rerank_top_n BETWEEN 1 AND 100),
    CHECK (memory_top_n BETWEEN 1 AND 50),
    CHECK (rewrite_candidate_count BETWEEN 1 AND 2),
    CHECK (rewrite_threshold >= 0.0 AND rewrite_threshold <= 1.0),
    CHECK (jsonb_typeof(generation_strategies) = 'array')
);

INSERT INTO chat_runtime_config (
    domain_id,
    generation_strategies,
    gating_preset,
    rewrite_query_profile,
    rewrite_anchor_injection_enabled,
    metadata_json,
    updated_by
)
SELECT d.domain_id,
       COALESCE(methods.method_codes, '[]'::jsonb),
       'full_gating',
       'compact_anchor',
       FALSE,
       jsonb_build_object('seed', 'V44', 'requires_source_gating_batch_id_for_rewrite', TRUE),
       'migration:V44'
FROM tech_doc_domain d
LEFT JOIN LATERAL (
    SELECT jsonb_agg(p.method_code ORDER BY p.method_code) AS method_codes
    FROM tech_doc_domain_method_policy p
    WHERE p.domain_id = d.domain_id
      AND p.enabled IS TRUE
) methods ON TRUE
WHERE d.status = 'active'
ON CONFLICT (domain_id) DO NOTHING;

CREATE INDEX IF NOT EXISTS idx_chat_runtime_config_updated
    ON chat_runtime_config (updated_at DESC);

COMMENT ON TABLE chat_runtime_config IS
    'Persistent per-domain runtime configuration used by the online chat RAG path.';

COMMENT ON COLUMN chat_runtime_config.source_gating_batch_id IS
    'Completed gating snapshot selected by an operator after Admin RAG quality evaluation.';
