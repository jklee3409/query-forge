# progress.md

## [2026-06-24] Session Summary (RAG Java Source-of-Truth Migration Guide Phase 9B)
- What was done: Strengthened Python eval regression/audit tests for the Phase 9A official Java-backed retrieval backend policy; no production logic change was needed.
- Official Java-backed policy regression: `retrieval_eval_backend=java`, `official_eval_backend=java`, and `eval_retrieval_backend=java` are covered as Java client branch selectors; `retrieval_eval_backend=legacy` and the implicit default remain legacy; explicit legacy still wins over legacy Java opt-in flags.
- Metadata contract: Tests pin `official_backend`, `retrieval_eval_backend`, `legacy_available`, `legacy_fallback_used`, `official_java_endpoint`, `supported_modes`, and `blocked_modes` on retrieval eval metadata.
- Supported/blocked modes: Supported Java-backed official eval modes remain `raw_only`, `selective_rewrite`, `anchor_aware_rewrite`, and `strategy_router`; `agentic_multi_query` stays blocked before Java client calls.
- Comparison runner: Regression coverage confirms comparison variants set explicit legacy/java backend policies and the report contract keeps `schema_version`, `metric_delta_rows`, and `mismatch_rows`.
- Legacy/Admin/Phase 10: Python legacy eval remains available as fallback/regression path; Admin GUI was not touched; Phase 10 still needs router GUI regression and StrategyRouter agentic enhancement.
- Validation: `python -m py_compile pipeline/eval/java_retrieval_client.py pipeline/eval/retrieval_eval.py pipeline/eval/retrieval_eval_compare.py pipeline/tests/test_java_retrieval_client.py pipeline/tests/test_retrieval_eval_compare.py` passed; `python -m unittest pipeline.tests.test_eval_runtime pipeline.tests.test_strategy_router_eval pipeline.tests.test_java_retrieval_client pipeline.tests.test_retrieval_eval_compare -q` passed; focused Java endpoint and `/api/chat/ask` regression commands passed; `git diff --check` passed.
- Remaining risks: The unconditional Java default remains deferred until a reviewed same-dataset Java-backed comparison run is accepted; agentic Java eval remains future Phase 10+ work.

## [2026-06-24] Session Summary (RAG Java Source-of-Truth Migration Guide Phase 9A)
- What was done: Added `retrieval_eval_backend=java|legacy` / `official_eval_backend=java|legacy` policy handling for retrieval eval and kept legacy `use_java_backend` as an alias.
- Official Java-backed eval path: Supported non-agentic modes can now be selected as the official Java-backed path with `retrieval_eval_backend=java`; the actual implicit default remains legacy for compatibility with configs that do not provide Java backend/domain settings.
- Legacy fallback: `retrieval_eval_backend=legacy` prevents Java client construction even if old Java opt-in flags are present. Report rows and summaries record `official_backend=java`, `retrieval_eval_backend`, `legacy_available=true`, and `legacy_fallback_used`.
- Supported/blocked modes: Java-backed eval remains limited to `raw_only`, `selective_rewrite`, `anchor_aware_rewrite`, and `strategy_router`; `agentic_multi_query` remains blocked before Java client calls.
- Comparison runner: The Phase 8C/8D comparison runner still executes both legacy and Java variants and now writes explicit backend policy into its temporary configs.
- Scope: No Java production/controller/service/DTO or endpoint contract changes, no Admin GUI changes, no StrategyRouter changes, no DB schema changes, no Python legacy deletion, no agentic support, and no Phase 10 work.
- Validation: `python -m py_compile pipeline/eval/java_retrieval_client.py pipeline/eval/retrieval_eval.py pipeline/eval/retrieval_eval_compare.py pipeline/tests/test_java_retrieval_client.py pipeline/tests/test_retrieval_eval_compare.py` passed; `python -m unittest pipeline.tests.test_eval_runtime pipeline.tests.test_strategy_router_eval pipeline.tests.test_java_retrieval_client pipeline.tests.test_retrieval_eval_compare -q` passed; focused Java eval endpoint and `/api/chat/ask` regression tests passed; `git diff --check` passed.
- Remaining risks: Real Java-backed official comparison still needs review on the selected dataset/snapshot before making Java the unconditional default.

## [2026-06-24] Session Summary (RAG Java Source-of-Truth Migration Guide Phase 8D)
- What was done: Fixed the legacy vs Java-backed comparison report contract and Phase 9 readiness criteria without changing official eval defaults.
- Contract: The report now carries `schema_version`, `generated_at`, `compared_modes`, `legacy_summary`, `java_summary`, `metric_delta_rows`, `mismatch_rows`, `blocked_modes`, and Java endpoint/backend metadata while preserving existing Phase 8C aliases.
- Phase 9 readiness criteria: Java endpoint smoke tests pass. Comparison runner passes for supported non-agentic modes. Metric delta report is reviewed. Mismatch sample report is reviewed. agentic_multi_query remains blocked or has a separate approved policy. Python legacy eval remains available as fallback/regression path. Admin GUI impact is none or explicitly tested.
- Supported/blocked modes: `raw_only`, `selective_rewrite`, `anchor_aware_rewrite`, and `strategy_router` remain supported; `agentic_multi_query` remains fail-fast blocked.
- Scope: Official eval path/default `use_java_backend` were not switched, legacy eval remains, and no Java production/controller/DTO, Admin GUI, StrategyRouter, agentic eval, DB schema, Phase 9, or Phase 10 work was changed.
- Validation: Required Python py_compile, comparison unittest, combined eval unittest command, focused Java eval controller test, and `git diff --check` passed.
- Remaining risks: Phase 9 still needs a live same-dataset/snapshot comparison review before the official switch.

## [2026-06-24] Session Summary (RAG Java Source-of-Truth Migration Guide Phase 8C)
- What was done: Added `eval/retrieval_eval_compare.py` for legacy vs Java-backed retrieval comparison reports before the Phase 9 official switch.
- Comparison runner: Supports same-config legacy/java execution through temporary comparison config variants or injected fake runners/Java client, computes metric deltas for `recall@5`, `hit@5`, `mrr@10`, and `ndcg@10`, and emits chunk-id mismatch rows without full content.
- Modes/error policy: Supports only `raw_only`, `selective_rewrite`, `anchor_aware_rewrite`, and `strategy_router`; `agentic_multi_query` and unsupported modes fail fast. Java backend errors remain run-level fail-fast.
- Legacy path: Existing Python legacy eval remains intact and default behavior is unchanged; official eval was not switched to Java.
- Scope: No Java production/controller/service, Java endpoint contract, Admin GUI, StrategyRouter, DB schema, agentic eval support, or Phase 9/10 work was changed.
- Validation: Required Python py_compile, comparison unittest, combined eval unittest command, focused Java eval controller test, and `git diff --check` passed.
- Remaining risks: The comparison runner is a dry-run/report basis; final Phase 9 switching still needs live same-dataset Java-backed audit review.

## [2026-06-24] Session Summary (RAG Java Source-of-Truth Migration Guide Phase 8B)
- What was done: Fixed the opt-in Java-backed retrieval eval runtime path so Java-enabled sample/mode evaluation fail-fast validates supported modes, uses the Java client for non-agentic modes, feeds `retrievedChunkIds` into existing metrics, and records additive Java backend metadata in rows/reports.
- Contract: Java-backed rows now expose `backend=java`, `java_endpoint=/api/rag/eval/retrieval`, `java_forced_mode`, `java_selected_mode`, and `java_warnings`; summary reports record `backend`, `java_endpoint`, supported modes, and `java_error_policy=fail_fast_run_level`.
- Legacy path: `use_java_backend=false` still keeps the Python legacy eval branch; official eval was not switched to Java and Python legacy eval was not removed.
- Scope: No Java production/controller/service code, Admin GUI, StrategyRouter, agentic eval support, DB schema, migration, or Phase 8C/9/10 work was changed.
- Validation: `python -m py_compile pipeline/eval/java_retrieval_client.py pipeline/eval/retrieval_eval.py pipeline/tests/test_java_retrieval_client.py`, Java client unittest, combined eval runtime unittest, focused Java controller test, and `git diff --check` passed.
- Remaining risks: Java-backed opt-in still needs live same-dataset comparison before Phase 9 official eval switching; `agentic_multi_query` remains blocked for Java-backed eval.

## [2026-06-24] Session Summary (RAG Java Source-of-Truth Migration Guide Phase 8A)
- What was done: Added an opt-in Python Java retrieval eval client adapter for `POST /api/rag/eval/retrieval` and wired retrieval eval to use it only when Java backend config is enabled.
- Contract: Client requests always send `persistPolicy=NONE` and `answerGeneration=false`, use `retrievedChunkIds` as the primary metric input, preserve response order, and reject `agentic_multi_query` before any HTTP request.
- Legacy path: `use_java_backend` defaults to false, so existing Python legacy eval and default retrieval modes remain unchanged; no official eval path switch was made.
- Scope: No Java production/controller/service code, Admin GUI, StrategyRouter, DB schema, or Phase 8B/9/10 work was changed.
- Validation: Targeted py_compile, Java client tests, legacy strategy-router eval test, focused Java controller test, and `git diff --check` were run.
- Remaining risks: Full Java-backed eval still needs a live same-dataset comparison in Phase 9 before official eval is switched; agentic Java eval remains blocked.

## [2026-06-23] Session Summary (Strategy Router Retrieval Eval Mode)
- What was done: Added an explicit Python retrieval-eval `strategy_router` mode that mirrors the live Java QueryStrategyRouter decision order, delegates each sample to one concrete raw/selective/anchor/agentic strategy, and records sample-level selected strategy, router reason, and LLM call counts.
- Key decisions: Kept `LEGACY_DEFAULT_MODES` unchanged, left Java/DB/UI paths untouched, made agentic routing opt-in via `strategy_router_agentic_enabled`, and used conservative multi-signal rules before selecting expensive agentic multi-query.
- Issues encountered: Validation was limited to targeted py_compile and unit tests; no DB-backed retrieval evaluation was run.
- Next steps: Run a same-snapshot config with `retrieval_modes: [strategy_router]` and inspect selection counts plus `rewrite_cases_*` traces before comparing against explicit modes.

