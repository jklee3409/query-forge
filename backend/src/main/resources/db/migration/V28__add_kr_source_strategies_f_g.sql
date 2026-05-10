ALTER TABLE synthetic_query_generation_method
    DROP CONSTRAINT IF EXISTS synthetic_query_generation_method_method_code_check;

ALTER TABLE synthetic_query_generation_method
    ADD CONSTRAINT synthetic_query_generation_method_method_code_check
        CHECK (method_code IN ('A', 'B', 'C', 'D', 'E', 'F', 'G'));

CREATE TABLE IF NOT EXISTS synthetic_queries_raw_f (
    LIKE synthetic_queries_raw_e INCLUDING DEFAULTS INCLUDING CONSTRAINTS INCLUDING STORAGE INCLUDING COMMENTS
);

CREATE TABLE IF NOT EXISTS synthetic_queries_raw_g (
    LIKE synthetic_queries_raw_d INCLUDING DEFAULTS INCLUDING CONSTRAINTS INCLUDING STORAGE INCLUDING COMMENTS
);

ALTER TABLE synthetic_queries_raw_f
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

ALTER TABLE synthetic_queries_raw_g
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

ALTER TABLE synthetic_queries_raw_f
    DROP CONSTRAINT IF EXISTS ck_synthetic_queries_raw_e_strategy;

ALTER TABLE synthetic_queries_raw_f
    DROP CONSTRAINT IF EXISTS ck_synthetic_queries_raw_d_strategy;

ALTER TABLE synthetic_queries_raw_f
    DROP CONSTRAINT IF EXISTS synthetic_queries_raw_generation_strategy_check;

ALTER TABLE synthetic_queries_raw_f
    DROP CONSTRAINT IF EXISTS synthetic_queries_raw_e_pkey;

ALTER TABLE synthetic_queries_raw_f
    DROP CONSTRAINT IF EXISTS synthetic_queries_raw_d_pkey;

ALTER TABLE synthetic_queries_raw_f
    DROP CONSTRAINT IF EXISTS synthetic_queries_raw_e_generation_method_id_fkey;

ALTER TABLE synthetic_queries_raw_f
    DROP CONSTRAINT IF EXISTS synthetic_queries_raw_e_generation_batch_id_fkey;

ALTER TABLE synthetic_queries_raw_f
    DROP CONSTRAINT IF EXISTS synthetic_queries_raw_d_generation_method_id_fkey;

ALTER TABLE synthetic_queries_raw_f
    DROP CONSTRAINT IF EXISTS synthetic_queries_raw_d_generation_batch_id_fkey;

ALTER TABLE synthetic_queries_raw_g
    DROP CONSTRAINT IF EXISTS ck_synthetic_queries_raw_e_strategy;

ALTER TABLE synthetic_queries_raw_g
    DROP CONSTRAINT IF EXISTS ck_synthetic_queries_raw_d_strategy;

ALTER TABLE synthetic_queries_raw_g
    DROP CONSTRAINT IF EXISTS synthetic_queries_raw_generation_strategy_check;

ALTER TABLE synthetic_queries_raw_g
    DROP CONSTRAINT IF EXISTS synthetic_queries_raw_e_pkey;

ALTER TABLE synthetic_queries_raw_g
    DROP CONSTRAINT IF EXISTS synthetic_queries_raw_d_pkey;

ALTER TABLE synthetic_queries_raw_g
    DROP CONSTRAINT IF EXISTS synthetic_queries_raw_e_generation_method_id_fkey;

ALTER TABLE synthetic_queries_raw_g
    DROP CONSTRAINT IF EXISTS synthetic_queries_raw_e_generation_batch_id_fkey;

ALTER TABLE synthetic_queries_raw_g
    DROP CONSTRAINT IF EXISTS synthetic_queries_raw_d_generation_method_id_fkey;

ALTER TABLE synthetic_queries_raw_g
    DROP CONSTRAINT IF EXISTS synthetic_queries_raw_d_generation_batch_id_fkey;

UPDATE synthetic_queries_raw_f
SET generation_strategy = 'F'
WHERE generation_strategy IS DISTINCT FROM 'F';

UPDATE synthetic_queries_raw_g
SET generation_strategy = 'G'
WHERE generation_strategy IS DISTINCT FROM 'G';

