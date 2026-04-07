CREATE TABLE IF NOT EXISTS synthetic_query_generation_method (
    generation_method_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    method_code TEXT NOT NULL UNIQUE,
    method_name TEXT NOT NULL,
    description TEXT,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    prompt_template_version TEXT,
    summary_strategy TEXT,
    translation_strategy TEXT,
    query_language_strategy TEXT,
    terminology_preservation_rule TEXT,
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CHECK (method_code IN ('A', 'B', 'C', 'D'))
);

CREATE TABLE IF NOT EXISTS synthetic_query_generation_batch (
    batch_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    generation_method_id UUID NOT NULL REFERENCES synthetic_query_generation_method (generation_method_id),
    version_name TEXT NOT NULL,
    source_document_version TEXT,
    source_generation_run_id UUID REFERENCES experiment_runs (experiment_run_id),
    status TEXT NOT NULL DEFAULT 'planned',
    started_at TIMESTAMPTZ,
    finished_at TIMESTAMPTZ,
    total_generated_count INTEGER NOT NULL DEFAULT 0,
    created_by TEXT,
    config_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    metrics_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CHECK (status IN ('planned', 'running', 'completed', 'failed', 'cancelled'))
);

CREATE TABLE IF NOT EXISTS quality_gating_batch (
    gating_batch_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    gating_preset TEXT NOT NULL,
    generation_method_id UUID REFERENCES synthetic_query_generation_method (generation_method_id),
    generation_batch_id UUID REFERENCES synthetic_query_generation_batch (batch_id),
    source_generation_run_id UUID REFERENCES experiment_runs (experiment_run_id),
    source_gating_run_id UUID REFERENCES experiment_runs (experiment_run_id),
    stage_config_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    status TEXT NOT NULL DEFAULT 'planned',
    started_at TIMESTAMPTZ,
    finished_at TIMESTAMPTZ,
    processed_count INTEGER NOT NULL DEFAULT 0,
    accepted_count INTEGER NOT NULL DEFAULT 0,
    rejected_count INTEGER NOT NULL DEFAULT 0,
    rejection_summary JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_by TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CHECK (status IN ('planned', 'running', 'completed', 'failed', 'cancelled')),
    CHECK (gating_preset IN ('ungated', 'rule_only', 'rule_plus_llm', 'full_gating'))
);

