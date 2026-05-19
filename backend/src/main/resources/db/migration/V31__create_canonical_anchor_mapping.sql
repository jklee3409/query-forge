CREATE TABLE IF NOT EXISTS canonical_anchor_mapping (
    mapping_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    mapping_version TEXT NOT NULL,
    normalization_version TEXT NOT NULL,
    alias_text TEXT NOT NULL,
    normalized_alias TEXT NOT NULL,
    display_alias TEXT,
    alias_language TEXT NOT NULL,
    canonical_term_id UUID NOT NULL REFERENCES corpus_glossary_terms (term_id) ON DELETE RESTRICT,
    alias_term_id UUID REFERENCES corpus_glossary_terms (term_id) ON DELETE SET NULL,
    confidence DOUBLE PRECISION,
    review_status TEXT NOT NULL DEFAULT 'pending',
    mapping_status TEXT NOT NULL DEFAULT 'active',
    source TEXT,
    provenance JSONB NOT NULL DEFAULT '{}'::jsonb,
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CHECK (length(trim(mapping_version)) > 0),
    CHECK (length(trim(normalization_version)) > 0),
    CHECK (length(trim(alias_text)) > 0),
    CHECK (length(trim(normalized_alias)) > 0),
    CHECK (alias_language IN ('en', 'ko', 'und')),
    CHECK (confidence IS NULL OR (confidence >= 0.0 AND confidence <= 1.0)),
    CHECK (review_status IN ('pending', 'approved', 'rejected')),
    CHECK (mapping_status IN ('active', 'inactive', 'deprecated')),
    CHECK (alias_term_id IS NULL OR alias_term_id <> canonical_term_id)
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_canonical_anchor_mapping_approved_active_alias
    ON canonical_anchor_mapping (
        mapping_version,
        normalization_version,
        alias_language,
        normalized_alias
    )
    WHERE review_status = 'approved'
      AND mapping_status = 'active';

CREATE INDEX IF NOT EXISTS idx_canonical_anchor_mapping_alias_lookup
    ON canonical_anchor_mapping (
        mapping_version,
        normalization_version,
        alias_language,
        normalized_alias,
        review_status,
        mapping_status
    );

CREATE INDEX IF NOT EXISTS idx_canonical_anchor_mapping_canonical_term
    ON canonical_anchor_mapping (canonical_term_id, mapping_status, review_status);

CREATE INDEX IF NOT EXISTS idx_canonical_anchor_mapping_alias_term
    ON canonical_anchor_mapping (alias_term_id)
    WHERE alias_term_id IS NOT NULL;

CREATE OR REPLACE FUNCTION reject_canonical_anchor_mapping_self_row()
RETURNS trigger AS $$
DECLARE
    canonical_normalized TEXT;
BEGIN
    SELECT normalized_form
      INTO canonical_normalized
      FROM corpus_glossary_terms
     WHERE term_id = NEW.canonical_term_id;

    IF canonical_normalized IS NOT NULL
       AND canonical_normalized = NEW.normalized_alias THEN
        RAISE EXCEPTION 'canonical_anchor_mapping must not store canonical self rows: %', NEW.normalized_alias;
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_reject_canonical_anchor_mapping_self_row
    ON canonical_anchor_mapping;

CREATE TRIGGER trg_reject_canonical_anchor_mapping_self_row
    BEFORE INSERT OR UPDATE OF canonical_term_id, normalized_alias
    ON canonical_anchor_mapping
    FOR EACH ROW
    EXECUTE FUNCTION reject_canonical_anchor_mapping_self_row();

COMMENT ON TABLE canonical_anchor_mapping IS
    'Additive alias-to-canonical anchor mapping. Canonical self rows are intentionally not stored.';
