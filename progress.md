# progress.md

## Overview
High-level progress tracking for the project.

---

## [2026-04-13] Session Summary
- What was done: Read `.codex/AGENTS.md`, created root `index.md`/`progress.md`, and added `index.md`/`progress.md` for each major working directory (`.codex`, `backend`, `configs`, `data`, `docs`, `frontend`, `infra`, `pipeline`, `scripts`).
- Key decisions: Used `.codex/AGENTS.md` as the authoritative agent policy and documented each directory based on currently implemented files and execution flow.
- Issues encountered: Root `AGENTS.md` was not present; runtime/build artifact directories were excluded from documentation scope.
- Next steps: Maintain directory-level `index.md`/`progress.md` together with code/config changes in each affected directory.

## [2026-04-13] Session Summary (Raw Table Split Refactor)
- What was done: Reworked synthetic-query storage from single `synthetic_queries_raw` writes to strategy-specific writes (`synthetic_queries_raw_a/b/c/d`) and switched gating/memory/eval/admin-console/rag reads to split-backed source (`synthetic_queries_raw_all`).
- Key decisions: Added Flyway `V17__split_strategy_raw_tables_and_drop_legacy_raw.sql` to migrate data, introduce `synthetic_query_registry` for FK integrity, drop legacy `synthetic_queries_raw`, and create union view `synthetic_queries_raw_all`.
- Issues encountered: Existing workspace had unrelated modifications; this session avoided reverting non-target files and validated only touched paths.
- Next steps: Apply migration in target DB, then run admin GUI generation/gating smoke checks and verify A/B/C/D strategy counts independently.

## [2026-04-13] Session Summary (Root README Rewrite)
- What was done: Rewrote root `README.md` in Korean narrative form and aligned content with `.codex/AGENTS.md` requirements (project objective, research overview/details, methodology, and fixed end-to-end flow).
- Key decisions: Replaced the previous experiment-report style README with project-level guidance that emphasizes A/B/C/D strategy separation, selective/dynamic gating, and retrieval-aware evaluation dataset constraints.
- Issues encountered: Existing README content was not suitable as a stable root guide due to encoding/readability issues in terminal output.
- Next steps: Keep directory-level READMEs synchronized with implementation changes and expand per-module Korean documentation where legacy/default templates remain.

## [2026-04-13] Session Summary (Skill: git-commit)
- What was done: Created `.codex/skills/git-commit` using the `skill-creator` workflow, implemented a commit workflow based on `git diff`, and added AngularJS-style commit guidance with Korean + English technical message examples.
- Key decisions: Focused the skill on logical commit splitting, unnecessary file exclusion, and staged-diff verification before each commit.
- Issues encountered: Initial `openai.yaml` generation had a short-description length violation and `$git-commit` prompt escaping issue; regenerated metadata with valid interface values.
- Next steps: Use `$git-commit` in real commit sessions and refine exclusion heuristics if project-specific noise patterns are observed.

## [2026-04-13] Session Summary (Admin Gating Reset)
- What was done: Updated admin gating run flow to clear previous completed/failed/cancelled gating batches for the same generation method before creating a new gating batch, and added an integration test for cleanup scope.
- Key decisions: Cleanup nulls `synthetic_queries_gated.gating_batch_id` first, then deletes target `quality_gating_batch` rows so dependent per-batch artifacts are removed via FK cascade without touching running batches.
- Issues encountered: Needed to preserve in-flight gating jobs, so cleanup scope excludes `planned/running` statuses.
- Next steps: Validate from Admin GUI by running A-method gating twice and confirming prior batch/result rows are replaced by the latest run context.

## [2026-04-13] Session Summary (Gating Filter + Pagination)
- What was done: Added method-based filtering (`method_code`) for admin gating result queries and implemented result table pagination in `frontend/src/pages/GatingPage.jsx`.
- Key decisions: Kept backend response shape unchanged (`List<GatingResultRow>`) and implemented frontend paging with `limit/offset` + `pageSize+1` next-page probing.
- Issues encountered: Frontend file contained mixed-encoding labels; focused on behavior/API consistency first and deferred pure text normalization.
- Next steps: Perform Admin GUI smoke checks for A/B/C/D filtering and confirm result-page navigation across larger gating batches.

## [2026-04-14] Session Summary (RAG Snapshot Evaluation Wiring)
- What was done: Added snapshot-aware RAG test flow by introducing optional `sourceGatingBatchId` in rag run request, validating selected gating batch (completed/preset/method match), and wiring fixed `source_gating_run_id` into experiment config.
- Key decisions: Preserved backward compatibility with auto-latest behavior when no snapshot batch is selected, and aligned Python config keys by writing both `memory_generation_strategies` and `source_generation_strategies`.
- Issues encountered: Existing eval path loaded memory entries across runs; added source gating run filtering in runtime retrieval/rewrite path to prevent cross-run memory mixing.
- Next steps: Execute admin GUI smoke for snapshot vs auto-latest runs and compare retrieval/answer reports for deterministic reruns.

## [2026-04-14] Session Summary (RAG Snapshot Dropdown Visibility)
- What was done: Adjusted Admin RAG snapshot dropdown behavior to show all completed gating snapshots and added runtime refresh wiring for gating batch list updates.
- Key decisions: Moved compatibility enforcement to run-time validation so UI can expose full snapshot inventory while still blocking incompatible preset/method combinations.
- Issues encountered: Existing UI filtered snapshot list by effective preset/method, which could hide valid completed snapshots from operator view.
- Next steps: Validate operator scenario where completed batch count in GUI dropdown matches backend `gating/batches` API result.

## [2026-04-14] Session Summary (RAG UX + Backoffice Visual Refresh)
- What was done: Redesigned Admin backoffice shell (`frontend/src/App.jsx`, `frontend/src/styles.css`) and rebuilt RAG test UI (`frontend/src/pages/RagPage.jsx`) with clearer control semantics and run-comparison visualization.
- Key decisions: Resolved snapshot/method duplicated input by auto-locking method selection when snapshot carries a fixed method, while keeping submit-time compatibility validation for safety.
- Issues encountered: Existing RAG options were hard to interpret, so field-level helper text now explicitly maps GUI knobs to runtime config keys (`rewrite_threshold`, `retrieval_top_k`, `rerank_top_n`).
- Next steps: Validate operator workflow for two-run chart comparison and collect feedback on metric prioritization in the dashboard.

## [2026-04-13] Session Summary (Gating Rule Ratio + Funnel Filter)
- What was done: Added configurable Korean-ratio threshold to admin gating Rule stage (GUI -> backend config -> pipeline rule evaluation), clarified min/max token labels, and added method-based funnel filtering (`전체/A/B/C/D`) in gating execution screen.
- Key decisions: Preserved legacy defaults by keeping separate defaults for general queries (`0.40`) and code-mixed queries (`0.20`), while applying the same user-entered ratio to both when explicitly set from Admin GUI.
- Issues encountered: Funnel stage summary table cannot provide method-specific counts, so method-filtered funnel counts are derived directly from `synthetic_query_gating_result`.
- Next steps: Run Admin GUI smoke checks for funnel filter switching and confirm expected ratio behavior for method D (`code_mixed`) runs.

---

## Notes
- Keep this file concise
- Only record important changes
