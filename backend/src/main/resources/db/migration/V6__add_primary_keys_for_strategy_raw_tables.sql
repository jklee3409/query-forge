DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'synthetic_queries_raw_a_pkey'
    ) THEN
        ALTER TABLE synthetic_queries_raw_a
            ADD CONSTRAINT synthetic_queries_raw_a_pkey PRIMARY KEY (synthetic_query_id);
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'synthetic_queries_raw_b_pkey'
    ) THEN
        ALTER TABLE synthetic_queries_raw_b
            ADD CONSTRAINT synthetic_queries_raw_b_pkey PRIMARY KEY (synthetic_query_id);
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'synthetic_queries_raw_c_pkey'
    ) THEN
        ALTER TABLE synthetic_queries_raw_c
            ADD CONSTRAINT synthetic_queries_raw_c_pkey PRIMARY KEY (synthetic_query_id);
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'synthetic_queries_raw_d_pkey'
    ) THEN
        ALTER TABLE synthetic_queries_raw_d
            ADD CONSTRAINT synthetic_queries_raw_d_pkey PRIMARY KEY (synthetic_query_id);
    END IF;
END $$;

