-- Align strategy tables to latest synthetic raw schema.
ALTER TABLE synthetic_queries_raw_a
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

ALTER TABLE synthetic_queries_raw_b
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

ALTER TABLE synthetic_queries_raw_c
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

ALTER TABLE synthetic_queries_raw_d
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

CREATE INDEX IF NOT EXISTS idx_synthetic_queries_raw_a_batch
    ON synthetic_queries_raw_a (generation_batch_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_synthetic_queries_raw_b_batch
    ON synthetic_queries_raw_b (generation_batch_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_synthetic_queries_raw_c_batch
    ON synthetic_queries_raw_c (generation_batch_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_synthetic_queries_raw_d_batch
    ON synthetic_queries_raw_d (generation_batch_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_synthetic_queries_raw_a_method
    ON synthetic_queries_raw_a (generation_method_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_synthetic_queries_raw_b_method
    ON synthetic_queries_raw_b (generation_method_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_synthetic_queries_raw_c_method
    ON synthetic_queries_raw_c (generation_method_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_synthetic_queries_raw_d_method
    ON synthetic_queries_raw_d (generation_method_id, created_at DESC);

UPDATE synthetic_queries_raw_a SET generation_strategy = 'A' WHERE generation_strategy IS DISTINCT FROM 'A';
UPDATE synthetic_queries_raw_b SET generation_strategy = 'B' WHERE generation_strategy IS DISTINCT FROM 'B';
UPDATE synthetic_queries_raw_c SET generation_strategy = 'C' WHERE generation_strategy IS DISTINCT FROM 'C';
UPDATE synthetic_queries_raw_d SET generation_strategy = 'D' WHERE generation_strategy IS DISTINCT FROM 'D';

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'ck_synthetic_queries_raw_a_strategy'
    ) THEN
        ALTER TABLE synthetic_queries_raw_a
            ADD CONSTRAINT ck_synthetic_queries_raw_a_strategy
                CHECK (generation_strategy = 'A');
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'ck_synthetic_queries_raw_b_strategy'
    ) THEN
        ALTER TABLE synthetic_queries_raw_b
            ADD CONSTRAINT ck_synthetic_queries_raw_b_strategy
                CHECK (generation_strategy = 'B');
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'ck_synthetic_queries_raw_c_strategy'
    ) THEN
        ALTER TABLE synthetic_queries_raw_c
            ADD CONSTRAINT ck_synthetic_queries_raw_c_strategy
                CHECK (generation_strategy = 'C');
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'ck_synthetic_queries_raw_d_strategy'
    ) THEN
        ALTER TABLE synthetic_queries_raw_d
            ADD CONSTRAINT ck_synthetic_queries_raw_d_strategy
                CHECK (generation_strategy = 'D');
    END IF;
END $$;

CREATE TABLE IF NOT EXISTS synthetic_query_registry (
    synthetic_query_id TEXT PRIMARY KEY,
    generation_strategy TEXT NOT NULL CHECK (generation_strategy IN ('A', 'B', 'C', 'D')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_synthetic_query_registry_strategy
    ON synthetic_query_registry (generation_strategy, created_at DESC);

-- Migrate legacy rows into strategy tables if legacy table is still present.
DO $$
BEGIN
    IF to_regclass('public.synthetic_queries_raw') IS NOT NULL THEN
        INSERT INTO synthetic_queries_raw_a (
            synthetic_query_id,
            experiment_run_id,
            generation_method_id,
            generation_batch_id,
            chunk_id_source,
            source_chunk_group_id,
            target_doc_id,
            target_chunk_ids,
            answerability_type,
            query_text,
            normalized_query_text,
            query_language,
            language_profile,
            query_type,
            generation_strategy,
            prompt_asset_id,
            prompt_template_version,
            prompt_version,
            prompt_hash,
            source_summary,
            source_fingerprint,
            source_chunk_ids,
            glossary_terms,
            llm_provider,
            llm_model,
            generation_asset_ids,
            llm_output,
            metadata,
            created_at
        )
        SELECT
            synthetic_query_id,
            experiment_run_id,
            generation_method_id,
            generation_batch_id,
            chunk_id_source,
            source_chunk_group_id,
            target_doc_id,
            target_chunk_ids,
            answerability_type,
            query_text,
            normalized_query_text,
            query_language,
            language_profile,
            query_type,
            generation_strategy,
            prompt_asset_id,
            prompt_template_version,
            prompt_version,
            prompt_hash,
            source_summary,
            source_fingerprint,
            source_chunk_ids,
            glossary_terms,
            llm_provider,
            llm_model,
            generation_asset_ids,
            llm_output,
            metadata,
            created_at
        FROM synthetic_queries_raw
        WHERE generation_strategy = 'A'
        ON CONFLICT (synthetic_query_id) DO UPDATE
        SET experiment_run_id = EXCLUDED.experiment_run_id,
            generation_method_id = EXCLUDED.generation_method_id,
            generation_batch_id = EXCLUDED.generation_batch_id,
            chunk_id_source = EXCLUDED.chunk_id_source,
            source_chunk_group_id = EXCLUDED.source_chunk_group_id,
            target_doc_id = EXCLUDED.target_doc_id,
            target_chunk_ids = EXCLUDED.target_chunk_ids,
            answerability_type = EXCLUDED.answerability_type,
            query_text = EXCLUDED.query_text,
            normalized_query_text = EXCLUDED.normalized_query_text,
            query_language = EXCLUDED.query_language,
            language_profile = EXCLUDED.language_profile,
            query_type = EXCLUDED.query_type,
            generation_strategy = EXCLUDED.generation_strategy,
            prompt_asset_id = EXCLUDED.prompt_asset_id,
            prompt_template_version = EXCLUDED.prompt_template_version,
            prompt_version = EXCLUDED.prompt_version,
            prompt_hash = EXCLUDED.prompt_hash,
            source_summary = EXCLUDED.source_summary,
            source_fingerprint = EXCLUDED.source_fingerprint,
            source_chunk_ids = EXCLUDED.source_chunk_ids,
            glossary_terms = EXCLUDED.glossary_terms,
            llm_provider = EXCLUDED.llm_provider,
            llm_model = EXCLUDED.llm_model,
            generation_asset_ids = EXCLUDED.generation_asset_ids,
            llm_output = EXCLUDED.llm_output,
            metadata = EXCLUDED.metadata;

        INSERT INTO synthetic_queries_raw_b (
            synthetic_query_id,
            experiment_run_id,
            generation_method_id,
            generation_batch_id,
            chunk_id_source,
            source_chunk_group_id,
            target_doc_id,
            target_chunk_ids,
            answerability_type,
            query_text,
            normalized_query_text,
            query_language,
            language_profile,
            query_type,
            generation_strategy,
            prompt_asset_id,
            prompt_template_version,
            prompt_version,
            prompt_hash,
            source_summary,
            source_fingerprint,
            source_chunk_ids,
            glossary_terms,
            llm_provider,
            llm_model,
            generation_asset_ids,
            llm_output,
            metadata,
            created_at
        )
        SELECT
            synthetic_query_id,
            experiment_run_id,
            generation_method_id,
            generation_batch_id,
            chunk_id_source,
            source_chunk_group_id,
            target_doc_id,
            target_chunk_ids,
            answerability_type,
            query_text,
            normalized_query_text,
            query_language,
            language_profile,
            query_type,
            generation_strategy,
            prompt_asset_id,
            prompt_template_version,
            prompt_version,
            prompt_hash,
            source_summary,
            source_fingerprint,
            source_chunk_ids,
            glossary_terms,
            llm_provider,
            llm_model,
            generation_asset_ids,
            llm_output,
            metadata,
            created_at
        FROM synthetic_queries_raw
        WHERE generation_strategy = 'B'
        ON CONFLICT (synthetic_query_id) DO UPDATE
        SET experiment_run_id = EXCLUDED.experiment_run_id,
            generation_method_id = EXCLUDED.generation_method_id,
            generation_batch_id = EXCLUDED.generation_batch_id,
            chunk_id_source = EXCLUDED.chunk_id_source,
            source_chunk_group_id = EXCLUDED.source_chunk_group_id,
            target_doc_id = EXCLUDED.target_doc_id,
            target_chunk_ids = EXCLUDED.target_chunk_ids,
            answerability_type = EXCLUDED.answerability_type,
            query_text = EXCLUDED.query_text,
            normalized_query_text = EXCLUDED.normalized_query_text,
            query_language = EXCLUDED.query_language,
            language_profile = EXCLUDED.language_profile,
            query_type = EXCLUDED.query_type,
            generation_strategy = EXCLUDED.generation_strategy,
            prompt_asset_id = EXCLUDED.prompt_asset_id,
            prompt_template_version = EXCLUDED.prompt_template_version,
            prompt_version = EXCLUDED.prompt_version,
            prompt_hash = EXCLUDED.prompt_hash,
            source_summary = EXCLUDED.source_summary,
            source_fingerprint = EXCLUDED.source_fingerprint,
            source_chunk_ids = EXCLUDED.source_chunk_ids,
            glossary_terms = EXCLUDED.glossary_terms,
            llm_provider = EXCLUDED.llm_provider,
            llm_model = EXCLUDED.llm_model,
            generation_asset_ids = EXCLUDED.generation_asset_ids,
            llm_output = EXCLUDED.llm_output,
            metadata = EXCLUDED.metadata;

        INSERT INTO synthetic_queries_raw_c (
            synthetic_query_id,
            experiment_run_id,
            generation_method_id,
            generation_batch_id,
            chunk_id_source,
            source_chunk_group_id,
            target_doc_id,
            target_chunk_ids,
            answerability_type,
            query_text,
            normalized_query_text,
            query_language,
            language_profile,
            query_type,
            generation_strategy,
            prompt_asset_id,
            prompt_template_version,
            prompt_version,
            prompt_hash,
            source_summary,
            source_fingerprint,
            source_chunk_ids,
            glossary_terms,
            llm_provider,
            llm_model,
            generation_asset_ids,
            llm_output,
            metadata,
            created_at
        )
        SELECT
            synthetic_query_id,
            experiment_run_id,
            generation_method_id,
            generation_batch_id,
            chunk_id_source,
            source_chunk_group_id,
            target_doc_id,
            target_chunk_ids,
            answerability_type,
            query_text,
            normalized_query_text,
            query_language,
            language_profile,
            query_type,
            generation_strategy,
            prompt_asset_id,
            prompt_template_version,
            prompt_version,
            prompt_hash,
            source_summary,
            source_fingerprint,
            source_chunk_ids,
            glossary_terms,
            llm_provider,
            llm_model,
            generation_asset_ids,
            llm_output,
            metadata,
            created_at
        FROM synthetic_queries_raw
        WHERE generation_strategy = 'C'
        ON CONFLICT (synthetic_query_id) DO UPDATE
        SET experiment_run_id = EXCLUDED.experiment_run_id,
            generation_method_id = EXCLUDED.generation_method_id,
            generation_batch_id = EXCLUDED.generation_batch_id,
            chunk_id_source = EXCLUDED.chunk_id_source,
            source_chunk_group_id = EXCLUDED.source_chunk_group_id,
            target_doc_id = EXCLUDED.target_doc_id,
            target_chunk_ids = EXCLUDED.target_chunk_ids,
            answerability_type = EXCLUDED.answerability_type,
            query_text = EXCLUDED.query_text,
            normalized_query_text = EXCLUDED.normalized_query_text,
            query_language = EXCLUDED.query_language,
            language_profile = EXCLUDED.language_profile,
            query_type = EXCLUDED.query_type,
            generation_strategy = EXCLUDED.generation_strategy,
            prompt_asset_id = EXCLUDED.prompt_asset_id,
            prompt_template_version = EXCLUDED.prompt_template_version,
            prompt_version = EXCLUDED.prompt_version,
            prompt_hash = EXCLUDED.prompt_hash,
            source_summary = EXCLUDED.source_summary,
            source_fingerprint = EXCLUDED.source_fingerprint,
            source_chunk_ids = EXCLUDED.source_chunk_ids,
            glossary_terms = EXCLUDED.glossary_terms,
            llm_provider = EXCLUDED.llm_provider,
            llm_model = EXCLUDED.llm_model,
            generation_asset_ids = EXCLUDED.generation_asset_ids,
            llm_output = EXCLUDED.llm_output,
            metadata = EXCLUDED.metadata;

        INSERT INTO synthetic_queries_raw_d (
            synthetic_query_id,
            experiment_run_id,
            generation_method_id,
            generation_batch_id,
            chunk_id_source,
            source_chunk_group_id,
            target_doc_id,
            target_chunk_ids,
            answerability_type,
            query_text,
            normalized_query_text,
            query_language,
            language_profile,
            query_type,
            generation_strategy,
            prompt_asset_id,
            prompt_template_version,
            prompt_version,
            prompt_hash,
            source_summary,
            source_fingerprint,
            source_chunk_ids,
            glossary_terms,
            llm_provider,
            llm_model,
            generation_asset_ids,
            llm_output,
            metadata,
            created_at
        )
        SELECT
            synthetic_query_id,
            experiment_run_id,
            generation_method_id,
            generation_batch_id,
            chunk_id_source,
            source_chunk_group_id,
            target_doc_id,
            target_chunk_ids,
            answerability_type,
            query_text,
            normalized_query_text,
            query_language,
            language_profile,
            query_type,
            generation_strategy,
            prompt_asset_id,
            prompt_template_version,
            prompt_version,
            prompt_hash,
            source_summary,
            source_fingerprint,
            source_chunk_ids,
            glossary_terms,
            llm_provider,
            llm_model,
            generation_asset_ids,
            llm_output,
            metadata,
            created_at
        FROM synthetic_queries_raw
        WHERE generation_strategy = 'D'
        ON CONFLICT (synthetic_query_id) DO UPDATE
        SET experiment_run_id = EXCLUDED.experiment_run_id,
            generation_method_id = EXCLUDED.generation_method_id,
            generation_batch_id = EXCLUDED.generation_batch_id,
            chunk_id_source = EXCLUDED.chunk_id_source,
            source_chunk_group_id = EXCLUDED.source_chunk_group_id,
            target_doc_id = EXCLUDED.target_doc_id,
            target_chunk_ids = EXCLUDED.target_chunk_ids,
            answerability_type = EXCLUDED.answerability_type,
            query_text = EXCLUDED.query_text,
            normalized_query_text = EXCLUDED.normalized_query_text,
            query_language = EXCLUDED.query_language,
            language_profile = EXCLUDED.language_profile,
            query_type = EXCLUDED.query_type,
            generation_strategy = EXCLUDED.generation_strategy,
            prompt_asset_id = EXCLUDED.prompt_asset_id,
            prompt_template_version = EXCLUDED.prompt_template_version,
            prompt_version = EXCLUDED.prompt_version,
            prompt_hash = EXCLUDED.prompt_hash,
            source_summary = EXCLUDED.source_summary,
            source_fingerprint = EXCLUDED.source_fingerprint,
            source_chunk_ids = EXCLUDED.source_chunk_ids,
            glossary_terms = EXCLUDED.glossary_terms,
            llm_provider = EXCLUDED.llm_provider,
            llm_model = EXCLUDED.llm_model,
            generation_asset_ids = EXCLUDED.generation_asset_ids,
            llm_output = EXCLUDED.llm_output,
            metadata = EXCLUDED.metadata;
    END IF;
END $$;

INSERT INTO synthetic_query_registry (
    synthetic_query_id,
    generation_strategy,
    created_at
)
SELECT synthetic_query_id, generation_strategy, created_at
FROM synthetic_queries_raw_a
UNION ALL
SELECT synthetic_query_id, generation_strategy, created_at
FROM synthetic_queries_raw_b
UNION ALL
SELECT synthetic_query_id, generation_strategy, created_at
FROM synthetic_queries_raw_c
UNION ALL
SELECT synthetic_query_id, generation_strategy, created_at
FROM synthetic_queries_raw_d
ON CONFLICT (synthetic_query_id) DO UPDATE
SET generation_strategy = EXCLUDED.generation_strategy;

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
          AND NOT EXISTS (SELECT 1 FROM synthetic_queries_raw_d WHERE synthetic_query_id = OLD.synthetic_query_id);
        RETURN OLD;
    END IF;

    RETURN NULL;
END $$;

DROP TRIGGER IF EXISTS trg_sync_registry_from_raw_a ON synthetic_queries_raw_a;
DROP TRIGGER IF EXISTS trg_sync_registry_from_raw_b ON synthetic_queries_raw_b;
DROP TRIGGER IF EXISTS trg_sync_registry_from_raw_c ON synthetic_queries_raw_c;
DROP TRIGGER IF EXISTS trg_sync_registry_from_raw_d ON synthetic_queries_raw_d;

CREATE TRIGGER trg_sync_registry_from_raw_a
AFTER INSERT OR UPDATE OR DELETE
ON synthetic_queries_raw_a
FOR EACH ROW
EXECUTE FUNCTION sync_synthetic_query_registry();

CREATE TRIGGER trg_sync_registry_from_raw_b
AFTER INSERT OR UPDATE OR DELETE
ON synthetic_queries_raw_b
FOR EACH ROW
EXECUTE FUNCTION sync_synthetic_query_registry();

CREATE TRIGGER trg_sync_registry_from_raw_c
AFTER INSERT OR UPDATE OR DELETE
ON synthetic_queries_raw_c
FOR EACH ROW
EXECUTE FUNCTION sync_synthetic_query_registry();

CREATE TRIGGER trg_sync_registry_from_raw_d
AFTER INSERT OR UPDATE OR DELETE
ON synthetic_queries_raw_d
FOR EACH ROW
EXECUTE FUNCTION sync_synthetic_query_registry();

ALTER TABLE synthetic_queries_gated
    DROP CONSTRAINT IF EXISTS synthetic_queries_gated_synthetic_query_id_fkey;
ALTER TABLE synthetic_queries_gated
    DROP CONSTRAINT IF EXISTS fk_synthetic_queries_gated_registry;
ALTER TABLE synthetic_query_source_link
    DROP CONSTRAINT IF EXISTS synthetic_query_source_link_synthetic_query_id_fkey;
ALTER TABLE synthetic_query_source_link
    DROP CONSTRAINT IF EXISTS fk_synthetic_query_source_link_registry;
ALTER TABLE synthetic_query_gating_result
    DROP CONSTRAINT IF EXISTS synthetic_query_gating_result_synthetic_query_id_fkey;
ALTER TABLE synthetic_query_gating_result
    DROP CONSTRAINT IF EXISTS fk_synthetic_query_gating_result_registry;
ALTER TABLE synthetic_query_gating_history
    DROP CONSTRAINT IF EXISTS synthetic_query_gating_history_synthetic_query_id_fkey;
ALTER TABLE synthetic_query_gating_history
    DROP CONSTRAINT IF EXISTS fk_synthetic_query_gating_history_registry;

ALTER TABLE synthetic_queries_gated
    ADD CONSTRAINT fk_synthetic_queries_gated_registry
        FOREIGN KEY (synthetic_query_id)
        REFERENCES synthetic_query_registry (synthetic_query_id)
        ON DELETE CASCADE;
ALTER TABLE synthetic_query_source_link
    ADD CONSTRAINT fk_synthetic_query_source_link_registry
        FOREIGN KEY (synthetic_query_id)
        REFERENCES synthetic_query_registry (synthetic_query_id)
        ON DELETE CASCADE;
ALTER TABLE synthetic_query_gating_result
    ADD CONSTRAINT fk_synthetic_query_gating_result_registry
        FOREIGN KEY (synthetic_query_id)
        REFERENCES synthetic_query_registry (synthetic_query_id)
        ON DELETE CASCADE;
ALTER TABLE synthetic_query_gating_history
    ADD CONSTRAINT fk_synthetic_query_gating_history_registry
        FOREIGN KEY (synthetic_query_id)
        REFERENCES synthetic_query_registry (synthetic_query_id)
        ON DELETE CASCADE;

DROP TABLE IF EXISTS synthetic_queries_raw;

CREATE OR REPLACE VIEW synthetic_queries_raw_all AS
SELECT * FROM synthetic_queries_raw_a
UNION ALL
SELECT * FROM synthetic_queries_raw_b
UNION ALL
SELECT * FROM synthetic_queries_raw_c
UNION ALL
SELECT * FROM synthetic_queries_raw_d;
