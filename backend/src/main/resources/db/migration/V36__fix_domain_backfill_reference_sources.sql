-- Repair domain backfill gaps from V35:
-- 1. Canonical corpus source rows may be created by config sync after Flyway.
-- 2. Existing eval samples may store source_product as reference source ids.

WITH source_mapping(domain_key, source_id) AS (
    VALUES
        ('spring', 'spring-boot-reference'),
        ('spring', 'spring-data-commons-reference'),
        ('spring', 'spring-data-jpa-reference'),
        ('spring', 'spring-framework-reference'),
        ('spring', 'spring-security-reference'),
        ('python', 'docs-python-org-ko-3-14')
)
INSERT INTO tech_doc_domain_source (domain_id, source_id, source_role, active)
SELECT d.domain_id, cs.source_id, 'primary', TRUE
FROM source_mapping sm
JOIN tech_doc_domain d ON d.domain_key = sm.domain_key
JOIN corpus_sources cs ON cs.source_id = sm.source_id
ON CONFLICT (source_id) DO UPDATE
SET domain_id = EXCLUDED.domain_id,
    source_role = EXCLUDED.source_role,
    active = EXCLUDED.active;

UPDATE corpus_sources cs
SET domain_id = tds.domain_id,
    updated_at = NOW()
FROM tech_doc_domain_source tds
WHERE cs.source_id = tds.source_id
  AND cs.domain_id IS DISTINCT FROM tds.domain_id;

UPDATE corpus_documents d
SET domain_id = s.domain_id
FROM corpus_sources s
WHERE d.source_id = s.source_id
  AND s.domain_id IS NOT NULL
  AND d.domain_id IS DISTINCT FROM s.domain_id;

UPDATE corpus_sections s
SET domain_id = d.domain_id
FROM corpus_documents d
WHERE s.document_id = d.document_id
  AND d.domain_id IS NOT NULL
  AND s.domain_id IS DISTINCT FROM d.domain_id;

UPDATE corpus_chunks c
SET domain_id = d.domain_id
FROM corpus_documents d
WHERE c.document_id = d.document_id
  AND d.domain_id IS NOT NULL
  AND c.domain_id IS DISTINCT FROM d.domain_id;

UPDATE corpus_chunk_relations r
SET domain_id = c.domain_id
FROM corpus_chunks c
WHERE r.source_chunk_id = c.chunk_id
  AND c.domain_id IS NOT NULL
  AND r.domain_id IS DISTINCT FROM c.domain_id;

UPDATE corpus_glossary_terms t
SET domain_id = d.domain_id
FROM corpus_documents d
WHERE t.first_seen_document_id = d.document_id
  AND d.domain_id IS NOT NULL
  AND t.domain_id IS DISTINCT FROM d.domain_id;

UPDATE corpus_glossary_terms t
SET domain_id = c.domain_id
FROM corpus_chunks c
WHERE t.first_seen_chunk_id = c.chunk_id
  AND c.domain_id IS NOT NULL
  AND t.domain_id IS DISTINCT FROM c.domain_id;

UPDATE corpus_glossary_evidence e
SET domain_id = c.domain_id
FROM corpus_chunks c
WHERE e.chunk_id = c.chunk_id
  AND c.domain_id IS NOT NULL
  AND e.domain_id IS DISTINCT FROM c.domain_id;

UPDATE corpus_glossary_terms t
SET domain_id = e.domain_id
FROM corpus_glossary_evidence e
WHERE t.term_id = e.term_id
  AND e.domain_id IS NOT NULL
  AND t.domain_id IS NULL;

UPDATE corpus_glossary_aliases a
SET domain_id = t.domain_id
FROM corpus_glossary_terms t
WHERE a.term_id = t.term_id
  AND t.domain_id IS NOT NULL
  AND a.domain_id IS DISTINCT FROM t.domain_id;

UPDATE chunk_generation_asset a
SET domain_id = c.domain_id
FROM corpus_chunks c
WHERE a.chunk_id = c.chunk_id
  AND c.domain_id IS NOT NULL
  AND a.domain_id IS DISTINCT FROM c.domain_id;

UPDATE chunk_generation_asset a
SET domain_id = d.domain_id
FROM corpus_documents d
WHERE a.source_document_id = d.document_id
  AND d.domain_id IS NOT NULL
  AND a.domain_id IS DISTINCT FROM d.domain_id;

