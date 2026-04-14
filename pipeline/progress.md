# progress.md

## Overview
High-level pipeline progress tracking.

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

---

## Notes
- Keep this file concise.
- Record only major pipeline changes.
