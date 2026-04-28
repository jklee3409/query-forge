ALTER TABLE rag_test_run
    ADD COLUMN IF NOT EXISTS rewrite_anchor_injection_enabled BOOLEAN;

UPDATE rag_test_run r
SET rewrite_anchor_injection_enabled = COALESCE(
        NULLIF(rc.config_json ->> 'rewrite_anchor_injection_enabled', '')::boolean,
        CASE WHEN COALESCE(r.rewrite_enabled, FALSE) THEN TRUE ELSE FALSE END
                                     )
FROM rag_test_run_config rc
WHERE rc.rag_test_run_id = r.rag_test_run_id
  AND r.rewrite_anchor_injection_enabled IS NULL;

UPDATE rag_test_run
SET rewrite_anchor_injection_enabled = CASE
                                           WHEN COALESCE(rewrite_enabled, FALSE) THEN TRUE
                                           ELSE FALSE
    END
WHERE rewrite_anchor_injection_enabled IS NULL;

ALTER TABLE rag_test_run
    ALTER COLUMN rewrite_anchor_injection_enabled SET NOT NULL;

ALTER TABLE rag_test_run
    ALTER COLUMN rewrite_anchor_injection_enabled SET DEFAULT TRUE;
