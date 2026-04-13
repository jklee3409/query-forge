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