## [2026-06-22] Session Summary (Agentic Multi-Query Retrieval Eval Mode)
- What was done: Added Python retrieval-eval support for explicit `anchor_aware_rewrite` and `agentic_multi_query` modes, including agentic planner fallback, sequential subquery selective rewrite/retrieval, chunk-id RRF merge, per-sample latency, and degraded-query summary counts.
- Key decisions: Kept Java live path untouched, preserved legacy default eval modes, retained snapshot/domain scoping through existing corpus and memory filters, and excluded token-usage aggregation for this step.
- Issues encountered: Local validation used py_compile and an in-process smoke with a mock planner fallback rather than a full DB-backed eval run.
- Next steps: Run a same-snapshot comparison config with `raw_only`, `selective_rewrite`, `anchor_aware_rewrite`, and `agentic_multi_query`, then inspect `rewrite_cases_*` for `agentic_plan` and `subquery_traces`.

## [2026-06-16] Session Summary (Memory Domain Persistence)
- What was done: Updated `memory/build_memory.py` so gated row loaders carry `domain_id` into `memory_entries`, and added a regression test that verifies the insert contract includes the domain.
- Key decisions: Used the existing gated query domain as the source of truth, with quality-gating result domain as the batch/stage-cutoff fallback where applicable.
- Issues encountered: Existing Spring C memory rows from Admin RAG build had valid snapshot metadata but NULL `domain_id`, making domain-scoped live Chat readiness report no built synthetic memory.
- Next steps: Keep future RAG memory builds snapshot-pinned and verify Chat readiness after any new domain-scoped snapshot build.

## [2026-05-29] Session Summary (Detailed Intent Rewrite Runtime)
- What was done: Added `rewrite_query_profile` runtime support to selective rewrite, added detailed-intent prompt selection, passed Admin raw config into rewrite-stage LLM client creation, and recorded the profile in retrieval rewrite artifacts.
- Key decisions: `compact_anchor` remains the default prompt path; `detailed_intent` prefers `selective_rewrite_detailed_intent_v1.md` with compact prompt fallback. `llm_rewrite_model` now reaches `load_stage_config(stage="rewrite", raw_config=...)`.
- Issues encountered: `python -m py_compile eval/runtime.py eval/retrieval_eval.py` and `python -m unittest pipeline.tests.test_eval_runtime.EvalRuntimeRewriteTests -q` passed.
- Next steps: Inspect next fixed-snapshot `rewrite_cases_*.json` for `rewrite_query_profile`, detailed standalone candidates, and rewrite-only model usage.

## [2026-05-27] Session Summary (Domain-Aware Rewrite Payload)
- What was done: Added `_rewrite_domain_context(source_product)` and passed it through `run_selective_rewrite -> build_rewrite_candidates_v2` so the LLM receives the active technical-document domain and Korean-to-English technical term examples.
- Key decisions: Domain context is derived from source/product scope and remains prompt metadata only; it does not change retrieval scoring, memory lookup, or final adoption thresholds.
- Issues encountered: Targeted tests were extended to assert PostgreSQL domain context propagation into the candidate builder.
- Next steps: Compare rewrite-case payloads from the next Admin run to confirm Spring/PostgreSQL/Kubernetes domains receive the expected `current_technical_domain` and examples.

## [2026-05-27] Session Summary (Trusted Rewrite Memory Candidate Split)
- What was done: Updated `eval/runtime.py` so selective rewrite first asks the LLM for a raw-only `standalone` candidate with raw-retrieval evidence but no synthetic-memory anchor payload, then asks for an `expanded` candidate only when trusted memory rows overlap raw retrieval doc/chunk evidence.
- Key decisions: Prompt-side anchor, terminology, canonical, and multi-source hints are built only from trusted memory. Standalone scoring now uses raw-query anchors only and cannot be rejected for missing trusted-memory anchors/targets. Heuristic fallback follows the same separation, and rewrite retrieval context avoids loading a dense backend for metadata.
- Issues encountered: Run `76c16e3b-e92a-4b01-8b3f-10859adb2c8b` exposed generic standalone rewrites plus incorrect memory-anchor scoring penalties.
- Next steps: Use fixed-snapshot RAG traces to compare rejected reasons, exact-anchor recovery, and top-memory contamination before/after this split.

## [2026-05-26] Session Summary (Selective Rewrite v3 Runtime)
- What was done: Updated selective rewrite runtime to prefer `selective_rewrite_v3.md` for Korean/code-mixed queries, loosened the LLM response schema to require only `label` and `query`, and capped rewrite candidates at two.
- Key decisions: Kept English rewrite prompt selection on `selective_rewrite_en_v1.md`; legacy v2/v1 remain fallbacks, and optional v2 metadata fields still feed existing scoring/diagnostics when present.
- Issues encountered: Targeted validation is recorded in the root session summary.
- Next steps: Run same-snapshot Admin RAG rewrite-effect evaluation to compare v3 latency and bad-rewrite rate against v2.

## [2026-05-26] Session Summary (Selective Rewrite Final Candidate Guard)
- What was done: Updated `eval/runtime.py` so selective rewrite adoption compares `final_candidate_score` against a raw final-score baseline and rejects confident raw top-result loss through a raw-loss guard. Added regression coverage for high-confidence raw top1 loss.
- Key decisions: Kept prompt-only synthetic memory semantics and legacy `force_rewrite` compatibility, while making normal selective adoption stricter against candidates that look better by raw retrieval score but lose strong raw evidence.
- Issues encountered: `python -m py_compile ...` and `python -m unittest pipeline.tests.test_eval_runtime.EvalRuntimeRewriteTests -q` passed.
- Next steps: Re-run fixed-snapshot RAG comparisons and inspect `final_score_delta` plus `raw_loss_guard_*` fields in rewrite cases.

## [2026-05-26] Session Summary (A/C Long English Generation Retry)
- What was done: Added compact retry handling in `generation/synthetic_query_generator.py` for KO summary and A/C query-generation JSON calls when the provider returns `max_tokens_truncated`, including narrowed payload candidates and concise retry instructions.
- Key decisions: Retries only compact prompt payload after MAX_TOKENS; cached chunk assets, raw strategy tables, grounding metadata, and final schema remain unchanged.
- Issues encountered: `python -m py_compile pipeline/generation/synthetic_query_generator.py` passed. The change was needed to complete PostgreSQL A/C 1,000-query batches after earlier failed same-version attempts were deleted.
- Next steps: Keep Admin-generated payload bounds aligned with this retry path for future English technical-doc domains.

## [2026-05-26] Session Summary (Rewrite Anchor Artifact Fields)
- What was done: Persisted prompt-side rewrite anchor candidates, terminology hints, canonical hints, and multi-source hints into retrieval-eval rewrite case artifacts so backend anchor evaluation can use the exact rewrite inputs.
- Key decisions: The eval-retrieval -> eval-answer order and retrieval scoring behavior are unchanged; this is artifact observability only.
- Issues encountered: `python -m py_compile pipeline/eval/runtime.py pipeline/eval/retrieval_eval.py` passed.
- Next steps: Inspect a new `rewrite_cases_*.json` from a small Admin run to confirm the added fields are present for rewrite modes.

## [2026-05-25] Session Summary (Rewrite Payload Retrieval Context)
- What was done: Added `retrieval_context` to selective rewrite LLM payloads, including runtime retrieval backend, vector store, retriever name/mode, dense embedding model, fusion weights, top-K, and memory candidate pool sizes.
- Key decisions: The context is metadata-only and does not change candidate schema, memory selection, retrieval scoring, or adoption policy; LLM uses it only to choose query structure.
- Issues encountered: Targeted py_compile and rewrite payload unit test passed.
- Next steps: Compare rewrite traces across BM25/dense/hybrid modes after a fixed-snapshot rerun.

## [2026-05-25] Session Summary (Rewrite Memory Rerank and Anchor Coverage)
- What was done: Added rewrite-memory candidate pool reranking from raw top-K overlap, target chunk/doc metadata, canonical anchors, utility score, and product match. Extended rewrite candidate diagnostics with source memory target hits and LLM-declared anchor coverage validation.
- Key decisions: Prompt memory rows are now sanitized and indexed; downstream code uses `source_memory_index` to validate target-hit evidence without exposing memory/document/chunk IDs to the LLM.
- Issues encountered: Targeted runtime/DB-ANN tests passed. Existing tests were updated to assert sanitized memory prompt rows hide internal IDs while retaining canonical anchor terms.
- Next steps: Adjust Admin threshold defaults and runtime config knobs to take advantage of the new lower-risk candidate selection.

## [2026-05-25] Session Summary (Raw Retrieval Cache and Stable Ranking)
- What was done: Added per-sample raw retrieval precomputation for retrieval eval, shared raw cache loading for answer eval, `raw_query` rewrite-case observability, and deterministic tie-break ordering for local and DB-ANN ranking paths.
- Key decisions: The cache is keyed by experiment output (`raw_retrieval_cache_{experiment}.json`) and reused in-process by rewrite modes; answer eval loads the persisted cache when retrieval eval already produced it, otherwise computes missing samples once.
- Issues encountered: Targeted runtime and DB-ANN unit tests passed. Empty raw retrieval now fails the run by default when a candidate scope exists, avoiding silent raw-only row loss.
- Next steps: Add memory-candidate reranking and sanitized rewrite prompt schema on top of the shared raw top-K context.

