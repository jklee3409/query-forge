ALTER TABLE synthetic_query_generation_method
    DROP CONSTRAINT IF EXISTS synthetic_query_generation_method_method_code_check;

ALTER TABLE synthetic_query_generation_method
    ADD CONSTRAINT synthetic_query_generation_method_method_code_check
        CHECK (method_code IN ('A', 'B', 'C', 'D', 'E'));

CREATE TABLE IF NOT EXISTS synthetic_queries_raw_e (
    LIKE synthetic_queries_raw_d INCLUDING DEFAULTS INCLUDING CONSTRAINTS INCLUDING STORAGE INCLUDING COMMENTS
);

ALTER TABLE synthetic_queries_raw_e
    ADD COLUMN IF NOT EXISTS generation_method_id UUID REFERENCES synthetic_query_generation_method (generation_method_id),
    ADD COLUMN IF NOT EXISTS generation_batch_id UUID REFERENCES synthetic_query_generation_batch (batch_id),
    ADD COLUMN IF NOT EXISTS prompt_template_version TEXT,
    ADD COLUMN IF NOT EXISTS language_profile TEXT,
    ADD COLUMN IF NOT EXISTS source_chunk_group_id UUID,
    ADD COLUMN IF NOT EXISTS normalized_query_text TEXT,
    ADD COLUMN IF NOT EXISTS llm_provider TEXT,
    ADD COLUMN IF NOT EXISTS llm_model TEXT,
    ADD COLUMN IF NOT EXISTS source_fingerprint TEXT,
    ADD COLUMN IF NOT EXISTS generation_asset_ids JSONB NOT NULL DEFAULT '[]'::jsonb;

CREATE INDEX IF NOT EXISTS idx_synthetic_queries_raw_e_chunk_id_source
    ON synthetic_queries_raw_e (chunk_id_source);

CREATE INDEX IF NOT EXISTS idx_synthetic_queries_raw_e_batch
    ON synthetic_queries_raw_e (generation_batch_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_synthetic_queries_raw_e_method
    ON synthetic_queries_raw_e (generation_method_id, created_at DESC);

UPDATE synthetic_queries_raw_e
SET generation_strategy = 'E'
WHERE generation_strategy IS DISTINCT FROM 'E';

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'synthetic_queries_raw_e_pkey'
    ) THEN
        ALTER TABLE synthetic_queries_raw_e
            ADD CONSTRAINT synthetic_queries_raw_e_pkey PRIMARY KEY (synthetic_query_id);
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'ck_synthetic_queries_raw_e_strategy'
    ) THEN
        ALTER TABLE synthetic_queries_raw_e
            ADD CONSTRAINT ck_synthetic_queries_raw_e_strategy
                CHECK (generation_strategy = 'E');
    END IF;
END $$;

ALTER TABLE synthetic_query_registry
    DROP CONSTRAINT IF EXISTS synthetic_query_registry_generation_strategy_check;

ALTER TABLE synthetic_query_registry
    ADD CONSTRAINT synthetic_query_registry_generation_strategy_check
        CHECK (generation_strategy IN ('A', 'B', 'C', 'D', 'E'));

INSERT INTO synthetic_query_registry (
    synthetic_query_id,
    generation_strategy,
    created_at
)
SELECT synthetic_query_id, generation_strategy, created_at
FROM synthetic_queries_raw_e
ON CONFLICT (synthetic_query_id) DO UPDATE
SET generation_strategy = EXCLUDED.generation_strategy;

DROP TRIGGER IF EXISTS trg_sync_registry_from_raw_e ON synthetic_queries_raw_e;

CREATE TRIGGER trg_sync_registry_from_raw_e
AFTER INSERT OR UPDATE OR DELETE
ON synthetic_queries_raw_e
FOR EACH ROW
EXECUTE FUNCTION sync_synthetic_query_registry();