UPDATE chunk_embeddings e
SET domain_id = c.domain_id
FROM corpus_chunks c
WHERE e.chunk_id = c.chunk_id
  AND c.domain_id IS NOT NULL
  AND e.domain_id IS DISTINCT FROM c.domain_id;

DO $$
DECLARE
    raw_table TEXT;
BEGIN
    FOREACH raw_table IN ARRAY ARRAY[
        'synthetic_queries_raw_a',
        'synthetic_queries_raw_b',
        'synthetic_queries_raw_c',
        'synthetic_queries_raw_d',
        'synthetic_queries_raw_e',
        'synthetic_queries_raw_f',
        'synthetic_queries_raw_g'
    ]
    LOOP
        EXECUTE format(
            'UPDATE %I r
             SET domain_id = c.domain_id
             FROM corpus_chunks c
             WHERE r.chunk_id_source = c.chunk_id
               AND c.domain_id IS NOT NULL
               AND r.domain_id IS DISTINCT FROM c.domain_id',
            raw_table
        );
    END LOOP;
END $$;

CREATE OR REPLACE VIEW synthetic_queries_raw_all AS
SELECT * FROM synthetic_queries_raw_a
UNION ALL
SELECT * FROM synthetic_queries_raw_b
UNION ALL
SELECT * FROM synthetic_queries_raw_c
UNION ALL
SELECT * FROM synthetic_queries_raw_d
UNION ALL
SELECT * FROM synthetic_queries_raw_e
UNION ALL
SELECT * FROM synthetic_queries_raw_f
UNION ALL
SELECT * FROM synthetic_queries_raw_g;

UPDATE synthetic_query_registry reg
SET domain_id = raw.domain_id
FROM synthetic_queries_raw_all raw
WHERE reg.synthetic_query_id = raw.synthetic_query_id
  AND raw.domain_id IS NOT NULL
  AND reg.domain_id IS DISTINCT FROM raw.domain_id;

WITH batch_domains AS (
    SELECT generation_batch_id,
           MIN(domain_id) AS domain_id
    FROM synthetic_queries_raw_all
    WHERE generation_batch_id IS NOT NULL
      AND domain_id IS NOT NULL
    GROUP BY generation_batch_id
    HAVING COUNT(DISTINCT domain_id) = 1
)
UPDATE synthetic_query_generation_batch b
SET domain_id = bd.domain_id
FROM batch_domains bd
WHERE b.batch_id = bd.generation_batch_id
  AND b.domain_id IS DISTINCT FROM bd.domain_id;

UPDATE quality_gating_batch gb
SET domain_id = b.domain_id
FROM synthetic_query_generation_batch b
WHERE gb.generation_batch_id = b.batch_id
  AND b.domain_id IS NOT NULL
  AND gb.domain_id IS DISTINCT FROM b.domain_id;

UPDATE synthetic_queries_gated g
SET domain_id = reg.domain_id
FROM synthetic_query_registry reg
WHERE g.synthetic_query_id = reg.synthetic_query_id
  AND reg.domain_id IS NOT NULL
  AND g.domain_id IS DISTINCT FROM reg.domain_id;

UPDATE synthetic_query_gating_result gr
SET domain_id = gb.domain_id
FROM quality_gating_batch gb
WHERE gr.gating_batch_id = gb.gating_batch_id
  AND gb.domain_id IS NOT NULL
  AND gr.domain_id IS DISTINCT FROM gb.domain_id;

UPDATE synthetic_query_gating_result gr
SET domain_id = reg.domain_id
FROM synthetic_query_registry reg
WHERE gr.synthetic_query_id = reg.synthetic_query_id
  AND reg.domain_id IS NOT NULL
  AND gr.domain_id IS NULL;

UPDATE synthetic_query_gating_history gh
SET domain_id = gb.domain_id
FROM quality_gating_batch gb
WHERE gh.gating_batch_id = gb.gating_batch_id
  AND gb.domain_id IS NOT NULL
  AND gh.domain_id IS DISTINCT FROM gb.domain_id;

UPDATE synthetic_query_gating_history gh
SET domain_id = reg.domain_id
FROM synthetic_query_registry reg
WHERE gh.synthetic_query_id = reg.synthetic_query_id
  AND reg.domain_id IS NOT NULL
  AND gh.domain_id IS NULL;

