CREATE TABLE IF NOT EXISTS tech_doc_domain (
    domain_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    domain_key TEXT NOT NULL UNIQUE,
    display_name TEXT NOT NULL,
    description TEXT,
    primary_language TEXT,
    source_language TEXT,
    status TEXT NOT NULL DEFAULT 'active',
    metadata_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_by TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CHECK (status IN ('active', 'archived'))
);

CREATE TABLE IF NOT EXISTS tech_doc_domain_source (
    domain_id UUID NOT NULL REFERENCES tech_doc_domain(domain_id) ON DELETE CASCADE,
    source_id TEXT NOT NULL REFERENCES corpus_sources(source_id) ON DELETE CASCADE,
    source_role TEXT NOT NULL DEFAULT 'primary',
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (domain_id, source_id),
    UNIQUE (source_id),
    CHECK (source_role IN ('primary', 'supplemental', 'legacy'))
);

CREATE TABLE IF NOT EXISTS tech_doc_domain_method_policy (
    domain_id UUID NOT NULL REFERENCES tech_doc_domain(domain_id) ON DELETE CASCADE,
    method_code TEXT NOT NULL REFERENCES synthetic_query_generation_method(method_code),
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    default_query_language TEXT,
    metadata_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (domain_id, method_code)
);

ALTER TABLE prompt_assets
    ADD COLUMN IF NOT EXISTS storage_backend TEXT NOT NULL DEFAULT 'file',
    ADD COLUMN IF NOT EXISTS content_body TEXT,
    ADD COLUMN IF NOT EXISTS parent_prompt_asset_id UUID REFERENCES prompt_assets(prompt_asset_id),
    ADD COLUMN IF NOT EXISTS updated_by TEXT,
    ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW();

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'ck_prompt_assets_storage_backend'
    ) THEN
        ALTER TABLE prompt_assets
            ADD CONSTRAINT ck_prompt_assets_storage_backend
                CHECK (storage_backend IN ('file', 'db'));
    END IF;
END $$;

