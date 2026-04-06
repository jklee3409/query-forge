ALTER TABLE corpus_runs
    ADD COLUMN cancel_requested_at TIMESTAMPTZ,
    ADD COLUMN updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW();

ALTER TABLE corpus_run_steps
    ADD COLUMN command_line TEXT,
    ADD COLUMN stdout_log_path TEXT,
    ADD COLUMN stderr_log_path TEXT,
    ADD COLUMN stdout_excerpt TEXT,
    ADD COLUMN stderr_excerpt TEXT,
    ADD COLUMN updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW();

CREATE INDEX idx_corpus_runs_cancel_requested_at
    ON corpus_runs (cancel_requested_at);
