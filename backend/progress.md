# progress.md

## Overview
High-level backend progress tracking.

## [2026-05-04] Session Summary (Anchor Re-extraction Scope Precedence Fix)
- What was done: Updated `AnchorExtractionService.findTargetChunks(...)` so `documentIds` takes precedence over `chunkIds` in `POST /api/admin/corpus/anchors/extract`. Added integration test `anchorReExtractionWithDocumentScopeRemovesDocumentAnchorsFirst` to verify document-wide evidence deletion even when a chunk filter is also present.
- Key decisions: Preserved existing chunk-only scoped re-extraction (`chunkIds` only) and changed only mixed-scope behavior to prevent stale document anchors after re-extraction.
- Issues encountered: Previous mixed-scope behavior used `documentIds AND chunkIds` intersection, which could leave old anchors in non-selected chunks of the same document.
- Next steps: Keep API usage explicit: use `documentIds` for document-level reset/re-extract and `chunkIds` for chunk-only re-extract.

## [2026-05-04] Session Summary (Backend Index: Anchor Injection Purpose Documentation)
- What was done: Updated `backend/index.md` Key Notes to explicitly document that anchor extraction/injection is a rewrite-grounding control for preserving technical intent when Korean rewrite over English technical-doc memory drops anchor terms.
- Key decisions: Kept this as documentation-only clarification tied to existing `rewrite_anchor_injection_enabled` runtime/config path.
- Issues encountered: None.
- Next steps: Apply extraction-quality hardening in pipeline glossary path so injected anchors stay technical (exclude polite/functional phrases).

## [2026-05-04] Session Summary (Backend Documentation Realignment)
- What was done: Updated `backend/README.md` and `backend/index.md` to reflect current backend scope: Admin Console APIs, corpus/pipeline orchestration APIs, online RAG APIs, React admin static serving, warning status model, and anchor extraction delegation to `pipeline/cli.py extract-anchor-candidates`.
- Key decisions: Removed legacy wording that implied Thymeleaf-admin 중심/미구현 RAG 상태, and aligned docs with current controllers/services/migrations already in repository.
- Issues encountered: None.
- Next steps: Keep backend docs synchronized with future API surface changes (`admin/console`, `admin/corpus`, `admin/pipeline`, `rag`).

## [2026-05-04] Session Summary (Anchor Re-extraction -> Pipeline Glossary Delegation)
- What was done: Refactored `AnchorExtractionService` so `POST /api/admin/corpus/anchors/extract` no longer runs backend-local anchor heuristics; it now writes scoped chunk JSONL, calls `python pipeline/cli.py extract-anchor-candidates`, reads returned candidate JSONL, then continues existing glossary evidence replace/term refresh/synthetic remap flow.
- Key decisions: Chose pipeline-logic delegation as the primary path to remove duplicate anchor extraction implementations and keep glossary/anchor candidate semantics aligned between ingest pipeline and backend re-extraction API.
- Issues encountered: The new pipeline command initially failed on UTF-8 BOM JSONL inputs; fixed by reading input with `utf-8-sig` in `pipeline/preprocess/extract_anchor_candidates.py`.
- Next steps: Evaluate extraction precision/coverage deltas on non-Spring sources through existing Anchor Eval runs and tune only in pipeline extractor path when needed.

## [2026-05-04] Session Summary (Anchor Re-extraction Hybrid Candidate Scoring)
- What was done: Upgraded `AnchorExtractionService` keyphrase extraction for `POST /api/admin/corpus/anchors/extract` from simple n-gram accumulation to a hybrid scorer that combines regex-derived technical candidates, phrase normalization, stopword/all-stopword rejection, token rarity bonus (`1/sqrt(freq)`), and technical-marker bonuses (camelCase, symbol separators, alpha+digit patterns). Added integration coverage in `CorpusAdminMutationIntegrationTest` for scoped chunk re-extraction.
- Key decisions: Kept existing extraction pipeline contract and DB flow intact (target chunk resolution -> evidence replacement -> glossary refresh -> synthetic anchor remap) and only strengthened candidate ranking/filtering inside the existing service to minimize churn.
- Issues encountered: None.
- Next steps: Compare anchor precision/coverage on non-Spring technical sources via existing Anchor Eval run flow and tune score weights only if false positives remain high.

