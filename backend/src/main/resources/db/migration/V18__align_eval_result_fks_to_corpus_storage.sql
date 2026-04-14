-- Align eval result FK references with corpus storage.
-- Evaluation/retrieval runtime reads from corpus_* tables, so rerank/retrieval
-- result FKs must not point to legacy documents/chunks tables.

ALTER TABLE rerank_results
    DROP CONSTRAINT IF EXISTS rerank_results_document_id_fkey;

ALTER TABLE rerank_results
    DROP CONSTRAINT IF EXISTS rerank_results_chunk_id_fkey;

ALTER TABLE retrieval_results
    DROP CONSTRAINT IF EXISTS retrieval_results_document_id_fkey;

ALTER TABLE retrieval_results
    DROP CONSTRAINT IF EXISTS retrieval_results_chunk_id_fkey;

ALTER TABLE memory_entries
    DROP CONSTRAINT IF EXISTS memory_entries_target_doc_id_fkey;

ALTER TABLE memory_entries
    DROP CONSTRAINT IF EXISTS memory_entries_chunk_id_source_fkey;

-- Normalize stale references before re-attaching constraints to corpus storage.
UPDATE rerank_results rr
SET chunk_id = NULL
WHERE rr.chunk_id IS NOT NULL
  AND NOT EXISTS (
      SELECT 1
      FROM corpus_chunks c
      WHERE c.chunk_id = rr.chunk_id
  );

UPDATE rerank_results rr
SET document_id = NULL
WHERE rr.document_id IS NOT NULL
  AND NOT EXISTS (
      SELECT 1
      FROM corpus_documents d
      WHERE d.document_id = rr.document_id
  );

UPDATE retrieval_results rr
SET chunk_id = NULL
WHERE rr.chunk_id IS NOT NULL
  AND NOT EXISTS (
      SELECT 1
      FROM corpus_chunks c
      WHERE c.chunk_id = rr.chunk_id
  );

UPDATE retrieval_results rr
SET document_id = NULL
WHERE rr.document_id IS NOT NULL
  AND NOT EXISTS (
      SELECT 1
      FROM corpus_documents d
      WHERE d.document_id = rr.document_id
  );

UPDATE memory_entries m
SET target_doc_id = NULL
WHERE m.target_doc_id IS NOT NULL
  AND NOT EXISTS (
      SELECT 1
      FROM corpus_documents d
      WHERE d.document_id = m.target_doc_id
  );

UPDATE memory_entries m
SET chunk_id_source = NULL
WHERE m.chunk_id_source IS NOT NULL
  AND NOT EXISTS (
      SELECT 1
      FROM corpus_chunks c
      WHERE c.chunk_id = m.chunk_id_source
  );

-- Clean up historical corpus rows created without document FK protection.
UPDATE corpus_sections s
SET parent_section_id = NULL
WHERE s.parent_section_id IS NOT NULL
  AND NOT EXISTS (
      SELECT 1
      FROM corpus_sections p
      WHERE p.section_id = s.parent_section_id
  );

DELETE FROM corpus_chunks c
WHERE NOT EXISTS (
    SELECT 1
    FROM corpus_documents d
    WHERE d.document_id = c.document_id
);

DELETE FROM corpus_sections s
WHERE NOT EXISTS (
    SELECT 1
    FROM corpus_documents d
    WHERE d.document_id = s.document_id
);

ALTER TABLE rerank_results
    ADD CONSTRAINT rerank_results_chunk_id_fkey
        FOREIGN KEY (chunk_id)
        REFERENCES corpus_chunks (chunk_id)
        ON DELETE SET NULL
        NOT VALID;

ALTER TABLE rerank_results
    ADD CONSTRAINT rerank_results_document_id_fkey
        FOREIGN KEY (document_id)
        REFERENCES corpus_documents (document_id)
        ON DELETE SET NULL
        NOT VALID;

ALTER TABLE retrieval_results
    ADD CONSTRAINT retrieval_results_chunk_id_fkey
        FOREIGN KEY (chunk_id)
        REFERENCES corpus_chunks (chunk_id)
        ON DELETE SET NULL
        NOT VALID;

ALTER TABLE retrieval_results
    ADD CONSTRAINT retrieval_results_document_id_fkey
        FOREIGN KEY (document_id)
        REFERENCES corpus_documents (document_id)
        ON DELETE SET NULL
        NOT VALID;

ALTER TABLE memory_entries
    ADD CONSTRAINT memory_entries_target_doc_id_fkey
        FOREIGN KEY (target_doc_id)
        REFERENCES corpus_documents (document_id)
        ON DELETE SET NULL
        NOT VALID;

ALTER TABLE memory_entries
    ADD CONSTRAINT memory_entries_chunk_id_source_fkey
        FOREIGN KEY (chunk_id_source)
        REFERENCES corpus_chunks (chunk_id)
        ON DELETE SET NULL
        NOT VALID;

-- Ensure corpus hierarchy referential constraints exist in environments that
-- were initialized before corpus FK hardening.
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'corpus_sections_document_id_fkey'
    ) THEN
        ALTER TABLE corpus_sections
            ADD CONSTRAINT corpus_sections_document_id_fkey
                FOREIGN KEY (document_id)
                REFERENCES corpus_documents (document_id)
                ON DELETE CASCADE
                NOT VALID;
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'corpus_chunks_document_id_fkey'
    ) THEN
        ALTER TABLE corpus_chunks
            ADD CONSTRAINT corpus_chunks_document_id_fkey
                FOREIGN KEY (document_id)
                REFERENCES corpus_documents (document_id)
                ON DELETE CASCADE
                NOT VALID;
    END IF;
END $$;
