ALTER TABLE corpus_documents
    ADD COLUMN IF NOT EXISTS superseded_by_document_id TEXT;

ALTER TABLE corpus_documents
    ADD COLUMN IF NOT EXISTS import_run_id UUID;

ALTER TABLE corpus_documents
    ADD COLUMN IF NOT EXISTS metadata_json JSONB NOT NULL DEFAULT '{}'::jsonb;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'fk_corpus_documents_superseded'
    ) THEN
        ALTER TABLE corpus_documents
            ADD CONSTRAINT fk_corpus_documents_superseded
            FOREIGN KEY (superseded_by_document_id)
            REFERENCES corpus_documents (document_id);
    END IF;

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
