# index.md

## Directory Overview
Python pipeline for data processing, synthetic query generation, quality gating, memory building, dataset construction, and evaluation.

---

## Structure
- `generation/synthetic_query_generator.py`: synthetic query generation (A/B/C/D/E/F/G)
- `gating/quality_gating.py`: quality gating
- `memory/build_memory.py`: memory entry construction
- `datasets/build_eval_dataset.py`: evaluation dataset creation
- `eval/*`: retrieval/answer evaluation stages
- `common/*`: shared config, experiment run, llm, embedding, and utility modules
- `cli.py`: pipeline command entrypoint
- `preprocess/extract_anchor_candidates.py`: chunk JSONL -> glossary-logic anchor candidate JSONL bridge for backend re-extraction reuse

---

## Responsibilities
- Preserve fixed pipeline stage order from AGENTS constraints.
- Enforce strategy-separated synthetic raw writes.
- Keep gating/memory/eval compatible with split raw tables and query-language-aware evaluation.

---

## Key Notes
- Synthetic generation now writes to `synthetic_queries_raw_a/b/c/d/e/f/g` by strategy.
- Synthetic query structured-output validation is strategy-aware (`A/B/C/D/E/F/G` required query fields differ), and final `query_text` fallback extraction is restricted to query-only fields (`query`, `query_en`, `query_ko`, `query_code_mixed`) to avoid metadata leakage.
- Gating/memory/eval reads use `synthetic_queries_raw_all` (union view over split tables).
- KR-source strategy variants `F/G` are physical-split strategies and do not reuse `C/E` raw tables.
- `F/G` Korean summary generation path applies a higher summary output-token floor (min `2048`) and truncation-only source-length fallback retries (`3200/2200/1400 chars`) to reduce `MAX_TOKENS` failures on long KR source chunks, without affecting other strategy/stage token budgets.
- `F/G` generation now defaults to deterministic extractive KO summaries (`fg_summary_mode=extractive`) to avoid a per-chunk summary LLM call, strips chunk overlap context before prompting, scopes relation/glossary loading to selected source data, and passes `related_chunks_ko` evidence for near/far query grounding.
- This directory assumes DB migration `V17` is applied before runtime execution.
- Quality gating rule thresholds include configurable Korean-ratio keys (`rule_min_korean_ratio`, `rule_min_korean_ratio_code_mixed`).
- Quality gating self-eval now accepts language-neutral `naturalness` scoring while keeping backward compatibility with legacy `korean_naturalness` outputs.
- Retrieval/answer evaluation can be pinned to a snapshot via `source_gating_run_id`, with memory lookup filtering by `memory_entries.metadata.source_gate_run_id`.
- Memory build now clears stale rows for the active snapshot before insertion and tags rows with `memory_experiment_key`; retrieval/answer eval loads memory by the current experiment key to prevent snapshot contamination.
- Retrieval metrics use bounded exact expected-chunk `nDCG@10`, and answer correctness reads `expected_answer_key_points` from `eval_samples`.
- Local retrieval now uses explicit `RetrieverConfig` modes (`bm25_only`, `dense_only`, `hybrid`) in `common/local_retriever.py` for retrieval eval, answer eval, memory lookup, rewrite candidate scoring, and gating utility scoring.
- Dense/Hybrid retrieval defaults to `intfloat/multilingual-e5-small` and treats hash embedding as an explicit fallback option (`dense_fallback_enabled=true`) rather than the normal path; BM25 mode does not load a dense model.
- Retriever diagnostics include dense similarity, BM25 score, and technical-token overlap so short Korean developer prompts and code-mixed terms can be inspected per memory candidate.
- Selective rewrite scoring recomputes memory affinity for each candidate query and treats retrieval-shift only as a small tie-breaker, preventing threshold decisions from being driven by rank movement alone.
- Selective rewrite payload now supports bounded `terminology_hints` injection (raw-query technical tokens + top memory glossary/query technical tokens) when `rewrite_anchor_injection_enabled=true`, with optional cap `rewrite_terminology_hints_max_count`.
- Selective rewrite adoption now uses staged decomposition (`retrieval_gain_score`, `terminology_preservation_score`, `memory_alignment_score`, `verbosity_penalty`, `final_candidate_score`) with explicit rejection reasons for traceability.
- Rewrite adoption thresholds are now config-driven via `rewrite_adoption_policy` and support category-aware stricter gating (`short_user`, `code_mixed`) plus low-memory similarity extra guard.
- `rewrite_always` now respects candidate validity: when every rewrite candidate is rejected by preservation/verbosity/threshold rules, runtime falls back to the raw query instead of force-applying a known-bad candidate. The default `short_user` profile is also relaxed enough to allow compact technical anchor expansion when retrieval gain is clear.
- Short-user rewrite scoring now derives lightweight `memory target` content tokens from the top memory query/glossary, rejects underspecified generic rewrites that omit those targets under strong memory confidence, and rewards candidates that add the missing target anchor without extra LLM or DB work.
- Cohere rerank fallback returns no artificial relevance scores; callers preserve local hybrid ranking when external rerank is unavailable.
- Synthetic-free baseline config (`synthetic_free_baseline=true`) is supported with `build-memory` no-op and eval-time memory loading bypass for raw-only baseline execution.
- Retrieval eval supports official bundled comparison modes with per-preset snapshot mapping (`comparison_snapshots`) and preserves per-mode quality summaries; deprecated retrieval-side latency aggregates are no longer used by stored RAG Performance payloads.
- Answer eval now records per-sample latency fields (`query_eval_total_latency_ms`, `final_rewrite_latency_ms`, `pure_rewrite_latency_ms`) and writes the corresponding averaged run-level payload (`avg_query_eval_total_latency_ms`, `avg_final_rewrite_latency_ms`, `avg_pure_rewrite_latency_ms`) with sample counts and exclusion count.
- Legacy RAG results are not backfilled from old retrieval latency rows or rewrite-overhead math; the new latency payload exists only for newly executed runs.
- Answer eval CSV export is additive-field tolerant: detail CSV fieldnames are auto-extended from row payload keys so newly introduced rewrite observability columns do not fail `eval-answer`.
- Memory build and eval-dataset sampling now require valid corpus joins (`corpus_documents`/`corpus_chunks`) to block stale ID propagation.
- Eval runtime chunk loading excludes orphan chunks by joining `corpus_documents`, and import skips chunk rows with missing corpus document references.
- Selective rewrite prompt loading prefers `configs/prompts/rewrite/selective_rewrite_v2.md` with automatic fallback to `selective_rewrite_v1.md`.
- Eval runtime can load English eval samples (`user_query_en`) through `eval_query_language=en`, and selective rewrite receives the sample query language for English/Korean candidate generation.
- Eval runtime now enforces dataset-aware corpus scope in retrieval/answer evaluation by deriving allowed product scope from eval sample `source_product` (including alias normalization such as `*-reference -> *`) with expected-doc fallback, preventing unrelated corpus domains from polluting RAG eval metrics.
- `memory_only_*` retrieval modes default to direct top-memory synthetic query retrieval, preserving the original synthetic-query leverage used in earlier A/B/C/D experiments. Intent-preserving raw-query guidance remains available only when `memory_lookup_intent_preserving_enabled=true` is explicitly configured.
- Eval runtime now reuses in-process retrievers for repeated chunk/memory ranking calls (bounded cache keyed by data object identity + retriever config), reducing repeated retriever construction and memory-filter recomputation in rewrite-heavy and memory-heavy evaluation paths.
- Selective rewrite adoption now considers retrieval shift (`top-k` composition change + `top1` change) in addition to confidence, reducing zero-adoption lock when rerank scores are flat.
- Langfuse LLM observability is integrated at `common/langfuse_observability.py` and wired only through `common/llm_client.py` with fail-open behavior.
- LLM JSON calls now classify retry-exhausted failures by category (`request_failed`, `response_empty`, `response_blocked`, `invalid_json`, `schema_mismatch`, `missing_required_key`, `max_tokens_truncated`) and log provider response metadata (`status`, `finish_reason`, `block_reason`) so post-processing failures are distinguishable from transport/API failures.
- Retry-exhaustion exception text now includes the same failure category/metadata (`category`, `status`, `finish_reason`, `block_reason`), so truncated job stderr tails can still expose dominant failure mode without full process logs.
- Normalize preprocessing now supports legacy HTML containers by falling back from `article.doc` to `div#content` and `body` when extracting section records.
- Spring docs collector now skips placeholder-templated URLs (`{...}`) and treats per-URL fetch failures as skip-with-metrics (`fetch_failures`) instead of whole-run aborts.
- `extract-anchor-candidates` CLI command reuses glossary extraction logic for arbitrary chunk scopes so backend anchor re-extraction can share the same extractor path instead of maintaining a separate implementation.
- Concept-anchor extraction now applies shared technical-quality gates and multilingual candidate filtering (Stanza langid + Kiwi noun candidates + YAKE + multilingual E5 rerank fallback) to suppress non-technical helper phrases while preserving technical anchors.
