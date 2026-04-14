# index.md

## Directory Overview
Python pipeline for data processing, synthetic query generation, quality gating, memory building, dataset construction, and evaluation.

---

## Structure
- `generation/synthetic_query_generator.py`: synthetic query generation (A/B/C/D)
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
- Keep gating/memory/eval compatible with split raw tables.

---

## Key Notes
- Synthetic generation now writes to `synthetic_queries_raw_a/b/c/d` by strategy.
- Gating/memory/eval reads use `synthetic_queries_raw_all` (union view over split tables).
- This directory assumes DB migration `V17` is applied before runtime execution.
- Quality gating rule thresholds include configurable Korean-ratio keys (`rule_min_korean_ratio`, `rule_min_korean_ratio_code_mixed`).
- Retrieval/answer evaluation can be pinned to a snapshot via `source_gating_run_id`, with memory lookup filtering by `memory_entries.metadata.source_gate_run_id`.
- Retrieval eval supports official bundled comparison modes with per-preset snapshot mapping (`comparison_snapshots`) and preserves per-mode summaries/latencies.
- Answer eval outputs explicit end-to-end metrics required by AGENTS 3.6 (`correctness`, `grounding`, `hallucination_rate`) alongside legacy overlap metrics.
- Memory build and eval-dataset sampling now require valid corpus joins (`corpus_documents`/`corpus_chunks`) to block stale ID propagation.
- Eval runtime chunk loading excludes orphan chunks by joining `corpus_documents`, and import skips chunk rows with missing corpus document references.
- Selective rewrite adoption now considers retrieval shift (`top-k` composition change + `top1` change) in addition to confidence, reducing zero-adoption lock when rerank scores are flat.