UPDATE synthetic_queries_raw_f
SET query_language = 'en'
WHERE query_language IS DISTINCT FROM 'en';

UPDATE synthetic_queries_raw_g
SET query_language = 'ko'
WHERE query_language IS DISTINCT FROM 'ko';

ALTER TABLE synthetic_queries_raw_f
    ALTER COLUMN query_language SET DEFAULT 'en';

ALTER TABLE synthetic_queries_raw_g
    ALTER COLUMN query_language SET DEFAULT 'ko';

CREATE INDEX IF NOT EXISTS idx_synthetic_queries_raw_f_chunk_id_source
    ON synthetic_queries_raw_f (chunk_id_source);

CREATE INDEX IF NOT EXISTS idx_synthetic_queries_raw_g_chunk_id_source
    ON synthetic_queries_raw_g (chunk_id_source);

CREATE INDEX IF NOT EXISTS idx_synthetic_queries_raw_f_batch
    ON synthetic_queries_raw_f (generation_batch_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_synthetic_queries_raw_g_batch
    ON synthetic_queries_raw_g (generation_batch_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_synthetic_queries_raw_f_method
    ON synthetic_queries_raw_f (generation_method_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_synthetic_queries_raw_g_method
    ON synthetic_queries_raw_g (generation_method_id, created_at DESC);

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conrelid = 'synthetic_queries_raw_f'::regclass
          AND contype = 'p'
    ) THEN
        ALTER TABLE synthetic_queries_raw_f
            ADD CONSTRAINT synthetic_queries_raw_f_pkey PRIMARY KEY (synthetic_query_id);
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conrelid = 'synthetic_queries_raw_f'::regclass
          AND conname = 'ck_synthetic_queries_raw_f_strategy'
    ) THEN
        ALTER TABLE synthetic_queries_raw_f
            ADD CONSTRAINT ck_synthetic_queries_raw_f_strategy
                CHECK (generation_strategy = 'F');
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conrelid = 'synthetic_queries_raw_f'::regclass
          AND conname = 'synthetic_queries_raw_generation_strategy_check'
    ) THEN
        ALTER TABLE synthetic_queries_raw_f
            ADD CONSTRAINT synthetic_queries_raw_generation_strategy_check
                CHECK (generation_strategy IN ('A', 'B', 'C', 'D', 'E', 'F', 'G'));
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conrelid = 'synthetic_queries_raw_f'::regclass
          AND conname = 'synthetic_queries_raw_f_generation_method_id_fkey'
    ) THEN
        ALTER TABLE synthetic_queries_raw_f
            ADD CONSTRAINT synthetic_queries_raw_f_generation_method_id_fkey
                FOREIGN KEY (generation_method_id)
                REFERENCES synthetic_query_generation_method (generation_method_id);
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conrelid = 'synthetic_queries_raw_f'::regclass
          AND conname = 'synthetic_queries_raw_f_generation_batch_id_fkey'
    ) THEN
        ALTER TABLE synthetic_queries_raw_f
            ADD CONSTRAINT synthetic_queries_raw_f_generation_batch_id_fkey
                FOREIGN KEY (generation_batch_id)
                REFERENCES synthetic_query_generation_batch (batch_id);
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conrelid = 'synthetic_queries_raw_g'::regclass
          AND contype = 'p'
    ) THEN
        ALTER TABLE synthetic_queries_raw_g
            ADD CONSTRAINT synthetic_queries_raw_g_pkey PRIMARY KEY (synthetic_query_id);
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conrelid = 'synthetic_queries_raw_g'::regclass
          AND conname = 'ck_synthetic_queries_raw_g_strategy'
    ) THEN
        ALTER TABLE synthetic_queries_raw_g
            ADD CONSTRAINT ck_synthetic_queries_raw_g_strategy
                CHECK (generation_strategy = 'G');
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conrelid = 'synthetic_queries_raw_g'::regclass
          AND conname = 'synthetic_queries_raw_generation_strategy_check'
    ) THEN
        ALTER TABLE synthetic_queries_raw_g
            ADD CONSTRAINT synthetic_queries_raw_generation_strategy_check
                CHECK (generation_strategy IN ('A', 'B', 'C', 'D', 'E', 'F', 'G'));
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conrelid = 'synthetic_queries_raw_g'::regclass
          AND conname = 'synthetic_queries_raw_g_generation_method_id_fkey'
    ) THEN
        ALTER TABLE synthetic_queries_raw_g
            ADD CONSTRAINT synthetic_queries_raw_g_generation_method_id_fkey
                FOREIGN KEY (generation_method_id)
                REFERENCES synthetic_query_generation_method (generation_method_id);
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conrelid = 'synthetic_queries_raw_g'::regclass
          AND conname = 'synthetic_queries_raw_g_generation_batch_id_fkey'
    ) THEN
        ALTER TABLE synthetic_queries_raw_g
            ADD CONSTRAINT synthetic_queries_raw_g_generation_batch_id_fkey
                FOREIGN KEY (generation_batch_id)
                REFERENCES synthetic_query_generation_batch (batch_id);
    END IF;