UPDATE memory_entries m
SET domain_id = g.domain_id
FROM synthetic_queries_gated g
WHERE m.source_gated_query_id = g.gated_query_id
  AND g.domain_id IS NOT NULL
  AND m.domain_id IS DISTINCT FROM g.domain_id;

WITH domain_alias(domain_key, alias) AS (
    VALUES
        ('spring', 'spring'),
        ('spring', 'spring-boot'),
        ('spring', 'spring-boot-reference'),
        ('spring', 'spring-data'),
        ('spring', 'spring-data-commons'),
        ('spring', 'spring-data-commons-reference'),
        ('spring', 'spring-data-jpa'),
        ('spring', 'spring-data-jpa-reference'),
        ('spring', 'spring-framework'),
        ('spring', 'spring-framework-reference'),
        ('spring', 'spring-security'),
        ('spring', 'spring-security-reference'),
        ('python', 'python'),
        ('python', 'python-reference'),
        ('python', 'docs-python-org-ko-3-14')
),
sample_candidates AS (
    SELECT s.sample_id, d.domain_id
    FROM eval_samples s
    JOIN domain_alias a ON LOWER(COALESCE(s.source_product, '')) = a.alias
    JOIN tech_doc_domain d ON d.domain_key = a.domain_key
    UNION
    SELECT s.sample_id, d.domain_id
    FROM eval_samples s
    JOIN domain_alias a ON LOWER(COALESCE(s.metadata ->> 'source_product', '')) = a.alias
        OR LOWER(COALESCE(s.metadata ->> 'source_id', '')) = a.alias
        OR LOWER(COALESCE(s.metadata ->> 'product', '')) = a.alias
    JOIN tech_doc_domain d ON d.domain_key = a.domain_key
    UNION
    SELECT s.sample_id, d.domain_id
    FROM eval_samples s
    CROSS JOIN LATERAL jsonb_array_elements_text(
        CASE
            WHEN jsonb_typeof(s.expected_doc_ids) = 'array' THEN s.expected_doc_ids
            ELSE '[]'::jsonb
        END
    ) expected_doc(doc_id)
    JOIN corpus_documents cd ON cd.document_id = expected_doc.doc_id
    JOIN tech_doc_domain d ON d.domain_id = cd.domain_id
    WHERE cd.domain_id IS NOT NULL
    UNION
    SELECT s.sample_id, d.domain_id
    FROM eval_samples s
    CROSS JOIN LATERAL jsonb_array_elements_text(
        CASE
            WHEN jsonb_typeof(s.expected_chunk_ids) = 'array' THEN s.expected_chunk_ids
            ELSE '[]'::jsonb
        END
    ) expected_chunk(chunk_id)
    JOIN corpus_chunks cc ON cc.chunk_id = expected_chunk.chunk_id
    JOIN tech_doc_domain d ON d.domain_id = cc.domain_id
    WHERE cc.domain_id IS NOT NULL
),
sample_domains AS (
    SELECT sample_id,
           MIN(domain_id) AS domain_id
    FROM sample_candidates
    GROUP BY sample_id
    HAVING COUNT(DISTINCT domain_id) = 1
)
UPDATE eval_samples s
SET domain_id = sd.domain_id
FROM sample_domains sd
WHERE s.sample_id = sd.sample_id
  AND s.domain_id IS DISTINCT FROM sd.domain_id;

WITH dataset_domains AS (
    SELECT i.dataset_id,
           MIN(s.domain_id) AS domain_id
    FROM eval_dataset_item i
    JOIN eval_samples s ON s.sample_id = i.sample_id
    WHERE s.domain_id IS NOT NULL
      AND i.active IS TRUE
    GROUP BY i.dataset_id
    HAVING COUNT(DISTINCT s.domain_id) = 1
)
UPDATE eval_dataset d
SET domain_id = dd.domain_id
FROM dataset_domains dd
WHERE d.dataset_id = dd.dataset_id
  AND d.domain_id IS DISTINCT FROM dd.domain_id;

UPDATE eval_dataset_item i
SET domain_id = s.domain_id
FROM eval_samples s
WHERE i.sample_id = s.sample_id
  AND s.domain_id IS NOT NULL
  AND i.domain_id IS DISTINCT FROM s.domain_id;

