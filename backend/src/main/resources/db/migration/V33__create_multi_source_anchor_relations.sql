CREATE TABLE IF NOT EXISTS canonical_anchor_relation_run (
    run_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    run_name TEXT NOT NULL,
    relation_version TEXT NOT NULL,
    mapping_version TEXT NOT NULL,
    normalization_version TEXT NOT NULL,
    canonical_anchor_runtime_schema_version TEXT NOT NULL,
    status TEXT NOT NULL DEFAULT 'running',
    relation_type_allowlist JSONB NOT NULL DEFAULT '[]'::jsonb,
    min_relation_score DOUBLE PRECISION NOT NULL DEFAULT 0.0,
    max_relations_per_anchor INTEGER,
    candidate_anchor_count INTEGER NOT NULL DEFAULT 0,
    relation_count INTEGER NOT NULL DEFAULT 0,
    evidence_count INTEGER NOT NULL DEFAULT 0,
    summary_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_by TEXT,
    error_message TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    finished_at TIMESTAMPTZ,
    CHECK (length(trim(run_name)) > 0),
    CHECK (length(trim(relation_version)) > 0),
    CHECK (length(trim(mapping_version)) > 0),
    CHECK (length(trim(normalization_version)) > 0),
    CHECK (length(trim(canonical_anchor_runtime_schema_version)) > 0),
    CHECK (status IN ('running', 'completed', 'failed')),
    CHECK (min_relation_score >= 0.0 AND min_relation_score <= 1.0),
    CHECK (max_relations_per_anchor IS NULL OR max_relations_per_anchor > 0)
);

CREATE TABLE IF NOT EXISTS canonical_anchor_relation (
    relation_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    run_id UUID REFERENCES canonical_anchor_relation_run (run_id) ON DELETE SET NULL,
    relation_version TEXT NOT NULL,
    mapping_version TEXT NOT NULL,
    normalization_version TEXT NOT NULL,
    canonical_anchor_runtime_schema_version TEXT NOT NULL,
    canonical_anchor_id UUID NOT NULL REFERENCES corpus_glossary_terms (term_id) ON DELETE CASCADE,
    related_anchor_id UUID NOT NULL REFERENCES corpus_glossary_terms (term_id) ON DELETE CASCADE,
    relation_type TEXT NOT NULL,
    relation_score DOUBLE PRECISION NOT NULL,
    relation_source TEXT NOT NULL,
    evidence_count INTEGER NOT NULL DEFAULT 0,
    source_query_id TEXT REFERENCES synthetic_query_registry (synthetic_query_id) ON DELETE SET NULL,
    source_chunk_id TEXT REFERENCES corpus_chunks (chunk_id) ON DELETE SET NULL,
    source_section_id TEXT REFERENCES corpus_sections (section_id) ON DELETE SET NULL,
    method_code TEXT,
    status TEXT NOT NULL DEFAULT 'active',
    metadata_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CHECK (length(trim(relation_version)) > 0),
    CHECK (length(trim(mapping_version)) > 0),
    CHECK (length(trim(normalization_version)) > 0),
    CHECK (length(trim(canonical_anchor_runtime_schema_version)) > 0),
    CHECK (canonical_anchor_id <> related_anchor_id),
    CHECK (relation_type IN (
        'canonical_alias',
        'glossary_alias',
        'semantic_similarity',
        'chunk_cooccurrence',
        'section_cooccurrence',
        'synthetic_query_cooccurrence',
        'memory_query_cooccurrence',
        'retrieval_utility'
    )),
    CHECK (relation_score >= 0.0 AND relation_score <= 1.0),
    CHECK (evidence_count >= 0),
    CHECK (status IN ('active', 'superseded', 'disabled'))
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_canonical_anchor_relation_key
    ON canonical_anchor_relation (
        relation_version,
        canonical_anchor_id,
        related_anchor_id,
        relation_type,
        relation_source
    );

CREATE INDEX IF NOT EXISTS idx_canonical_anchor_relation_lookup
    ON canonical_anchor_relation (
        relation_version,
        canonical_anchor_id,
        status,
        relation_score DESC,
        evidence_count DESC
    );

CREATE INDEX IF NOT EXISTS idx_canonical_anchor_relation_related
    ON canonical_anchor_relation (
        relation_version,
        related_anchor_id,
        status,
        relation_score DESC
    );

CREATE INDEX IF NOT EXISTS idx_canonical_anchor_relation_type_source
    ON canonical_anchor_relation (
        relation_version,
        relation_type,
        relation_source,
        status
    );

CREATE INDEX IF NOT EXISTS idx_canonical_anchor_relation_run
    ON canonical_anchor_relation (run_id, status);

ALTER TABLE rag_test_run
    ADD COLUMN IF NOT EXISTS multi_source_anchor_expansion_enabled BOOLEAN NOT NULL DEFAULT FALSE;

COMMENT ON TABLE canonical_anchor_relation_run IS
    'Admin-triggered build history for additive multi-source anchor relation indexes.';

COMMENT ON TABLE canonical_anchor_relation IS
    'Additive runtime lookup table for related canonical anchors. Synthetic query text/data remains immutable.';