## [2026-05-25] Session Summary (Compact Rewrite Verbosity Gate)
- What was done: Updated selective rewrite adoption so the verbosity rejection uses the existing length ratio plus an optional absolute compact-query cap (`max_compact_query_chars`) for short-user queries.
- Key decisions: Kept retrieval-score improvement, terminology preservation, memory alignment, and prompt-only synthetic memory behavior unchanged.
- Issues encountered: Targeted `python -m py_compile pipeline/eval/runtime.py` passed.
- Next steps: Inspect a rerun's rewrite cases for `candidate_compact_chars` and `verbosity_exceeds_limit` reduction.

## [2026-05-20] Session Summary (Prompt-Only Synthetic Memory Rewrite Eval)
- What was done: Reworked `eval/runtime.py` selective rewrite so raw retrieval is computed first, synthetic memory lookup is passed only to the LLM rewrite prompt, candidate retrieval is run once per rewritten query, and final retrieval is never merged with memory or raw results.
- Key decisions: Adoption now uses raw-vs-candidate retrieval score delta with existing threshold/min-improvement gates; `missing_memory_target` remains diagnostic/penalty data but no longer hard-blocks prompt-context-only memory rewrites.
- Issues encountered: `python -m py_compile eval/runtime.py eval/retrieval_eval.py eval/answer_eval.py` passed; no full eval, full test, or pipeline run was performed.
- Next steps: Inspect a small rewrite_cases JSON from a later fixed-snapshot smoke for selected_rewrite, raw/final ranks, and memory_hint=false fields.

## [2026-05-20] Session Summary (Import Corpus Domain ID)
- What was done: Added `--domain-id` support to `import-corpus`, recorded it in import run source/config snapshots, and applied it after import to corpus sources, documents, sections, chunks, relations, glossary terms, aliases, and evidence.
- Key decisions: Domain assignment is a post-import propagation step rather than a rewrite of every row upsert, preserving existing import idempotency and dedupe behavior.
- Issues encountered: `python -m compileall pipeline\cli.py pipeline\loaders\common.py pipeline\loaders\import_corpus_to_postgres.py` passed.
- Next steps: Run a migrated-DB CLI smoke with a known `tech_doc_domain.domain_id` when DB work is acceptable.

## Overview
High-level pipeline progress tracking.

## [2026-05-20] Session Summary (Synthetic Memory as Retrieval Hints)
- What was done: Updated retrieval/answer eval runtime so selective rewrite and memory-only modes preserve raw-query retrieval and use top memory anchors as a bounded guided retrieval query. Added `memory_hint_query` and `memory_hint_retrieval_applied` observability fields to rewrite payloads.
- Key decisions: Synthetic memory text is no longer the default final retrieval query. Direct memory replacement is retained only behind `memory_lookup_direct_enabled=true`; default merging uses `max_score` and at most 3 hint anchors.
- Issues encountered: The existing anchor extractor treated first-letter-capitalized words as technical, so memory-hint filtering now requires stronger markers such as separators, digits, acronyms, or inner camel-case.
- Next steps: Re-run snapshot-pinned retrieval eval to compare `raw_only`, `memory_only_gated`, and `selective_rewrite` after hint retrieval is active.

## [2026-05-19] Session Summary (DB-ANN Hybrid Candidate Union)
- What was done: Extended `eval/runtime.py` DB-ANN hybrid mode to collect chunk and memory candidates from dense ANN, trigram lexical similarity, and technical-token exact-match pools, then dedupe and feed the union into the existing `rank_with_precomputed_embeddings` hybrid scorer.
- Key decisions: Preserved `dense_only` DB-ANN as ANN-only, kept Admin materialized chunk embeddings and memory `query_embedding` usage unchanged, and avoided changes to eval stage orchestration, rewrite policy, or stored query/memory text.
- Issues encountered: Added `test_db_ann_runtime.py` coverage for lexical chunk/memory candidates recovering when ANN prefilter misses them. Validation passed with `python -m py_compile eval\runtime.py tests\test_db_ann_runtime.py`, `python -m unittest pipeline.tests.test_db_ann_runtime -q`, `python -m unittest pipeline.tests.test_eval_runtime -q`, and a read-only PostgreSQL smoke for the new SQL paths.
- Next steps: Run a fresh same-snapshot Admin RAG `db_ann` evaluation to measure whether `memory_only_gated` recovers toward local hybrid while latency remains below local full-scan.

## [2026-05-19] Session Summary (Multi-source Anchor Runtime Hints)
- What was done: Added runtime loading for precomputed `canonical_anchor_relation` rows into an in-memory alias/relation index, built bounded `multi_source_anchor_hints`, injected them into selective rewrite prompts, and recorded anchor-expansion diagnostics in retrieval/answer summaries and rewrite cases.
- Key decisions: Runtime does not perform heavy all-pairs similarity. Expanded anchors are filtered by relation type, relation score, per-seed cap, total cap, term-type priority, raw-query overlap, and concept score floor; they remain optional low-priority hints and are not mandatory preservation targets.
- Issues encountered: Targeted validation passed with `python -m py_compile eval\runtime.py eval\retrieval_eval.py eval\answer_eval.py tests\test_eval_runtime.py` and `python -m unittest pipeline.tests.test_eval_runtime.EvalRuntimeRewriteTests -q`.
- Next steps: After the DB relation build exists, run a small same-snapshot off/on smoke and inspect `multi_source_anchor_diagnostics` before broad comparison.

## [2026-05-19] Session Summary (Language-Specific Rewrite Prompt Loading)
- What was done: Updated `eval/runtime.py` so selective rewrite loads `selective_rewrite_en_v1.md` for `query_language=en` and keeps the existing `selective_rewrite_v2`/`v1` fallback chain for Korean queries.
- Key decisions: Prompt selection follows the evaluation sample query language; no synthetic table layout, memory text, dense query text, or rewrite scoring rule was changed.
- Issues encountered: Targeted `python -m py_compile eval\runtime.py tests\test_eval_runtime.py` and `python -m unittest pipeline.tests.test_eval_runtime.EvalRuntimeRewriteTests -q` passed.
- Next steps: Inspect English rewrite debug logs from an E/en Admin run to tune the new prompt only if it over-expands.

## [2026-05-19] Session Summary (Canonical Anchor Rewrite Prompt Hints)
- What was done: Added `eval/runtime.py` compact canonical-anchor hint construction for selective rewrite LLM payloads and covered it in `tests/test_eval_runtime.py`.
- Key decisions: Canonical rewrite hints are emitted only when `rewrite_anchor_injection_enabled=true`, include only approved/self-fallback `used_for_scoring=true` anchors, omit full metadata and `canonical_term_id`, and reuse `rewrite_terminology_hints_max_count` for capping.
- Issues encountered: Targeted validation passed with `python -m py_compile pipeline\eval\runtime.py pipeline\eval\retrieval_eval.py pipeline\eval\answer_eval.py` and `python -m unittest pipeline.tests.test_eval_runtime.EvalRuntimeRewriteTests -q`.
- Next steps: Inspect a small Admin RAG rewrite debug payload before any broader comparison run.

## [2026-05-19] Session Summary (Canonical Anchor Version Pins in Eval Reports)
- What was done: Added a shared canonical anchor version payload helper and included `anchor_mapping_version`, `anchor_normalization_version`, `canonical_anchor_runtime_schema_version`, and grouped `canonical_anchor_versions` in retrieval/answer summary JSON payloads.
- Key decisions: Kept existing anchor normalization semantics unchanged and did not alter query text, dense inputs, memory query text, raw synthetic payload semantics, or report metric calculations.
- Issues encountered: Targeted `python -m py_compile common\anchor_normalization.py eval\retrieval_eval.py eval\answer_eval.py` passed.
- Next steps: Session 12 should document dry-run/backfill review policy around these version pins without executing evaluation or data updates.

## [2026-05-19] Session Summary (Hybrid Retrieval Canonical Expansion)
- What was done: Extended `common/local_retriever.py` with optional BM25/technical-only lexical query/document fields and canonical-anchor expansion helpers; wired memory retrieval, DB-ANN local reranking, selective-rewrite chunk retrieval, and gating utility chunk ranking to use existing canonical metadata additively.
- Key decisions: Dense query text and dense passage text remain original; expansion uses only active scoring anchors with a non-empty `canonical_term_id`, skips miss/ambiguous/unresolved anchors, and does not infer language/type or perform DB lookups/backfills.
- Issues encountered: Targeted validation passed with `python -m py_compile common\local_retriever.py eval\runtime.py gating\quality_gating.py tests\test_eval_runtime.py` and `python -m unittest pipeline.tests.test_eval_runtime -q`.
- Next steps: Session 11 should add mapping/normalization version recording to runtime/Admin payloads and surface canonical alias details in Admin UI.

## [2026-05-19] Session Summary (Rewrite Scoring Canonical Metadata)
- What was done: Updated `eval/runtime.py` so memory top-N rows carry existing `canonical_anchors` metadata and selective rewrite scoring additively boosts terminology preservation, anchor overlap, and memory-target tokens when raw/candidate aliases share a `used_for_scoring=true` canonical anchor.
- Key decisions: Filtered canonical metadata out of rewrite LLM prompt memory candidates, kept query/candidate/memory/dense text unchanged, and avoided DB lookup, mapping insert, migration apply, or alias-language/term-type inference.
- Issues encountered: Targeted validation passed with `python -m py_compile eval\runtime.py tests\test_eval_runtime.py` and `python -m unittest pipeline.tests.test_eval_runtime -q`.
- Next steps: In Session 10, apply canonical expansion narrowly to hybrid retrieval lexical/technical-overlap fields while preserving dense-query and stored-text identity.

