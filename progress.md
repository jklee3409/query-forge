# progress.md

## Overview
High-level progress tracking for the project.

## [2026-04-28] Session Summary (English Synthetic E + English Short-User 80 Eval Path)
- What was done: Added English synthetic generation strategy `E` end-to-end for Admin synthetic runs, extended split raw storage/schema/method registry to `A/B/C/D/E`, made quality gating and selective rewrite language-aware for English eval runs, added separate English short-user-80 dataset assets/scripts, and exposed eval query language selection in Admin RAG UI/runtime.
- Key decisions: Kept AGENTS strategy-separated storage by adding `synthetic_queries_raw_e` instead of merging tables; English short-user 80 uses a separate dataset id/key (`human_eval_short_user_80_en`) and runtime now selects `user_query_en` via `eval_query_language=en`.
- Issues encountered: Existing `GatingPage.jsx` JSX `->` warnings remain pre-existing; runtime rewrite tests needed fail-open heuristic fallback and updated patch target after the new language-aware rewrite wrapper.
- Next steps: Apply Flyway `V21`, run `scripts/build_short_user_en_dataset.py` without `--skip-db` against the target DB, then execute paired KO/EN RAG runs on matched snapshots.

## [2026-04-21] Session Summary (RAG Test Presets + Run Names)
- What was done: Updated `/admin/rag-tests` so retriever mode drives fixed RAG presets, added operator-provided RAG test names through the Admin API, backfilled legacy default RAG labels, and rebuilt the bundled React asset.
- Key decisions: RAG tests now force `intfloat/multilingual-e5-small`, disable hash fallback and Cohere rerank for clean BM25/Dense/Hybrid comparisons, use Hybrid weights `0.60/0.32/0.08`, candidate pool `50`, and default `retrieval_top_k=10`.
- Issues encountered: Vite still emits the existing `GatingPage.jsx` literal `->` JSX warnings, but build completes.
- Next steps: Run one named BM25, Dense, and Hybrid RAG test on the same dataset/snapshot and compare run-name-labeled results.

## [2026-04-21] Session Summary (Retriever Mode Separation + Admin Controls)
- What was done: Implemented BM25 Only, Dense Only, and Hybrid local retrieval modes across quality gating utility scoring, eval retrieval, answer eval, memory lookup, and rewrite candidate evaluation. Added explicit retriever config propagation from Admin GUI/API into experiment YAML and refreshed the bundled admin React asset.
- Key decisions: Kept existing RAG strategy modes (`raw_only`, `memory_only_*`, `rewrite_*`) separate from the new ranking-engine mode. Dense/Hybrid now default to `intfloat/multilingual-e5-small` and only use hash fallback when `dense_fallback_enabled=true` is explicitly configured.
- Issues encountered: Frontend production build still emits pre-existing JSX warnings for literal `->` option text in `GatingPage.jsx`, but build completes.
- Next steps: Run same dataset/snapshot BM25 vs Dense vs Hybrid RAG tests and compare retrieval quality together with latency.

## [2026-04-20] Session Summary (Local Retriever BM25 + Dense Switch)
- What was done: Switched Python-side local retrieval from hash/overlap scoring to a cached BM25 + dense retriever for eval retrieval, eval answer retrieval, memory lookup, and quality-gating utility scoring. Added CPU defaults for `intfloat/multilingual-e5-small` and documented local retrieval env knobs.
- Key decisions: Kept Cohere as an external reranker when available, but made local ranking strong enough to be a meaningful fallback. `sentence-transformers` is now a pipeline dependency; if the runtime does not have it installed yet, the new retriever falls back to BM25 + hash embedding.
- Issues encountered: Current interpreter lacks `sentence-transformers`, so validation used BM25 + hash fallback and improved local-only `human_eval_short_user_80` metrics to Recall@5 `0.4750`, Hit@5 `0.5375`, MRR@10 `0.3425`, nDCG@10 `0.3811`.
- Next steps: Sync the backend pipeline Python environment with the updated pipeline dependency set, then rerun controlled A/C/D RAG tests with Cohere quota available or explicitly disabled.

