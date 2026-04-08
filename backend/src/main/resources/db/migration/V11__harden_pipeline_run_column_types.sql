ALTER TABLE corpus_runs
    ALTER COLUMN source_scope TYPE jsonb USING COALESCE(NULLIF(source_scope::text, ''), '{}')::jsonb,
    ALTER COLUMN config_snapshot TYPE jsonb USING COALESCE(NULLIF(config_snapshot::text, ''), '{}')::jsonb,
    ALTER COLUMN summary_json TYPE jsonb USING COALESCE(NULLIF(summary_json::text, ''), '{}')::jsonb,
    ALTER COLUMN error_message TYPE TEXT,
    ALTER COLUMN created_by TYPE TEXT;

ALTER TABLE corpus_runs
    ALTER COLUMN source_scope SET DEFAULT '{}'::jsonb,
    ALTER COLUMN config_snapshot SET DEFAULT '{}'::jsonb,
    ALTER COLUMN summary_json SET DEFAULT '{}'::jsonb;

ALTER TABLE corpus_run_steps
    ALTER COLUMN metrics_json TYPE jsonb USING COALESCE(NULLIF(metrics_json::text, ''), '{}')::jsonb,
    ALTER COLUMN step_name TYPE TEXT,
    ALTER COLUMN input_artifact_path TYPE TEXT,
    ALTER COLUMN output_artifact_path TYPE TEXT,
    ALTER COLUMN command_line TYPE TEXT,
    ALTER COLUMN stdout_log_path TYPE TEXT,
    ALTER COLUMN stderr_log_path TYPE TEXT,
    ALTER COLUMN stdout_excerpt TYPE TEXT,
    ALTER COLUMN stderr_excerpt TYPE TEXT,
    ALTER COLUMN error_message TYPE TEXT;

ALTER TABLE corpus_run_steps
    ALTER COLUMN metrics_json SET DEFAULT '{}'::jsonb;
