CREATE TABLE IF NOT EXISTS document_content_fingerprint (
    fingerprint_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    canonical_url TEXT NOT NULL,
    title_normalized TEXT NOT NULL,
    normalized_text_hash TEXT NOT NULL,
    raw_text_hash TEXT,
    source_id TEXT REFERENCES corpus_sources (source_id),
    version_label TEXT,
    latest_document_id TEXT REFERENCES corpus_documents (document_id),
    metadata_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_document_content_fingerprint_canonical_hash
    ON document_content_fingerprint (canonical_url, normalized_text_hash, version_label);

CREATE UNIQUE INDEX IF NOT EXISTS uq_document_content_fingerprint_title_hash
    ON document_content_fingerprint (title_normalized, normalized_text_hash);

CREATE INDEX IF NOT EXISTS idx_document_content_fingerprint_latest_doc
    ON document_content_fingerprint (latest_document_id, updated_at DESC);

ALTER TABLE synthetic_queries_raw
    ADD COLUMN IF NOT EXISTS generation_method_id UUID REFERENCES synthetic_query_generation_method (generation_method_id);

ALTER TABLE synthetic_queries_raw
    ADD COLUMN IF NOT EXISTS generation_batch_id UUID REFERENCES synthetic_query_generation_batch (batch_id);

ALTER TABLE synthetic_queries_raw
    ADD COLUMN IF NOT EXISTS prompt_template_version TEXT;

ALTER TABLE synthetic_queries_raw
    ADD COLUMN IF NOT EXISTS language_profile TEXT;

ALTER TABLE synthetic_queries_raw
    ADD COLUMN IF NOT EXISTS source_chunk_group_id UUID;

ALTER TABLE synthetic_queries_raw
    ADD COLUMN IF NOT EXISTS normalized_query_text TEXT;

CREATE INDEX IF NOT EXISTS idx_synthetic_queries_raw_batch
    ON synthetic_queries_raw (generation_batch_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_synthetic_queries_raw_method
    ON synthetic_queries_raw (generation_method_id, created_at DESC);

CREATE TABLE IF NOT EXISTS synthetic_query_source_link (
    source_link_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    synthetic_query_id TEXT NOT NULL REFERENCES synthetic_queries_raw (synthetic_query_id) ON DELETE CASCADE,
    source_doc_id TEXT REFERENCES corpus_documents (document_id),
    source_chunk_id TEXT REFERENCES corpus_chunks (chunk_id),
    source_chunk_group_id UUID,
    source_role TEXT NOT NULL DEFAULT 'primary',
    metadata_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (synthetic_query_id, source_chunk_id, source_role)
);

CREATE INDEX IF NOT EXISTS idx_synthetic_query_source_link_doc
    ON synthetic_query_source_link (source_doc_id, source_role);

CREATE INDEX IF NOT EXISTS idx_synthetic_query_source_link_chunk
    ON synthetic_query_source_link (source_chunk_id, source_role);

ALTER TABLE synthetic_queries_gated
    ADD COLUMN IF NOT EXISTS gating_batch_id UUID REFERENCES quality_gating_batch (gating_batch_id);

ALTER TABLE synthetic_queries_gated
    ADD COLUMN IF NOT EXISTS rejected_stage TEXT;

ALTER TABLE synthetic_queries_gated
    ADD COLUMN IF NOT EXISTS rejected_reason TEXT;

CREATE INDEX IF NOT EXISTS idx_synthetic_queries_gated_batch
    ON synthetic_queries_gated (gating_batch_id, created_at DESC);

CREATE TABLE IF NOT EXISTS synthetic_query_gating_result (
    result_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    gating_batch_id UUID NOT NULL REFERENCES quality_gating_batch (gating_batch_id) ON DELETE CASCADE,
    synthetic_query_id TEXT NOT NULL REFERENCES synthetic_queries_raw (synthetic_query_id) ON DELETE CASCADE,
    query_text TEXT NOT NULL,
    query_type TEXT,
    language_profile TEXT,
    generation_strategy TEXT,
    rule_pass BOOLEAN,
    llm_eval_score DOUBLE PRECISION,
    utility_score DOUBLE PRECISION,
    diversity_pass BOOLEAN,
    novelty_score DOUBLE PRECISION,
    final_score DOUBLE PRECISION,
    accepted BOOLEAN NOT NULL DEFAULT FALSE,
    rejected_stage TEXT,
    rejected_reason TEXT,
    llm_scores JSONB NOT NULL DEFAULT '{}'::jsonb,
    stage_payload_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (gating_batch_id, synthetic_query_id)
);

CREATE INDEX IF NOT EXISTS idx_synthetic_query_gating_result_batch
    ON synthetic_query_gating_result (gating_batch_id, accepted, created_at DESC);

CREATE TABLE IF NOT EXISTS synthetic_query_gating_history (
    history_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    gating_batch_id UUID NOT NULL REFERENCES quality_gating_batch (gating_batch_id) ON DELETE CASCADE,
    synthetic_query_id TEXT NOT NULL REFERENCES synthetic_queries_raw (synthetic_query_id) ON DELETE CASCADE,
    stage_name TEXT NOT NULL,
    stage_order INTEGER NOT NULL,
    passed BOOLEAN,
    score DOUBLE PRECISION,
    reason TEXT,
    payload_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_synthetic_query_gating_history_batch_stage
    ON synthetic_query_gating_history (gating_batch_id, stage_order, synthetic_query_id);

CREATE TABLE IF NOT EXISTS quality_gating_stage_result (
    stage_result_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    gating_batch_id UUID NOT NULL REFERENCES quality_gating_batch (gating_batch_id) ON DELETE CASCADE,
    stage_name TEXT NOT NULL,
    stage_order INTEGER NOT NULL,
    input_count INTEGER NOT NULL DEFAULT 0,
    passed_count INTEGER NOT NULL DEFAULT 0,
    rejected_count INTEGER NOT NULL DEFAULT 0,
    metrics_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (gating_batch_id, stage_name)
);

CREATE INDEX IF NOT EXISTS idx_quality_gating_stage_result_batch
    ON quality_gating_stage_result (gating_batch_id, stage_order);

ALTER TABLE rag_test_result_summary
    ADD COLUMN IF NOT EXISTS rewrite_rejection_rate DOUBLE PRECISION;

ALTER TABLE rag_test_result_summary
    ADD COLUMN IF NOT EXISTS average_confidence_delta DOUBLE PRECISION;

ALTER TABLE rag_test_result_detail
    ADD COLUMN IF NOT EXISTS answerability_type TEXT;

ALTER TABLE rag_test_result_detail
    ADD COLUMN IF NOT EXISTS generation_method TEXT;

ALTER TABLE rag_test_result_detail
    ADD COLUMN IF NOT EXISTS single_or_multi_chunk TEXT;

CREATE TABLE IF NOT EXISTS online_query_rewrite_log (
    rewrite_log_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    online_query_id UUID NOT NULL REFERENCES online_queries (online_query_id) ON DELETE CASCADE,
    run_id UUID REFERENCES rag_test_run (rag_test_run_id),
    raw_query TEXT NOT NULL,
    final_query TEXT,
    rewrite_strategy TEXT,
    generation_method_codes JSONB NOT NULL DEFAULT '[]'::jsonb,
    generation_batch_ids JSONB NOT NULL DEFAULT '[]'::jsonb,
    gating_applied BOOLEAN NOT NULL DEFAULT TRUE,
    gating_preset TEXT,
    rewrite_applied BOOLEAN NOT NULL DEFAULT FALSE,
    selective_rewrite BOOLEAN,
    use_session_context BOOLEAN,
    raw_confidence DOUBLE PRECISION,
    selected_confidence DOUBLE PRECISION,
    confidence_delta DOUBLE PRECISION,
    decision_reason TEXT,
    rejection_reason TEXT,
    metadata_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_online_query_rewrite_log_query
    ON online_query_rewrite_log (online_query_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_online_query_rewrite_log_created
    ON online_query_rewrite_log (created_at DESC);

CREATE TABLE IF NOT EXISTS rewrite_candidate_log (
    candidate_log_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    rewrite_log_id UUID REFERENCES online_query_rewrite_log (rewrite_log_id) ON DELETE CASCADE,
    online_query_id UUID NOT NULL REFERENCES online_queries (online_query_id) ON DELETE CASCADE,
    rewrite_candidate_id UUID REFERENCES rewrite_candidates (rewrite_candidate_id) ON DELETE SET NULL,
    candidate_rank INTEGER NOT NULL,
    candidate_label TEXT,
    candidate_query TEXT NOT NULL,
    confidence_score DOUBLE PRECISION,
    selected BOOLEAN NOT NULL DEFAULT FALSE,
    rejection_reason TEXT,
    retrieval_top_k_docs JSONB NOT NULL DEFAULT '[]'::jsonb,
    score_breakdown JSONB NOT NULL DEFAULT '{}'::jsonb,
    metadata_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_rewrite_candidate_log_rewrite
    ON rewrite_candidate_log (rewrite_log_id, candidate_rank);

CREATE TABLE IF NOT EXISTS memory_retrieval_log (
    memory_retrieval_log_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    rewrite_log_id UUID REFERENCES online_query_rewrite_log (rewrite_log_id) ON DELETE CASCADE,
    online_query_id UUID NOT NULL REFERENCES online_queries (online_query_id) ON DELETE CASCADE,
    memory_id UUID REFERENCES memory_entries (memory_id),
    retrieval_rank INTEGER NOT NULL,
    similarity DOUBLE PRECISION,
    query_text TEXT,
    target_doc_id TEXT,
    target_chunk_ids JSONB NOT NULL DEFAULT '[]'::jsonb,
    generation_strategy TEXT,
    metadata_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_memory_retrieval_log_rewrite
    ON memory_retrieval_log (rewrite_log_id, retrieval_rank);

CREATE OR REPLACE VIEW document_source AS
SELECT
    (to_jsonb(s) ->> 'source_id')::text AS source_id,
    (to_jsonb(s) ->> 'source_type')::text AS source_type,
    (to_jsonb(s) ->> 'product_name')::text AS product_name,
    (to_jsonb(s) ->> 'source_name')::text AS source_name,
    (to_jsonb(s) ->> 'base_url')::text AS base_url,
    COALESCE(to_jsonb(s) -> 'include_patterns', '[]'::jsonb) AS include_patterns,
    COALESCE(to_jsonb(s) -> 'exclude_patterns', '[]'::jsonb) AS exclude_patterns,
    (to_jsonb(s) ->> 'default_version')::text AS default_version,
    COALESCE((to_jsonb(s) ->> 'enabled')::boolean, TRUE) AS enabled,
    (to_jsonb(s) ->> 'created_at')::timestamptz AS created_at,
    (to_jsonb(s) ->> 'updated_at')::timestamptz AS updated_at
FROM corpus_sources s;

CREATE OR REPLACE VIEW collected_document AS
SELECT
    (to_jsonb(d) ->> 'document_id')::text AS document_id,
    (to_jsonb(d) ->> 'source_id')::text AS source_id,
    (to_jsonb(d) ->> 'product_name')::text AS product_name,
    (to_jsonb(d) ->> 'version_label')::text AS version_label,
    (to_jsonb(d) ->> 'canonical_url')::text AS canonical_url,
    (to_jsonb(d) ->> 'title')::text AS title,
    COALESCE(to_jsonb(d) -> 'heading_hierarchy_json', '[]'::jsonb) AS heading_hierarchy_json,
    (to_jsonb(d) ->> 'raw_checksum')::text AS raw_checksum,
    (to_jsonb(d) ->> 'cleaned_checksum')::text AS cleaned_checksum,
    (to_jsonb(d) ->> 'raw_text')::text AS raw_text,
    (to_jsonb(d) ->> 'cleaned_text')::text AS cleaned_text,
    (to_jsonb(d) ->> 'language_code')::text AS language_code,
    COALESCE((to_jsonb(d) ->> 'is_active')::boolean, TRUE) AS is_active,
    NULLIF((to_jsonb(d) ->> 'import_run_id')::text, '')::uuid AS import_run_id,
    COALESCE(to_jsonb(d) -> 'metadata_json', '{}'::jsonb) AS metadata_json,
    (to_jsonb(d) ->> 'created_at')::timestamptz AS created_at,
    (to_jsonb(d) ->> 'updated_at')::timestamptz AS updated_at
FROM corpus_documents d;

CREATE OR REPLACE VIEW normalized_document AS
SELECT
    (to_jsonb(d) ->> 'document_id')::text AS document_id,
    (to_jsonb(d) ->> 'source_id')::text AS source_id,
    (to_jsonb(d) ->> 'product_name')::text AS product_name,
    (to_jsonb(d) ->> 'version_label')::text AS version_label,
    (to_jsonb(d) ->> 'canonical_url')::text AS canonical_url,
    (to_jsonb(d) ->> 'title')::text AS title,
    (to_jsonb(d) ->> 'cleaned_checksum')::text AS normalized_text_hash,
    (to_jsonb(d) ->> 'cleaned_text')::text AS cleaned_text,
    COALESCE(to_jsonb(d) -> 'metadata_json', '{}'::jsonb) AS metadata_json,
    (to_jsonb(d) ->> 'updated_at')::timestamptz AS updated_at
FROM corpus_documents d;

CREATE OR REPLACE VIEW pipeline_execution AS
SELECT
    (to_jsonb(r) ->> 'run_id')::uuid AS pipeline_execution_id,
    (to_jsonb(r) ->> 'run_type')::text AS execution_type,
    (to_jsonb(r) ->> 'run_status')::text AS execution_status,
    (to_jsonb(r) ->> 'trigger_type')::text AS trigger_type,
    COALESCE(to_jsonb(r) -> 'source_scope', '{}'::jsonb) AS source_scope,
    COALESCE(to_jsonb(r) -> 'config_snapshot', '{}'::jsonb) AS config_snapshot,
    COALESCE(to_jsonb(r) -> 'summary_json', '{}'::jsonb) AS summary_json,
    (to_jsonb(r) ->> 'error_message')::text AS error_message,
    (to_jsonb(r) ->> 'started_at')::timestamptz AS started_at,
    (to_jsonb(r) ->> 'finished_at')::timestamptz AS finished_at,
    (to_jsonb(r) ->> 'duration_ms')::bigint AS duration_ms,
    (to_jsonb(r) ->> 'created_by')::text AS created_by,
    (to_jsonb(r) ->> 'created_at')::timestamptz AS created_at
FROM corpus_runs r;

CREATE OR REPLACE VIEW pipeline_execution_step AS
SELECT
    (to_jsonb(s) ->> 'step_id')::uuid AS pipeline_execution_step_id,
    (to_jsonb(s) ->> 'run_id')::uuid AS pipeline_execution_id,
    (to_jsonb(s) ->> 'step_name')::text AS step_name,
    COALESCE((to_jsonb(s) ->> 'step_order')::integer, 0) AS step_order,
    (to_jsonb(s) ->> 'step_status')::text AS step_status,
    (to_jsonb(s) ->> 'input_artifact_path')::text AS input_artifact_path,
    (to_jsonb(s) ->> 'output_artifact_path')::text AS output_artifact_path,
    COALESCE(to_jsonb(s) -> 'metrics_json', '{}'::jsonb) AS metrics_json,
    (to_jsonb(s) ->> 'error_message')::text AS error_message,
    (to_jsonb(s) ->> 'started_at')::timestamptz AS started_at,
    (to_jsonb(s) ->> 'finished_at')::timestamptz AS finished_at,
    (to_jsonb(s) ->> 'created_at')::timestamptz AS created_at
FROM corpus_run_steps s;