## [2026-05-02] Session Summary (Corpus Anchor List API for Pipeline UI)
- What was done: Added `GET /api/admin/corpus/anchors` in `CorpusAdminController/Service/Repository`, with new DTO `AnchorSummary`. The API supports `document_id`, `chunk_id`, `keyword`, `active_only`, `limit`, `offset` and returns paginated anchor rows ordered by scoped evidence density.
- Key decisions: Introduced a dedicated anchor listing path instead of extending existing glossary list responses, so document/chunk evidence scope can be handled as a first-class filter contract.
- Issues encountered: None.
- Next steps: If anchor volume grows significantly, consider adding index tuning around `corpus_glossary_evidence(term_id, document_id, chunk_id)` for scoped query acceleration.

## [2026-05-02] Session Summary (Pipeline Warning Status Model + History Backfill)
- What was done: Added Flyway `V27__add_pipeline_warning_status_and_backfill.sql` to extend `corpus_runs.run_status` and `corpus_run_steps.step_status` with `warning`, then backfilled existing pipeline history to warning where steps were skipped or produced zero-effective outputs. Updated pipeline dashboard issue query to include warning steps in recent problematic-step list.
- Key decisions: Warning was implemented as first-class status (not metadata-only flag) so admin run history and API consumers can distinguish partial-success from full-success consistently.
- Issues encountered: Initial backfill cast failed on heterogeneous `metrics_json` shapes; migration predicates were rewritten with safe text checks/regex-gated numeric parsing.
- Next steps: Restart backend process to activate updated `PipelineAdminService` runtime warning aggregation logic for newly generated runs.

## [2026-05-02] Session Summary (Pipeline full_ingest Failure Debug + Retry)
- What was done: Investigated failed Admin Pipeline run `e28f9bce-37a1-4569-96bb-12dbd62e83ec` (`full_ingest`) and confirmed collect-stage crash on `requests.exceptions.HTTPError` (`404`) for templated URL `https://arahansa.github.io/docs_spring/{spring-framework-docs}/beans.html`. Triggered backend retry API and verified rerun `7be03cad-094c-424c-8852-e164f269b17d` completed with `success`.
- Key decisions: Applied operational recovery only, because collector-side invalid URL/fetch-failure handling was already present in the current workspace at debug time.
- Issues encountered: Rerun succeeded but marked `normalize/chunk/glossary/import` as skipped (`no_documents_pending`) after collect persistence.
- Next steps: If this source needs broader ingestion coverage, tighten source crawling scope configuration to avoid placeholder/template links and improve valid-page discovery.

## [2026-05-01] Session Summary (Anchor Re-extraction API + Active Anchor Mapping)
- What was done: Added Flyway `V25__add_anchor_reextract_and_query_anchor_links.sql` to create `synthetic_query_anchor_link` and backfill link rows from synthetic query source chunks to active glossary evidence. Added corpus API `POST /api/admin/corpus/anchors/extract` and implemented `AnchorExtractionService` to: resolve selected document/chunk scope, replace chunk-level glossary evidence, refresh glossary term active/evidence state, and remap affected synthetic queries to valid active anchors.
- Key decisions: Scoped replacement deletes to selected chunk evidence only, preserving all existing corpus document/chunk rows. Kept legacy raw `glossary_terms` snapshot unchanged for backward compatibility while adding `mappedAnchors` in synthetic query detail as the active-anchor source.
- Issues encountered: None in backend test path; full backend tests passed.
- Next steps: Extend admin GUI trigger for anchor extraction and evaluate adopting mapped active anchors in downstream memory/rewrite runtime paths.

## [2026-04-28] Session Summary (Gating Dense/Hybrid Failure Guard + Retriever Mode Exposure)
- What was done: Updated gating retriever config default so `dense_fallback_enabled` is enabled by default (unless fixed-mode preset flow), preventing whole-batch failure when sentence-transformers/torch dense backend is unavailable. Extended `GatingBatchRow` mapping to expose `retrieverMode` from `stage_config_json.retriever_config.retriever_mode`.
- Key decisions: Kept explicit user override behavior; if operators set fallback false intentionally, strict failure semantics stay available. Added retriever mode exposure in DTO/repository only (no schema change).
- Issues encountered: Existing E-method specific fallback path (`forceBm25RetrieverConfig`) remains in service and is orthogonal to this generic Dense/Hybrid failure guard.
- Next steps: Run one gating batch each with `dense_only` and `hybrid` under no-sentence-transformers environment and verify batch completes with fallback backend.