## [2026-04-20] Session Summary (Rewrite Evidence Scoring + Research Modes)
- What was done: Fixed selective rewrite evidence scoring so candidate queries recompute snapshot-memory affinity instead of reusing raw-query memory similarity; Cohere rerank fallback no longer emits synthetic relevance scores. Retrieval/memory lookup now uses hybrid semantic + lexical + technical-token scoring, and Admin RAG runs include `memory_only_gated` and `rewrite_always` alongside `raw_only` and selective modes.
- Key decisions: Preserved the AGENTS pipeline order and strategy-separated synthetic storage while making each snapshot evaluation expose raw retrieval, memory-only retrieval, forced rewrite, and selective rewrite for research attribution.
- Issues encountered: `python -m unittest discover pipeline/tests -v` still fails in pre-existing `test_corpus_import` migration setup (`corpus_sources` missing), while targeted runtime/LLM tests and backend tests pass.
- Next steps: Run fresh A/C/D rewrite-effect tests and compare `raw_only`, `memory_only_gated`, `rewrite_always`, and `selective_rewrite` to separate candidate quality from selective gate quality.

## [2026-04-20] Session Summary (RAG Memory Snapshot Isolation + Raw Comparison)
- What was done: Fixed RAG quality-test memory contamination by making memory builds clear stale rows for the selected snapshot and tagging memory rows with `memory_experiment_key`; retrieval/answer eval now loads only the current experiment's memory. Cleaned live DB stale rejected memory rows, removed orphan memory embeddings, and backfilled memory experiment keys.
- Key decisions: `raw_only` is now included alongside synthetic rewrite/memory modes for non-baseline RAG tests, while synthetic-free baseline remains raw-only. Admin default `rewrite_threshold` is now `0.10`.
- Issues encountered: Existing frontend build still reports unrelated `GatingPage.jsx` JSX warnings for literal `->` labels, but build completes.
- Next steps: Run a fresh same-dataset synthetic rewrite test and compare `raw_only` vs rewrite mode in the single-run detail modal.

## [2026-04-19] Session Summary (Synthetic-Random Short-User Dataset 80 Rebuild)
- What was done: Added `scripts/rebuild_short_user_dataset_from_synthetic.py` and rebuilt dataset `b2d47254-8655-4c9c-81ac-7615677ec5bd` from live `synthetic_queries_raw_all` by random sampling 80 candidates, compressing queries into short Korean user style, and refreshing both DB dataset items and `data/eval/human_eval_short_user_test_80.jsonl`.
- Key decisions: Kept retrieval-aware schema unchanged (`expected_doc_ids`, `expected_chunk_ids`, `expected_answer_key_points`) and stored source provenance in sample metadata (`source_synthetic_query_id`, `source_generation_strategy`, `target_method`).
- Issues encountered: Initial compression heuristics produced low-quality particle-only prompts; stopword filters and compression templates were tightened and rerun.
- Next steps: Run one controlled A/C quality test on the rebuilt set and optionally add a manual reject-list for low-information compressed prompts.

## [2026-04-19] Session Summary (Rewrite v2 + Backend Prompt Unification)
- What was done: Added retrieval-optimized rewrite prompt asset `selective_rewrite_v2` and switched pipeline rewrite prompt resolution to prefer v2 with v1 fallback.
- Key decisions: Unified backend `/api/chat/ask` rewrite candidate generation with prompt-based LLM path (Gemini/OpenAI env-driven) plus safe heuristic fallback, so admin online ask path no longer relies only on hardcoded templates.
- Issues encountered: Existing workspace had unrelated docs report modifications; this change was scoped to rewrite prompt loading/generation paths only.
- Next steps: Run one A/B compare on the same snapshot with v1/v2 prompt roots and tune threshold/candidate wording by category (`short_user`, `follow_up`, `code_mixed`).

