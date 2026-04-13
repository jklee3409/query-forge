# progress.md

## Overview
High-level backend progress tracking.

---

## [2026-04-13] Session Summary (Synthetic Raw Split)
- What was done: Added `V17__split_strategy_raw_tables_and_drop_legacy_raw.sql` and switched backend synthetic raw reads from single-table `synthetic_queries_raw` to split-table union view `synthetic_queries_raw_all`.
- Key decisions: `AdminConsoleRepository` batch provenance sync now updates `synthetic_queries_raw_a/b/c/d` separately.
- Issues encountered: Existing workspace had unrelated changes; only split-table refactor paths were touched.
- Next steps: Apply migration in runtime DB and verify Admin Console synthetic/gating/RAG flows with split-table storage.

---

## Notes
- Keep this file concise.
- Record only major backend changes.
