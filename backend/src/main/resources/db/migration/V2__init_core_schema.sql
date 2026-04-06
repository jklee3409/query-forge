CREATE TABLE prompt_assets (
    prompt_asset_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    prompt_family TEXT NOT NULL,
    prompt_name TEXT NOT NULL,
    version TEXT NOT NULL,
    content_path TEXT NOT NULL,
    content_hash TEXT NOT NULL,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (prompt_family, prompt_name, version)
);

CREATE TABLE experiments (
    experiment_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    experiment_key TEXT NOT NULL UNIQUE,
    category TEXT NOT NULL,
    description TEXT,
    config_path TEXT NOT NULL,
    config_hash TEXT,
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE experiment_runs (
    experiment_run_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    experiment_id UUID REFERENCES experiments (experiment_id),
    run_label TEXT,
    status TEXT NOT NULL DEFAULT 'planned',
    parameters JSONB NOT NULL DEFAULT '{}'::jsonb,
    metrics JSONB NOT NULL DEFAULT '{}'::jsonb,
    notes TEXT,
    started_at TIMESTAMPTZ,
    finished_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CHECK (status IN ('planned', 'running', 'completed', 'failed', 'cancelled'))
);

CREATE TABLE documents (
    document_id TEXT PRIMARY KEY,
    document_family TEXT,
    source_url TEXT NOT NULL,
    product TEXT NOT NULL,
    version_if_available TEXT,
    title TEXT NOT NULL,
    source_format TEXT NOT NULL,
    heading_hierarchy JSONB NOT NULL DEFAULT '[]'::jsonb,
    source_hash TEXT,
    raw_text TEXT,
    cleaned_text TEXT,
    language_code TEXT NOT NULL DEFAULT 'en',
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE sections (
    section_id TEXT PRIMARY KEY,
    document_id TEXT NOT NULL REFERENCES documents (document_id) ON DELETE CASCADE,
    parent_section_id TEXT REFERENCES sections (section_id),
    source_url TEXT,
    title TEXT NOT NULL,
    section_path TEXT NOT NULL,
    heading_level INTEGER,
    heading_hierarchy JSONB NOT NULL DEFAULT '[]'::jsonb,
    raw_text TEXT,
    cleaned_text TEXT,
    structural_blocks JSONB NOT NULL DEFAULT '[]'::jsonb,
    dedupe_hash TEXT,
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (document_id, section_path)
);

CREATE TABLE chunks (
    chunk_id TEXT PRIMARY KEY,
    document_id TEXT NOT NULL REFERENCES documents (document_id) ON DELETE CASCADE,
    section_id TEXT REFERENCES sections (section_id) ON DELETE SET NULL,
    chunk_index_in_doc INTEGER NOT NULL,
    section_path TEXT NOT NULL,
    content TEXT NOT NULL,
    char_len INTEGER,
    token_len INTEGER,
    previous_chunk_id TEXT,
    next_chunk_id TEXT,
    code_presence BOOLEAN NOT NULL DEFAULT FALSE,
    product TEXT NOT NULL,
    version_if_available TEXT,
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (document_id, chunk_index_in_doc)
);

CREATE TABLE chunk_neighbors (
    source_chunk_id TEXT NOT NULL REFERENCES chunks (chunk_id) ON DELETE CASCADE,
    neighbor_chunk_id TEXT NOT NULL REFERENCES chunks (chunk_id) ON DELETE CASCADE,
    neighbor_type TEXT NOT NULL,
    distance INTEGER NOT NULL,
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (source_chunk_id, neighbor_chunk_id, neighbor_type),
    CHECK (neighbor_type IN ('near', 'far'))
);

CREATE TABLE glossary_terms (
    glossary_term_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    term_type TEXT NOT NULL,
    canonical_form TEXT NOT NULL,
    aliases JSONB NOT NULL DEFAULT '[]'::jsonb,
    keep_in_english BOOLEAN NOT NULL DEFAULT TRUE,
    source_product TEXT,
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (term_type, canonical_form)
);

CREATE TABLE synthetic_queries_raw (
    synthetic_query_id TEXT PRIMARY KEY,
    experiment_run_id UUID REFERENCES experiment_runs (experiment_run_id),
    chunk_id_source TEXT REFERENCES chunks (chunk_id),
    target_doc_id TEXT REFERENCES documents (document_id),
    target_chunk_ids JSONB NOT NULL DEFAULT '[]'::jsonb,
    answerability_type TEXT NOT NULL,
    query_text TEXT NOT NULL,
    query_language TEXT NOT NULL DEFAULT 'ko',
    query_type TEXT NOT NULL,
    generation_strategy TEXT NOT NULL,
    prompt_asset_id UUID REFERENCES prompt_assets (prompt_asset_id),
    prompt_version TEXT,
    prompt_hash TEXT,
    source_summary TEXT,
    source_chunk_ids JSONB NOT NULL DEFAULT '[]'::jsonb,
    glossary_terms JSONB NOT NULL DEFAULT '[]'::jsonb,
    llm_output JSONB NOT NULL DEFAULT '{}'::jsonb,
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CHECK (answerability_type IN ('single', 'near', 'far')),
    CHECK (generation_strategy IN ('A', 'B', 'C', 'D'))
);

CREATE TABLE synthetic_queries_gated (
    gated_query_id TEXT PRIMARY KEY,
    synthetic_query_id TEXT NOT NULL REFERENCES synthetic_queries_raw (synthetic_query_id) ON DELETE CASCADE,
    gating_preset TEXT NOT NULL,
    passed_rule_filter BOOLEAN,
    passed_llm_self_eval BOOLEAN,
    passed_retrieval_utility BOOLEAN,
    passed_diversity BOOLEAN,
    final_decision BOOLEAN NOT NULL,
    llm_scores JSONB NOT NULL DEFAULT '{}'::jsonb,
    utility_score DOUBLE PRECISION,
    novelty_score DOUBLE PRECISION,
    final_score DOUBLE PRECISION,
    rejection_reasons JSONB NOT NULL DEFAULT '[]'::jsonb,
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (synthetic_query_id, gating_preset),
    CHECK (gating_preset IN ('ungated', 'rule_only', 'rule_plus_llm', 'full_gating'))
);

CREATE TABLE query_embeddings (
    embedding_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_type TEXT NOT NULL,
    owner_id TEXT NOT NULL,
    embedding_model TEXT NOT NULL,
    embedding_dim INTEGER NOT NULL DEFAULT 3072,
    embedding HALFVEC(3072),
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (owner_type, owner_id, embedding_model),
    CHECK (owner_type IN ('chunk', 'synthetic_raw', 'synthetic_gated', 'memory', 'online_query', 'rewrite_candidate', 'eval_sample'))
);

CREATE TABLE memory_entries (
    memory_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    source_gated_query_id TEXT NOT NULL REFERENCES synthetic_queries_gated (gated_query_id),
    query_text TEXT NOT NULL,
    query_type TEXT NOT NULL,
    generation_strategy TEXT NOT NULL,
    target_chunk_ids JSONB NOT NULL DEFAULT '[]'::jsonb,
    target_doc_id TEXT REFERENCES documents (document_id),
    chunk_id_source TEXT REFERENCES chunks (chunk_id),
    product TEXT,
    glossary_terms JSONB NOT NULL DEFAULT '[]'::jsonb,
    llm_scores JSONB NOT NULL DEFAULT '{}'::jsonb,
    utility_score DOUBLE PRECISION,
    novelty_score DOUBLE PRECISION,
    final_score DOUBLE PRECISION,
    prompt_version TEXT,
    prompt_hash TEXT,
    query_embedding HALFVEC(3072),
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE eval_samples (
    sample_id TEXT PRIMARY KEY,
    split TEXT NOT NULL,
    user_query_ko TEXT NOT NULL,
    dialog_context JSONB NOT NULL DEFAULT '{}'::jsonb,
    expected_doc_ids JSONB NOT NULL DEFAULT '[]'::jsonb,
    expected_chunk_ids JSONB NOT NULL DEFAULT '[]'::jsonb,
    expected_answer_key_points JSONB NOT NULL DEFAULT '[]'::jsonb,
    query_category TEXT NOT NULL,
    difficulty TEXT,
    single_or_multi_chunk TEXT,
    source_product TEXT,
    source_version_if_available TEXT,
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CHECK (split IN ('dev', 'test', 'train'))
);

CREATE TABLE eval_judgments (
    judgment_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    sample_id TEXT NOT NULL REFERENCES eval_samples (sample_id) ON DELETE CASCADE,
    experiment_run_id UUID REFERENCES experiment_runs (experiment_run_id),
    evaluator_type TEXT NOT NULL,
    metrics JSONB NOT NULL DEFAULT '{}'::jsonb,
    notes TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CHECK (evaluator_type IN ('human', 'llm', 'rule'))
);

CREATE TABLE online_queries (
    online_query_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    experiment_run_id UUID REFERENCES experiment_runs (experiment_run_id),
    session_id TEXT,
    raw_query TEXT NOT NULL,
    final_query_used TEXT,
    rewrite_applied BOOLEAN NOT NULL DEFAULT FALSE,
    rewrite_strategy TEXT,
    session_context_snapshot JSONB NOT NULL DEFAULT '{}'::jsonb,
    memory_top_n JSONB NOT NULL DEFAULT '[]'::jsonb,
    raw_score DOUBLE PRECISION,
    selected_rewrite_candidate_id UUID,
    selected_reason TEXT,
    rejected_reason TEXT,
    threshold DOUBLE PRECISION,
    latency_breakdown JSONB NOT NULL DEFAULT '{}'::jsonb,
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE rewrite_candidates (
    rewrite_candidate_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    online_query_id UUID NOT NULL REFERENCES online_queries (online_query_id) ON DELETE CASCADE,
    candidate_rank INTEGER NOT NULL,
    candidate_label TEXT,
    candidate_query TEXT NOT NULL,
    prompt_asset_id UUID REFERENCES prompt_assets (prompt_asset_id),
    prompt_version TEXT,
    prompt_hash TEXT,
    memory_source_ids JSONB NOT NULL DEFAULT '[]'::jsonb,
    retrieval_top_k_docs JSONB NOT NULL DEFAULT '[]'::jsonb,
    confidence_score DOUBLE PRECISION,
    adopted BOOLEAN NOT NULL DEFAULT FALSE,
    rejected_reason TEXT,
    score_breakdown JSONB NOT NULL DEFAULT '{}'::jsonb,
    latency_ms INTEGER,
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (online_query_id, candidate_rank)
);

CREATE TABLE retrieval_results (
    retrieval_result_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    online_query_id UUID REFERENCES online_queries (online_query_id) ON DELETE CASCADE,
    rewrite_candidate_id UUID REFERENCES rewrite_candidates (rewrite_candidate_id) ON DELETE CASCADE,
    eval_sample_id TEXT REFERENCES eval_samples (sample_id) ON DELETE CASCADE,
    result_scope TEXT NOT NULL,
    rank INTEGER NOT NULL,
    document_id TEXT REFERENCES documents (document_id),
    chunk_id TEXT REFERENCES chunks (chunk_id),
    retriever_name TEXT NOT NULL DEFAULT 'pgvector',
    score DOUBLE PRECISION,
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CHECK (result_scope IN ('raw', 'rewrite_candidate', 'memory', 'eval'))
);

CREATE TABLE rerank_results (
    rerank_result_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    online_query_id UUID REFERENCES online_queries (online_query_id) ON DELETE CASCADE,
    rewrite_candidate_id UUID REFERENCES rewrite_candidates (rewrite_candidate_id) ON DELETE CASCADE,
    eval_sample_id TEXT REFERENCES eval_samples (sample_id) ON DELETE CASCADE,
    rank INTEGER NOT NULL,
    document_id TEXT REFERENCES documents (document_id),
    chunk_id TEXT REFERENCES chunks (chunk_id),
    model_name TEXT NOT NULL DEFAULT 'cohere-rerank',
    relevance_score DOUBLE PRECISION,
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE answers (
    answer_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    online_query_id UUID NOT NULL UNIQUE REFERENCES online_queries (online_query_id) ON DELETE CASCADE,
    answer_text TEXT NOT NULL,
    answer_language TEXT NOT NULL DEFAULT 'ko',
    cited_document_ids JSONB NOT NULL DEFAULT '[]'::jsonb,
    cited_chunk_ids JSONB NOT NULL DEFAULT '[]'::jsonb,
    generation_model TEXT,
    prompt_asset_id UUID REFERENCES prompt_assets (prompt_asset_id),
    prompt_version TEXT,
    prompt_hash TEXT,
    faithfulness_notes TEXT,
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

ALTER TABLE online_queries
    ADD CONSTRAINT fk_online_queries_selected_rewrite
    FOREIGN KEY (selected_rewrite_candidate_id)
    REFERENCES rewrite_candidates (rewrite_candidate_id);

CREATE INDEX idx_documents_product ON documents (product);
CREATE INDEX idx_sections_document_id ON sections (document_id);
CREATE INDEX idx_chunks_document_id ON chunks (document_id);
CREATE INDEX idx_chunks_section_id ON chunks (section_id);
CREATE INDEX idx_chunk_neighbors_source ON chunk_neighbors (source_chunk_id, neighbor_type);
CREATE INDEX idx_synthetic_queries_raw_chunk_id_source ON synthetic_queries_raw (chunk_id_source);
CREATE INDEX idx_synthetic_queries_gated_preset ON synthetic_queries_gated (gating_preset, final_decision);
CREATE INDEX idx_memory_entries_target_doc_id ON memory_entries (target_doc_id);
CREATE INDEX idx_online_queries_created_at ON online_queries (created_at DESC);
CREATE INDEX idx_rewrite_candidates_online_query_id ON rewrite_candidates (online_query_id);
CREATE INDEX idx_retrieval_results_online_query_id ON retrieval_results (online_query_id);
CREATE INDEX idx_rerank_results_online_query_id ON rerank_results (online_query_id);

CREATE INDEX idx_query_embeddings_vector
    ON query_embeddings
    USING hnsw (embedding halfvec_cosine_ops);

CREATE INDEX idx_memory_entries_query_embedding
    ON memory_entries
    USING hnsw (query_embedding halfvec_cosine_ops);