## [2026-04-19] Session Summary (RAG Compare Time Formatting + KST Alignment)
- What was done: Fixed `/admin/rag-tests` compare-workspace time presentation so duration metrics use real converted values (`ms -> s -> m+s`) consistently across metric cards, latest-summary cards, and run-history metric snippets, while removing raw-ms secondary text from workspace cards.
- Key decisions: Reused existing duration helpers and unified workspace formatting through the same conversion policy already used in detailed table presentation; kept metric extraction and delta math unchanged.
- Issues encountered: Existing unrelated JSX warnings in `GatingPage.jsx` remain out of scope.
- Next steps: Validate with long-duration run pairs that operator interpretation is faster for `Total Duration`, `Eval-Retrieval Stage`, and `Eval-Answer Stage`.

## [2026-04-19] Session Summary (RAG Run Full-Delete Cascade Expansion)
- What was done: Expanded Admin RAG run delete path to enforce full deletion scope, including run-linked `llm_job`/`llm_job_item` history and linked `experiment_runs` artifacts (`eval_judgments`, `retrieval_results`, `rerank_results`, `online_queries`) in one transactional flow.
- Key decisions: Preserved existing API contract and missing-run behavior while deriving linked `experiment_run_id` from `source_experiment_run_id` plus persisted JSON payloads (`rag_test_run.metrics_json`, `rag_test_result_summary.metrics_json`, `rag_eval_experiment_record.metrics`, `llm_job.result_json`) for deterministic cleanup.
- Issues encountered: Some eval artifacts are linked via metadata (`metadata->>'experiment_run_id'`) rather than FK columns, so explicit metadata-based delete predicates were required.
- Next steps: Decide whether `memory_entries` rows keyed by `metadata.memory_build_run_id` should also be part of the full-delete boundary and whether FK-level cascade policies should be tightened in future migrations.

## [2026-04-19] Session Summary (RAG Detailed Compare Table Density + Time Unit Normalization)
- What was done: Refined only the `/admin/rag-tests` detailed comparison table for denser readability by adjusting table typography/row spacing, rebalancing column widths, and centering the `Delta / Change` + `Result` judgment flow.
- Key decisions: Kept existing API/data contracts and metric math unchanged; applied presentation-only updates in `frontend/src/pages/RagPage.jsx` (`buildDeltaInterpretation` and table display helpers) and `frontend/src/styles.css` (table-specific classes).
- Issues encountered: Existing unrelated `GatingPage.jsx` JSX warning lines remain outside this scope.
- Next steps: Run operator UI-smoke with long performance values to confirm preferred `%` vs `x` wording threshold in table delta headlines.

## [2026-04-19] Session Summary (RAG Compare Workspace Card UX Refinement)
- What was done: Refined `/admin/rag-tests` compare workspace cards (run info cards, winner summary cards, and grouped metric cards) for faster A/B decision reading, adding interpreted change text and duration-friendly value presentation.
- Key decisions: Kept existing API/data contracts and metric extraction/delta math unchanged; implemented presentation helpers in `frontend/src/pages/RagPage.jsx` and card-focused styles in `frontend/src/styles.css`.
- Issues encountered: Existing `GatingPage.jsx` JSX warning lines (`->` in option labels) are pre-existing and outside this task scope.
- Next steps: Verify operator readability on real runs and tune wording thresholds for `% vs x` performance change messages.

## [2026-04-19] Session Summary (RAG Detailed Compare Table UX Refactor)
- What was done: Refactored only the `/admin/rag-tests` quality/performance detailed comparison table into a section-aware comparison table with interpreted delta/change, result chips, KPI row emphasis, and run-label readability improvements.
- Key decisions: Preserved existing API contracts and metric extraction/delta calculation logic; applied presentation-layer-only changes in `frontend/src/pages/RagPage.jsx` and `frontend/src/styles.css`.
- Issues encountered: Existing workspace had unrelated modified docs/config files and pre-existing JSX warning lines in `GatingPage.jsx`; scope was kept to the detailed comparison table components/styles only.
- Next steps: Validate operator scan speed/readability with real run pairs and tune delta/result wording if team preference favors Korean copy or stricter KPI wording.

