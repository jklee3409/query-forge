DO $$
DECLARE constraint_row RECORD;
BEGIN
    FOR constraint_row IN
        SELECT conname
        FROM pg_constraint
        WHERE conrelid = 'corpus_runs'::regclass
          AND contype = 'c'
          AND pg_get_constraintdef(oid) ILIKE '%run_status%'
    LOOP
        EXECUTE format('ALTER TABLE corpus_runs DROP CONSTRAINT %I', constraint_row.conname);
    END LOOP;
END
$$;

ALTER TABLE corpus_runs
    ADD CONSTRAINT ck_corpus_runs_run_status
        CHECK (run_status IN ('queued', 'running', 'success', 'warning', 'failed', 'cancelled'));

DO $$
DECLARE constraint_row RECORD;
BEGIN
    FOR constraint_row IN
        SELECT conname
        FROM pg_constraint
        WHERE conrelid = 'corpus_run_steps'::regclass
          AND contype = 'c'
          AND pg_get_constraintdef(oid) ILIKE '%step_status%'
    LOOP
        EXECUTE format('ALTER TABLE corpus_run_steps DROP CONSTRAINT %I', constraint_row.conname);
    END LOOP;
END
$$;

ALTER TABLE corpus_run_steps
    ADD CONSTRAINT ck_corpus_run_steps_step_status
        CHECK (step_status IN ('queued', 'running', 'success', 'warning', 'failed', 'cancelled'));

UPDATE corpus_run_steps
SET step_status = 'warning',
    error_message = COALESCE(NULLIF(error_message, ''), 'Step skipped: ' || COALESCE(metrics_json ->> 'reason', 'unknown')),
    updated_at = NOW()
WHERE step_status = 'success'
  AND LOWER(COALESCE(metrics_json ->> 'skipped', 'false')) IN ('true', 't', '1', 'yes');

UPDATE corpus_run_steps
SET step_status = 'warning',
    error_message = COALESCE(NULLIF(error_message, ''), 'Collect completed with fetch failures.'),
    updated_at = NOW()
WHERE step_status = 'success'
  AND step_name = 'collect'
  AND CASE
          WHEN COALESCE(metrics_json ->> 'fetch_failures', '') ~ '^-?\d+$' THEN (metrics_json ->> 'fetch_failures')::INT
          ELSE 0
      END > 0;

UPDATE corpus_run_steps
SET step_status = 'warning',
    error_message = COALESCE(NULLIF(error_message, ''), 'Collect discovered documents but persisted none.'),
    updated_at = NOW()
WHERE step_status = 'success'
  AND step_name = 'collect'
  AND CASE
          WHEN COALESCE(metrics_json ->> 'documents_discovered', '') ~ '^-?\d+$' THEN (metrics_json ->> 'documents_discovered')::INT
          ELSE 0
      END > 0
  AND CASE
          WHEN COALESCE(metrics_json ->> 'documents_persisted', '') ~ '^-?\d+$' THEN (metrics_json ->> 'documents_persisted')::INT
          ELSE 0
      END = 0;

UPDATE corpus_run_steps
SET step_status = 'warning',
    error_message = COALESCE(NULLIF(error_message, ''), 'Normalize processed documents but produced 0 sections.'),
    updated_at = NOW()
WHERE step_status = 'success'
  AND step_name = 'normalize'
  AND CASE
          WHEN COALESCE(metrics_json ->> 'documents_processed', '') ~ '^-?\d+$' THEN (metrics_json ->> 'documents_processed')::INT
          ELSE 0
      END > 0
  AND CASE
          WHEN COALESCE(metrics_json ->> 'sections_written', '') ~ '^-?\d+$' THEN (metrics_json ->> 'sections_written')::INT
          ELSE 0
      END = 0;

UPDATE corpus_run_steps
SET step_status = 'warning',
    error_message = COALESCE(NULLIF(error_message, ''), 'Chunk processed documents but produced 0 chunks.'),
    updated_at = NOW()
WHERE step_status = 'success'
  AND step_name = 'chunk'
  AND CASE
          WHEN COALESCE(metrics_json ->> 'documents_processed', '') ~ '^-?\d+$' THEN (metrics_json ->> 'documents_processed')::INT
          ELSE 0
      END > 0
  AND CASE
          WHEN COALESCE(metrics_json ->> 'chunks_written', '') ~ '^-?\d+$' THEN (metrics_json ->> 'chunks_written')::INT
          ELSE 0
      END = 0;

UPDATE corpus_run_steps
SET step_status = 'warning',
    error_message = COALESCE(NULLIF(error_message, ''), 'Glossary processed documents but produced 0 terms.'),
    updated_at = NOW()
WHERE step_status = 'success'
  AND step_name = 'glossary'
  AND CASE
          WHEN COALESCE(metrics_json ->> 'documents_processed', '') ~ '^-?\d+$' THEN (metrics_json ->> 'documents_processed')::INT
          ELSE 0
      END > 0
  AND CASE
          WHEN COALESCE(metrics_json ->> 'glossary_terms_written', '') ~ '^-?\d+$' THEN (metrics_json ->> 'glossary_terms_written')::INT
          ELSE 0
      END = 0;

WITH warning_runs AS (
    SELECT run_id, COUNT(*) AS warning_step_count
    FROM corpus_run_steps
    WHERE step_status = 'warning'
    GROUP BY run_id
)
UPDATE corpus_runs r
SET run_status = CASE WHEN r.run_status = 'success' THEN 'warning' ELSE r.run_status END,
    error_message = CASE
        WHEN r.run_status = 'success' THEN COALESCE(NULLIF(r.error_message, ''), 'Completed with warnings.')
        ELSE r.error_message
    END,
    summary_json = COALESCE(r.summary_json, '{}'::jsonb)
        || jsonb_build_object(
            'warning', TRUE,
            'warning_step_count', warning_runs.warning_step_count
        ),
    updated_at = NOW()
FROM warning_runs
WHERE r.run_id = warning_runs.run_id;
