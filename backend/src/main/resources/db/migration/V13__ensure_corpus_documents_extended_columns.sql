ALTER TABLE corpus_documents
    ADD COLUMN IF NOT EXISTS import_run_id UUID;

ALTER TABLE corpus_documents
    ADD COLUMN IF NOT EXISTS metadata_json JSONB NOT NULL DEFAULT '{}'::jsonb;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'fk_corpus_documents_import_run'
    ) THEN
        ALTER TABLE corpus_documents
            ADD CONSTRAINT fk_corpus_documents_import_run
            FOREIGN KEY (import_run_id)
            REFERENCES corpus_runs (run_id);
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_corpus_documents_run_id
    ON corpus_documents (import_run_id);
