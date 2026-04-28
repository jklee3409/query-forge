ALTER TABLE synthetic_queries_raw_e
    DROP CONSTRAINT IF EXISTS ck_synthetic_queries_raw_d_strategy;

ALTER TABLE synthetic_queries_raw_e
    DROP CONSTRAINT IF EXISTS synthetic_queries_raw_generation_strategy_check;

UPDATE synthetic_queries_raw_e
SET generation_strategy = 'E'
WHERE generation_strategy IS DISTINCT FROM 'E';

UPDATE synthetic_queries_raw_e
SET query_language = 'en'
WHERE query_language IS DISTINCT FROM 'en';

ALTER TABLE synthetic_queries_raw_e
    ALTER COLUMN query_language SET DEFAULT 'en';

ALTER TABLE synthetic_queries_raw_e
    DROP CONSTRAINT IF EXISTS ck_synthetic_queries_raw_e_strategy;

ALTER TABLE synthetic_queries_raw_e
    ADD CONSTRAINT ck_synthetic_queries_raw_e_strategy
        CHECK (generation_strategy = 'E');

ALTER TABLE synthetic_queries_raw_e
    ADD CONSTRAINT synthetic_queries_raw_generation_strategy_check
        CHECK (generation_strategy IN ('A', 'B', 'C', 'D', 'E'));

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conrelid = 'synthetic_queries_raw_e'::regclass
          AND conname = 'synthetic_queries_raw_e_generation_method_id_fkey'
    ) THEN
        ALTER TABLE synthetic_queries_raw_e
            ADD CONSTRAINT synthetic_queries_raw_e_generation_method_id_fkey
                FOREIGN KEY (generation_method_id)
                REFERENCES synthetic_query_generation_method (generation_method_id);
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conrelid = 'synthetic_queries_raw_e'::regclass
          AND conname = 'synthetic_queries_raw_e_generation_batch_id_fkey'
    ) THEN
        ALTER TABLE synthetic_queries_raw_e
            ADD CONSTRAINT synthetic_queries_raw_e_generation_batch_id_fkey
                FOREIGN KEY (generation_batch_id)
                REFERENCES synthetic_query_generation_batch (batch_id);
    END IF;
END $$;