## [2026-04-28] Session Summary (RAG Rewrite Retrieval Strategy Request/Config Wiring)
- What was done: Extended `RagTestRunRequest` with `rewriteRetrievalStrategy`, added backend normalization/validation (`replace`, `interleave`, `max_score`), and persisted the strategy into generated experiment config plus `rag_eval_experiment_record.rewrite_config`.
- Key decisions: Default strategy remains `replace` when omitted so existing clients and historical run semantics are unchanged.
- Issues encountered: None.
- Next steps: Add API-level integration coverage for invalid strategy rejection and default/explicit strategy config persistence.

## [2026-04-28] Session Summary (E Gating Dense Dependency Fallback)
- What was done: Investigated failed gating batch `15660322-b3a9-4391-a88a-464fc6e5e11a` and confirmed the failure came from retrieval utility dense backend bootstrap (`sentence-transformers` unavailable), then updated `AdminConsoleService.runGating` to force BM25-only retriever config by default for method `E` when retriever config is omitted.
- Key decisions: Kept user-provided retriever config untouched; applied fallback only to default E-path to unblock English strategy gating without changing the overall gating pipeline flow.
- Issues encountered: Existing batch was already persisted with hybrid+dense-required config and cannot be auto-healed; rerun is required.
- Next steps: Re-run quality gating for method `E` (same generation batch) and verify `stage_config_json.retriever_config.retriever_mode = bm25_only` plus successful completion.

## [2026-04-28] Session Summary (Raw E Constraint Hotfix)
- What was done: Added Flyway `V22` to normalize `synthetic_queries_raw_e` after live `E` generation failure, removing copied `D`-only strategy checks, widening the generic strategy check to include `E`, forcing `query_language` default to `en`, and restoring missing FK constraints for `generation_method_id` / `generation_batch_id`.
- Key decisions: Fixed the live DB bug with a follow-up migration instead of rewriting applied `V21`; the fix is idempotent enough to be applied manually first and later picked up by Flyway startup without changing generation/runtime code paths.
- Issues encountered: `V21` created `synthetic_queries_raw_e` via `LIKE synthetic_queries_raw_d INCLUDING CONSTRAINTS`, so `D`-only checks and `A-D` generic checks were copied into the new table and blocked the first real `E` insert.
- Next steps: Apply `V22` in the target DB, retry failed generation job `2b0ed910-8e2f-4186-8424-436b3c9b8148`, and confirm `synthetic_queries_raw_e` rows are written for batch `ca64cad2-27d4-4510-b251-a4037bbd8dfd`.

## [2026-04-28] Session Summary (English Strategy E + Eval Query Language Wiring)
- What was done: Added Flyway `V21` for `synthetic_queries_raw_e`, expanded method/registry constraints to include `E`, added `eval_samples.user_query_en/query_language`, and wired Admin Console DTO/service/repository paths to persist `eval_query_language` and expose English dataset preview fields.
- Key decisions: Admin synthetic remains DB-driven for strategy listing, so backend changes focused on schema/default config normalization and language-aware defaults (`E` => `query_language=en`, Korean-ratio defaults `0.0`).
- Issues encountered: Existing runtime detail loaders assumed `user_query_ko`; `LlmJobService` now resolves display query text by sample language.
- Next steps: Apply the new migration in the target DB and run one Admin synthetic `E` batch plus one snapshot-bound English RAG test.

## [2026-04-21] Session Summary (RAG Test Run Names + Fixed Presets)
- What was done: Extended `RagTestRunRequest` with `runName`, persisted it as `rag_test_run.run_label` and experiment config `run_name`, added migration `V20` to rename legacy auto-labeled RAG runs, and made Admin RAG tests resolve retriever settings through fixed mode presets.
- Key decisions: RAG tests default `retrieval_top_k` to `10`; BM25/Dense/Hybrid now force server-side weights and flags (`candidate_pool_k=50`, Hybrid `0.60/0.32/0.08`, dense model fixed to `intfloat/multilingual-e5-small`, hash fallback off, Cohere rerank off) while leaving quality-gating's existing configurable resolver path intact.
- Issues encountered: None.
- Next steps: Add request-level integration coverage for `runName` persistence and fixed retriever preset normalization.

## [2026-04-21] Session Summary (Retriever Config API Wiring)
- What was done: Added Admin Console `RetrieverConfigRequest` and wired `runGating` / `runRagTest` to write explicit retriever mode, dense model, fallback, rerank, candidate-pool, and fusion-weight settings into stage config, experiment YAML, and RAG experiment records.
- Key decisions: Preserved existing RAG retrieval strategy mode bundling while treating BM25/Dense/Hybrid as a separate ranking-engine config. Default ranking mode is Hybrid with `intfloat/multilingual-e5-small`, dense required, hash fallback disabled, and Cohere rerank enabled.
- Issues encountered: Frontend build regenerated the backend static React JS asset hash.
- Next steps: Add request-level integration assertions for retriever config persistence and run mode-by-mode RAG evaluations through Admin.

