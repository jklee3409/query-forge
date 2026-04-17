# progress.md

## Overview
High-level pipeline progress tracking.

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

---

## Notes
- Keep this file concise.
- Record only major pipeline changes.
