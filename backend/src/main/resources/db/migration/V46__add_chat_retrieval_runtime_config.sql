ALTER TABLE IF EXISTS chat_runtime_config
    ADD COLUMN IF NOT EXISTS retrieval_backend TEXT NOT NULL DEFAULT 'local',
    ADD COLUMN IF NOT EXISTS dense_embedding_model TEXT NOT NULL DEFAULT 'intfloat/multilingual-e5-small',
    ADD COLUMN IF NOT EXISTS retriever_mode TEXT NOT NULL DEFAULT 'hybrid',
    ADD COLUMN IF NOT EXISTS retriever_candidate_pool_k INTEGER NOT NULL DEFAULT 50,
    ADD COLUMN IF NOT EXISTS retriever_dense_weight DOUBLE PRECISION NOT NULL DEFAULT 0.60,
    ADD COLUMN IF NOT EXISTS retriever_bm25_weight DOUBLE PRECISION NOT NULL DEFAULT 0.32,
    ADD COLUMN IF NOT EXISTS retriever_technical_weight DOUBLE PRECISION NOT NULL DEFAULT 0.08;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'ck_chat_runtime_config_retrieval_backend'
    ) THEN
        ALTER TABLE chat_runtime_config
            ADD CONSTRAINT ck_chat_runtime_config_retrieval_backend
                CHECK (retrieval_backend IN ('local', 'db_ann'));
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'ck_chat_runtime_config_retriever_mode'
    ) THEN
        ALTER TABLE chat_runtime_config
            ADD CONSTRAINT ck_chat_runtime_config_retriever_mode
                CHECK (retriever_mode IN ('bm25_only', 'dense_only', 'hybrid'));
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'ck_chat_runtime_config_retriever_candidate_pool'
    ) THEN
        ALTER TABLE chat_runtime_config
            ADD CONSTRAINT ck_chat_runtime_config_retriever_candidate_pool
                CHECK (retriever_candidate_pool_k BETWEEN 1 AND 500);
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'ck_chat_runtime_config_retriever_weights'
    ) THEN
        ALTER TABLE chat_runtime_config
            ADD CONSTRAINT ck_chat_runtime_config_retriever_weights
                CHECK (
                    retriever_dense_weight >= 0.0 AND retriever_dense_weight <= 1.0
                    AND retriever_bm25_weight >= 0.0 AND retriever_bm25_weight <= 1.0
                    AND retriever_technical_weight >= 0.0 AND retriever_technical_weight <= 1.0
                    AND (retriever_dense_weight + retriever_bm25_weight + retriever_technical_weight) > 0.0
                );
    END IF;
END $$;

COMMENT ON COLUMN chat_runtime_config.retrieval_backend IS
    'Live chat retrieval backend. Mirrors Admin RAG retrieval_backend for promoted configs.';

COMMENT ON COLUMN chat_runtime_config.dense_embedding_model IS
    'Dense embedding model used by live chat DB ANN retrieval when retrieval_backend=db_ann.';

COMMENT ON COLUMN chat_runtime_config.retriever_mode IS
    'Live chat retriever mode such as bm25_only, dense_only, or hybrid.';

COMMENT ON COLUMN chat_runtime_config.retriever_candidate_pool_k IS
    'Candidate pool size used before live chat retriever fusion/rerank.';
