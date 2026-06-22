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
- Synthetic generation accepts an optional `source_ids` list in experiment config and filters chunk loading with `d.source_id = ANY(...)`, allowing Admin all-allowed-sources to run as one batch while staying source-scoped.
- Synthetic generation retry/resume target accounting reads the live `synthetic_queries_raw_all` count for the active `generation_batch_id`, so existing/reused rows count toward `max_total_queries` instead of resetting the count per Python process attempt.
- Synthetic query structured-output validation is strategy-aware (`A/B/C/D/E/F/G` required query fields differ), and final `query_text` fallback extraction is restricted to query-only fields (`query`, `query_en`, `query_ko`, `query_code_mixed`) to avoid metadata leakage.
- A/C generation over long English documentation retries provider `max_tokens_truncated` failures with compacted KO-summary/query payloads and concise JSON retry prompts, preserving the same output schema and strategy-specific raw-table writes.
- Strategy B runtime follows the intended research path: original English chunk -> cached `KO_TRANSLATED_CHUNK` -> deterministic extractive `KO_SUMMARY` -> query-only Korean output (`query_ko`, `query_type`, `answerability_type`). B no longer requires an EN extractive summary asset, B query payloads use bounded evidence windows for long/all-source runs, trace char metrics include the empty B payload fields (`original_chunk_ko`, `extractive_summary_en`), and B rows remain in `synthetic_queries_raw_b` even for `code_mixed` query type.
- Strategy B translation is now deterministic segmented full translation: `corpus_chunks.chunk_text` remains unchanged, text segments are split on heading/paragraph/list/table/code-fence boundaries, fenced code is preserved verbatim, segment translations are cached in `chunk_generation_asset`, and the final reconstructed full Korean translation is stored as `asset_type='KO_TRANSLATED_CHUNK'` with `translation_mode=segmented_full` metadata.
- Strategy B can opt into Gemini Batch API with `llm_execution_mode=gemini_batch` (`gemini_batch_input_mode=inline|jsonl`). The path batches only translation/query cache misses, keeps deterministic KO summaries as upstream assets, records batch job/item usage metadata, and leaves online generation as the default.
- Gating/memory/eval reads use `synthetic_queries_raw_all` (union view over split tables).
- Quality gating source selection prefers explicit `source_generation_batch_ids` and falls back to `source_generation_run_ids`; resume checkpoint slicing is used only when the processed prefix is complete, so recovered generation batches whose raw rows span multiple retry experiment runs are still gated as one batch-scoped target.
- KR-source strategy variants `F/G` are physical-split strategies and do not reuse `C/E` raw tables.
- `F/G` Korean summary generation path applies a higher summary output-token floor (min `2048`) and truncation-only source-length fallback retries (`3200/2200/1400 chars`) to reduce `MAX_TOKENS` failures on long KR source chunks, without affecting other strategy/stage token budgets.
- `F/G` generation now defaults to deterministic extractive KO summaries (`fg_summary_mode=extractive`) to avoid a per-chunk summary LLM call, strips chunk overlap context before prompting, scopes relation/glossary loading to selected source data, and passes `related_chunks_ko` evidence for near/far query grounding. Deterministic KO summary cache template versions include `max_chars` so summary assets generated with different bounds do not collide.
- This directory assumes DB migration `V17` is applied before runtime execution.
- Quality gating rule thresholds include configurable Korean-ratio keys (`rule_min_korean_ratio`, `rule_min_korean_ratio_code_mixed`).
- Quality gating self-eval now accepts language-neutral `naturalness` scoring while keeping backward compatibility with legacy `korean_naturalness` outputs.
- Retrieval/answer evaluation can be pinned to a snapshot via `source_gating_run_id`, with memory lookup filtering by `memory_entries.metadata.source_gate_run_id`.
- Retrieval eval supports explicit `anchor_aware_rewrite` and `agentic_multi_query` modes. Agentic eval remains single-snapshot/single-domain, plans up to four subqueries, runs each through the existing selective rewrite/retrieval path, and merges final candidates with chunk-id RRF.
- Memory build now clears stale rows for the active snapshot before insertion, persists `domain_id` from the gated query domain, and tags rows with `memory_experiment_key`; retrieval/answer eval loads memory by the current experiment key to prevent snapshot contamination.
- Admin `db-ann` evaluation now has a dedicated pgvector path: `materialize-chunk-embeddings` stores model-specific chunk vectors in `chunk_embeddings`, and retrieval/answer eval can use PostgreSQL ANN (`<=>` + HNSW) instead of full local chunk/memory loading.
- `db-ann` hybrid retrieval unions dense ANN candidates with DB lexical and technical-token candidates before the existing hybrid rerank, reducing dense-prefilter recall loss while preserving the same Admin chunk-embedding preparation flow.
- `db-ann` memory lookup uses `memory_entries.query_embedding` filtered by `metadata.embedding_model` plus experiment/snapshot identity, and hybrid mode now unions ANN, lexical, and technical memory candidates before reranking. The online hash path remains isolated outside this runtime.
- Retrieval metrics use bounded exact expected-chunk `nDCG@10`, and answer correctness reads `expected_answer_key_points` from `eval_samples`.
- Local retrieval now uses explicit `RetrieverConfig` modes (`bm25_only`, `dense_only`, `hybrid`) in `common/local_retriever.py` for retrieval eval, answer eval, memory lookup, rewrite candidate scoring, and gating utility scoring.
- Dense/Hybrid retrieval defaults to `intfloat/multilingual-e5-small` and treats hash embedding as an explicit fallback option (`dense_fallback_enabled=true`) rather than the normal path; BM25 mode does not load a dense model.
- Retriever diagnostics include dense similarity, BM25 score, and technical-token overlap so short Korean developer prompts and code-mixed terms can be inspected per memory candidate.
- Selective rewrite scoring recomputes memory affinity for each candidate query and treats retrieval-shift only as a small tie-breaker, preventing threshold decisions from being driven by rank movement alone.
- Selective rewrite evaluation now treats synthetic memory as LLM prompt examples/context only. The default rewrite path computes raw-query retrieval, generates rewritten-query candidates from top memory examples, retrieves each candidate query directly, and selects either raw retrieval or one rewritten-query retrieval result without raw/memory/rewrite merging.
- Retrieval/answer evaluation now computes raw-query retrieval once per sample, persists a `raw_retrieval_cache_{experiment}.json` report artifact, and reuses that same raw top-K for `raw_only`, rewrite modes, and answer evaluation. Local and DB-ANN ranking use deterministic chunk/memory ID tie-breakers to keep raw metrics reproducible across same-snapshot reruns.
- Selective rewrite memory examples are now reranked after raw retrieval with raw top-K chunk/doc overlap, memory target metadata, canonical-anchor overlap, utility score, and product/domain match. Prompt memory rows hide internal IDs and expose only synthetic query, target title/section, glossary/canonical anchors, and short evidence summary.
- Selective rewrite LLM payloads now include `retrieval_context` with the actual retrieval backend, vector store, retriever mode/name, dense embedding model, fusion weights, top-K, and memory candidate pool settings so the prompt can adapt query structure to BM25, dense, hybrid, local, or DB ANN runtime behavior.
- Selective rewrite LLM output accepts lightweight v3 candidates with required `label`/`query` only; legacy metadata fields (`preserved_raw_terms`, `added_anchors`, `source_memory_index`, `intent_risk`) remain optional and are still used when present.
- Selective rewrite supports `rewrite_query_profile`: `compact_anchor` keeps the current compact retrieval-query prompt path, while `detailed_intent` loads `selective_rewrite_detailed_intent_v1.md` and allows longer self-contained query expansion before optional memory/anchor expansion.
- Rewrite-stage LLM calls now receive the raw experiment config, so Admin `llm_rewrite_model` overrides can change only the query-rewrite model without changing synthetic generation or quality-gating models.
- Selective rewrite now splits LLM candidate generation by policy: `raw_standalone` prompts receive the raw query/session context plus raw-retrieval evidence, while `memory_expanded` prompts are emitted only from trusted memory rows whose target doc/chunk evidence overlaps raw retrieval. Synthetic-memory anchor injection cannot enter standalone candidates or standalone scoring.
- Selective rewrite LLM payloads include dynamic `domain_context` derived from `source_product`, with the active documentation domain and Korean-to-English technical term examples so short Korean queries can recover terms such as `Transaction`, `COMMIT`, `Spring Security`, and `readiness probe`.
- Selective rewrite payload now supports bounded `terminology_hints` injection (raw-query technical tokens + top memory glossary/query technical tokens) when `rewrite_anchor_injection_enabled=true`, with optional cap `rewrite_terminology_hints_max_count`.
- Selective rewrite payload now also supports compact `canonical_anchor_hints` from top memory `canonical_anchors`, limited to approved/self-fallback `used_for_scoring=true` anchors and omitting full metadata/term IDs from the LLM prompt.
- Selective rewrite payload now supports optional `multi_source_anchor_hints` from precomputed canonical-anchor relation lookup. The runtime loads the relation table once per eval run and applies score/type/count/term-type/raw-intent filters before exposing low-priority hints to the prompt.
- Retrieval-eval rewrite case artifacts now preserve the prompt-side `anchor_candidates`, `terminology_hints`, `canonical_anchor_hints`, and `multi_source_anchor_hints` for backend DB-backed anchor usage/evaluation.
- Selective rewrite adoption now uses staged decomposition (`retrieval_gain_score`, `terminology_preservation_score`, `memory_alignment_score`, `verbosity_penalty`, `final_candidate_score`) and compares final candidate score against a raw final-score baseline with a raw-loss guard for confident raw top-result loss.
- Rewrite adoption thresholds are now config-driven via `rewrite_adoption_policy` and support category-aware stricter gating (`short_user`, `code_mixed`) plus low-memory similarity extra guard.
- `rewrite_always` remains a legacy/ablation runtime mode only; Admin operational/final RAG test configs no longer generate it. The default `short_user` profile uses the same final-score adoption gate and raw-loss guard while allowing compact technical expansion when evidence is clear.
- Short-user rewrite scoring now derives lightweight `memory target` content tokens from the top memory query/glossary, rejects underspecified generic rewrites that omit those targets under strong memory confidence, and rewards candidates that add the missing target anchor without extra LLM or DB work.
- Cohere rerank fallback returns no artificial relevance scores; callers preserve local hybrid ranking when external rerank is unavailable.
- Synthetic-free baseline config (`synthetic_free_baseline=true`) is supported with `build-memory` no-op and eval-time memory loading bypass for raw-only baseline execution.
- Retrieval eval supports official bundled comparison modes with per-preset snapshot mapping (`comparison_snapshots`) and preserves per-mode quality summaries; deprecated retrieval-side latency aggregates are no longer used by stored RAG Performance payloads.
- Answer eval now records per-sample latency fields (`query_eval_total_latency_ms`, `final_rewrite_latency_ms`, `pure_rewrite_latency_ms`) and writes the corresponding averaged run-level payload (`avg_query_eval_total_latency_ms`, `avg_final_rewrite_latency_ms`, `avg_pure_rewrite_latency_ms`) with sample counts and exclusion count.
- Legacy RAG results are not backfilled from old retrieval latency rows or rewrite-overhead math; the new latency payload exists only for newly executed runs.
- Answer eval CSV export is additive-field tolerant: detail CSV fieldnames are auto-extended from row payload keys so newly introduced rewrite observability columns do not fail `eval-answer`.
- Memory build and eval-dataset sampling now require valid corpus joins (`corpus_documents`/`corpus_chunks`) to block stale ID propagation.
- Eval runtime chunk loading excludes orphan chunks by joining `corpus_documents`, and import skips chunk rows with missing corpus document references.
- Selective rewrite prompt loading is language-aware: English evaluation queries use `configs/prompts/rewrite/selective_rewrite_en_v1.md`, while Korean/code-mixed queries prefer `selective_rewrite_v3.md` with automatic fallback to `selective_rewrite_v2.md` and then `selective_rewrite_v1.md`.
- Eval runtime can load English eval samples (`user_query_en`) through `eval_query_language=en`, and selective rewrite receives the sample query language for English/Korean candidate generation.
- Eval runtime now enforces dataset-aware corpus scope in retrieval/answer evaluation by deriving allowed product scope from eval sample `source_product` (including alias normalization such as `*-reference -> *`) with expected-doc fallback, preventing unrelated corpus domains from polluting RAG eval metrics.
- `memory_only_*` retrieval modes now default to intent-preserving retrieval hints: raw query retrieval is preserved, top memory anchors are appended as a bounded guided query, and results are merged by score. Direct top-memory synthetic query retrieval remains available only with `memory_lookup_direct_enabled=true`.
- Eval runtime now reuses in-process retrievers for repeated chunk/memory ranking calls (bounded cache keyed by data object identity + retriever config), reducing repeated retriever construction and memory-filter recomputation in rewrite-heavy and memory-heavy evaluation paths.
- `import-corpus` supports `--domain-id`, records domain context in run snapshots, and applies it to imported corpus/source/glossary artifacts after import so standalone pipeline imports can stay aligned with technical-document domains.
- Selective rewrite adoption now considers retrieval shift (`top-k` composition change + `top1` change) in addition to confidence, reducing zero-adoption lock when rerank scores are flat.
- Langfuse LLM observability is integrated at `common/langfuse_observability.py` and wired only through `common/llm_client.py` with fail-open behavior.
- Gemini fallback defaults in `common/llm_client.py` are pinned to `gemini-2.5-flash-lite`; when the primary model is already Flash-Lite the duplicate fallback target is skipped, avoiding an extra higher-cost fallback path.
- LLM JSON calls now classify retry-exhausted failures by category (`request_failed`, `response_empty`, `response_blocked`, `invalid_json`, `schema_mismatch`, `missing_required_key`, `max_tokens_truncated`) and log provider response metadata (`status`, `finish_reason`, `block_reason`) so post-processing failures are distinguishable from transport/API failures.
- Retry-exhaustion exception text now includes the same failure category/metadata (`category`, `status`, `finish_reason`, `block_reason`), so truncated job stderr tails can still expose dominant failure mode without full process logs.
- Normalize preprocessing now supports legacy HTML containers by falling back from `article.doc` to `div#content` and `body` when extracting section records.
- Spring docs collector now skips placeholder-templated URLs (`{...}`) and treats per-URL fetch failures as skip-with-metrics (`fetch_failures`) instead of whole-run aborts.
- `extract-anchor-candidates` CLI command reuses glossary extraction logic for arbitrary chunk scopes so backend anchor re-extraction can share the same extractor path instead of maintaining a separate implementation.
- Concept-anchor extraction now applies shared technical-quality gates and multilingual candidate filtering (Stanza langid + Kiwi noun candidates + YAKE + multilingual E5 rerank fallback) to suppress non-technical helper phrases while preserving technical anchors.