CREATE TABLE IF NOT EXISTS eval_dataset (
    dataset_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    dataset_key TEXT NOT NULL UNIQUE,
    dataset_name TEXT NOT NULL,
    description TEXT,
    version TEXT,
    split_strategy TEXT,
    total_items INTEGER NOT NULL DEFAULT 0,
    category_distribution JSONB NOT NULL DEFAULT '{}'::jsonb,
    single_multi_distribution JSONB NOT NULL DEFAULT '{}'::jsonb,
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS eval_dataset_item (
    dataset_item_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    dataset_id UUID NOT NULL REFERENCES eval_dataset (dataset_id) ON DELETE CASCADE,
    sample_id TEXT NOT NULL REFERENCES eval_samples (sample_id) ON DELETE CASCADE,
    query_category TEXT,
    single_or_multi_chunk TEXT,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (dataset_id, sample_id)
);

CREATE TABLE IF NOT EXISTS rag_test_run (
    rag_test_run_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    run_label TEXT,
    status TEXT NOT NULL DEFAULT 'planned',
    dataset_id UUID REFERENCES eval_dataset (dataset_id),
    generation_method_codes JSONB NOT NULL DEFAULT '[]'::jsonb,
    generation_batch_ids JSONB NOT NULL DEFAULT '[]'::jsonb,
    gating_applied BOOLEAN NOT NULL DEFAULT TRUE,
    gating_preset TEXT,
    rewrite_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    selective_rewrite BOOLEAN NOT NULL DEFAULT TRUE,
    use_session_context BOOLEAN NOT NULL DEFAULT FALSE,
    top_k INTEGER,
    threshold DOUBLE PRECISION,
    retrieval_top_k INTEGER,
    rerank_top_n INTEGER,
    experiment_config_name TEXT,
    source_experiment_run_id UUID REFERENCES experiment_runs (experiment_run_id),
    started_at TIMESTAMPTZ,
    finished_at TIMESTAMPTZ,
    created_by TEXT,
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CHECK (status IN ('planned', 'running', 'completed', 'failed', 'cancelled'))
);

CREATE TABLE IF NOT EXISTS rag_test_run_config (
    config_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    rag_test_run_id UUID NOT NULL UNIQUE REFERENCES rag_test_run (rag_test_run_id) ON DELETE CASCADE,
    config_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS rag_test_result_summary (
    rag_test_run_id UUID PRIMARY KEY REFERENCES rag_test_run (rag_test_run_id) ON DELETE CASCADE,
    recall_at_5 DOUBLE PRECISION,
    hit_at_5 DOUBLE PRECISION,
    mrr_at_10 DOUBLE PRECISION,
    ndcg_at_10 DOUBLE PRECISION,
    latency_avg_ms DOUBLE PRECISION,
    rewrite_acceptance_rate DOUBLE PRECISION,
    answer_metrics JSONB NOT NULL DEFAULT '{}'::jsonb,
    metrics_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS rag_test_result_detail (
    detail_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    rag_test_run_id UUID NOT NULL REFERENCES rag_test_run (rag_test_run_id) ON DELETE CASCADE,
    sample_id TEXT REFERENCES eval_samples (sample_id) ON DELETE CASCADE,
    query_category TEXT,
    raw_query TEXT,
    rewrite_query TEXT,
    rewrite_applied BOOLEAN,
    memory_candidates JSONB NOT NULL DEFAULT '[]'::jsonb,
    rewrite_candidates JSONB NOT NULL DEFAULT '[]'::jsonb,
    retrieved_chunks JSONB NOT NULL DEFAULT '[]'::jsonb,
    metric_contribution JSONB NOT NULL DEFAULT '{}'::jsonb,
    hit_target BOOLEAN,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_synthetic_query_generation_batch_method
    ON synthetic_query_generation_batch (generation_method_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_quality_gating_batch_method
    ON quality_gating_batch (generation_method_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_eval_dataset_item_dataset
    ON eval_dataset_item (dataset_id, active);

CREATE INDEX IF NOT EXISTS idx_rag_test_run_dataset
    ON rag_test_run (dataset_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_rag_test_result_detail_run
    ON rag_test_result_detail (rag_test_run_id, created_at DESC);

INSERT INTO synthetic_query_generation_method (
    method_code,
    method_name,
    description,
    active,
    prompt_template_version,
    summary_strategy,
    translation_strategy,
    query_language_strategy,
    terminology_preservation_rule,
    metadata
)
VALUES
    ('A', 'A안 (EN->EN Summary->EN Query->KO 번역)', '영문 요약/질의 후 한국어로 번역하는 방식', TRUE, 'v1', 'extractive_en', 'en_to_ko_after_query', 'ko_final', 'glossary english term preserve', '{}'::jsonb),
    ('B', 'B안 (EN->KO 번역->KO Summary->KO Query)', '원문을 한국어로 변환한 뒤 한국어 중심으로 생성하는 방식', TRUE, 'v1', 'extractive_ko', 'en_to_ko_before_summary', 'ko_only', 'glossary english term preserve', '{}'::jsonb),
    ('C', 'C안 (기본 제안)', '영문 요약 + 한국어 summary + glossary 결합 생성', TRUE, 'v1', 'extractive_en_plus_ko', 'summary_only', 'ko_with_terms', 'technical terms keep english', '{}'::jsonb),
    ('D', 'D안 (ablation code-mixed)', 'C안 기반 code-mixed 질의 추가 생성', TRUE, 'v1', 'extractive_en_plus_ko', 'summary_only', 'ko_and_code_mixed', 'technical terms keep english', '{}'::jsonb)
ON CONFLICT (method_code) DO UPDATE
SET method_name = EXCLUDED.method_name,
    description = EXCLUDED.description,
    active = EXCLUDED.active,
    prompt_template_version = EXCLUDED.prompt_template_version,
    summary_strategy = EXCLUDED.summary_strategy,
    translation_strategy = EXCLUDED.translation_strategy,
    query_language_strategy = EXCLUDED.query_language_strategy,
    terminology_preservation_rule = EXCLUDED.terminology_preservation_rule,
    updated_at = NOW();