## [2026-05-19] Session Summary (Memory Canonical Anchor Metadata)
- What was done: Wired `memory/build_memory.py` to add `canonical_anchors`, `anchor_mapping_version`, and `anchor_normalization_version` to new `memory_entries.metadata` payloads, preserving existing snapshot/source metadata and original memory/source query fields.
- Key decisions: Reused the canonical resolver metadata contract, loaded explicit raw-row `query_language`/`language_profile`, used glossary term candidates for `term_type` and self fallback, and skipped mapping-table lookup when `canonical_anchor_mapping` is absent.
- Issues encountered: Targeted validation passed with `python -m py_compile memory\build_memory.py` and `python -m unittest pipeline.tests.test_build_memory_canonical_anchor_metadata -q`.
- Next steps: Apply canonical metadata only in scoring/expansion consumers in later sessions; do not backfill or regenerate memory without an explicit reviewed plan.

## [2026-05-19] Session Summary (Synthetic Canonical Anchor Metadata)
- What was done: Wired `generation/synthetic_query_generator.py` to add canonical anchor metadata to newly built raw synthetic row payloads for A~G. The payload uses `common/anchor_normalization.resolve_canonical_anchors`, stores version pins, and keeps `query_text` plus `glossary_terms` unchanged.
- Key decisions: Loaded explicit glossary term metadata for resolver `term_type`/fallback candidates, used query language/profile only as an explicit alias-language source, skipped mapping-table lookup when V31 is not applied, and preserved split raw-table writes.
- Issues encountered: Targeted validation passed with `python -m py_compile generation/synthetic_query_generator.py` and `python -m unittest pipeline.tests.test_synthetic_query_generator -q`.
- Next steps: Extend the metadata-only canonical payload to new memory entries in Session 8 without memory rebuild/backfill.

## [2026-05-19] Session Summary (Canonical Anchor Resolver Contract Review)
- What was done: Checked `common/anchor_normalization.py` resolver SQL against V31 columns and the existing `corpus_glossary_terms` schema.
- Key decisions: Left resolver code unchanged because approved-active lookup, pending lookup, and self-fallback queries reference columns available in the reviewed schema and preserve metadata-only behavior.
- Issues encountered: Targeted validation passed with `python -m unittest pipeline.tests.test_anchor_normalization -q`.
- Next steps: In Session 7, thread resolver output into new synthetic metadata only, without overwriting existing raw query text or merging strategy tables.

## [2026-05-19] Session Summary (Canonical Anchor Normalizer and Resolver Draft)
- What was done: Added `common/anchor_normalization.py` with `anchor-normalize-v1` fixtures, constants, explicit alias-language validation, display-vs-normalized alias separation, and a metadata-only `resolve_canonical_anchors` draft.
- Key decisions: Preserved existing `common/anchor_quality.py` semantics and kept resolver output additive; it returns canonical anchor metadata without replacing query, glossary, synthetic, or memory text.
- Issues encountered: Targeted validation passed with `python -m unittest pipeline.tests.test_anchor_normalization -q`.
- Next steps: Wire this resolver into new synthetic/memory metadata paths in later sessions after `V31` is reviewed/applied; do not run backfill or memory regeneration from this session.

## [2026-05-19] Session Summary (Quality Gating Batch-Scoped Source Load)
- What was done: Updated `gating/quality_gating.py` to resolve `source_generation_batch_ids`, load raw rows by `generation_batch_id` before falling back to `source_generation_run_ids`, and resume without skipping unprocessed prefix rows when source scope expands.
- Key decisions: Kept auto-latest disabled while allowing batch identity as the primary explicit source identity; checkpoint slicing is now used only when the prefix up to the last processed query is already fully processed.
- Issues encountered: Batch `c122d7c2-3bc5-4442-94d1-90c9cd1a31fa` had 1465 raw B rows by batch ID but only 105 rows by the stored final source generation run ID, causing gating batch `ca4ee519-3a9b-4803-a217-06b58ef097de` to complete early; the first re-run exposed the existing checkpoint optimization as a second skip path.
- Next steps: Re-run job `29b16a6f-43a1-46a0-85e4-ae7e2b6096ed` is running; initial DB check showed gating result rows moving past the previous 105-row ceiling.

## [2026-05-19] Session Summary (Synthetic Retry Target Accounting Fix)
- What was done: Changed synthetic generation target accounting to seed `generated_count` from `synthetic_queries_raw_all` by `generation_batch_id` and refresh it after cached-row attach or new row insert.
- Key decisions: Applied the same cumulative target guard to online generation and Strategy B Gemini Batch mode, including pending batch-query slot reservation so deferred inserts cannot exceed `max_total_queries`.
- Issues encountered: Existing tests that directly exercise Strategy B Gemini Batch now patch live batch-count reads; added coverage for already-target-satisfied batches skipping Gemini Batch submission.
- Next steps: Run a retry-forced live smoke with a low target and verify `initial_generated_queries`, `new_generated_queries`, and `generated_queries` in the experiment summary.

## [2026-05-18] Session Summary (Strategy B Segmented Full Translation)
- What was done: Added deterministic segmented full translation for Strategy B in `generation/synthetic_query_generator.py`, splitting source chunks on structural boundaries, preserving fenced code verbatim, translating only text segments, caching segment assets, and reconstructing final `KO_TRANSLATED_CHUNK` assets with segmentation metadata.
- Key decisions: Kept original corpus chunks, retrieval/eval inputs, synthetic raw split tables, and B `EN chunk -> KO translation -> KO summary/query` semantics unchanged. Segment and final translation assets use distinct prompt-template version suffixes under the existing `chunk_generation_asset` schema, so no migration was required.
- Issues encountered: Full Gemini Batch translation was also updated to submit segment requests instead of whole chunks, preserving batch query flow while avoiding long single-output translation responses.
- Next steps: Smoke-test one long Spring Security chunk and confirm metadata (`translation_mode=segmented_full`, segment offsets/checksums, reconstruction checksum) before larger B runs.

## [2026-05-18] Session Summary (Strategy B Gemini Batch API Support)
- What was done: Added `common/gemini_batch.py`, persisted online `_llm_meta.usage`, and implemented Strategy B `llm_execution_mode=gemini_batch` as `translation batch -> deterministic KO summary -> query batch`.
- Key decisions: Kept online mode as the default, kept `llm_batch_size` as DB commit cadence only, submitted only cache misses, and failed observably on partial batch item errors instead of inserting incomplete lineage.
- Issues encountered: Live Gemini Batch was not exercised; adapter and generator paths were validated with fake/unit tests.
- Next steps: Run a tiny live Strategy B batch smoke and inspect batch job metadata, per-item usage, asset lineage, and `synthetic_queries_raw_b` rows before larger runs.

## [2026-05-18] Session Summary (Gemini Flash-Lite Fallback Pin)
- What was done: Updated `common/llm_client.py` so Gemini default fallback models resolve to `gemini-2.5-flash-lite` instead of escalating Flash-Lite primary calls to `gemini-2.5-flash`.
- Key decisions: Preserved retry/fallback mechanics and provider routing; same-model fallback targets are still skipped by the existing resolver, so Flash-Lite primary calls do not perform duplicate fallback calls.
- Issues encountered: None during implementation.
- Next steps: Use this cost guard together with gradual Strategy B batch scale-up and token-usage inspection before any 1000-query run.

## [2026-05-15] Session Summary (Strategy B Admin Path Validation)
- What was done: Validated the Strategy B generator through Admin-created experiment configs. Current-code one-source and all-allowed-sources runs completed with B rows only in `synthetic_queries_raw_b`, query-only LLM responses, KO translation assets, deterministic KO summary assets, and populated B payload-limit/char traces.
- Key decisions: Treated `original_chunk_ko` and `extractive_summary_en` trace values of `0` as the expected B payload semantics; source summaries remained bounded deterministic KO summaries (`v2:B:extractive:max900` in Admin runs).
- Issues encountered: The stale 8080 Admin run lacked the translation cap and reproduced `max_tokens_truncated`, confirming the value of the B-only Admin default.
- Next steps: Increase B all-source generation size gradually while tracking translation retries/truncation and payload char distributions.

## [2026-05-15] Session Summary (Strategy B Smoke Trace Verification)
- What was done: Extended B query trace payload char metrics to include `original_chunk_ko` and `extractive_summary_en`, then ran `strategy_b_smoke` for one `code_mixed` B query and verified both fields remain `0` while B uses KO translation + deterministic KO summary.
- Key decisions: Kept B query output query-only and raw writes in `synthetic_queries_raw_b`; trace changes are additive observability only.
- Issues encountered: The first smoke failed in translation with `category=max_tokens_truncated` until the smoke/Admin config used a B-specific translation output-token budget.
- Next steps: Re-run through Admin job orchestration and inspect LLM job result payloads against the same row/asset expectations.

## [2026-05-15] Session Summary (Strategy B Long-Chunk Hardening)
- What was done: Hardened `generation/synthetic_query_generator.py` so Strategy B query-generation payloads use bounded evidence windows for `original_chunk_en`, `translated_chunk_ko`, and `extractive_summary_ko` while preserving the cached translation -> deterministic KO summary -> query-only KO method.
- Key decisions: Preserved split raw-table storage, prompt asset lineage, and B query-only schema. Deterministic KO summary cache template versions now include `max_chars`, preventing reused summary assets when B/F/G summary length bounds differ.
- Issues encountered: Targeted validation passed: `python -m py_compile ..\pipeline\generation\synthetic_query_generator.py` and `python -m unittest pipeline.tests.test_synthetic_query_generator -q`.
- Next steps: Run a tiny controlled B generation to inspect `b_query_payload_limits`, `b_query_payload_chars`, `generation_asset_ids`, and raw `synthetic_queries_raw_b` storage before wiring Admin defaults.