END $$;

ALTER TABLE synthetic_query_registry
    DROP CONSTRAINT IF EXISTS synthetic_query_registry_generation_strategy_check;

ALTER TABLE synthetic_query_registry
    ADD CONSTRAINT synthetic_query_registry_generation_strategy_check
        CHECK (generation_strategy IN ('A', 'B', 'C', 'D', 'E', 'F', 'G'));

INSERT INTO synthetic_query_registry (
    synthetic_query_id,
    generation_strategy,
    created_at
)
SELECT synthetic_query_id, generation_strategy, created_at
FROM synthetic_queries_raw_f
ON CONFLICT (synthetic_query_id) DO UPDATE
SET generation_strategy = EXCLUDED.generation_strategy;

INSERT INTO synthetic_query_registry (
    synthetic_query_id,
    generation_strategy,
    created_at
)
SELECT synthetic_query_id, generation_strategy, created_at
FROM synthetic_queries_raw_g
ON CONFLICT (synthetic_query_id) DO UPDATE
SET generation_strategy = EXCLUDED.generation_strategy;

DROP TRIGGER IF EXISTS trg_sync_registry_from_raw_f ON synthetic_queries_raw_f;

CREATE TRIGGER trg_sync_registry_from_raw_f
AFTER INSERT OR UPDATE OR DELETE
ON synthetic_queries_raw_f
FOR EACH ROW
EXECUTE FUNCTION sync_synthetic_query_registry();

DROP TRIGGER IF EXISTS trg_sync_registry_from_raw_g ON synthetic_queries_raw_g;

CREATE TRIGGER trg_sync_registry_from_raw_g
AFTER INSERT OR UPDATE OR DELETE
ON synthetic_queries_raw_g
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
          AND NOT EXISTS (SELECT 1 FROM synthetic_queries_raw_e WHERE synthetic_query_id = OLD.synthetic_query_id)
          AND NOT EXISTS (SELECT 1 FROM synthetic_queries_raw_f WHERE synthetic_query_id = OLD.synthetic_query_id)
          AND NOT EXISTS (SELECT 1 FROM synthetic_queries_raw_g WHERE synthetic_query_id = OLD.synthetic_query_id);
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
SELECT * FROM synthetic_queries_raw_e
UNION ALL
SELECT * FROM synthetic_queries_raw_f
UNION ALL
SELECT * FROM synthetic_queries_raw_g;

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
    'F',
    'F (KR Source -> EN Query)',
    'Korean technical document path: KR extractive summary -> KR synthetic query -> EN synthetic query for cross-language comparison.',
    TRUE,
    'v1',
    'extractive_ko',
    'ko_query_to_en_query',
    'ko_to_en',
    'preserve technical anchors from korean source',
    jsonb_build_object(
        'language', 'en',
        'source_document_language', 'ko',
        'added_at', '2026-05-10'
    )
),
(
    'G',
    'G (KR Source -> KR Query)',
    'Korean technical document path: KR extractive summary -> KR synthetic query for same-language KR-source retrieval evaluation.',
    TRUE,
    'v1',
    'extractive_ko',
    'none',
    'ko_only',
    'preserve technical anchors from korean source',
    jsonb_build_object(
        'language', 'ko',
        'source_document_language', 'ko',
        'added_at', '2026-05-10'
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
