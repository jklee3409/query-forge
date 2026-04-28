# index.md

## Directory Overview
Python pipeline for data processing, synthetic query generation, quality gating, memory building, dataset construction, and evaluation.

---

## Structure
- `generation/synthetic_query_generator.py`: synthetic query generation (A/B/C/D/E)
- `gating/quality_gating.py`: quality gating
- `memory/build_memory.py`: memory entry construction
- `datasets/build_eval_dataset.py`: evaluation dataset creation
- `eval/*`: retrieval/answer evaluation stages
- `common/*`: shared config, experiment run, llm, embedding, and utility modules
- `cli.py`: pipeline command entrypoint

---

## Responsibilities
- Preserve fixed pipeline stage order from AGENTS constraints.
- Enforce strategy-separated synthetic raw writes.
- Keep gating/memory/eval compatible with split raw tables and query-language-aware evaluation.

---

## Key Notes
- Synthetic generation now writes to `synthetic_queries_raw_a/b/c/d/e` by strategy.
- Gating/memory/eval reads use `synthetic_queries_raw_all` (union view over split tables).
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
- Cohere rerank fallback returns no artificial relevance scores; callers preserve local hybrid ranking when external rerank is unavailable.
- Synthetic-free baseline config (`synthetic_free_baseline=true`) is supported with `build-memory` no-op and eval-time memory loading bypass for raw-only baseline execution.
- Retrieval eval supports official bundled comparison modes with per-preset snapshot mapping (`comparison_snapshots`) and preserves per-mode summaries/latencies.
- Answer eval outputs explicit end-to-end metrics required by AGENTS 3.6 (`correctness`, `grounding`, `hallucination_rate`) alongside legacy overlap metrics.
- Memory build and eval-dataset sampling now require valid corpus joins (`corpus_documents`/`corpus_chunks`) to block stale ID propagation.
- Eval runtime chunk loading excludes orphan chunks by joining `corpus_documents`, and import skips chunk rows with missing corpus document references.
- Selective rewrite prompt loading prefers `configs/prompts/rewrite/selective_rewrite_v2.md` with automatic fallback to `selective_rewrite_v1.md`.
- Eval runtime can load English eval samples (`user_query_en`) through `eval_query_language=en`, and selective rewrite receives the sample query language for English/Korean candidate generation.
- Selective rewrite adoption now considers retrieval shift (`top-k` composition change + `top1` change) in addition to confidence, reducing zero-adoption lock when rerank scores are flat.
- Langfuse LLM observability is integrated at `common/langfuse_observability.py` and wired only through `common/llm_client.py` with fail-open behavior.