## [2026-05-15] Session Summary (Strategy B Runtime Path)
- What was done: Updated `generation/synthetic_query_generator.py` so Strategy B skips mandatory EN extractive summary generation, caches `KO_TRANSLATED_CHUNK` from the original English chunk, builds deterministic extractive `KO_SUMMARY` from that translation, and feeds B query generation with original EN chunk + KO translation + KO summary + glossary/query controls.
- Key decisions: B now stays in `synthetic_queries_raw_b` even for `code_mixed` query type; A/C code-mixed rerouting to D and D/E/F/G behavior remain unchanged. Synthetic raw table structure and fixed pipeline order were not changed.
- Issues encountered: Targeted validation passed: `python -m py_compile ..\pipeline\generation\synthetic_query_generator.py` and `python -m unittest pipeline.tests.test_synthetic_query_generator -q`.
- Next steps: Run a tiny controlled B generation against one source/chunk to inspect `generation_asset_ids`, `source_summary`, and `llm_output.trace.ko_summary` before all-source hardening.

## [2026-05-15] Session Summary (Strategy B Query Schema Alignment)
- What was done: Aligned Strategy B runtime/stability schema with the new query-only prompt contract by requiring `query_ko`, `query_type`, and `answerability_type` only, removing translated/summary output requirements from the LLM stability spec.
- Key decisions: Kept the current generation loop and raw-table writes unchanged; this is a minimal schema-contract alignment, not the full Phase 3 B pipeline rewrite.
- Issues encountered: `python -m py_compile pipeline\generation\synthetic_query_generator.py pipeline\eval\llm_stability_runner.py` and `python -m unittest pipeline.tests.test_synthetic_query_generator pipeline.tests.test_llm_stability_runner -q` passed.
- Next steps: Change the B generation path so it does not depend on mandatory EN extractive summary and instead derives KO translation/summary artifacts from the original chunk.

## [2026-05-15] Session Summary (Synthetic Generator Multi-Source Filter)
- What was done: Added `source_ids` support to `generation/synthetic_query_generator.py` so one generation run can filter chunks by multiple allowed source IDs via `d.source_id = ANY(...)`. The run parameters and summary now record the active `source_ids` list.
- Key decisions: Kept existing `source_id` and `source_document_id` behavior unchanged for narrowed runs. `source_ids` is additive and normalized from YAML lists or comma-separated strings.
- Issues encountered: `python -m py_compile pipeline/generation/synthetic_query_generator.py` passed.
- Next steps: Run a small live B generation with all Spring sources and confirm chunk selection spans only the configured `source_ids`.

## [2026-05-13] Session Summary (Admin RAG DB-ANN Eval Path + Chunk Embedding Materialization)
- What was done: Added `memory/materialize_chunk_embeddings.py` and CLI command `materialize-chunk-embeddings` to precompute per-model chunk vectors into `chunk_embeddings` without recollecting documents. Refactored retrieval/answer eval runtime to support `retrieval_backend=db_ann`, reusing one pgvector ANN adapter for raw retrieval, memory lookup, rewrite candidate retrieval, and rewrite candidate memory lookup.
- Key decisions: Preserved A/B/C/D/E/F/G split storage and snapshot/source identity filters while keeping online hash embedding behavior separate from admin dense eval. `db-ann` rejects dense fallback and enforces query/chunk embedding model equality.
- Issues encountered: None during targeted unit-test and py-compile validation.
- Next steps: Run one real admin `db-ann` evaluation against a materialized model and compare retrieval/rewrite latency against the local full-load path.

## [2026-05-13] Session Summary (RAG Latency Measurement Redesign for Answer Eval)
- What was done: Added per-sample latency measurement for `query_eval_total_latency_ms`, `final_rewrite_latency_ms`, and `pure_rewrite_latency_ms` across `eval/runtime.py` and `eval/answer_eval.py`, then aggregated them into run-level averages with `eval_sample_count`, `rewrite_sample_count`, `pure_rewrite_sample_count`, and `excluded_sample_count`. Extended answer-eval CSV coverage to tolerate and verify the new fields.
- Key decisions: `avg_final_rewrite_latency_ms` is averaged only over samples where rewrite was actually applied/finalized; `avg_pure_rewrite_latency_ms` is averaged only over samples where the rewrite LLM call actually happened; legacy runs are not backfilled from retrieval latency summaries or rewrite-overhead deltas.
- Issues encountered: None in runtime logic; validation continued through targeted `unittest` because full project build/test was intentionally out of scope.
- Next steps: Use the next fresh RAG evaluation run to inspect how often `excluded_sample_count` stays at zero under normal answer-eval completion.

## [2026-05-12] Session Summary (Short-User Memory-Target Guard for Generic Rewrite Rejection)
- What was done: Added lightweight content-token based `memory target` scoring in `eval/runtime.py` so short-user rewrites can derive compact target anchors from the top memory query/glossary, reject underspecified generic rewrites that omit those anchors (`missing_memory_target`), and reward candidates that add them. Extended `common/experiment_config.py` with policy knobs (`underspecified_memory_norm_cutoff`, `memory_target_missing`, `memory_target_presence`) and added regression tests in `tests/test_eval_runtime.py`.
- Key decisions: Kept this as a runtime-only heuristic layer with no extra LLM calls, no extra DB queries, and no prompt change, so low-spec laptop evaluation cost stays flat.
- Issues encountered: None in code path; verification still uses `python -m unittest pipeline.tests.test_eval_runtime -q` because `pytest` is unavailable locally.
- Next steps: Re-run the same `A/full_gating` snapshot condition and inspect whether previously generic rewrites recover target-anchor cases such as executable `jar` style queries without raising broad bad-rewrite rate.

## [2026-05-12] Session Summary (Rewrite-Always Validity Guard + Short-User Relaxation)
- What was done: Updated `eval/runtime.py` so `rewrite_always` no longer force-applies candidates that already failed preservation/verbosity/threshold checks; it now applies the best eligible candidate and otherwise falls back to the raw query. Relaxed default `short_user` rewrite adoption policy in `common/experiment_config.py` (`preservation_floor 0.76`, `max_length_ratio 1.70`, lower verbosity penalty) and added regression tests in `tests/test_eval_runtime.py`.
- Key decisions: Kept the fix inside rewrite selection/policy only, avoiding any extra LLM call, retriever expansion, or DB access so low-spec laptop runs stay cheap.
- Issues encountered: `pytest` was unavailable in the local Python environment, so verification used `python -m unittest pipeline.tests.test_eval_runtime -q`.
- Next steps: Re-run the same `A/full_gating` dataset/snapshot condition and compare `rewrite_always` against `c7c42735-5be9-4941-a53d-fe9fb4572f6a` and `6f7ae4d0-b311-4224-8249-9a5d8e302c31`.

## [2026-05-12] Session Summary (Memory Lookup Default Revert)
- What was done: Changed `eval/retrieval_eval.py` so `memory_only_*` modes default back to direct top-memory synthetic query retrieval instead of raw-query intent-preserving guided lookup.
- Key decisions: Kept `memory_lookup_intent_preserving_enabled=true` as an explicit opt-in for future controlled ablations, but restored the default behavior that uses synthetic queries as the retrieval query.
- Issues encountered: Before/After analysis showed short Korean user queries lost retrieval signal when raw intent was preserved too strongly with only product-level hints.
- Next steps: Re-run the same A/full-gating snapshot with the restored default and compare `memory_only_gated`, `rewrite_always`, and `selective_rewrite` against the prior runs.

## [2026-05-11] Session Summary (F/G Synthetic Query Routing, Grounding, and Speed)
- What was done: Hardened `generation/synthetic_query_generator.py` for F/G by keeping F/G `code_mixed` outputs in their own strategy tables, scoping relation/glossary SQL to the selected chunks/documents, removing overlap context from F/G primary evidence, passing compact `related_chunks_ko` evidence for near/far, and adding deterministic extractive KO summaries as the default F/G summary path.
- Key decisions: Left A/B/C `code_mixed` compatibility with strategy D intact, while preventing E/F/G from being hijacked by D. Deterministic F/G summaries are cached as `KO_SUMMARY` assets with provider/model `deterministic/extractive-ko-v1`, with `fg_summary_mode=llm` available for fallback.
- Issues encountered: The targeted generator test caught a summary-candidate dedupe indentation issue that made truncation retry candidates empty; fixed alongside the F/G work.
- Next steps: Smoke-test a small Python KR F/G batch and compare generated query traces for `related_chunks_ko_count`, `fg_summary_mode`, and raw table destination.

## [2026-05-11] Session Summary (F/G summary_ko MAX_TOKENS 절단 범위 한정 대응)
- What was done: Scoped fix in `generation/synthetic_query_generator.py` to harden only F/G `summary_extraction_ko` path against truncation: raised F/G summary output-token floor to `2048` and added truncation-only retry with progressively shortened source text candidates (`3200 -> 2200 -> 1400 chars`) when and only when failure category is `max_tokens_truncated`.
- Key decisions: Did not change prompts, strategy routing, or non-F/G flows; restricted logic to the confirmed failing branch (`_resolve_or_create_summary_ko`, `prompt_version_suffix in {F,G}`) and only for `MAX_TOKENS`-classified failures.
- Issues encountered: None.
- Next steps: Re-run `admin_gen_31f26eea21bd` and `admin_gen_560d9fdb54a2` and verify summary_ko no longer exits with `category=max_tokens_truncated`.

## [2026-05-11] Session Summary (Retry Exhaustion Error Message에 Failure Category 직렬화)
- What was done: Updated `common/llm_client.py` so `_RetryableLlmError` string representation always includes structured failure details (`category`, `status`, `finish_reason`, `block_reason`) when available.
- Key decisions: Kept generation/retry strategy unchanged; only enriched exception text so downstream job error tails can distinguish post-processing failures from request-layer failures without full stdout/stderr logs.
- Issues encountered: Existing Admin `llm_job_item.error_message` stores truncated stderr tail, so category logs were often not visible before this change.
- Next steps: Re-run failed `summary_extraction_ko` jobs and classify by surfaced category (`invalid_json`, `missing_required_key`, `response_blocked`, `max_tokens_truncated`, etc.) before tuning stage-specific token budgets.

