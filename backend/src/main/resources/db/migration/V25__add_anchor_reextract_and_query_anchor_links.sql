CREATE TABLE IF NOT EXISTS synthetic_query_anchor_link (
    synthetic_query_id TEXT NOT NULL REFERENCES synthetic_query_registry (synthetic_query_id) ON DELETE CASCADE,
    term_id UUID NOT NULL REFERENCES corpus_glossary_terms (term_id) ON DELETE CASCADE,
    source_chunk_id TEXT NOT NULL REFERENCES corpus_chunks (chunk_id) ON DELETE CASCADE,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (synthetic_query_id, term_id, source_chunk_id)
);

CREATE INDEX IF NOT EXISTS idx_synthetic_query_anchor_link_query
    ON synthetic_query_anchor_link (synthetic_query_id, is_active, updated_at DESC);

CREATE INDEX IF NOT EXISTS idx_synthetic_query_anchor_link_term
    ON synthetic_query_anchor_link (term_id, is_active, updated_at DESC);

CREATE INDEX IF NOT EXISTS idx_synthetic_query_anchor_link_chunk
    ON synthetic_query_anchor_link (source_chunk_id, is_active, updated_at DESC);

WITH query_chunks AS (
    SELECT r.synthetic_query_id,
           r.chunk_id_source AS chunk_id
    FROM synthetic_queries_raw_all r
    WHERE r.chunk_id_source IS NOT NULL
    UNION
    SELECT r.synthetic_query_id,
           elements.value AS chunk_id
    FROM synthetic_queries_raw_all r
    JOIN LATERAL jsonb_array_elements_text(COALESCE(r.source_chunk_ids, '[]'::jsonb)) elements ON TRUE
),
term_candidates AS (
    SELECT DISTINCT
           qc.synthetic_query_id,
           e.term_id,
           qc.chunk_id
    FROM query_chunks qc
    JOIN corpus_glossary_evidence e
      ON e.chunk_id = qc.chunk_id
    JOIN corpus_glossary_terms t
      ON t.term_id = e.term_id
     AND t.is_active = TRUE
)
INSERT INTO synthetic_query_anchor_link (
    synthetic_query_id,
    term_id,
    source_chunk_id,
    is_active,
    created_at,
    updated_at
)
SELECT tc.synthetic_query_id,
       tc.term_id,
       tc.chunk_id,
       TRUE,
       NOW(),
       NOW()
FROM term_candidates tc
ON CONFLICT (synthetic_query_id, term_id, source_chunk_id) DO UPDATE
SET is_active = TRUE,
    updated_at = NOW();
