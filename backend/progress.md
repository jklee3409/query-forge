# progress.md

## Overview
High-level backend progress tracking.

## [2026-04-19] Session Summary (Gating Result Pass-Stage Full Filter Coverage)
- What was done: Extended gating result query filtering to support all quality-gating pass stages via `pass_stage` (`rejected`, `passed_rule`, `passed_llm`, `passed_utility`, `passed_diversity`, `passed_all`) across controller/service/repository layers.
- Key decisions: Preserved existing endpoint shape (`GET /api/admin/console/gating/batches/{gatingBatchId}/results`) and added strict service-layer value normalization with stage-specific SQL predicates in repository to avoid business-logic refactor.
- Issues encountered: None.
- Next steps: Keep API docs/examples aligned with the expanded `pass_stage` set and monitor operator usage for potential alias needs.

## [2026-04-18] Session Summary (RAG Performance Metrics Aggregation for Test Runs)
- What was done: Extended `LlmJobService` RAG finalization path to capture per-stage command duration (`build-memory`, `eval-retrieval`, `eval-answer`) and total run duration, then persisted these as `metrics_json.performance`.
- Key decisions: Added only additive observability fields (`total_duration_ms`, `orchestration_overhead_ms`, `stage_duration_ms`, representative latency, rewrite overhead) without changing retrieval/answer score computation or gating/rewrite decisions.
- Issues encountered: None.
- Next steps: Add backend integration coverage to assert `metrics_json.performance` presence on completed RAG runs and validate rewrite-overhead math against latency rows.

## [2026-04-18] Session Summary (Gating Top10 Score + Request DTO Nesting)
- What was done: Extended admin gating request flow to support `target_top10` utility score and refactored `GatingBatchRunRequest` to nested DTO structure (`GatingRunConfig`) instead of large flat payload fields.
- Key decisions: Kept service defaults/validation semantics intact and mapped nested config into both `stage_config_json` and generated experiment YAML so pipeline execution receives the same operator inputs.
- Issues encountered: None.
- Next steps: Maintain API docs/examples with nested gating request body and add compatibility adapter only if external flat-payload clients are confirmed.

## [2026-04-18] Session Summary (Failed Generation Cleanup Guard)
- What was done: Added failed-generation cleanup path in `LlmJobService.handleJobFailure` to purge synthetic raw rows by `generation_batch_id` when `GENERATE_SYNTHETIC_QUERY` job reaches final failed state (retry exhausted).
- Key decisions: Cleanup targets strategy-split raw tables (`synthetic_queries_raw_a/b/c/d`) via new repository method `deleteSyntheticQueriesByGenerationBatch`, relying on registry FK cascade to remove dependent synthetic-linked rows.
- Issues encountered: None.
- Next steps: Add integration coverage for failure-exhausted generation job ensuring raw rows are removed and batch remains `failed` with cleanup metadata.

## [2026-04-17] Session Summary (Synthetic Run Random Sampling Request Wiring)
- What was done: Extended admin synthetic run DTO (`SyntheticBatchRunRequest`) with `randomChunkSampling` and wrote it to experiment config as `random_chunk_sampling` in `AdminConsoleService.runSyntheticGeneration`.
- Key decisions: Preserved existing validation/ranges (`max_total_queries` up to `2000`) and kept `limit_chunks` optional so full-corpus generation remains available when omitted.
- Issues encountered: None.
- Next steps: Add integration coverage for `random_chunk_sampling=true` request payload to config persistence path.

## [2026-04-17] Session Summary (Stage-Cutoff Validation/Config Wiring)
- What was done: Extended `RagTestRunRequest` with `stageCutoffEnabled/stageCutoffLevel` and updated `AdminConsoleService.runRagTest` to support stage-cutoff memory-source mode from full-gating batches.
- Key decisions: Enforced strict guards (`exploratory` only, `gatingApplied=true`, explicit `sourceGatingBatchId`, source snapshot must be completed `full_gating`, method compatibility, non-null `source_gating_run_id`) and persisted `stage_cutoff_*` keys into run config/experiment record.
- Issues encountered: Existing service contained multiple ongoing feature edits, so stage-cutoff changes were merged without reverting unrelated in-flight changes.
- Next steps: Add/extend API integration test coverage for invalid stage-cutoff combinations and successful full-gating cutoff run creation.

## [2026-04-17] Session Summary (Backend Concurrency/Transaction Scope Hardening)
- What was done: Reduced long transaction windows in high-latency paths by changing `RagService.ask/reindex` to run without a surrounding service transaction, switched `AdminConsoleService.runRagTest` to non-transactional wrapper mode for file-write flow, and added DB-level advisory lock orchestration for pipeline run start in `PipelineAdminService` + `PipelineAdminRepository`.
- Key decisions: Preserved existing business flow/API DTO behavior while tightening only transaction boundaries and start-run concurrency control (`pg_advisory_xact_lock` + re-check active run inside locked transaction).
- Issues encountered: Existing source files include mixed-encoding localized strings, so lock/transaction changes were applied in narrow scoped edits to avoid unrelated churn.
- Next steps: Monitor production metrics for reduced lock-wait/transaction-time in `ask`, `reindex`, and pipeline start bursts.

## [2026-04-17] Session Summary (Synthetic-free Baseline Validation/Config)
- What was done: Extended `RagTestRunRequest` with `syntheticFreeBaseline` and updated `AdminConsoleService.runRagTest` to support exploratory synthetic-free baseline runs with method list empty (`[]`), forced `ungated + rewrite_off`, and baseline-specific retrieval mode config.
- Key decisions: Kept official run discipline unchanged (baseline blocked for official mode), rejected conflicting snapshot/batch inputs in baseline mode, and persisted `synthetic_free_baseline` into experiment config and retrieval metadata.
- Issues encountered: Existing service file already had unrelated pending edits (rule defaults/delete API wiring), so baseline changes were added without reverting external deltas.
- Next steps: Validate API-level rejection cases for baseline + official/snapshot payloads from Admin UI and external clients.

## [2026-04-15] Session Summary (RAG Detail Metric Contribution Enrichment)
- What was done: Updated `LlmJobService.loadRewriteCasesForRun` to persist extended rewrite contribution fields into `rag_test_result_detail.metric_contribution` (`raw_confidence`, `best_candidate_confidence`, `confidence_delta`, `rewrite_reason`).
- Key decisions: Kept existing detail-row schema intact and enriched JSON payload only, so UI/debug consumers can inspect rewrite decisions without DB schema changes.
- Issues encountered: Existing ingestion path only saved retrieval metric deltas (`raw_mrr/mode_mrr/raw_ndcg/mode_ndcg`), which obscured rewrite decision diagnostics.
- Next steps: Expose new contribution fields in run-detail UI if deeper per-sample rewrite analysis is needed.

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