## [2026-04-20] Session Summary (RAG Research Mode Bundle)
- What was done: Updated exploratory Admin RAG retrieval mode resolution so synthetic-backed runs include `memory_only_gated` and `rewrite_always` with `raw_only` and the selected selective rewrite mode.
- Key decisions: Kept synthetic-free baseline as `raw_only` only; synthetic-backed runs now satisfy the AGENTS query rewrite evaluation shape needed to distinguish memory quality, forced rewrite quality, and selective gate behavior.
- Issues encountered: None.
- Next steps: Add request-level integration assertions for the bundled exploratory mode list.

## [2026-04-20] Session Summary (RAG Raw Mode Pairing + Threshold Default)
- What was done: Updated `AdminConsoleService.runRagTest` so synthetic-backed RAG runs include `raw_only` with rewrite/memory modes, including official gating-effect runs; synthetic-free baseline still resolves to `raw_only` only.
- Key decisions: Changed backend fallback/default `rewrite_threshold` from `0.05` to `0.10` in request handling and generated experiment configs.
- Issues encountered: None.
- Next steps: Add request-level integration coverage for exploratory selective rewrite, rewrite-always, official gating-effect, and synthetic-free baseline mode resolution.

## [2026-04-19] Session Summary (Prompt-based Rewrite Candidate Generation in Ask Path)
- What was done: Added `RewriteCandidateService` and routed `RagService.ask`/`previewRewrite` candidate construction through prompt-driven LLM generation instead of hardcoded-only templates.
- Key decisions: Prompt loading resolves `selective_rewrite_v2` first (`v1` fallback), supports env-driven Gemini/OpenAI providers, and falls back to deterministic heuristic candidates on any LLM/prompt failure.
- Issues encountered: None.
- Next steps: Add integration coverage for prompt/LLM fallback matrix and monitor rewrite adoption deltas after v2 rollout.

## [2026-04-19] Session Summary (RAG Timezone Baseline + Run Label Time Shortening)
- What was done: Updated backend time baseline settings in `application.yml` to use `Asia/Seoul` for DB session initialization (`SET TIME ZONE 'Asia/Seoul'`) and app-level serialization/JDBC timezone configuration, and shortened newly created RAG run labels in `AdminConsoleService` to `yyyy-MM-dd HH:mm` KST format.
- Key decisions: Kept TIMESTAMPTZ schema and instant-based persistence model unchanged to avoid timestamp semantic breakage; applied timezone baseline at connection/session and presentation-label levels.
- Issues encountered: Existing historical rows remain valid instants; no destructive timestamp shift migration was introduced.
- Next steps: After backend restart, verify newly created run labels and timestamp display alignment in `/admin/rag-tests`.

## [2026-04-19] Session Summary (RAG Delete Full-Cascade Scope)
- What was done: Expanded `AdminConsoleRepository.deleteRagTestRun(...)` to delete run-linked `llm_job` rows before `rag_test_run` removal and then remove linked experiment artifacts by collected `experiment_run_id` set (`eval_judgments`, `retrieval_results`, `rerank_results`, `online_queries`, `experiment_runs`).
- Key decisions: Added run-existence pre-check to keep missing-run semantics stable, and collected experiment IDs from both direct FK (`source_experiment_run_id`) and persisted JSON payloads (`metrics_json`/`metrics`/`result_json`) to prevent orphaned eval history.
- Issues encountered: Retrieval/rerank eval rows may reference run lineage only through metadata JSON, so cleanup uses explicit `metadata ->> 'experiment_run_id'` matching.
- Next steps: Evaluate whether memory-build lineage cleanup (`memory_entries.metadata.memory_build_run_id`) should be included in the same delete boundary.

## [2026-04-19] Session Summary (Gating Pass-Stage Exact-Semantics Update)
- What was done: Updated quality-gating result `pass_stage` filtering semantics in backend so each stage option returns rows that passed up to that stage only and then failed at the immediate next stage (`passed_rule/llm/utility/diversity`), while `passed_all` remains final accepted rows.
- Key decisions: Introduced `failed_rule` as the primary reject filter token and kept backward compatibility by mapping legacy `rejected` to the same behavior in service-layer normalization.
- Issues encountered: None.
- Next steps: Keep API docs/examples aligned with `failed_rule` as canonical and preserve `rejected` only as compatibility alias.

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