CREATE OR REPLACE FUNCTION sync_synthetic_query_registry()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP IN ('INSERT', 'UPDATE') THEN
        INSERT INTO synthetic_query_registry (
            synthetic_query_id,
            generation_strategy,
            created_at
        )
        VALUES (
            NEW.synthetic_query_id,
            NEW.generation_strategy,
            COALESCE(NEW.created_at, NOW())
        )
        ON CONFLICT (synthetic_query_id) DO UPDATE
        SET generation_strategy = EXCLUDED.generation_strategy;
        RETURN NEW;
    END IF;

    IF TG_OP = 'DELETE' THEN
        DELETE FROM synthetic_query_registry reg
        WHERE reg.synthetic_query_id = OLD.synthetic_query_id
          AND NOT EXISTS (SELECT 1 FROM synthetic_queries_raw_a WHERE synthetic_query_id = OLD.synthetic_query_id)
          AND NOT EXISTS (SELECT 1 FROM synthetic_queries_raw_b WHERE synthetic_query_id = OLD.synthetic_query_id)
          AND NOT EXISTS (SELECT 1 FROM synthetic_queries_raw_c WHERE synthetic_query_id = OLD.synthetic_query_id)
          AND NOT EXISTS (SELECT 1 FROM synthetic_queries_raw_d WHERE synthetic_query_id = OLD.synthetic_query_id)
          AND NOT EXISTS (SELECT 1 FROM synthetic_queries_raw_e WHERE synthetic_query_id = OLD.synthetic_query_id);
        RETURN OLD;
    END IF;

    RETURN NULL;
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
SELECT * FROM synthetic_queries_raw_e;

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
VALUES (
    'E',
    'E (EN Native Query)',
    'English-native synthetic developer queries generated directly for English user-query rewrite evaluation.',
    TRUE,
    'v1',
    'extractive_en',
    'none',
    'en_only',
    'technical terms keep english',
    jsonb_build_object(
        'language', 'en',
        'paired_comparison_goal', 'english_user_query_vs_korean_user_query',
        'added_at', '2026-04-28'
    )
)
ON CONFLICT (method_code) DO UPDATE
SET method_name = EXCLUDED.method_name,
    description = EXCLUDED.description,
    active = EXCLUDED.active,
    prompt_template_version = EXCLUDED.prompt_template_version,
    summary_strategy = EXCLUDED.summary_strategy,
    translation_strategy = EXCLUDED.translation_strategy,
    query_language_strategy = EXCLUDED.query_language_strategy,
    terminology_preservation_rule = EXCLUDED.terminology_preservation_rule,
    metadata = EXCLUDED.metadata,
    updated_at = NOW();

ALTER TABLE eval_samples
    ADD COLUMN IF NOT EXISTS user_query_en TEXT,
    ADD COLUMN IF NOT EXISTS query_language TEXT NOT NULL DEFAULT 'ko';

ALTER TABLE eval_samples
    DROP CONSTRAINT IF EXISTS eval_samples_query_language_check;

ALTER TABLE eval_samples
    ADD CONSTRAINT eval_samples_query_language_check
        CHECK (query_language IN ('ko', 'en'));

UPDATE eval_samples
SET query_language = CASE
    WHEN COALESCE(NULLIF(trim(user_query_en), ''), '') <> '' AND COALESCE(NULLIF(trim(user_query_ko), ''), '') = '' THEN 'en'
    ELSE 'ko'
END
WHERE query_language IS NULL
   OR query_language NOT IN ('ko', 'en');

INSERT INTO eval_dataset (
    dataset_id,
    dataset_key,
    dataset_name,
    description,
    version,
    split_strategy,
    total_items,
    category_distribution,
    single_multi_distribution,
    metadata
)
VALUES (
    '8f0d6e0f-6f9e-4d64-9b07-f4e8ce5ebec0',
    'human_eval_short_user_80_en',
    'English Short User Eval 80',
    'English short-user evaluation dataset for same-domain English technical document retrieval experiments.',
    'v1-seeded',
    'test_only',
    0,
    '{}'::jsonb,
    '{}'::jsonb,
    jsonb_build_object(
        'query_language', 'en',
        'dataset_family', 'short_user_80',
        'created_by_migration', 'V21'
    )
)
ON CONFLICT (dataset_key) DO UPDATE
SET dataset_name = EXCLUDED.dataset_name,
    description = EXCLUDED.description,
    metadata = eval_dataset.metadata || EXCLUDED.metadata,
    updated_at = NOW();
