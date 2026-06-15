ALTER TABLE IF EXISTS chat_runtime_config
    ADD COLUMN IF NOT EXISTS source_gating_batch_ids JSONB NOT NULL DEFAULT '[]'::jsonb,
    ADD COLUMN IF NOT EXISTS source_gating_run_ids JSONB NOT NULL DEFAULT '[]'::jsonb;

UPDATE chat_runtime_config
SET source_gating_batch_ids = CASE
        WHEN source_gating_batch_id IS NULL THEN '[]'::jsonb
        ELSE jsonb_build_array(source_gating_batch_id::text)
    END
WHERE source_gating_batch_ids = '[]'::jsonb
  AND source_gating_batch_id IS NOT NULL;

UPDATE chat_runtime_config
SET source_gating_run_ids = CASE
        WHEN source_gating_run_id IS NULL THEN '[]'::jsonb
        ELSE jsonb_build_array(source_gating_run_id::text)
    END
WHERE source_gating_run_ids = '[]'::jsonb
  AND source_gating_run_id IS NOT NULL;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'ck_chat_runtime_config_source_gating_batch_ids_array'
    ) THEN
        ALTER TABLE chat_runtime_config
            ADD CONSTRAINT ck_chat_runtime_config_source_gating_batch_ids_array
                CHECK (jsonb_typeof(source_gating_batch_ids) = 'array');
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'ck_chat_runtime_config_source_gating_run_ids_array'
    ) THEN
        ALTER TABLE chat_runtime_config
            ADD CONSTRAINT ck_chat_runtime_config_source_gating_run_ids_array
                CHECK (jsonb_typeof(source_gating_run_ids) = 'array');
    END IF;
END $$;

COMMENT ON COLUMN chat_runtime_config.source_gating_batch_ids IS
    'Ordered selected completed gating snapshots used by live chat synthetic memory retrieval. The singular source_gating_batch_id remains the primary/backward-compatible snapshot.';

COMMENT ON COLUMN chat_runtime_config.source_gating_run_ids IS
    'Distinct source gating run IDs resolved from source_gating_batch_ids for live chat synthetic memory filtering.';
