CREATE TABLE IF NOT EXISTS chunk_generation_asset (
    asset_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    chunk_id TEXT REFERENCES corpus_chunks (chunk_id) ON DELETE CASCADE,
    chunk_group_id UUID,
    source_document_id TEXT REFERENCES corpus_documents (document_id) ON DELETE CASCADE,
    asset_type TEXT NOT NULL,
    text_content TEXT NOT NULL,
    llm_provider TEXT NOT NULL,
    llm_model TEXT NOT NULL,
    prompt_template_version TEXT NOT NULL,
    source_fingerprint TEXT NOT NULL,
    metadata_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CHECK (asset_type IN (
        'EN_EXTRACTIVE_SUMMARY',
        'KO_TRANSLATED_CHUNK',
        'KO_SUMMARY',
        'MULTI_CHUNK_SUMMARY'
    )),
    CHECK ((chunk_id IS NOT NULL) OR (chunk_group_id IS NOT NULL))
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_chunk_generation_asset_chunk
    ON chunk_generation_asset (
        chunk_id,
        asset_type,
        llm_provider,
        llm_model,
        prompt_template_version,
        source_fingerprint
    )
    WHERE chunk_id IS NOT NULL
      AND chunk_group_id IS NULL;

CREATE UNIQUE INDEX IF NOT EXISTS uq_chunk_generation_asset_group
    ON chunk_generation_asset (
        chunk_group_id,
        asset_type,
        llm_provider,
        llm_model,
        prompt_template_version,
        source_fingerprint
    )
    WHERE chunk_group_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_chunk_generation_asset_doc
    ON chunk_generation_asset (source_document_id, created_at DESC);

ALTER TABLE synthetic_queries_raw
    ADD COLUMN IF NOT EXISTS llm_provider TEXT;

ALTER TABLE synthetic_queries_raw
    ADD COLUMN IF NOT EXISTS llm_model TEXT;

ALTER TABLE synthetic_queries_raw
    ADD COLUMN IF NOT EXISTS source_fingerprint TEXT;

ALTER TABLE synthetic_queries_raw
    ADD COLUMN IF NOT EXISTS generation_asset_ids JSONB NOT NULL DEFAULT '[]'::jsonb;

ALTER TABLE synthetic_queries_gated
    ADD COLUMN IF NOT EXISTS llm_provider TEXT;

ALTER TABLE synthetic_queries_gated
    ADD COLUMN IF NOT EXISTS llm_model TEXT;

ALTER TABLE synthetic_query_gating_result
    ADD COLUMN IF NOT EXISTS llm_provider TEXT;

ALTER TABLE synthetic_query_gating_result
    ADD COLUMN IF NOT EXISTS llm_model TEXT;

CREATE TABLE IF NOT EXISTS llm_job (
    job_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    job_type TEXT NOT NULL,
    job_status TEXT NOT NULL DEFAULT 'queued',
    priority INTEGER NOT NULL DEFAULT 100,
    generation_batch_id UUID REFERENCES synthetic_query_generation_batch (batch_id) ON DELETE SET NULL,
    gating_batch_id UUID REFERENCES quality_gating_batch (gating_batch_id) ON DELETE SET NULL,
    rag_test_run_id UUID REFERENCES rag_test_run (rag_test_run_id) ON DELETE SET NULL,
    experiment_name TEXT,
    command_name TEXT NOT NULL,
    command_args JSONB NOT NULL DEFAULT '{}'::jsonb,
    total_items INTEGER NOT NULL DEFAULT 1,
    processed_items INTEGER NOT NULL DEFAULT 0,
    progress_pct DOUBLE PRECISION NOT NULL DEFAULT 0.0,
    retry_count INTEGER NOT NULL DEFAULT 0,
    max_retries INTEGER NOT NULL DEFAULT 2,
    next_run_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    worker_id TEXT,
    started_at TIMESTAMPTZ,
    finished_at TIMESTAMPTZ,
    last_checkpoint JSONB NOT NULL DEFAULT '{}'::jsonb,
    result_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    error_message TEXT,
    created_by TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CHECK (job_type IN (
        'GENERATE_EN_SUMMARY',
        'TRANSLATE_CHUNK_TO_KO',
        'GENERATE_KO_SUMMARY',
        'GENERATE_SYNTHETIC_QUERY',
        'RUN_LLM_SELF_EVAL',
        'GENERATE_REWRITE_CANDIDATES',
        'RUN_RAG_TEST'
    )),
    CHECK (job_status IN (
        'queued',
        'running',
        'pause_requested',
        'paused',
        'cancel_requested',
        'completed',
        'failed',
        'cancelled'
    ))
);

CREATE INDEX IF NOT EXISTS idx_llm_job_queue
    ON llm_job (job_status, next_run_at, priority, created_at);

CREATE INDEX IF NOT EXISTS idx_llm_job_generation_batch
    ON llm_job (generation_batch_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_llm_job_gating_batch
    ON llm_job (gating_batch_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_llm_job_rag_test
    ON llm_job (rag_test_run_id, created_at DESC);

CREATE TABLE IF NOT EXISTS llm_job_item (
    job_item_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    job_id UUID NOT NULL REFERENCES llm_job (job_id) ON DELETE CASCADE,
    item_order INTEGER NOT NULL,
    item_type TEXT NOT NULL,
    item_status TEXT NOT NULL DEFAULT 'queued',
    retry_count INTEGER NOT NULL DEFAULT 0,
    max_retries INTEGER NOT NULL DEFAULT 2,
    payload_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    checkpoint_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    result_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    error_message TEXT,
    started_at TIMESTAMPTZ,
    finished_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CHECK (item_status IN ('queued', 'running', 'completed', 'failed', 'skipped', 'cancelled')),
    UNIQUE (job_id, item_order)
);

CREATE INDEX IF NOT EXISTS idx_llm_job_item_status
    ON llm_job_item (job_id, item_status, item_order);