## [2026-05-10] Session Summary (LLM Retry Failure Categorization + Structured JSON Fence Recovery)
- What was done: Updated `common/llm_client.py` so HTTP-success but post-processing failures are explicitly categorized (`response_empty`, `response_blocked`, `invalid_json`, `schema_mismatch`, `missing_required_key`, `max_tokens_truncated`) and logged per attempt with response metadata (`status`, `finish_reason`, `block_reason`). Added retry-exhaustion propagation with failure detail context and structured-output safe fence recovery on final attempt.
- Key decisions: Preserved existing model fallback/retry behavior and schema contracts; changed only parsing fallback policy and observability. Markdown fallback on structured-output path is restricted to fenced JSON parsing at the final attempt to reduce accidental over-acceptance of mixed natural-language output.
- Issues encountered: None.
- Next steps: Monitor real `summary_extraction_ko` runs and confirm whether dominant failures are parse/schema/safety/truncation related before tuning token budgets or prompt-level constraints.

## [2026-05-10] Session Summary (Strategy-aware Query Schema + Safe Query Text Extraction for E/F/G)
- What was done: Updated `generation/synthetic_query_generator.py` so query response schema required fields are strategy-specific (`A/B/C/D/E/F/G`) instead of globally requiring `query_ko`. Added strategy-aware schema resolver per query call.
- Key decisions: Kept `additionalProperties=True` and preserved `style_note` compatibility while restricting runtime fallback query extraction to real query fields only (`query`, `query_en`, `query_ko`, `query_code_mixed`).
- Issues encountered: None.
- Next steps: Run small live generation smoke on `E/F/G` to confirm retry/error rates remain stable with stricter per-strategy required fields.

## [2026-05-10] Session Summary (Synthetic Generator F/G Mapping + KR-Source Path Prompt Wiring)
- What was done: Extended `generation/synthetic_query_generator.py` with strategy mappings for `F/G` (`synthetic_queries_raw_f/g`), added prompt loading for `gen_f_v1.md` and `gen_g_v1.md`, added KR-source summary branch (`source_text_ko=chunk_text`) for `F/G`, and added `F` English query extraction/query-language handling.
- Key decisions: Kept changes scoped to generation/gating compatibility only (no enum refactor, no shared pipeline rewrite), while preserving existing `A/B/C/D/E` flow unchanged.
- Issues encountered: None.
- Next steps: After DB migration apply, run small-scope generation smoke for `F` and `G` with explicit source document filter and verify row insert destinations (`raw_f/raw_g`).

## [2026-05-08] Session Summary (Rewrite Failure Policy Strictness + Rewrite Stats Keys)
- What was done: Added explicit rewrite-failure-policy regression coverage in `tests/test_eval_runtime.py` for `fail_run`, `skip_to_raw`, and `heuristic_fallback`. Updated retrieval/answer summaries to emit explicit rewrite observability counters (`rewrite_llm_attempted_count`, `rewrite_llm_success_count`, `rewrite_llm_failure_count`, `rewrite_heuristic_fallback_count`) while keeping legacy alias keys for compatibility.
- Key decisions: Kept runtime decision behavior unchanged and focused on strict policy verification plus report-schema observability completeness.
- Issues encountered: None.
- Next steps: Keep downstream dashboards/readers aligned to the new explicit rewrite counter names and retire alias keys after consumers migrate.

## [2026-05-08] Session Summary (Gating Source Snapshot Strictness: No Auto-Latest)
- What was done: Removed auto-latest generation-run fallback from `gating/quality_gating.py` and made `gate-queries` fail fast unless `source_generation_run_id` or `source_generation_run_ids` is explicitly provided. Added unit tests in `pipeline/tests/test_quality_gating.py` to lock this behavior.
- Key decisions: Prioritized deterministic snapshot provenance over convenience fallback to align gating input selection with explicit run pinning.
- Issues encountered: None.
- Next steps: Keep Admin/CLI gating configs always writing explicit source generation run IDs for reproducible reruns.

## [2026-05-08] Session Summary (Pipeline Test Stability Without Docker)
- What was done: Updated `pipeline/tests/test_corpus_import.py` so Docker-dependent integration setup is skipped (`unittest.SkipTest`) when Docker daemon is unavailable, instead of failing the whole test run with an error.
- Key decisions: Matched backend test behavior (`disabledWithoutDocker` equivalent intent) and kept the integration test fully active when Docker is available.
- Issues encountered: `DockerException` was raised during `PostgresContainer(...)` construction (before `start()`), so the guard had to wrap constructor + startup together.
- Next steps: Keep Docker-dependent tests discoverable but non-blocking on no-Docker local/dev environments.

## [2026-05-08] Session Summary (Gating Multi-Source Runs + Rewrite Failure Policy Runtime Wiring)
- What was done: Updated `gating/quality_gating.py` to accept and apply `source_generation_run_ids` (with backward-compatible single `source_generation_run_id` fallback), and emit selected source run IDs in summary. Wired rewrite failure handling policy through eval runtime/retrieval/answer paths so rewrite candidate generation now supports deterministic `fail_run`, `skip_to_raw`, and `heuristic_fallback` modes with per-run counters.
- Key decisions: Kept backward compatibility for single-run config keys while preferring explicit multi-run source pinning in quality-gating and RAG evaluation.
- Issues encountered: None.
- Next steps: Validate policy-mode behavior on the same dataset/snapshot and compare rewrite adoption/latency deltas with fixed retrieval config.

## [2026-05-08] Session Summary (Eval Runtime Retriever Reuse Cache)
- What was done: Refactored `eval/runtime.py` retrieval hot path to reuse in-process retrievers via bounded caches for chunk ranking and memory ranking contexts. `retrieve_top_k` now reuses chunk retrievers, and `memory_top_n` reuses filtered eligible-memory retrievers for repeated queries under the same preset/run/strategy/config.
- Key decisions: Preserved all retrieval/rewrite decision logic and outputs; optimization is limited to avoiding repeated construction/filtering work inside the same eval process.
- Issues encountered: None.
- Next steps: Measure controlled latency deltas on rewrite-heavy runs (`rewrite_always`, selective modes) and memory-only modes using the same dataset/snapshot.

## [2026-05-07] Session Summary (Memory Lookup Intent-Preserving Retrieval)
- What was done: Updated `eval/retrieval_eval.py` memory-mode path to stop direct top-memory-query replacement. Added intent-preserving memory guidance query construction in `eval/runtime.py::build_memory_guided_query` (raw query base + product-level memory hints) and merged raw/guided retrieval results (`max_score|interleave|replace`, default `max_score`).
- Key decisions: Prioritized raw intent preservation for short Korean developer queries while still leveraging synthetic-memory shape; reduced aggressive glossary/class-token injection in guidance hints to avoid over-specific drift.
- Issues encountered: Initial hint scoring over-weighted glossary anchors and produced unnatural expansions (e.g., class/config tokens); adjusted guidance to prefer product-level hints (`Spring Security`) first.
- Next steps: Re-run `1f30b078-...` equivalent settings and compare per-sample `memory_only_gated` failures and aggregate `MRR@10 / nDCG@10` versus baseline.

## [2026-05-07] Session Summary (Eval Dataset Scope-based Corpus Filtering)
- What was done: Updated `eval/runtime.py`, `eval/retrieval_eval.py`, and `eval/answer_eval.py` to enforce dataset-aware retrieval scope. Eval sample loading now includes `source_product`/`source_version_if_available`, and chunk loading now filters by dataset product scope (with alias normalization like `*-reference -> *`) plus expected-doc fallback.
- Key decisions: Kept backward compatibility for legacy datasets by allowing expected-doc-id fallback when `source_product` is missing, while defaulting dataset-bound runs away from full-corpus retrieval.
- Issues encountered: Existing runs compared same dataset/snapshot under different corpus states, so absolute A/B metric deltas included corpus-scope drift noise.
- Next steps: Re-run controlled A/B with the same snapshot + same corpus state and verify that off-domain chunk dominance no longer appears in top-1 retrieval distribution.

## [2026-05-06] Session Summary (Selective Rewrite Scoring/Adoption Stabilization Phase-2)
- What was done: Refactored selective rewrite candidate evaluation into staged scoring in `eval/runtime.py` and added configurable adoption policy handling via `common/experiment_config.py::resolve_rewrite_adoption_policy`.
- Key decisions: Adoption now combines retrieval gain + terminology preservation + memory alignment, then applies verbosity/preservation penalties and category-aware thresholds (`short_user`, `code_mixed`, `troubleshooting`) without changing pipeline stage order.
- Issues encountered: Existing rewrite-memory-affinity unit case required explicit relaxed policy override because stricter default preservation/threshold gates can reject marginal candidates.
- Next steps: Tune policy keys (`rewrite_adoption_policy`) per dataset category and validate bad rewrite reduction under fixed retriever/snapshot settings.

## [2026-05-06] Session Summary (Selective Rewrite Terminology Hints Phase-1)
- What was done: Extended `eval/runtime.py::build_rewrite_candidates_v2` to include a compact `terminology_hints` payload (when anchor injection is enabled) built from raw-query technical tokens, top memory glossary terms, and top memory query technical tokens.
- Key decisions: Preserved existing `anchor_candidates` and `anchor_terms`; added bounded + deduplicated + technical/noise-filtered hint collection with safe default cap (`12`) and optional runtime override (`rewrite_terminology_hints_max_count`).
- Issues encountered: None.
- Next steps: Validate retrieval impact with same-snapshot experiments while holding retriever/threshold constant; monitor rewrite adoption and latency overhead.

