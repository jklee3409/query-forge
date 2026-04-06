CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE TABLE corpus_sources (
    source_id TEXT PRIMARY KEY,
    source_type TEXT NOT NULL,
    product_name TEXT NOT NULL,
    source_name TEXT NOT NULL,
    base_url TEXT NOT NULL,
    include_patterns JSONB NOT NULL DEFAULT '[]'::jsonb,
    exclude_patterns JSONB NOT NULL DEFAULT '[]'::jsonb,
    default_version TEXT,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE corpus_runs (
    run_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    run_type TEXT NOT NULL,
    run_status TEXT NOT NULL DEFAULT 'queued',
    trigger_type TEXT NOT NULL DEFAULT 'manual',
    source_scope JSONB NOT NULL DEFAULT '{}'::jsonb,
    config_snapshot JSONB NOT NULL DEFAULT '{}'::jsonb,
    started_at TIMESTAMPTZ,
    finished_at TIMESTAMPTZ,
    duration_ms BIGINT,
    summary_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    error_message TEXT,
    created_by TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CHECK (run_type IN ('collect', 'normalize', 'chunk', 'glossary', 'import', 'full_ingest')),
    CHECK (run_status IN ('queued', 'running', 'success', 'failed', 'cancelled')),
    CHECK (trigger_type IN ('manual', 'scheduled', 'api'))
);

CREATE TABLE corpus_run_steps (
    step_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    run_id UUID NOT NULL REFERENCES corpus_runs (run_id) ON DELETE CASCADE,
    step_name TEXT NOT NULL,
    step_order INTEGER NOT NULL,
    step_status TEXT NOT NULL DEFAULT 'queued',
    input_artifact_path TEXT,
    output_artifact_path TEXT,
    metrics_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    started_at TIMESTAMPTZ,
    finished_at TIMESTAMPTZ,
    error_message TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CHECK (step_status IN ('queued', 'running', 'success', 'failed', 'cancelled')),
    UNIQUE (run_id, step_order)
);

CREATE TABLE corpus_documents (
    document_id TEXT PRIMARY KEY,
    source_id TEXT NOT NULL REFERENCES corpus_sources (source_id),
    product_name TEXT NOT NULL,
    version_label TEXT,
    canonical_url TEXT NOT NULL,
    title TEXT NOT NULL,
    section_path_text TEXT,
    heading_hierarchy_json JSONB NOT NULL DEFAULT '[]'::jsonb,
    raw_checksum TEXT NOT NULL,
    cleaned_checksum TEXT NOT NULL,
    raw_text TEXT NOT NULL,
    cleaned_text TEXT NOT NULL,
    language_code TEXT NOT NULL DEFAULT 'en',
    content_type TEXT NOT NULL,
    collected_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    normalized_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    superseded_by_document_id TEXT REFERENCES corpus_documents (document_id),
    import_run_id UUID REFERENCES corpus_runs (run_id),
    metadata_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE corpus_sections (
    section_id TEXT PRIMARY KEY,
    document_id TEXT NOT NULL REFERENCES corpus_documents (document_id) ON DELETE CASCADE,
    parent_section_id TEXT REFERENCES corpus_sections (section_id),
    heading_level INTEGER,
    heading_text TEXT NOT NULL,
    section_order INTEGER NOT NULL,
    section_path_text TEXT NOT NULL,
    content_text TEXT NOT NULL,
    code_block_count INTEGER NOT NULL DEFAULT 0,
    table_count INTEGER NOT NULL DEFAULT 0,
    list_count INTEGER NOT NULL DEFAULT 0,
    import_run_id UUID REFERENCES corpus_runs (run_id),
    structural_blocks_json JSONB NOT NULL DEFAULT '[]'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (document_id, section_order),
    UNIQUE (document_id, section_path_text)
);

CREATE TABLE corpus_chunks (
    chunk_id TEXT PRIMARY KEY,
    document_id TEXT NOT NULL REFERENCES corpus_documents (document_id) ON DELETE CASCADE,
    section_id TEXT REFERENCES corpus_sections (section_id) ON DELETE SET NULL,
    chunk_index_in_document INTEGER NOT NULL,
    chunk_index_in_section INTEGER NOT NULL,
    section_path_text TEXT NOT NULL,
    chunk_text TEXT NOT NULL,
    char_len INTEGER NOT NULL,
    token_len INTEGER NOT NULL,
    overlap_from_prev_chars INTEGER NOT NULL DEFAULT 0,
    previous_chunk_id TEXT,
    next_chunk_id TEXT,
    code_presence BOOLEAN NOT NULL DEFAULT FALSE,
    table_presence BOOLEAN NOT NULL DEFAULT FALSE,
    list_presence BOOLEAN NOT NULL DEFAULT FALSE,
    product_name TEXT NOT NULL,
    version_label TEXT,
    content_checksum TEXT NOT NULL,
    import_run_id UUID NOT NULL REFERENCES corpus_runs (run_id),
    metadata_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (document_id, chunk_index_in_document)
);

ALTER TABLE corpus_chunks
    ADD CONSTRAINT fk_corpus_chunks_previous
    FOREIGN KEY (previous_chunk_id)
    REFERENCES corpus_chunks (chunk_id)
    DEFERRABLE INITIALLY DEFERRED;

ALTER TABLE corpus_chunks
    ADD CONSTRAINT fk_corpus_chunks_next
    FOREIGN KEY (next_chunk_id)
    REFERENCES corpus_chunks (chunk_id)
    DEFERRABLE INITIALLY DEFERRED;

CREATE TABLE corpus_chunk_relations (
    relation_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    source_chunk_id TEXT NOT NULL REFERENCES corpus_chunks (chunk_id) ON DELETE CASCADE,
    target_chunk_id TEXT NOT NULL REFERENCES corpus_chunks (chunk_id) ON DELETE CASCADE,
    relation_type TEXT NOT NULL,
    distance_in_doc INTEGER,
    import_run_id UUID REFERENCES corpus_runs (run_id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CHECK (relation_type IN ('near', 'far', 'same_section', 'same_document')),
    UNIQUE (source_chunk_id, target_chunk_id, relation_type)
);

CREATE TABLE corpus_glossary_terms (
    term_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    canonical_form TEXT NOT NULL,
    normalized_form TEXT NOT NULL,
    term_type TEXT NOT NULL,
    keep_in_english BOOLEAN NOT NULL DEFAULT TRUE,
    description_short TEXT,
    source_confidence DOUBLE PRECISION NOT NULL DEFAULT 0.0,
    first_seen_document_id TEXT REFERENCES corpus_documents (document_id),
    first_seen_chunk_id TEXT REFERENCES corpus_chunks (chunk_id),
    evidence_count INTEGER NOT NULL DEFAULT 0,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    import_run_id UUID REFERENCES corpus_runs (run_id),
    metadata_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CHECK (term_type IN ('product', 'annotation', 'class', 'interface', 'config_key', 'cli', 'artifact', 'api', 'property', 'concept')),
    UNIQUE (term_type, normalized_form)
);

CREATE TABLE corpus_glossary_aliases (
    alias_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    term_id UUID NOT NULL REFERENCES corpus_glossary_terms (term_id) ON DELETE CASCADE,
    alias_text TEXT NOT NULL,
    alias_language TEXT NOT NULL DEFAULT 'en',
    alias_type TEXT NOT NULL,
    import_run_id UUID REFERENCES corpus_runs (run_id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CHECK (alias_type IN ('same_case', 'kebab', 'dotted', 'spaced', 'translated', 'abbreviation')),
    UNIQUE (term_id, alias_text, alias_language)
);

CREATE TABLE corpus_glossary_evidence (
    evidence_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    term_id UUID NOT NULL REFERENCES corpus_glossary_terms (term_id) ON DELETE CASCADE,
    document_id TEXT NOT NULL REFERENCES corpus_documents (document_id) ON DELETE CASCADE,
    chunk_id TEXT NOT NULL REFERENCES corpus_chunks (chunk_id) ON DELETE CASCADE,
    matched_text TEXT NOT NULL,
    line_or_offset_info JSONB NOT NULL DEFAULT '{}'::jsonb,
    import_run_id UUID REFERENCES corpus_runs (run_id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE UNIQUE INDEX uq_corpus_documents_canonical_url_active
    ON corpus_documents (canonical_url)
    WHERE is_active;

CREATE INDEX idx_corpus_runs_status ON corpus_runs (run_status, started_at DESC);
CREATE INDEX idx_corpus_run_steps_run_id ON corpus_run_steps (run_id, step_order);
CREATE INDEX idx_corpus_documents_source_id ON corpus_documents (source_id, product_name, version_label);
CREATE INDEX idx_corpus_documents_run_id ON corpus_documents (import_run_id);
CREATE INDEX idx_corpus_sections_document_id ON corpus_sections (document_id, section_order);
CREATE INDEX idx_corpus_sections_run_id ON corpus_sections (import_run_id);
CREATE INDEX idx_corpus_chunks_document_idx ON corpus_chunks (document_id, chunk_index_in_document);
CREATE INDEX idx_corpus_chunks_section_idx ON corpus_chunks (section_id, chunk_index_in_section);
CREATE INDEX idx_corpus_chunks_run_id ON corpus_chunks (import_run_id);
CREATE INDEX idx_corpus_chunk_relations_source_idx ON corpus_chunk_relations (source_chunk_id, relation_type);
CREATE INDEX idx_corpus_chunk_relations_target_idx ON corpus_chunk_relations (target_chunk_id, relation_type);
CREATE INDEX idx_corpus_chunk_relations_run_id ON corpus_chunk_relations (import_run_id);
CREATE INDEX idx_corpus_glossary_terms_canonical_form ON corpus_glossary_terms (canonical_form);
CREATE INDEX idx_corpus_glossary_terms_run_id ON corpus_glossary_terms (import_run_id);
CREATE INDEX idx_corpus_glossary_aliases_alias_text ON corpus_glossary_aliases (alias_text);
CREATE INDEX idx_corpus_glossary_aliases_run_id ON corpus_glossary_aliases (import_run_id);
CREATE INDEX idx_corpus_glossary_evidence_term_id ON corpus_glossary_evidence (term_id);
CREATE INDEX idx_corpus_glossary_evidence_chunk_id ON corpus_glossary_evidence (chunk_id);
CREATE INDEX idx_corpus_glossary_evidence_run_id ON corpus_glossary_evidence (import_run_id);

CREATE INDEX idx_corpus_documents_title_trgm
    ON corpus_documents
    USING gin (title gin_trgm_ops);

CREATE INDEX idx_corpus_documents_cleaned_text_trgm
    ON corpus_documents
    USING gin (cleaned_text gin_trgm_ops);

CREATE INDEX idx_corpus_sections_heading_text_trgm
    ON corpus_sections
    USING gin (heading_text gin_trgm_ops);

CREATE INDEX idx_corpus_chunks_chunk_text_trgm
    ON corpus_chunks
    USING gin (chunk_text gin_trgm_ops);

CREATE INDEX idx_corpus_glossary_terms_canonical_form_trgm
    ON corpus_glossary_terms
    USING gin (canonical_form gin_trgm_ops);

CREATE INDEX idx_corpus_glossary_aliases_alias_text_trgm
    ON corpus_glossary_aliases
    USING gin (alias_text gin_trgm_ops);
