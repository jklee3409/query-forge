ALTER TABLE corpus_glossary_terms
    ADD COLUMN IF NOT EXISTS import_run_id UUID;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'fk_corpus_glossary_terms_import_run'
    ) THEN
        ALTER TABLE corpus_glossary_terms
            ADD CONSTRAINT fk_corpus_glossary_terms_import_run
            FOREIGN KEY (import_run_id)
            REFERENCES corpus_runs (run_id);
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_corpus_glossary_terms_run_id
    ON corpus_glossary_terms (import_run_id);