CREATE TABLE IF NOT EXISTS prompt_asset_binding (
    binding_key TEXT PRIMARY KEY,
    prompt_family TEXT NOT NULL,
    active_prompt_asset_id UUID NOT NULL REFERENCES prompt_assets(prompt_asset_id),
    fallback_prompt_asset_ids JSONB NOT NULL DEFAULT '[]'::jsonb,
    description TEXT,
    metadata_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    updated_by TEXT,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

INSERT INTO tech_doc_domain (
    domain_key,
    display_name,
    description,
    primary_language,
    source_language,
    metadata_json,
    created_by
)
VALUES
    (
        'spring',
        'Spring',
        'Spring Framework, Boot, Data, Security technical document domain',
        'ko',
        'en',
        '{"seed": "V35", "initial": true}'::jsonb,
        'migration'
    ),
    (
        'python',
        'Python',
        'Python Korean technical document domain',
        'ko',
        'ko',
        '{"seed": "V35", "initial": true}'::jsonb,
        'migration'
    )
ON CONFLICT (domain_key) DO UPDATE
SET display_name = EXCLUDED.display_name,
    description = EXCLUDED.description,
    primary_language = EXCLUDED.primary_language,
    source_language = EXCLUDED.source_language,
    updated_at = NOW();

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
SELECT d.domain_id, s.source_id, 'primary', TRUE
FROM source_mapping s
JOIN tech_doc_domain d ON d.domain_key = s.domain_key
JOIN corpus_sources cs ON cs.source_id = s.source_id
ON CONFLICT (source_id) DO UPDATE
SET domain_id = EXCLUDED.domain_id,
    source_role = EXCLUDED.source_role,
    active = EXCLUDED.active;

INSERT INTO tech_doc_domain_method_policy (
    domain_id,
    method_code,
    enabled,
    default_query_language,
    metadata_json
)
SELECT d.domain_id,
       v.method_code,
       TRUE,
       v.default_query_language,
       '{"seed": "V35"}'::jsonb
FROM tech_doc_domain d
JOIN (
    VALUES
        ('spring', 'A', 'ko'),
        ('spring', 'B', 'ko'),
        ('spring', 'C', 'ko'),
        ('spring', 'D', 'ko'),
        ('spring', 'E', 'en'),
        ('python', 'F', 'en'),
        ('python', 'G', 'ko')
) AS v(domain_key, method_code, default_query_language)
    ON v.domain_key = d.domain_key
JOIN synthetic_query_generation_method m
    ON m.method_code = v.method_code
ON CONFLICT (domain_id, method_code) DO UPDATE
SET enabled = EXCLUDED.enabled,
    default_query_language = EXCLUDED.default_query_language,
    metadata_json = tech_doc_domain_method_policy.metadata_json || EXCLUDED.metadata_json;

INSERT INTO prompt_assets (
    prompt_family,
    prompt_name,
    version,
    content_path,
    content_hash,
    is_active,
    metadata,
    storage_backend
)
VALUES
    ('query_generation', 'gen_a_v1', 'v4', 'configs/prompts/query_generation/gen_a_v1.md', 'seed:configs/prompts/query_generation/gen_a_v1.md', TRUE, '{"seed": "V35"}'::jsonb, 'file'),
    ('query_generation', 'gen_b_v1', 'v5', 'configs/prompts/query_generation/gen_b_v1.md', 'seed:configs/prompts/query_generation/gen_b_v1.md', TRUE, '{"seed": "V35"}'::jsonb, 'file'),
    ('query_generation', 'gen_c_v1', 'v4', 'configs/prompts/query_generation/gen_c_v1.md', 'seed:configs/prompts/query_generation/gen_c_v1.md', TRUE, '{"seed": "V35"}'::jsonb, 'file'),
    ('query_generation', 'gen_d_v1', 'v4', 'configs/prompts/query_generation/gen_d_v1.md', 'seed:configs/prompts/query_generation/gen_d_v1.md', TRUE, '{"seed": "V35"}'::jsonb, 'file'),
    ('query_generation', 'gen_e_v1', 'v1', 'configs/prompts/query_generation/gen_e_v1.md', 'seed:configs/prompts/query_generation/gen_e_v1.md', TRUE, '{"seed": "V35"}'::jsonb, 'file'),
    ('query_generation', 'gen_f_v1', 'v1', 'configs/prompts/query_generation/gen_f_v1.md', 'seed:configs/prompts/query_generation/gen_f_v1.md', TRUE, '{"seed": "V35"}'::jsonb, 'file'),
    ('query_generation', 'gen_g_v1', 'v1', 'configs/prompts/query_generation/gen_g_v1.md', 'seed:configs/prompts/query_generation/gen_g_v1.md', TRUE, '{"seed": "V35"}'::jsonb, 'file'),
    ('rewrite', 'selective_rewrite_v2', 'v2', 'configs/prompts/rewrite/selective_rewrite_v2.md', 'seed:configs/prompts/rewrite/selective_rewrite_v2.md', TRUE, '{"seed": "V35"}'::jsonb, 'file'),
    ('rewrite', 'selective_rewrite_v1', 'v1', 'configs/prompts/rewrite/selective_rewrite_v1.md', 'seed:configs/prompts/rewrite/selective_rewrite_v1.md', TRUE, '{"seed": "V35"}'::jsonb, 'file'),
    ('rewrite', 'selective_rewrite_en_v1', 'v1', 'configs/prompts/rewrite/selective_rewrite_en_v1.md', 'seed:configs/prompts/rewrite/selective_rewrite_en_v1.md', TRUE, '{"seed": "V35"}'::jsonb, 'file')
ON CONFLICT (prompt_family, prompt_name, version) DO NOTHING;

INSERT INTO prompt_asset_binding (
    binding_key,
    prompt_family,
    active_prompt_asset_id,
    description,
    metadata_json
)
SELECT v.binding_key,
       p.prompt_family,
       p.prompt_asset_id,
       v.description,
       '{"seed": "V35"}'::jsonb
FROM (
    VALUES
        ('query_generation.A', 'query_generation', 'gen_a_v1', 'v4', 'Strategy A synthetic query generation prompt'),
        ('query_generation.B', 'query_generation', 'gen_b_v1', 'v5', 'Strategy B synthetic query generation prompt'),
        ('query_generation.C', 'query_generation', 'gen_c_v1', 'v4', 'Strategy C synthetic query generation prompt'),
        ('query_generation.D', 'query_generation', 'gen_d_v1', 'v4', 'Strategy D synthetic query generation prompt'),
        ('query_generation.E', 'query_generation', 'gen_e_v1', 'v1', 'Strategy E synthetic query generation prompt'),
        ('query_generation.F', 'query_generation', 'gen_f_v1', 'v1', 'Strategy F synthetic query generation prompt'),
        ('query_generation.G', 'query_generation', 'gen_g_v1', 'v1', 'Strategy G synthetic query generation prompt'),
        ('rag_rewrite.ko', 'rewrite', 'selective_rewrite_v2', 'v2', 'Korean and code-mixed RAG rewrite prompt'),
        ('rag_rewrite.en', 'rewrite', 'selective_rewrite_en_v1', 'v1', 'English RAG rewrite prompt')
) AS v(binding_key, prompt_family, prompt_name, version, description)
JOIN prompt_assets p
    ON p.prompt_family = v.prompt_family
   AND p.prompt_name = v.prompt_name
   AND p.version = v.version
ON CONFLICT (binding_key) DO UPDATE
SET active_prompt_asset_id = EXCLUDED.active_prompt_asset_id,
    description = EXCLUDED.description,
    metadata_json = prompt_asset_binding.metadata_json || EXCLUDED.metadata_json,
    updated_at = NOW();

UPDATE prompt_asset_binding b
SET fallback_prompt_asset_ids = jsonb_build_array(p1.prompt_asset_id)
FROM prompt_assets p1
WHERE b.binding_key = 'rag_rewrite.ko'
  AND p1.prompt_family = 'rewrite'
  AND p1.prompt_name = 'selective_rewrite_v1'
  AND p1.version = 'v1';

UPDATE prompt_asset_binding b
SET fallback_prompt_asset_ids = jsonb_build_array(p1.prompt_asset_id, p2.prompt_asset_id)
FROM prompt_assets p1
JOIN prompt_assets p2
    ON p2.prompt_family = 'rewrite'
   AND p2.prompt_name = 'selective_rewrite_v1'
   AND p2.version = 'v1'
WHERE b.binding_key = 'rag_rewrite.en'
  AND p1.prompt_family = 'rewrite'
  AND p1.prompt_name = 'selective_rewrite_v2'
  AND p1.version = 'v2';

ALTER TABLE IF EXISTS corpus_sources
    ADD COLUMN IF NOT EXISTS domain_id UUID REFERENCES tech_doc_domain(domain_id);
ALTER TABLE IF EXISTS corpus_runs
    ADD COLUMN IF NOT EXISTS domain_id UUID REFERENCES tech_doc_domain(domain_id);
ALTER TABLE IF EXISTS corpus_documents
    ADD COLUMN IF NOT EXISTS domain_id UUID REFERENCES tech_doc_domain(domain_id);
ALTER TABLE IF EXISTS corpus_sections
    ADD COLUMN IF NOT EXISTS domain_id UUID REFERENCES tech_doc_domain(domain_id);
ALTER TABLE IF EXISTS corpus_chunks
    ADD COLUMN IF NOT EXISTS domain_id UUID REFERENCES tech_doc_domain(domain_id);
ALTER TABLE IF EXISTS corpus_chunk_relations
    ADD COLUMN IF NOT EXISTS domain_id UUID REFERENCES tech_doc_domain(domain_id);
ALTER TABLE IF EXISTS corpus_glossary_terms
    ADD COLUMN IF NOT EXISTS domain_id UUID REFERENCES tech_doc_domain(domain_id);
ALTER TABLE IF EXISTS corpus_glossary_aliases
    ADD COLUMN IF NOT EXISTS domain_id UUID REFERENCES tech_doc_domain(domain_id);
ALTER TABLE IF EXISTS corpus_glossary_evidence
    ADD COLUMN IF NOT EXISTS domain_id UUID REFERENCES tech_doc_domain(domain_id);
ALTER TABLE IF EXISTS chunk_generation_asset
    ADD COLUMN IF NOT EXISTS domain_id UUID REFERENCES tech_doc_domain(domain_id);
ALTER TABLE IF EXISTS chunk_embeddings
    ADD COLUMN IF NOT EXISTS domain_id UUID REFERENCES tech_doc_domain(domain_id);

ALTER TABLE IF EXISTS synthetic_query_generation_batch
    ADD COLUMN IF NOT EXISTS domain_id UUID REFERENCES tech_doc_domain(domain_id);
ALTER TABLE IF EXISTS synthetic_queries_raw_a
    ADD COLUMN IF NOT EXISTS domain_id UUID REFERENCES tech_doc_domain(domain_id);
ALTER TABLE IF EXISTS synthetic_queries_raw_b
    ADD COLUMN IF NOT EXISTS domain_id UUID REFERENCES tech_doc_domain(domain_id);
ALTER TABLE IF EXISTS synthetic_queries_raw_c
    ADD COLUMN IF NOT EXISTS domain_id UUID REFERENCES tech_doc_domain(domain_id);
ALTER TABLE IF EXISTS synthetic_queries_raw_d
    ADD COLUMN IF NOT EXISTS domain_id UUID REFERENCES tech_doc_domain(domain_id);
ALTER TABLE IF EXISTS synthetic_queries_raw_e
    ADD COLUMN IF NOT EXISTS domain_id UUID REFERENCES tech_doc_domain(domain_id);
ALTER TABLE IF EXISTS synthetic_queries_raw_f
    ADD COLUMN IF NOT EXISTS domain_id UUID REFERENCES tech_doc_domain(domain_id);
ALTER TABLE IF EXISTS synthetic_queries_raw_g
    ADD COLUMN IF NOT EXISTS domain_id UUID REFERENCES tech_doc_domain(domain_id);
ALTER TABLE IF EXISTS synthetic_query_registry
    ADD COLUMN IF NOT EXISTS domain_id UUID REFERENCES tech_doc_domain(domain_id);
ALTER TABLE IF EXISTS synthetic_query_source_link
    ADD COLUMN IF NOT EXISTS domain_id UUID REFERENCES tech_doc_domain(domain_id);
ALTER TABLE IF EXISTS quality_gating_batch
    ADD COLUMN IF NOT EXISTS domain_id UUID REFERENCES tech_doc_domain(domain_id);
ALTER TABLE IF EXISTS synthetic_queries_gated
    ADD COLUMN IF NOT EXISTS domain_id UUID REFERENCES tech_doc_domain(domain_id);
ALTER TABLE IF EXISTS synthetic_query_gating_result
    ADD COLUMN IF NOT EXISTS domain_id UUID REFERENCES tech_doc_domain(domain_id);
ALTER TABLE IF EXISTS synthetic_query_gating_history
    ADD COLUMN IF NOT EXISTS domain_id UUID REFERENCES tech_doc_domain(domain_id);
ALTER TABLE IF EXISTS memory_entries
    ADD COLUMN IF NOT EXISTS domain_id UUID REFERENCES tech_doc_domain(domain_id);

ALTER TABLE IF EXISTS eval_dataset
    ADD COLUMN IF NOT EXISTS domain_id UUID REFERENCES tech_doc_domain(domain_id);
ALTER TABLE IF EXISTS eval_samples
    ADD COLUMN IF NOT EXISTS domain_id UUID REFERENCES tech_doc_domain(domain_id);
ALTER TABLE IF EXISTS eval_dataset_item
    ADD COLUMN IF NOT EXISTS domain_id UUID REFERENCES tech_doc_domain(domain_id);
ALTER TABLE IF EXISTS rag_test_run
    ADD COLUMN IF NOT EXISTS domain_id UUID REFERENCES tech_doc_domain(domain_id);
ALTER TABLE IF EXISTS rag_test_result_summary
    ADD COLUMN IF NOT EXISTS domain_id UUID REFERENCES tech_doc_domain(domain_id);
ALTER TABLE IF EXISTS rag_test_result_detail
    ADD COLUMN IF NOT EXISTS domain_id UUID REFERENCES tech_doc_domain(domain_id);
ALTER TABLE IF EXISTS llm_job
    ADD COLUMN IF NOT EXISTS domain_id UUID REFERENCES tech_doc_domain(domain_id);

ALTER TABLE IF EXISTS anchor_eval_run
    ADD COLUMN IF NOT EXISTS domain_id UUID REFERENCES tech_doc_domain(domain_id);
ALTER TABLE IF EXISTS anchor_eval_sample
    ADD COLUMN IF NOT EXISTS domain_id UUID REFERENCES tech_doc_domain(domain_id);
ALTER TABLE IF EXISTS anchor_eval_candidate
    ADD COLUMN IF NOT EXISTS domain_id UUID REFERENCES tech_doc_domain(domain_id);
ALTER TABLE IF EXISTS anchor_normalization_run
    ADD COLUMN IF NOT EXISTS domain_id UUID REFERENCES tech_doc_domain(domain_id);
ALTER TABLE IF EXISTS anchor_normalization_candidate
    ADD COLUMN IF NOT EXISTS domain_id UUID REFERENCES tech_doc_domain(domain_id);
ALTER TABLE IF EXISTS canonical_anchor_mapping
    ADD COLUMN IF NOT EXISTS domain_id UUID REFERENCES tech_doc_domain(domain_id);
ALTER TABLE IF EXISTS canonical_anchor_relation_run
    ADD COLUMN IF NOT EXISTS domain_id UUID REFERENCES tech_doc_domain(domain_id);
ALTER TABLE IF EXISTS canonical_anchor_relation
    ADD COLUMN IF NOT EXISTS domain_id UUID REFERENCES tech_doc_domain(domain_id);

UPDATE corpus_sources cs
SET domain_id = tds.domain_id
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

        EXECUTE format(
            'CREATE INDEX IF NOT EXISTS %I ON %I (domain_id, created_at DESC)',
            'idx_' || raw_table || '_domain_created',
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

CREATE OR REPLACE FUNCTION sync_synthetic_query_registry()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP IN ('INSERT', 'UPDATE') THEN
        INSERT INTO synthetic_query_registry (
            synthetic_query_id,
            generation_strategy,
            domain_id,
            created_at
        )
        VALUES (
            NEW.synthetic_query_id,
            NEW.generation_strategy,
            NEW.domain_id,
            COALESCE(NEW.created_at, NOW())
        )
        ON CONFLICT (synthetic_query_id) DO UPDATE
        SET generation_strategy = EXCLUDED.generation_strategy,
            domain_id = COALESCE(EXCLUDED.domain_id, synthetic_query_registry.domain_id);
        RETURN NEW;
    END IF;

    IF TG_OP = 'DELETE' THEN
        DELETE FROM synthetic_query_registry reg
        WHERE reg.synthetic_query_id = OLD.synthetic_query_id
          AND NOT EXISTS (SELECT 1 FROM synthetic_queries_raw_a WHERE synthetic_query_id = OLD.synthetic_query_id)
          AND NOT EXISTS (SELECT 1 FROM synthetic_queries_raw_b WHERE synthetic_query_id = OLD.synthetic_query_id)
          AND NOT EXISTS (SELECT 1 FROM synthetic_queries_raw_c WHERE synthetic_query_id = OLD.synthetic_query_id)
          AND NOT EXISTS (SELECT 1 FROM synthetic_queries_raw_d WHERE synthetic_query_id = OLD.synthetic_query_id)
          AND NOT EXISTS (SELECT 1 FROM synthetic_queries_raw_e WHERE synthetic_query_id = OLD.synthetic_query_id)
          AND NOT EXISTS (SELECT 1 FROM synthetic_queries_raw_f WHERE synthetic_query_id = OLD.synthetic_query_id)
          AND NOT EXISTS (SELECT 1 FROM synthetic_queries_raw_g WHERE synthetic_query_id = OLD.synthetic_query_id);
        RETURN OLD;
    END IF;

    RETURN NULL;
END $$;

UPDATE synthetic_query_registry reg
SET domain_id = raw.domain_id
FROM synthetic_queries_raw_all raw
WHERE reg.synthetic_query_id = raw.synthetic_query_id
  AND raw.domain_id IS NOT NULL
  AND reg.domain_id IS DISTINCT FROM raw.domain_id;

WITH batch_domains AS (
    SELECT generation_batch_id,
           MIN(domain_id::text)::uuid AS domain_id
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

UPDATE eval_samples s
SET domain_id = d.domain_id
FROM tech_doc_domain d
WHERE (
        (d.domain_key = 'python' AND s.source_product = 'python')
        OR (d.domain_key = 'spring' AND s.source_product IN (
            'spring-boot',
            'spring-data-commons',
            'spring-data-jpa',
            'spring-framework',
            'spring-security'
        ))
    )
  AND s.domain_id IS DISTINCT FROM d.domain_id;

WITH dataset_domains AS (
    SELECT i.dataset_id,
           MIN(s.domain_id::text)::uuid AS domain_id
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
SET domain_id = d.domain_id
FROM eval_dataset d
WHERE i.dataset_id = d.dataset_id
  AND d.domain_id IS NOT NULL
  AND i.domain_id IS DISTINCT FROM d.domain_id;

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
    SELECT run_id, MIN(domain_id::text)::uuid AS domain_id
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
    SELECT run_id, MIN(domain_id::text)::uuid AS domain_id
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

CREATE INDEX IF NOT EXISTS idx_tech_doc_domain_status
    ON tech_doc_domain (status, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_tech_doc_domain_source_domain
    ON tech_doc_domain_source (domain_id, active, source_id);
CREATE INDEX IF NOT EXISTS idx_tech_doc_domain_method_domain
    ON tech_doc_domain_method_policy (domain_id, enabled, method_code);
CREATE INDEX IF NOT EXISTS idx_prompt_assets_family_active
    ON prompt_assets (prompt_family, is_active, updated_at DESC);
CREATE INDEX IF NOT EXISTS idx_prompt_asset_binding_family
    ON prompt_asset_binding (prompt_family, updated_at DESC);

CREATE INDEX IF NOT EXISTS idx_corpus_sources_domain
    ON corpus_sources (domain_id, enabled, source_id);
CREATE INDEX IF NOT EXISTS idx_corpus_documents_domain
    ON corpus_documents (domain_id, source_id, is_active);
CREATE INDEX IF NOT EXISTS idx_corpus_chunks_domain
    ON corpus_chunks (domain_id, document_id);
CREATE INDEX IF NOT EXISTS idx_corpus_glossary_terms_domain
    ON corpus_glossary_terms (domain_id, is_active, canonical_form);
CREATE INDEX IF NOT EXISTS idx_synthetic_query_generation_batch_domain
    ON synthetic_query_generation_batch (domain_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_synthetic_query_registry_domain
    ON synthetic_query_registry (domain_id, generation_strategy, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_quality_gating_batch_domain
    ON quality_gating_batch (domain_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_synthetic_queries_gated_domain
    ON synthetic_queries_gated (domain_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_memory_entries_domain
    ON memory_entries (domain_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_eval_dataset_domain
    ON eval_dataset (domain_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_eval_samples_domain
    ON eval_samples (domain_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_rag_test_run_domain
    ON rag_test_run (domain_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_llm_job_domain
    ON llm_job (domain_id, created_at DESC);

COMMENT ON TABLE tech_doc_domain IS
    'First-class technical document domain such as Spring or Python. Runtime artifacts are scoped by domain_id.';

COMMENT ON TABLE prompt_asset_binding IS
    'Global prompt binding catalog. Domain workspaces use active bindings but do not own prompt assets.';