## [2026-04-19] Session Summary (AGENTS Start-Checklist Enforcement)
- What was done: Added mandatory session-start checklist rule to `.codex/AGENTS.md` and aligned `.codex/progress.md` so Codex begins implementation turns by confirming AGENTS/progress/index checks and planned progress update.
- Key decisions: Enforced checklist exposure in the first working update before repository-modifying actions.
- Issues encountered: None.
- Next steps: Keep checklist format consistent across future implementation turns and adjust only when AGENTS process rules change.

## [2026-04-19] Session Summary (Quality Gating Stage Filter Semantics + Label Realignment)
- What was done: Updated `/admin/quality-gating` per-query stage filter semantics so each selected stage returns queries that passed up to that stage only (`passed_rule/llm/utility/diversity` now means "next stage failed", `passed_all` means final accepted). Replaced UI "탈락" filter with `Rule 탈락` (`failed_rule`) and renamed stage option labels to explicit transition wording (`Rule 통과 -> LLM 탈락`, etc.).
- Key decisions: Preserved API endpoint/DTO shapes and added backward-compatible alias handling so legacy `pass_stage=rejected` is normalized to the same `failed_rule` behavior.
- Issues encountered: None.
- Next steps: Run operator smoke validation on `/admin/quality-gating` for each stage option and align API docs/examples with `failed_rule` as the primary reject filter token.

## [2026-04-19] Session Summary (RAG Compare UI/UX Decision-Support Redesign)
- What was done: Re-read `.codex/AGENTS.md` and improved `/admin/rag-tests` comparison experience by restructuring metrics into explicit groups (`Retrieval Quality`, `Answer Quality`, `Performance`), adding top-level comparison summary (overall winner + retrieval/latency deltas), upgrading metric cards (A/B values, delta, direction badges, core-KPI emphasis), and improving linked comparison table readability (short run labels, numeric alignment, delta/result emphasis, grouped row labels).
- Key decisions: Kept existing API contracts and metric extraction/calculation logic unchanged; refactored only frontend information architecture/rendering (`frontend/src/pages/RagPage.jsx`, `frontend/src/styles.css`) with reusable metric-group metadata.
- Issues encountered: Existing UI text regions include mixed-encoding strings, so changes were scoped to stable comparison blocks and style classes to avoid unrelated churn.
- Next steps: Validate operator readability with real run pairs and tune summary weighting/core KPI priorities based on team decision criteria.

## [2026-04-19] Session Summary (Short-User Eval 80 Full Regeneration + Baseline Origin Verification)
- What was done: Replaced dataset `b2d47254-8655-4c9c-81ac-7615677ec5bd` with 80 fully regenerated short-user evaluation items (corpus chunk-first generation, not synthetic candidate reselection), re-audited mapping, and added `scripts/verify_eval_dataset_origin.py` for dataset-origin diagnostics.
- Key decisions: Adopted corpus-grounded new query generation to align with rewrite-effect research intent (realistic short user prompts) while preserving retrieval-aware schema and existing dataset ID wiring.
- Issues encountered: Initial regenerated prompts showed term-artifact noise; generator filters/templates were iteratively tightened until structural issues were zero and synthetic text overlap was zero.
- Next steps: Run controlled A/C rewrite-effect experiment on regenerated 80 set and perform focused manual QA for outlier technical term prompts.

## [2026-04-19] Session Summary (Short-User Eval Dataset 40->80 Expansion with Chunk-Mapping Audit)
- What was done: Audited dataset `b2d47254-8655-4c9c-81ac-7615677ec5bd` (base 40 items) for grounded mapping quality against live `corpus_chunks` and then expanded to 80 items by adding 40 new short-user queries sourced from current `synthetic_queries_raw_all` + mapped corpus chunk IDs.
- Key decisions: Kept retrieval-aware schema identical (`expected_doc_ids`, `expected_chunk_ids`, `expected_answer_key_points`) and updated the same dataset ID metadata/version to 80 while preserving original 40-item JSONL as baseline input.
- Issues encountered: Lexical overlap heuristics produced a small warning set for some domain-token-heavy prompts, but structural mapping checks (chunk existence/doc consistency) remained clean (`issue_count=0`).
- Next steps: Run one A/C comparative RAG smoke on the updated 80-item set and verify rewrite-gain deltas remain stable versus 40-item baseline.

