# progress.md

## Overview
High-level backend progress tracking.

---

## [2026-04-13] Session Summary (Synthetic Raw Split)
- What was done: Added `V17__split_strategy_raw_tables_and_drop_legacy_raw.sql` and switched backend synthetic raw reads from single-table `synthetic_queries_raw` to split-table union view `synthetic_queries_raw_all`.
- Key decisions: `AdminConsoleRepository` batch provenance sync now updates `synthetic_queries_raw_a/b/c/d` separately.
- Issues encountered: Existing workspace had unrelated changes; only split-table refactor paths were touched.
- Next steps: Apply migration in runtime DB and verify Admin Console synthetic/gating/RAG flows with split-table storage.

## [2026-04-13] Session Summary (Gating Re-run Cleanup)
- What was done: Added `AdminConsoleRepository.clearCompletedGatingResults(...)` and wired it into `AdminConsoleService.runGating(...)` to remove prior completed/failed/cancelled gating batches for the same method before starting a new run.
- Key decisions: Used method-scoped cleanup with status guard (`completed/failed/cancelled`) and set `synthetic_queries_gated.gating_batch_id` to `NULL` before deleting batch rows to keep FK consistency.
- Issues encountered: Required explicit coverage for deletion scope (target method only, running rows preserved), so integration test data setup was expanded.
- Next steps: Execute admin QA scenario (same method re-run) and monitor batch/result/history tables for non-accumulating behavior.

## [2026-04-13] Session Summary (Gating Results Filter + Pagination)
- What was done: Extended gating results API to accept `method_code` filter and updated admin gating UI to support method-specific result filtering with page-based navigation (`limit/offset`).
- Key decisions: Reused existing endpoint shape (`List<GatingResultRow>`) and implemented frontend pagination via `pageSize + 1` probing (`hasNext`) to avoid API contract break.
- Issues encountered: Existing UI file had mixed encoding text blocks; functional rewrite of `GatingPage.jsx` was applied while preserving API payload shape.
- Next steps: Run Admin GUI smoke test for A/B/C/D filter combinations and verify page transitions against large batches.

---

## Notes
- Keep this file concise.
- Record only major backend changes.
