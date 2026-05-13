BEGIN;

CREATE TABLE IF NOT EXISTS chunk_embeddings (
    chunk_embedding_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    chunk_id TEXT NOT NULL REFERENCES corpus_chunks (chunk_id) ON DELETE CASCADE,
    embedding_model TEXT NOT NULL,
    embedding_dim INTEGER NOT NULL DEFAULT 384,
    embedding HALFVEC(384) NOT NULL,
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (chunk_id, embedding_model)
);

CREATE INDEX IF NOT EXISTS idx_chunk_embeddings_chunk_model
    ON chunk_embeddings (chunk_id, embedding_model);

CREATE INDEX IF NOT EXISTS idx_chunk_embeddings_model_updated
    ON chunk_embeddings (embedding_model, updated_at DESC);

CREATE INDEX IF NOT EXISTS idx_chunk_embeddings_vector
    ON chunk_embeddings USING hnsw (embedding halfvec_cosine_ops);

COMMIT;