## [2026-04-19] Session Summary (Quality Gating Result Stage Filter Expansion + Progress Tracking Compliance)
- What was done: Re-read `.codex/AGENTS.md` and expanded Admin quality-gating per-query result filter from partial stage options to full stage coverage (`rejected`, `passed_rule`, `passed_llm`, `passed_utility`, `passed_diversity`, `passed_all`, plus unfiltered `전체`), wiring frontend selector -> backend API -> repository SQL.
- Key decisions: Kept the existing GET endpoint and business flow intact; added a single normalized query parameter (`pass_stage`) and stage-specific SQL predicates without introducing new DTO contracts.
- Issues encountered: Existing admin UI source contains mixed-encoding localized regions, so edits were constrained to stable filter/query blocks to avoid unrelated churn.
- Next steps: Run operator smoke check on `/admin/quality-gating` with each stage option to validate expected counts against funnel stage transitions.

## [2026-04-19] Session Summary (Gating/Synthetic Batch Filter Tightening + Result Token Badge UX)
- What was done: Updated Admin `GatingPage` per-query result table to render `query_type`, `rejected_stage`, `rejected_reason` as icon-like token badges instead of plain text, and tightened batch dropdown filters so failed/cancelled generation/gating batches are excluded from operator selection paths. Also updated Admin `SyntheticPage` query filter batch dropdown to hide failed/cancelled generation batches.
- Key decisions: Kept API/DTO contracts unchanged and applied UI-only rendering/filter logic; token badge parser accepts array/object/JSON-string/delimited text input to avoid backend coupling.
- Issues encountered: Existing frontend files contain mixed-encoding localized literals, so changes were applied in stable JSX/CSS blocks only.
- Next steps: Align token icon dictionary (`SU/FU/...`) with product terminology and add optional display-name mapping for non-engineering operators.

## [2026-04-19] Session Summary (Admin LLM Job Polling Removal)
- What was done: Removed periodic polling hooks that repeatedly called `/api/admin/console/llm-jobs?limit=120` from admin pages where this was causing unnecessary traffic/noise.
- Key decisions: Preserved explicit refresh triggers (manual refresh buttons and post-action reloads) instead of background polling, matching the “refresh-time reflection” requirement.
- Issues encountered: None.
- Next steps: Monitor operator flow and add per-section lightweight refresh CTA if any status visibility gaps appear.

## [2026-04-19] Session Summary (Quality Gating Runtime Stop + Data Purge Operations)
- What was done: Stopped active `gate-queries` runtime processes and removed related quality-gating data only for targeted in-flight/history IDs, including linked `llm_job`, `llm_job_item`, `synthetic_query_gating_result`, `synthetic_query_gating_history`, `synthetic_queries_gated`, and `quality_gating_batch` rows.
- Key decisions: Executed deletions with explicit target ID scopes and transactional ordering to avoid collateral cleanup outside requested batches/jobs.
- Issues encountered: Running-status snapshot changed during cleanup window, so target set was re-validated before each destructive step.
- Next steps: Add an operator-safe SQL/maintenance script for targeted gating batch+job cleanup to reduce manual repeat work.

## [2026-04-18] Session Summary (RAG Quality+Performance Integrated Tracking)
- What was done: Added RAG run performance aggregation in backend finalization (`total/stage/rewrite-overhead latency`) and exposed quality+performance integrated comparison in Admin `RagPage`.
- Key decisions: Kept existing retrieval/answer business logic unchanged and stored performance as additive payload (`metrics_json.performance`) for backward compatibility.
- Issues encountered: Existing frontend source includes mixed-encoding text regions; UI changes were applied in narrow stable blocks.
- Next steps: Run one completed RAG test pair and confirm `metrics_json.performance` values and compare-table deltas align with stage execution logs.