## [2026-05-04] Session Summary (Anchor Extraction Quality Gate Upgrade)
- What was done: Strengthened `preprocess/chunk_docs.py` concept-anchor extraction with high-confidence filtering (technical-marker/hint 기반), Korean Kiwi candidate narrowing (`NNG/NNP`, up to bigram), and stricter generic/helper token rejection. Added shared anchor quality tests in `tests/test_anchor_quality.py` and expanded rewrite payload test coverage for Korean functional phrase exclusion.
- Key decisions: Kept extractor entrypoint unchanged (`extract_glossary_terms`) so chunk ingest extraction and backend re-extraction (`extract-anchor-candidates`) continue to share the exact same logic path.
- Issues encountered: Smoke extraction initially emitted noisy candidates (`spring`, `required`, `사용 부탁`, `ilter.order`) from weak concept filtering and mixed-language Kiwi token sequences.
- Next steps: Validate precision/coverage on larger real corpora and adjust only compact stop-token lists/thresholds if domain-specific false positives remain.

## [2026-05-04] Session Summary (Pipeline README Current-State Sync)
- What was done: Rewrote `pipeline/README.md` from early-stage scaffold description to current implementation state, including full CLI command set (`extract-anchor-candidates`, generation/gating/memory/eval stages), strategy `A/B/C/D/E`, and runtime behavior notes.
- Key decisions: Documented only existing code paths and command interfaces in `pipeline/cli.py` without introducing new operational assumptions.
- Issues encountered: None.
- Next steps: Keep README command examples aligned when CLI arguments or default paths change.

## [2026-05-04] Session Summary (Anchor Candidate Bridge Command for Backend Re-extraction)
- What was done: Added `preprocess/extract_anchor_candidates.py` and wired new CLI subcommand `extract-anchor-candidates` in `pipeline/cli.py`. The command reads chunk JSONL, converts chunks to pseudo-section inputs, reuses `extract_glossary_terms(...)`, and emits per-chunk anchor candidate JSONL (`document_id/chunk_id/term_type/canonical_form`).
- Key decisions: Reused the existing glossary extractor as single source of truth instead of adding another anchor-only algorithm, so backend re-extraction can follow the same extraction semantics as pipeline glossary processing.
- Issues encountered: JSONL with UTF-8 BOM caused parse failures; input reader was switched to `utf-8-sig` for robust ingestion.
- Next steps: Monitor candidate noise for generic `concept` terms and tune ranking/normalization only inside pipeline extractor flow if needed.

## [2026-05-02] Session Summary (Collector Fetch-Failure Skip + Placeholder URL Guard)
- What was done: Updated `collectors/spring_docs_collector.py` to skip placeholder-templated paths containing `{...}` and continue collect runs on per-URL fetch failures (`requests` exceptions) instead of aborting the entire run. Added `fetch_failures` to collect summary metrics.
- Key decisions: Kept successful-page ingestion path unchanged and treated failed fetches as warning-level operational signals to improve run resilience.
- Issues encountered: None.
- Next steps: Monitor source-level `fetch_failures` trends and tighten per-source deny/allow rules if noisy URL patterns persist.

## [2026-05-02] Session Summary (Normalize Legacy HTML Fallback Parsing)
- What was done: Updated `preprocess/normalize_docs.py` so `extract_article` no longer depends only on `article.doc`; it now falls back to legacy `div#content` and finally `body`, with shared non-content node pruning.
- Key decisions: Preserved existing section/block extraction logic and only widened the root container detection to support legacy Asciidoctor-style pages.
- Issues encountered: Existing source `arahansa-github-io-docs-spring` had no `article.doc`, causing prior normalize runs to output zero sections.
- Next steps: Re-run ingest for affected sources and verify downstream chunk/glossary generation now receives non-empty section JSONL.

## [2026-04-28] Session Summary (BM25-Only Memory Build Dimension Fix)
- What was done: Fixed `memory/build_memory.py` so BM25-only runs no longer generate 3072-dim hash vectors against 384-dim DB columns. Added explicit target-dimension normalization (`384`) in `_embed_memory_query` for fallback/hash paths.
- Key decisions: Kept BM25-only behavior on retrieval mode (no dense backend), but aligned persisted vector dimensions to storage schema to avoid `expected 384 dimensions, not 3072` failures.
- Issues encountered: Recent schema alignment to `halfvec(384)` exposed legacy hash fallback dimension assumptions during `build-memory`.
- Next steps: Re-run failed BM25-only RAG tests and verify full pipeline completion (`build-memory -> eval-retrieval -> eval-answer`).

## [2026-04-28] Session Summary (Selective Rewrite Retrieval Merge Strategies)
- What was done: Added rewrite retrieval merge logic in `eval/runtime.py` with strategies `replace`, `interleave`, and `max_score` so rewritten queries can be combined with raw-query retrieval results instead of unconditional replacement. Wired strategy selection into both retrieval and answer eval stages via experiment config key `rewrite_retrieval_strategy`.
- Key decisions: Preserved backward compatibility by normalizing missing/invalid strategy inputs to `replace` at runtime.
- Issues encountered: None.
- Next steps: Evaluate strategy impact per snapshot with controlled one-variable experiments and monitor rewrite adoption vs MRR/nDCG deltas.

## [2026-04-28] Session Summary (Build-Memory Dense Embedding Model Alignment)
- What was done: Refactored `memory/build_memory.py` to derive memory embeddings from experiment retriever config via local retriever dense backend, replacing hardcoded hash embedding writes. Updated `query_embeddings` upsert to persist actual `embedding_dim` from vector length.
- Key decisions: Kept hash as runtime fallback model only when dense backend cannot load; otherwise memory embedding model now follows `dense_embedding_model` (e.g., `intfloat/multilingual-e5-small`) to align with RAG test retrieval space.
- Issues encountered: Legacy flow stamped `embedding_model=hash-embedding-v1` for all memory builds, which can degrade `memory_only_*` modes in dense-only RAG experiments.
- Next steps: Execute one RAG run using `dense_only` and verify memory build summary reports non-hash embedding model with expected dimensions.

## [2026-04-28] Session Summary (English Generation/Gating/Rewrite Eval Path)
- What was done: Extended synthetic generation to support strategy `E` with `gen_e_v1`, English query persistence (`query_language=en`, `language_profile=en`), generalized quality-gating self-eval from Korean-only naturalness to language-neutral naturalness, and updated eval runtime/retrieval/answer flows to load `user_query_en` via `eval_query_language`.
- Key decisions: Kept the existing prompt asset family names and added language awareness at runtime instead of duplicating the full eval stack; selective rewrite now falls back heuristically when LLM rewrite setup is unavailable.
- Issues encountered: Existing rewrite runtime tests patched the old builder name, so tests were realigned to the new language-aware rewrite wrapper.
- Next steps: Run one `generate-queries -> gate-queries -> build-memory -> eval-retrieval -> eval-answer` chain for strategy `E` after Flyway apply and inspect English rewrite adoption rates.

## [2026-04-21] Session Summary (Explicit Retriever Modes)
- What was done: Added `RetrieverConfig` and explicit `bm25_only` / `dense_only` / `hybrid` mode handling to `common/local_retriever.py`, then propagated that config through quality gating utility scoring, retrieval eval, answer eval, memory lookup, and selective rewrite candidate scoring.
- Key decisions: BM25 mode avoids dense model loading entirely; Dense/Hybrid require the configured sentence-transformers model by default and only allow hash embedding fallback when explicitly enabled. Eval/gating summaries now persist retriever metadata for reproducibility.
- Issues encountered: Existing eval runtime tests directly referenced the previous single dense-backend cache, so the tests were adjusted to clear the new keyed backend cache and pass explicit fallback config where needed.
- Next steps: Execute controlled BM25/Dense/Hybrid retrieval runs on the same dataset and snapshot to quantify quality and latency tradeoffs.

## [2026-04-20] Session Summary (BM25 + Local Dense Retriever)
- What was done: Added `common/local_retriever.py` with cached BM25 + dense ranking, wired retrieval eval, answer eval, memory lookup, and gating utility scoring through it, and added CPU-oriented sentence-transformers configuration/dependency.
- Key decisions: Default model is `intfloat/multilingual-e5-small` on CPU when `sentence-transformers` is installed; environments without it fall back to BM25 + hash embedding instead of fake Cohere scores. The retriever normalizes BM25, dense similarity, and technical-token overlap into the existing `[-1, 1]` score range.
- Issues encountered: Current Python environment does not have `sentence-transformers`, so validation used the BM25 + hash fallback path. On `human_eval_short_user_80`, local-only metrics improved from prior hash/overlap baseline to Recall@5 `0.4750`, Hit@5 `0.5375`, MRR@10 `0.3425`, nDCG@10 `0.3811`.
- Next steps: Install/sync `sentence-transformers` in the backend pipeline runtime and rerun A/C/D same-dataset RAG tests to measure real dense embedding contribution separately from BM25.

## [2026-04-20] Session Summary (Selective Rewrite Evidence Recalibration)
- What was done: Reworked eval runtime scoring so unavailable/erroring Cohere rerank returns no artificial scores, retrieval and memory lookup use hybrid semantic/lexical/technical-token similarity, and rewrite candidates recompute their own memory affinity before selective gating.
- Key decisions: Removed the previous failure mode where all candidate `base_confidence` equaled raw confidence and only a capped retrieval-shift bonus affected the threshold decision. Shift now acts only as a small tie-breaker when the candidate is not weaker by evidence score.
- Issues encountered: Full unittest discovery still hits an existing corpus import migration fixture issue unrelated to rewrite runtime; targeted `test_eval_runtime` and `test_llm_client` pass.
- Next steps: Re-run RAG evaluation with A/C/D snapshots and inspect `memory_similarity_delta`, `retrieval_shift_bonus`, and adoption rates by mode.

## [2026-04-20] Session Summary (Memory Snapshot Isolation + Metric Corrections)
- What was done: Updated `memory/build_memory.py` to delete stale memory rows for the active snapshot before rebuilding and store `memory_experiment_key`; retrieval/answer eval now loads memory only for the current experiment key.
- Key decisions: Fixed answer correctness to use `eval_samples.expected_answer_key_points` instead of looking inside `dialog_context`, and changed `nDCG@10` to exact expected-chunk relevance with bounded `[0,1]` output and doc fallback only when chunk ground truth is absent.
- Issues encountered: Existing historical memory rows needed data backfill/cleanup outside code changes.
- Next steps: Re-run one RAG evaluation to confirm `memory_entry_count_loaded` matches the current build-memory summary and rewrite gains are compared against same-run `raw_only`.

