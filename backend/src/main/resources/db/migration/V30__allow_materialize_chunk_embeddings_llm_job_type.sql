ALTER TABLE llm_job
    DROP CONSTRAINT IF EXISTS llm_job_job_type_check;

ALTER TABLE llm_job
    ADD CONSTRAINT llm_job_job_type_check
    CHECK (job_type IN (
        'GENERATE_EN_SUMMARY',
        'TRANSLATE_CHUNK_TO_KO',
        'GENERATE_KO_SUMMARY',
        'GENERATE_SYNTHETIC_QUERY',
        'RUN_LLM_SELF_EVAL',
        'GENERATE_REWRITE_CANDIDATES',
        'RUN_RAG_TEST',
        'MATERIALIZE_CHUNK_EMBEDDINGS'
    ));