## [2026-04-18] Session Summary (Langfuse Env Validation + Tracing Visibility Fix)
- What was done: Verified Langfuse key set completeness in `.env`, reorganized `.env`/`.env.example` with section comments, enabled `QUERY_FORGE_LANGFUSE_ENABLED` in local `.env`, and validated observer initialization + smoke trace emission.
- Key decisions: Added `QUERY_FORGE_PYTHON` env guidance for backend-triggered pipeline subprocess consistency so the runtime uses `.venv` where `langfuse` is installed.
- Issues encountered: Initial tracing was blocked by disabled flag and missing `langfuse` package in current Python runtime.
- Next steps: Run one real LLM stage (`generate-queries` or `gate-queries`) and verify trace volume/fields in Langfuse UI under free-tier caps.

## [2026-04-18] Session Summary (Langfuse Event Schema + Pipeline LLM Observability Integration)
- What was done: Designed Query Forge Langfuse event schema (`docs/experiments/langfuse_event_schema.md`) and integrated fail-open tracing into the centralized LLM execution path (`pipeline/common/llm_client.py` via `pipeline/common/langfuse_observability.py`).
- Key decisions: Kept business logic untouched by instrumenting only transport-layer LLM calls, added purpose-aware sampling and hard event caps for free-tier safety, and made Langfuse emission fully optional by environment flags.
- Issues encountered: `pytest` is not installed in the current environment, so verification used `python -m unittest pipeline.tests.test_llm_client -v`.
- Next steps: Enable Langfuse in one controlled environment and validate real event volume against configured per-minute/per-day caps before wider rollout.

## [2026-04-18] Session Summary (Gating Top10 + Nested DTO + Backend Wiring Verification)
- What was done: Added Admin gating support for `Target Top10` utility score end-to-end (GUI input, backend DTO/service mapping, experiment config write, and pipeline utility scoring logic).
- Key decisions: Converted gating run payload to nested request DTO (`config.stageFlags/ruleConfig/gatingWeights/utilityScoreWeights/thresholds`) to reduce flat parameter sprawl while keeping existing run behavior/defaults.
- Issues encountered: Frontend production build regenerated static asset hash files under backend static resources.
- Next steps: Run one real admin gating batch with custom `target_top10` and compare stage_config vs generated experiment YAML vs gating result distribution.

## [2026-04-18] Session Summary (Failed Synthetic Request Data Purge)
- What was done: Investigated failed generation job `2e62b19d-582a-4c8a-b1f0-edd08ec61ca5`, identified linked generation batch `b3896885-b823-4d53-81f2-1eed7d64a7ec`, and manually purged batch-linked synthetic raw rows from strategy tables.
- Key decisions: Implemented backend guard so final failed synthetic generation jobs automatically delete batch-linked synthetic queries, preventing partial artifacts from remaining after retry exhaustion.
- Issues encountered: None.
- Next steps: Add regression test for failed generation cleanup and validate Admin synthetic list/count consistency after failure.

## [2026-04-17] Session Summary (Admin Synthetic UX Clarification + Control Refresh)
- What was done: Updated Admin synthetic generation UI for clarity by removing unused `소스 문서 버전`, renaming count control to `생성 개수`, switching random-chunk option to explicit mode selector, and locking LLM model input to fixed Gemini model.
- Key decisions: Preserved generation API semantics (`random_chunk_sampling`) and kept model value deterministic from frontend constant to prevent operator-side accidental drift.
- Issues encountered: Existing UI files include mixed-encoding literal regions; changes were applied with functional focus and validated via frontend production build.
- Next steps: Execute A/C/D operator runs from GUI with `생성 개수=1000` and compare batch duration/throughput under random vs ordered chunk mode.

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