## [2026-04-19] Session Summary (Selective Rewrite Prompt v2 Preference)
- What was done: Updated `eval/runtime.py::_rewrite_prompt_text` to resolve `selective_rewrite_v2.md` first and fall back to `selective_rewrite_v1.md` across `PROMPT_ROOT`/default path candidates.
- Key decisions: Preserved retrieval eval runtime behavior and candidate schema; only prompt asset resolution order was changed.
- Issues encountered: None.
- Next steps: Run retrieval eval with controlled prompt root override to compare v1/v2 rewrite impact on adoption and retrieval metrics.

## [2026-04-18] Session Summary (Langfuse Event Schema + LLM Client Instrumentation)
- What was done: Added fail-open Langfuse instrumentation module (`common/langfuse_observability.py`) and connected it to the centralized LLM call path in `common/llm_client.py` without changing generation/gating/eval decision logic.
- Key decisions: Applied quota-safe defaults for free-tier usage (purpose-aware success sampling, full error sampling, payload truncation, per-minute/per-day emit caps, and optional score mode).
- Issues encountered: None.
- Next steps: Enable `QUERY_FORGE_LANGFUSE_ENABLED=true` in one controlled environment and validate trace volume against daily cap before broad rollout.

## [2026-04-18] Session Summary (Utility Top10 Scoring Support)
- What was done: Updated retrieval utility scoring in `gating/quality_gating.py` to evaluate top-10 reranked candidates and apply a new `target_top10` score bucket; added `target_top10` default in `common/experiment_config.py`.
- Key decisions: Preserved backward compatibility by falling back to `target_top5` when old configs do not define `target_top10`.
- Issues encountered: None.
- Next steps: Validate score-distribution impact on real gating batches where target chunks frequently appear in ranks 6-10.

## [2026-04-17] Session Summary (Synthetic Chunk Random Sampling Execution Path)
- What was done: Extended `generation/synthetic_query_generator.py` to support `random_chunk_sampling` in chunk loading and wired config parsing so generation can shuffle full-corpus chunk order before applying optional limit/early stop.
- Key decisions: Kept deterministic legacy path when disabled; when enabled, shuffle uses experiment `random_seed` for reproducibility and pairs with `max_total_queries` for random subset generation.
- Issues encountered: None.
- Next steps: Add focused generation smoke run to verify random-order chunk selection traces in run metrics (`random_chunk_sampling=true`).

## [2026-04-17] Session Summary (Stage-Cutoff Memory Build Path)
- What was done: Added stage-cutoff load path in `memory/build_memory.py` to read from `synthetic_query_gating_result` by `stage_cutoff_level` (`rule_only`, `rule_plus_llm`, `utility`, `diversity`, `full_gating`) using full-gating snapshot provenance, and updated memory metadata/summary fields accordingly.
- Key decisions: Kept default gated-row path unchanged and switched to stage-cutoff row loading only when `stage_cutoff_enabled=true`; fallback level normalization defaults to `full_gating`.
- Issues encountered: Full-gating provenance needs `synthetic_queries_gated` join scoped by source run id to avoid mixed historical rows.
- Next steps: Run exploratory cutoff smoke tests and verify `memory_entries_by_snapshot` + retrieval-mode metrics reflect selected cutoff stage.

## [2026-04-17] Session Summary (Synthetic-free Baseline Pipeline Guard)
- What was done: Added synthetic-free baseline handling in `build_memory.py` as stage-level no-op and updated `retrieval_eval.py` / `answer_eval.py` to skip memory loading when baseline/raw-only conditions are active.
- Key decisions: Preserved pipeline stage order for reproducibility while removing synthetic-query table dependency from baseline execution path (`synthetic_free_baseline=true`).
- Issues encountered: Retrieval/answer evaluators previously loaded memory rows unconditionally, which could still touch synthetic-linked tables even in raw-only mode.
- Next steps: Run baseline experiment command chain (`build-memory -> eval-retrieval -> eval-answer`) and verify summary payloads report `synthetic_free_baseline=true` with raw-only metrics.

## [2026-04-15] Session Summary (Rewrite Adoption Logic + Eval Dataset Rebuild)
- What was done: Updated `eval/runtime.py::run_selective_rewrite` to include retrieval-shift-aware candidate scoring and rebuilt eval dataset with `python pipeline/cli.py build-eval-dataset --experiment exp4`.
- Key decisions: Rewrite decision now uses `confidence + retrieval_shift_bonus` (top-k shift/Jaccard + top1 change) and blocks no-op rewrites where candidate query is identical to raw query.
- Issues encountered: Existing eval dataset had stale expected IDs; rebuilding restored corpus-grounded expected doc/chunk IDs and removed mismatch-driven zero metrics.
- Next steps: Add explicit preflight guard to fail eval when expected IDs do not map to current corpus.

## [2026-04-14] Session Summary (Memory/Eval Dataset Integrity Guard)
- What was done: Updated memory build and eval-dataset candidate loading to require valid `corpus_documents` + `corpus_chunks` joins instead of permissive left joins.
- Key decisions: Filtered invalid gated/raw references at read time so downstream stages do not ingest stale IDs into `memory_entries` or eval sampling flows.
- Issues encountered: Legacy-shadow-dependent records can survive from earlier runs; strict corpus joins avoid reusing those rows.
- Next steps: Validate `build-memory -> build-eval-dataset -> eval-*` flow in migrated DB and confirm skipped-invalid behavior is stable.

## [2026-04-14] Session Summary (Eval Corpus FK Hardening)
- What was done: Updated `eval/runtime.py::load_chunk_items` to join `corpus_documents` and exclude orphan chunks, and updated `loaders/import_chunks.py` to skip chunk rows referencing missing documents.
- Key decisions: Prevented invalid `document_id` propagation at both read path (evaluation runtime) and write path (corpus import) to stop recurring FK mismatches.
- Issues encountered: Existing DB contained historical orphan chunks from periods without document FK enforcement.
- Next steps: Run import/eval smoke after Flyway apply to confirm no new orphan chunk references are produced.

## [2026-04-14] Session Summary (AGENTS 3.6 Retrieval/Answer Eval Alignment)
- What was done: Added official comparison retrieval modes (`memory_only_rule_only`, `memory_only_full_gating`) with per-snapshot source-run mapping support, fixed sample-mode evaluator parameter wiring under concurrency, and added explicit answer metrics (`correctness`, `grounding`, `hallucination_rate`) to summary/detail outputs.
- Key decisions: Preserved per-mode metrics instead of collapsing to a single mode and stored retrieved doc/chunk IDs in metadata for `rerank_results` to stay robust under mixed FK environments.
- Issues encountered: Concurrent retrieval eval path had argument mismatch risk after mode extension; function signatures and caller wiring were normalized.
- Next steps: Run official comparison smoke tests to verify bundled modes, per-mode summaries, and answer-level metric reporting consistency.

---

## [2026-04-13] Session Summary (Synthetic Raw Split)
- What was done: Updated generation to write directly to strategy-specific raw tables (`synthetic_queries_raw_a/b/c/d`) and updated gating/memory/eval reads to `synthetic_queries_raw_all`.
- Key decisions: Kept read paths unified through the split-backed union view while enforcing strategy-separated writes.
- Issues encountered: Needed compatibility with existing gating/memory/eval flows while removing legacy single-table dependency.
- Next steps: Run end-to-end smoke (`generate-queries -> gate-queries -> build-memory -> build-eval-dataset`) after DB migration apply.

## [2026-04-13] Session Summary (Dynamic Korean Ratio Rule)
- What was done: Updated `gating/quality_gating.py::_rule_pass` to read Korean-ratio thresholds from experiment config (`rule_min_korean_ratio`, `rule_min_korean_ratio_code_mixed`).
- Key decisions: Preserved previous defaults (`0.40` general, `0.20` code-mixed) and added value clamping to `[0, 1]`.
- Issues encountered: Needed backward compatibility for existing experiment configs that do not define the new keys.
- Next steps: Validate with admin-triggered gating runs using custom Korean-ratio values.

## [2026-04-14] Session Summary (Snapshot-Bound Eval Filtering)
- What was done: Updated memory/eval runtime to support snapshot-bound filtering via `source_gating_run_id` and propagated this filter through retrieval/answer evaluation and selective rewrite paths.
- Key decisions: Added `source_generation_strategies` fallback in memory build config parsing and preserved backward compatibility with existing `memory_generation_strategies`.
- Issues encountered: Without run-id filtering, memory lookup could mix entries from different gating runs; this was resolved by reading `memory_entries.metadata.source_gate_run_id` and filtering in `memory_top_n`.
- Next steps: Validate repeated runs against the same snapshot to confirm stable metrics and verify behavior when snapshot is omitted.

## [2026-05-11] Session Summary (RAG Answer Eval CSV Field Drift Guard)
- What was done: Fixed `eval/answer_eval.py` CSV write path so detail rows no longer fail when runtime adds new keys (e.g., `rewrite_llm_attempted`, `rewrite_llm_succeeded`, `rewrite_heuristic_fallback_used`). `_write_csv` now expands fieldnames dynamically from row keys and writes safely.
- Key decisions: Chose schema-drift-tolerant CSV output to prevent hard failure in long-running RAG eval jobs while still preserving newly emitted observability columns in exported detail CSV.
- Issues encountered: Existing fixed `fieldnames` list lagged behind recently added rewrite observability fields, causing `ValueError` and full `eval-answer` stage failure.
- Next steps: Keep answer-detail CSV consumers tolerant to additive columns and monitor whether any downstream parser requires explicit column-order pinning.

---

## Notes
- Keep this file concise.
- Record only major pipeline changes.
