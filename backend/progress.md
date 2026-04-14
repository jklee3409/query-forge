# progress.md

## Overview
High-level backend progress tracking.

## [2026-04-14] Session Summary (RAG Job Retry/Timeout Stabilization)
- What was done: Hardened LLM job execution to resume from completed job items on retry, reset non-completed items safely before retry, and clear stale timestamps/messages when rerunning items.
- Key decisions: Prevented repeated execution of already-completed RAG stages during retry and added experiment subprocess timeout control (`query-forge.admin.pipeline.experiment-command-timeout-seconds`).
- Issues encountered: Prior retry behavior could leave inconsistent item states (`running` with old `finished_at`) and allow indefinite command waits.
- Next steps: Monitor running jobs for cleaner state transitions and tune timeout via `QUERY_FORGE_EXPERIMENT_TIMEOUT_SECONDS` if needed.

## [2026-04-14] Session Summary (Eval Result FK Alignment Migration)
- What was done: Added `V18__align_eval_result_fks_to_corpus_storage.sql` to move eval-result FK targets from legacy `documents/chunks` to `corpus_chunks` and to restore missing corpus document FKs.
- Key decisions: Included orphan reference cleanup SQL before constraint re-attachment to avoid immediate `ForeignKeyViolation` during `eval-answer`.
- Issues encountered: Mixed-schema live environments can keep legacy FK definitions if tables predate corpus migrations.
- Next steps: Apply migration in runtime DB and verify RAG jobs complete with aligned corpus FK constraints.

## [2026-04-14] Session Summary (Official RAG Experiment Discipline + Normalized Logging)
- What was done: Extended `runRagTest` validation/config flow to enforce official comparison discipline (`officialRun`, `officialComparisonType`, `comparisonGatingBatchIds`), removed official auto-latest snapshot fallback, enforced bundled official modes, and added standardized experiment record persistence (`rag_eval_experiment_record`, `V19`).
- Key decisions: Official runs now reject conflicting variable combinations instead of silently overriding, and write explicit isolation metadata (`official_variable_axis`, `official_isolation_validated`).
- Issues encountered: Existing summary aggregation collapsed multi-mode retrieval; finalization now persists mode-wise payloads and selects representative mode by priority.
- Next steps: Run DB migration apply and verify official `gating_effect`/`rewrite_effect` requests fail fast on missing or incompatible snapshot identities.

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

## [2026-04-13] Session Summary (Rule Korean Ratio + Funnel Method Filter)
- What was done: Extended `GatingBatchRunRequest`/`AdminConsoleService` to accept `ruleMinKoreanRatio`, inject it into experiment config, and added `method_code` support to gating funnel API.
- Key decisions: Added dual config keys (`rule_min_korean_ratio`, `rule_min_korean_ratio_code_mixed`) to preserve default behavior while enabling dynamic override from GUI.
- Issues encountered: Existing `quality_gating_stage_result` rows are batch-level aggregates only, so per-method funnel stats are computed from `synthetic_query_gating_result`.
- Next steps: Validate API responses for `GET /gating/batches/{id}/funnel?method_code=A|B|C|D` against real batch data.

## [2026-04-14] Session Summary (RAG Snapshot Batch Binding)
- What was done: Added optional `sourceGatingBatchId` to `RagTestRunRequest` and updated `AdminConsoleService.runRagTest` to bind RAG experiments to a validated gating snapshot (`source_gating_run_id`) when provided.
- Key decisions: Enforced snapshot safety checks (batch exists, completed status, preset/method compatibility, non-null source run) and kept fallback to latest matching gating run when snapshot is omitted.
- Issues encountered: RAG config originally only wrote `memory_generation_strategies`; updated to also emit `source_generation_strategies` for downstream memory builder compatibility.
- Next steps: Add/extend integration coverage for invalid snapshot selection and successful snapshot-bound run config generation.

---

## Notes
- Keep this file concise.
- Record only major backend changes.
