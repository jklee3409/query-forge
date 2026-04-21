WITH legacy_runs AS (
    SELECT
        rag_test_run_id,
        ROW_NUMBER() OVER (ORDER BY created_at, rag_test_run_id) AS legacy_seq
    FROM rag_test_run
    WHERE run_label IS NULL
       OR BTRIM(run_label) = ''
       OR run_label LIKE 'RAG 테스트 %'
)
UPDATE rag_test_run r
SET run_label = 'Legacy RAG Test ' || LPAD(legacy_runs.legacy_seq::text, 3, '0')
FROM legacy_runs
WHERE r.rag_test_run_id = legacy_runs.rag_test_run_id;
