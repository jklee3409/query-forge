# progress.md

## Overview
High-level pipeline progress tracking.

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

---

## Notes
- Keep this file concise.
- Record only major pipeline changes.