UPDATE rag_test_run r
SET domain_id = d.domain_id
FROM eval_dataset d
WHERE r.dataset_id = d.dataset_id
  AND d.domain_id IS NOT NULL
  AND r.domain_id IS DISTINCT FROM d.domain_id;

UPDATE rag_test_result_summary s
SET domain_id = r.domain_id
FROM rag_test_run r
WHERE s.rag_test_run_id = r.rag_test_run_id
  AND r.domain_id IS NOT NULL
  AND s.domain_id IS DISTINCT FROM r.domain_id;

UPDATE rag_test_result_detail d
SET domain_id = r.domain_id
FROM rag_test_run r
WHERE d.rag_test_run_id = r.rag_test_run_id
  AND r.domain_id IS NOT NULL
  AND d.domain_id IS DISTINCT FROM r.domain_id;

UPDATE llm_job j
SET domain_id = qb.domain_id
FROM synthetic_query_generation_batch qb
WHERE j.generation_batch_id = qb.batch_id
  AND qb.domain_id IS NOT NULL
  AND j.domain_id IS DISTINCT FROM qb.domain_id;

UPDATE llm_job j
SET domain_id = gb.domain_id
FROM quality_gating_batch gb
WHERE j.gating_batch_id = gb.gating_batch_id
  AND gb.domain_id IS NOT NULL
  AND j.domain_id IS DISTINCT FROM gb.domain_id;

UPDATE llm_job j
SET domain_id = rr.domain_id
FROM rag_test_run rr
WHERE j.rag_test_run_id = rr.rag_test_run_id
  AND rr.domain_id IS NOT NULL
  AND j.domain_id IS DISTINCT FROM rr.domain_id;

UPDATE anchor_eval_run r
SET domain_id = s.domain_id
FROM corpus_sources s
WHERE r.source_id = s.source_id
  AND s.domain_id IS NOT NULL
  AND r.domain_id IS DISTINCT FROM s.domain_id;

UPDATE anchor_eval_sample s
SET domain_id = c.domain_id
FROM corpus_chunks c
WHERE s.chunk_id = c.chunk_id
  AND c.domain_id IS NOT NULL
  AND s.domain_id IS DISTINCT FROM c.domain_id;

UPDATE anchor_eval_candidate c
SET domain_id = s.domain_id
FROM anchor_eval_sample s
WHERE c.sample_id = s.sample_id
  AND s.domain_id IS NOT NULL
  AND c.domain_id IS DISTINCT FROM s.domain_id;

UPDATE anchor_normalization_candidate c
SET domain_id = t.domain_id
FROM corpus_glossary_terms t
WHERE c.term_id = t.term_id
  AND t.domain_id IS NOT NULL
  AND c.domain_id IS DISTINCT FROM t.domain_id;

WITH normalization_run_domains AS (
    SELECT run_id, MIN(domain_id) AS domain_id
    FROM anchor_normalization_candidate
    WHERE domain_id IS NOT NULL
    GROUP BY run_id
    HAVING COUNT(DISTINCT domain_id) = 1
)
UPDATE anchor_normalization_run r
SET domain_id = rd.domain_id
FROM normalization_run_domains rd
WHERE r.run_id = rd.run_id
  AND r.domain_id IS DISTINCT FROM rd.domain_id;

UPDATE canonical_anchor_mapping m
SET domain_id = t.domain_id
FROM corpus_glossary_terms t
WHERE m.canonical_term_id = t.term_id
  AND t.domain_id IS NOT NULL
  AND m.domain_id IS DISTINCT FROM t.domain_id;

UPDATE canonical_anchor_relation rel
SET domain_id = t.domain_id
FROM corpus_glossary_terms t
WHERE rel.canonical_anchor_id = t.term_id
  AND t.domain_id IS NOT NULL
  AND rel.domain_id IS DISTINCT FROM t.domain_id;

WITH relation_run_domains AS (
    SELECT run_id, MIN(domain_id) AS domain_id
    FROM canonical_anchor_relation
    WHERE run_id IS NOT NULL
      AND domain_id IS NOT NULL
    GROUP BY run_id
    HAVING COUNT(DISTINCT domain_id) = 1
)
UPDATE canonical_anchor_relation_run r
SET domain_id = rd.domain_id
FROM relation_run_domains rd
WHERE r.run_id = rd.run_id
  AND r.domain_id IS DISTINCT FROM rd.domain_id;
