CREATE TABLE IF NOT EXISTS synthetic_queries_raw_a (
    LIKE synthetic_queries_raw INCLUDING DEFAULTS INCLUDING CONSTRAINTS INCLUDING STORAGE INCLUDING COMMENTS
);

CREATE TABLE IF NOT EXISTS synthetic_queries_raw_b (
    LIKE synthetic_queries_raw INCLUDING DEFAULTS INCLUDING CONSTRAINTS INCLUDING STORAGE INCLUDING COMMENTS
);

CREATE TABLE IF NOT EXISTS synthetic_queries_raw_c (
    LIKE synthetic_queries_raw INCLUDING DEFAULTS INCLUDING CONSTRAINTS INCLUDING STORAGE INCLUDING COMMENTS
);

CREATE TABLE IF NOT EXISTS synthetic_queries_raw_d (
    LIKE synthetic_queries_raw INCLUDING DEFAULTS INCLUDING CONSTRAINTS INCLUDING STORAGE INCLUDING COMMENTS
);

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_indexes
        WHERE schemaname = 'public'
          AND indexname = 'idx_synthetic_queries_raw_a_chunk_id_source'
    ) THEN
        CREATE INDEX idx_synthetic_queries_raw_a_chunk_id_source
            ON synthetic_queries_raw_a (chunk_id_source);
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM pg_indexes
        WHERE schemaname = 'public'
          AND indexname = 'idx_synthetic_queries_raw_b_chunk_id_source'
    ) THEN
        CREATE INDEX idx_synthetic_queries_raw_b_chunk_id_source
            ON synthetic_queries_raw_b (chunk_id_source);
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM pg_indexes
        WHERE schemaname = 'public'
          AND indexname = 'idx_synthetic_queries_raw_c_chunk_id_source'
    ) THEN
        CREATE INDEX idx_synthetic_queries_raw_c_chunk_id_source
            ON synthetic_queries_raw_c (chunk_id_source);
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM pg_indexes
        WHERE schemaname = 'public'
          AND indexname = 'idx_synthetic_queries_raw_d_chunk_id_source'
    ) THEN
        CREATE INDEX idx_synthetic_queries_raw_d_chunk_id_source
            ON synthetic_queries_raw_d (chunk_id_source);
    END IF;
END $$;

CREATE UNIQUE INDEX IF NOT EXISTS uq_memory_entries_source_gated_query_id
    ON memory_entries (source_gated_query_id);

