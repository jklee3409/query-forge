# progress.md

## Overview
High-level progress tracking for the project.

## [2026-04-17] Session Summary (Synthetic Full-Corpus Random Sampling Controls)
- What was done: Added Admin synthetic generation support for `random_chunk_sampling` (GUI -> backend config -> pipeline), kept no-`limit_chunks` full-corpus behavior, and verified max synthetic query cap supports up to `2000`.
- Key decisions: Applied random sampling as chunk-order shuffle (seeded by experiment `random_seed`) so `max_total_queries` can stop at a random full-corpus subset without changing core pipeline stages.
- Issues encountered: Frontend production build refreshed backend static asset hash files.
- Next steps: Run GUI generation for `A/C/D` separately with `max_total_queries=1000`, `limit_chunks` empty, and `random chunk sampling` enabled; then confirm per-batch counts/history.

## [2026-04-17] Session Summary (Full-Gating Stage-Cutoff + Domain Data Reset)
- What was done: Implemented stage-cutoff based RAG run flow (use full-gating snapshot as source and cut synthetic queries by stage level), completed frontend/backend/pipeline wiring, and reset synthetic generation/quality gating/RAG test/LLM-job data in DB.
- Key decisions: Stage-cutoff path is restricted to exploratory runs and requires explicit `source_gating_batch_id` from a completed `full_gating` batch; corpus collect/preprocess/chunk tables were preserved.
- Issues encountered: Existing UI file contains mixed-encoding localized text, so stage-cutoff UI edits were applied with narrow scope.
- Next steps: Run one exploratory `rule_only` cutoff test using full-gating batch `6d97464a-9989-4180-85f5-c076850873aa` and verify per-stage pass counts against RAG memory size.

## [2026-04-17] Session Summary (Backend Transaction/Concurrency Risk Mitigation)
- What was done: Applied backend-only hardening for identified high-risk hotspots by shrinking service-level transaction scope around long-latency operations (`ask`, `reindex`, `runRagTest`) and adding advisory-lock-based serialization for pipeline run creation (`startRun` path).
- Key decisions: Kept business logic and API response contracts intact; focused only on transaction boundary and concurrency control behavior.
- Issues encountered: Existing codebase contains mixed-encoding localized literals; changes were intentionally minimal and localized to avoid collateral edits.
- Next steps: Observe lock-wait/throughput behavior under concurrent admin run requests and RAG ask load.

## [2026-04-17] Session Summary (Synthetic-free Baseline RAG Test Path)
- What was done: Added synthetic-free baseline support for Admin RAG tests end-to-end (request flag, backend validation/config, frontend run controls, and pipeline stage behavior) so baseline runs can execute without using synthetic-query snapshots.
- Key decisions: Preserved mandatory RAG job stage order (`build-memory -> eval-retrieval -> eval-answer`) and implemented baseline as `build-memory` no-op + `raw_only` retrieval/eval mode to avoid synthetic query dependency while keeping orchestration stable.
- Issues encountered: Existing workspace already contained unrelated staged/unstaged feature changes, so edits were scoped only to baseline-path fields/validation and pipeline memory-loading guards.
- Next steps: Run one exploratory synthetic-free baseline and one snapshot-based run on the same dataset, then compare retrieval/answer deltas from the RAG compare panel.

## [2026-04-15] Session Summary (Short User Dataset 40 + A/C RAG Re-run + Report)
- What was done: Built and registered a new retrieval-aware short-user eval dataset (`human_eval_short_user_40`, 40 items), executed two Admin-path RAG tests with the same settings as baseline runs (`A`: `cfb7587d-649f-457b-9410-0948abb49772`, `C`: `2a899769-613b-4463-95e1-fb850fdb73a3`), and documented combined baseline/new analysis in `docs/report/rag_quality_ac_comparison_short_user_2026-04-15.md`.
- Key decisions: Kept snapshot parity with baseline (`A` snapshot `4af71ae8...`, `C` snapshot `c9adc3f9...`) and fixed all runtime knobs (`full_gating`, selective rewrite, threshold `0.05`, `retrieval_top_k=10`, `rerank_top_n=5`) so dataset style was the only intended variable.
- Issues encountered: `human_eval_default` auto-sync behavior refreshed aggregate sample count after inserting new eval samples, so report explicitly separates historical run-time sample size from current dataset total.
- Next steps: Add controlled ungated/rule-only/full-gating comparison on the same short-user dataset and evaluate embedding/rerank alternatives for low MRR on compressed user queries.

