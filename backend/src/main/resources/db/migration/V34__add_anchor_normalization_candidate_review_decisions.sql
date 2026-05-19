ALTER TABLE anchor_normalization_candidate
    ADD COLUMN IF NOT EXISTS review_decision TEXT NOT NULL DEFAULT 'pending',
    ADD COLUMN IF NOT EXISTS reviewed_by TEXT,
    ADD COLUMN IF NOT EXISTS review_note TEXT,
    ADD COLUMN IF NOT EXISTS reviewed_at TIMESTAMPTZ;

DO $$
BEGIN
    ALTER TABLE anchor_normalization_candidate
        ADD CONSTRAINT chk_anchor_normalization_candidate_review_decision
        CHECK (review_decision IN ('pending', 'approve', 'skip'));
EXCEPTION
    WHEN duplicate_object THEN NULL;
END $$;

CREATE INDEX IF NOT EXISTS idx_anchor_normalization_candidate_run_review
    ON anchor_normalization_candidate (run_id, review_decision);

COMMENT ON COLUMN anchor_normalization_candidate.review_decision IS
    'Manual operator decision after dry-run: pending, approve, or skip. Run approval applies only approved would_update candidates.';
