-- Align memory/query embedding vector dimensions to multilingual-e5-small (384).
-- Existing rows were generated with hash-embedding-v1 (3072) and are not compatible
-- with dense-only e5 retrieval experiments.

BEGIN;

-- Remove stale 3072-dim vectors before type conversion.
DELETE FROM query_embeddings;
UPDATE memory_entries
SET query_embedding = NULL
WHERE query_embedding IS NOT NULL;

-- Rebuild vector indexes around the new dimensions.
DROP INDEX IF EXISTS idx_query_embeddings_vector;
DROP INDEX IF EXISTS idx_memory_entries_query_embedding;

ALTER TABLE query_embeddings
    ALTER COLUMN embedding TYPE halfvec(384);
ALTER TABLE query_embeddings
    ALTER COLUMN embedding_dim SET DEFAULT 384;

ALTER TABLE memory_entries
    ALTER COLUMN query_embedding TYPE halfvec(384);

CREATE INDEX idx_query_embeddings_vector
    ON query_embeddings USING hnsw (embedding halfvec_cosine_ops);
CREATE INDEX idx_memory_entries_query_embedding
    ON memory_entries USING hnsw (query_embedding halfvec_cosine_ops);

COMMIT;