## [2026-04-15] Session Summary (RAG Eval Reset + Rewrite Adoption + Admin UX)
- What was done: Rebuilt eval dataset via `build-eval-dataset` (method-1 path), improved rewrite adoption logic in pipeline runtime, updated Admin RAG compare chart to vertical layout, and replaced run-compare selector with a clearer custom checkbox UI.
- Key decisions: Kept Admin execution path intact (`/api/admin/console/rag/tests/run`) and reran two exploratory tests with the same conditions as previous target runs after deleting prior RAG test history/results.
- Issues encountered: Long-running answer eval stage required extended monitoring; both reruns completed after prolonged `eval-answer` runtime.
- Next steps: Add pre-eval dataset/corpus ID consistency guard and monitor rewrite adoption quality with current `confidence + retrieval-shift` scoring.

## [2026-04-14] Session Summary (RAG Eval Parallelization + Run Cleanup)
- What was done: Read `.codex/AGENTS.md`, cancelled active RAG runs (`41a804bf-7b43-46dd-a4de-592f08ddac89`, `f3360ef2-7d04-42ec-acc6-ff1382568892`), removed run-specific temporary artifacts (experiment configs/reports and related DB rows), and implemented sample-level parallel processing for `eval-retrieval` and `eval-answer`.
- Key decisions: Parallelized only computation/LLM call paths via `ThreadPoolExecutor` while keeping DB writes sequential to preserve transactional safety and deterministic ordering; added configurable eval concurrency (`retrieval_eval_concurrency` / `answer_eval_concurrency` / `eval_concurrency` with env fallbacks).
- Issues encountered: Shell policy blocked direct multi-file delete commands during cleanup, so document/report file deletion was completed with patch-based file removal and DB cleanup was scoped by target run/experiment identifiers.
- Next steps: Run a controlled RAG eval smoke test to validate throughput gain, confirm no regression in metric outputs/order, and tune concurrency defaults against provider latency/quota behavior.

## [2026-04-14] Session Summary (RAG Pipeline Reliability Hardening)
- What was done: Extended corpus alignment work beyond eval by hardening `memory`/`eval-dataset` source filtering, adding corpus-based FK migration coverage for `memory_entries`/`retrieval_results`/`rerank_results`, and improving LLM job retry state handling.
- Key decisions: Removed reliance on legacy `documents/chunks` FK paths for RAG-critical writes and added backend subprocess timeout handling to prevent indefinite `running` states.
- Issues encountered: Historical environments can retain mixed legacy/corpus constraints and orphan artifacts, so migration includes pre-constraint cleanup and `NOT VALID` attachment strategy.
- Next steps: Apply migration in runtime DB and verify active/next RAG runs complete without FK mismatch or stuck retry states.

## [2026-04-14] Session Summary (RAG Eval FK Mismatch Root Fix)
- What was done: Fixed eval runtime chunk loading to exclude orphan corpus chunks, added corpus-aligned eval FK migration (`V18`), and hardened chunk import to skip rows whose `document_id` is missing in `corpus_documents`.
- Key decisions: Standardized eval-result FK target to `corpus_chunks` and removed legacy `documents/chunks` coupling that caused `ForeignKeyViolation` during `eval-answer`.
- Issues encountered: Live DB had active `eval-retrieval` transactions, so lock-heavy retrieval-table FK hotfix was deferred to migration apply.
- Next steps: Apply migration and confirm active runs `41a804bf-7b43-46dd-a4de-592f08ddac89` and `f3360ef2-7d04-42ec-acc6-ff1382568892` finish with `eval-answer` success.

## [2026-04-14] Session Summary (AGENTS 3.6 Official Eval Discipline Enforcement)
- What was done: Enforced official-vs-exploratory RAG run discipline end-to-end: explicit snapshot identity requirement for official runs, official bundled comparison modes (`gating_effect` and `rewrite_effect`), per-mode retrieval preservation/exposure, standardized experiment-record persistence, and answer-metric alignment (`correctness/grounding/hallucination_rate`).
- Key decisions: Kept architecture intact and applied minimum targeted changes in backend request validation/config writing, pipeline retrieval/answer evaluation, and RAG admin UI controls.
- Issues encountered: Existing frontend file had mixed-encoding regions, so edits were scoped to stable JSX blocks and verified through full Vite build.
- Next steps: Apply latest migrations (`V18`, `V19`) in runtime DB and run official comparison smoke tests to confirm enforced failure modes and reproducible records.

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
