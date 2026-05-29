# progress.md

## Overview
High-level progress tracking for the project.

## [2026-05-29] Session Summary (Detailed Rewrite Profile Prompt)
- What was done: Added `prompts/rewrite/selective_rewrite_detailed_intent_v1.md` and registered `rewrite_query_profiles` in `app/model_catalog.yml`.
- Key decisions: `compact_anchor` remains the default Admin runtime profile; `detailed_intent` is a separate prompt asset for expanding short Korean/code-mixed queries into self-contained technical-document questions.
- Issues encountered: Validation is covered by backend/frontend/pipeline targeted checks in the root session summary; no live LLM prompt call was executed.
- Next steps: Compare detailed-intent prompt traces against compact-anchor traces on the same snapshot before changing defaults.

## [2026-05-27] Session Summary (Admin Eval Artifact Ignore)
- What was done: Removed tracked `configs/experiments/admin_eval_*.yaml` runtime artifacts and added ignore rules for future `admin_eval_*.yaml`/`*.yml` files.
- Key decisions: Admin-generated eval configs are transient run artifacts; durable reproducibility should use named experiment presets instead.
- Issues encountered: None.
- Next steps: Keep future official experiment conditions in curated preset files, not UUID-named Admin run configs.

## [2026-05-27] Session Summary (Domain-Aware Selective Rewrite v3)
- What was done: Updated `prompts/rewrite/selective_rewrite_v3.md` to consume `domain_context` and instruct the LLM to translate Korean technical terms into domain-appropriate English documentation terms.
- Key decisions: Added few-shot examples for Spring and PostgreSQL short Korean queries, including `트랜잭션 -> Transaction` and PostgreSQL `COMMIT` recovery from raw retrieval evidence.
- Issues encountered: Prompt validation is static/targeted only; no live LLM prompt run was executed.
- Next steps: Inspect real prompt payloads and rejected rewrite cases after the next fixed-snapshot RAG rerun.

## [2026-05-27] Session Summary (Selective Rewrite v3 Candidate Policy)
- What was done: Rewrote `prompts/rewrite/selective_rewrite_v3.md` around `candidate_policy`, raw-retrieval evidence, and separate raw-standalone vs trusted-memory-expanded examples.
- Key decisions: `raw_standalone` ignores synthetic memory hints but may use exact terms from raw-query retrieval evidence; `memory_expanded` may use trusted memory anchors only when they clearly match raw intent.
- Issues encountered: Run `76c16e3b-e92a-4b01-8b3f-10859adb2c8b` showed that generic Korean paraphrases were not enough to cross rewrite adoption thresholds.
- Next steps: Inspect real rewrite payloads to confirm standalone prompts recover evidence-backed exact technical terms without copying synthetic memory anchors.

## [2026-05-26] Session Summary (Selective Rewrite v3 Activation)
- What was done: Marked `prompts/rewrite/selective_rewrite_v3.md` active as the lightweight Korean/code-mixed rewrite prompt.
- Key decisions: v3 keeps the simplified input/output contract and omits mandatory `intent_risk`; runtime and catalog activation are recorded in pipeline/backend progress.
- Issues encountered: Validation is recorded in the root session summary.
- Next steps: Compare v3 rewrite-effect latency and retrieval metrics against the prior v2 prompt on the same snapshot/dataset.

## [2026-05-26] Session Summary (Selective Rewrite v3 Draft)
- What was done: Added `prompts/rewrite/selective_rewrite_v3.md` as a lightweight draft system prompt for real-time hybrid retrieval query rewriting.
- Key decisions: Kept v3 separate from the active v2 runtime binding, removed retriever-backend input dependencies and `intent_risk`, capped candidates at two, and kept only three few-shot examples.
- Issues encountered: Static prompt/document update only; no runtime loader, DB prompt binding, or migration was changed.
- Next steps: If v3 is adopted operationally, update prompt binding/runtime schema and evaluate latency plus retrieval quality against the same snapshot and dataset.

## [2026-05-26] Session Summary (Cautious Selective Rewrite Prompts)
- What was done: Raised `app/model_catalog.yml` `rewrite_threshold` default to `0.05` and updated Korean/code-mixed and English rewrite prompts to `v5`/`v3`.
- Key decisions: The rewrite prompt now prioritizes compatible synthetic query examples as retrieval-structure guidance and treats anchors as optional cautious grounding hints instead of forced additions.
- Issues encountered: Validation is recorded in the root session summary.
- Next steps: Inspect next Admin rewrite traces for lower bad-rewrite rate and enough accepted candidates under the stricter `0.05` threshold.

## [2026-05-26] Session Summary (PostgreSQL Source Presets)
- What was done: Added source presets for official English PostgreSQL current docs and PostGIS docs: `app/sources/postgresql-docs-current.yaml` and `app/sources/postgis-docs-current.yaml`.
- Key decisions: PostGIS supplements the same PostgreSQL technical-document domain so corpus size reaches Spring-scale chunk volume; the stale PostgreSQL 17 preset was removed after duplicate-section import conflict cleanup.
- Issues encountered: Final PostgreSQL domain verification showed 1,644 documents and 2,147 chunks across the two active source presets.
- Next steps: Keep these source IDs attached to the PostgreSQL domain for future ingestion, generation, and gating reruns.

## [2026-05-25] Session Summary (Domain-Neutral Query Generation Prompts)
- What was done: Updated `prompts/query_generation/gen_a_v1.md`, `gen_b_v1.md`, `gen_c_v1.md`, and `gen_d_v1.md` to replace Spring-specific anchor guidance with generic source-grounded technical-document anchors.
- Key decisions: Removed C's domain-specific `SAP flow` wording, preserved all output schemas and prompt versions, and left E/F/G unchanged because no Spring/Python-dependent wording was present.
- Issues encountered: Prompt validation was static only; no LLM generation or pipeline run was executed.
- Next steps: Inspect the next cross-domain synthetic generation samples for anchor fidelity without Spring/Python bias.

## [2026-05-25] Session Summary (RAG Runtime Defaults Catalog)
- What was done: Added `rewrite_memory_candidate_pool_n` and `retriever_mode_defaults` to `app/model_catalog.yml` so Admin RAG default threshold/top-K/rerank/candidate-pool/mode-weight values are served from one catalog.
- Key decisions: The catalog is now the source for frontend form hydration and backend omitted-field defaults; fallback constants remain only for catalog-missing safety.
- Issues encountered: Static config and targeted backend/frontend validation only; no live Admin API smoke was run.
- Next steps: Add future RAG GUI runtime defaults to this catalog before wiring UI controls.

## [2026-05-25] Session Summary (Rewrite Prompts Retrieval Context)
- What was done: Updated Korean/code-mixed `selective_rewrite_v2` and English `selective_rewrite_en_v1` prompts to consume `retrieval_context`, including backend/vector store/retriever mode/embedding model/fusion weights.
- Key decisions: The rewrite output schema did not change; LLM now receives retriever-aware guidance while synthetic queries remain examples and anchor evidence only.
- Issues encountered: No live LLM prompt execution was run.
- Next steps: Inspect real rewrite payloads from a fixed-snapshot rerun to verify candidate structures differ between BM25, dense, and hybrid modes.

## [2026-05-25] Session Summary (Selective Rewrite v4 Global Few-Shot)
- What was done: Updated `prompts/rewrite/selective_rewrite_v2.md` to version `v4`, making the rewrite prompt global across technical-doc domains and expanding the examples to five few-shots with explicit synthetic-example and anchor-injection roles.
- Key decisions: Synthetic queries remain search-friendly examples only, never raw-query replacements. The prompt now requires at least one retriever-specific candidate when compatible anchors exist and rejects shorter/vaguer rewrites as a prompt-level behavior.
- Issues encountered: Static prompt inspection only; no live LLM or DB run was executed.
- Next steps: Compare fixed-snapshot rewrite traces to confirm candidates preserve raw intent while adding compatible anchors from A/C memory.

## [2026-05-25] Session Summary (RAG Rewrite Threshold Catalog Default)
- What was done: Updated `app/model_catalog.yml` so `default_parameter_ranges.rewrite_threshold.default` is `0.02`.
- Key decisions: Kept min/max unchanged and aligned the catalog default with Admin backend/frontend runtime defaults.
- Issues encountered: Catalog edit was validated through backend compile and frontend runtime build in the same stage.
- Next steps: Keep future GUI defaults sourced from the catalog where practical to avoid another split-brain threshold.

## [2026-05-25] Session Summary (Rewrite Prompt Structured Metadata)
- What was done: Updated Korean/code-mixed and English selective rewrite prompts to consume sanitized memory rows and emit `query`, `preserved_raw_terms`, `added_anchors`, `source_memory_index`, and `intent_risk` for every candidate.
- Key decisions: Internal memory/document/chunk IDs are explicitly forbidden; `source_memory_index` is only a prompt-local index used by post-processing.
- Issues encountered: Prompt changes were validated through targeted runtime unit tests, not a live LLM run.
- Next steps: Inspect rewrite traces after a fixed-snapshot rerun to verify declared anchors are compact and covered by query text.

## [2026-05-25] Session Summary (Short-User Rewrite Prompt Compactness)
- What was done: Updated `prompts/rewrite/selective_rewrite_v2.md` to tell the LLM to keep short-user Korean rewrite candidates within the compact adoption gate when possible and prefer decisive anchors over filler.
- Key decisions: Preserved the existing prompt id, schema, labels, and prompt-only memory policy.
- Issues encountered: Prompt validation was static only; no live LLM run was performed.
- Next steps: Compare fixed-snapshot rewrite traces after rerun to verify compact anchor phrases replace verbosity rejections.

## [2026-05-20] Session Summary (Rewrite Prompt Memory Context Clarification)
- What was done: Updated Korean/code-mixed and English selective rewrite prompts to describe `top_memory_candidates` as synthetic query examples / compatible retrieval-anchor context only.
- Key decisions: Added explicit wording that memory queries must not be copied wholesale or used directly as retrieval queries; only compatible anchors/concepts may be borrowed for final rewritten query candidates.
- Issues encountered: Prompt changes were validated by static inspection only; no live LLM prompt execution was performed.
- Next steps: Inspect later rewrite traces for copied-memory-query regressions and anchor compatibility.

## [2026-05-20] Session Summary (Selective Rewrite v3 Retrieval Anchors)
- What was done: Updated `prompts/rewrite/selective_rewrite_v2.md` to metadata version `v3`, reframing Korean/code-mixed rewrite as an English technical-document search query rather than a natural Korean sentence.
- Key decisions: Preserved the existing JSON schema and labels while redefining candidate roles as conservative standalone, anchor-expanded, and compact retrieval-phrase strategies.
- Issues encountered: No live LLM prompt execution was run; validation was kept to prompt/schema/static checks.
- Next steps: Compare fixed-snapshot rewrite traces to confirm compatible synthetic-memory anchors appear in final candidates without topic drift.

## [2026-05-19] Session Summary (Multi-source Rewrite Hint Prompt Policy)
- What was done: Updated Korean/code-mixed and English selective rewrite prompt assets to accept optional `multi_source_anchor_hints`.
- Key decisions: Prompt policy explicitly treats multi-source anchors as lower-priority optional hints that must never override raw-query anchors, user intent, or session constraints.
- Issues encountered: Covered by targeted runtime prompt-payload tests; no prompt-only live LLM execution was run.
- Next steps: Tune prompt wording only after inspecting real off/on rewrite payloads and bad-case diagnostics.

## [2026-05-19] Session Summary (English Rewrite Prompt Asset)
- What was done: Added `prompts/rewrite/selective_rewrite_en_v1.md` for English-native RAG rewrite candidate generation in E/F-style English synthetic-query evaluation.
- Key decisions: Kept the existing Korean/code-mixed `selective_rewrite_v2` asset unchanged and made the English prompt a separate asset with its own id/version.
- Issues encountered: Covered by pipeline prompt-selection tests; no standalone prompt execution was run.
- Next steps: Tune the English prompt only after reviewing real E/en rewrite payloads.

## [2026-05-19] Session Summary (Selective Rewrite Canonical Anchor Hints)
- What was done: Updated `prompts/rewrite/selective_rewrite_v2.md` to document optional `canonical_anchor_hints` input and require intent-compatible canonical/normalized anchor preservation without synonym expansion or topic substitution.
- Key decisions: Kept the prompt family/id/version unchanged and treated canonical hints as compact additive input, not as raw canonical metadata or a new runtime option list.
- Issues encountered: Covered through the pipeline targeted rewrite runtime test class; no standalone prompt-only command was needed.
- Next steps: Tune wording only if real rewrite debug payloads show over-injection or ignored canonical hints.

## [2026-05-15] Session Summary (Strategy B Admin Config Smoke)
- What was done: Inspected Admin-generated Strategy B configs from live smoke runs. The current-code one-source config persisted B-only safe defaults, and the all-allowed-sources config additionally wrote the five Spring reference `source_ids` in a single batch config.
- Key decisions: Kept generated `admin_gen_*.yaml` files as ignored runtime artifacts; no tracked experiment preset changes were needed after validation.
- Issues encountered: A stale 8080-generated Admin config lacked the B defaults, so runtime process freshness must be checked before interpreting Admin smoke failures.
- Next steps: Keep `strategy_b_smoke.yaml` for controlled one-row checks and use Admin-generated configs for incremental all-source scaling.

## [2026-05-15] Session Summary (Strategy B Smoke Preset)
- What was done: Added `experiments/strategy_b_smoke.yaml`, a one-source/one-chunk/one-query Strategy B smoke preset with forced `code_mixed` query type, bounded B payload settings, and a B-specific translation output-token budget.
- Key decisions: Kept the preset narrow and explicit so B smoke runs are reproducible without changing broader `gen_b` or Admin defaults for other strategies.
- Issues encountered: Initial B smoke showed translation truncation under the global output-token cap; the preset now mirrors the B-only Admin safety value.
- Next steps: Use this preset for quick B row/trace/asset validation before larger all-source Strategy B runs.

## [2026-05-15] Session Summary (Strategy B Query-Only Prompt Contract)
- What was done: Updated `prompts/query_generation/gen_b_v1.md` to version `v5` with a query-only output contract (`query_ko`, `query_type`, `answerability_type`) and clarified that `translated_chunk_ko` and `extractive_summary_ko` are upstream inputs, not query-generation outputs.
- Key decisions: Kept Strategy B as Korean-native (`EN chunk -> KO translation -> KO extractive summary -> KO synthetic query`) while reducing final generation output size for LLM stability.
- Issues encountered: None in config editing.
- Next steps: Implement the runtime B path so EN extractive summary is no longer mandatory and KO translation/summary artifacts are generated or cached directly from the original chunk.

## [2026-05-13] Session Summary (Model Catalog Retrieval Backend Allowlist)
- What was done: Extended `configs/app/model_catalog.yml` with `retrieval_backends` entries for `local` and `db_ann` so Admin runtime option selection can be allowlist-driven for retrieval backend as well as model/mode/policy.
- Key decisions: Left default backend as `local` to avoid implicit `db-ann` selection before chunk embeddings are materialized.
- Issues encountered: None.
- Next steps: If environment-specific policy is needed later, use the same catalog metadata fields (`status`, `availability`, `reason`) to gate `db_ann`.

## [2026-05-12] Session Summary (Selective Rewrite Prompt Guard Rollback)
- What was done: Rolled back the extra intent-locked query-expansion wording in `prompts/rewrite/selective_rewrite_v2.md` to the previous rewrite-candidate style while preserving the same prompt id/schema and terminology-hint inputs.
- Key decisions: Kept basic intent preservation and technical-token preservation, but removed the stricter "raw target only/minimal expansion/topic-shift prohibition" wording that over-constrained short Korean query rewrites.
- Issues encountered: Before/After RAG analysis showed the stricter prompt and raw-query preservation path could suppress useful synthetic-memory retrieval anchors.
- Next steps: Re-run same-snapshot A/full-gating retrieval to confirm rewrite candidates recover useful synthetic-query anchors without reintroducing severe topic drift.

## [2026-05-11] Session Summary (F/G Prompt Grounding Contract)
- What was done: Updated `prompts/query_generation/gen_f_v1.md` and `gen_g_v1.md` to include `related_chunks_ko` as optional near/far evidence and to prohibit queries derived from overlap/previous-chunk context.
- Key decisions: Kept existing F/G output schemas unchanged (`F: query_ko + query_en`, `G: query_ko`) and made only grounding/control text updates aligned with the runtime payload.
- Issues encountered: None.
- Next steps: Inspect a small F/G generation sample for answerability alignment before large-batch generation.

## [2026-05-10] Session Summary (gen_e_v1 `code_mixed` Interpretation Clarification)
- What was done: Revised `prompts/query_generation/gen_e_v1.md` so `query_type=code_mixed` is explicitly interpreted as English-native query composition with exact technical/code token preservation.
- Key decisions: Kept `code_mixed` in shared query-type enum for pipeline compatibility; adjusted only prompt semantics (`Rules`, `Quality targets`, `Query type control`, `Forbidden patterns`).
- Issues encountered: Previous wording could be misread as requiring Korean-English language mixing, which is inconsistent with strategy E's English-only retrieval path.
- Next steps: Spot-check E outputs under `code_mixed` to ensure no Korean token injection and no anchor loss.

## [2026-05-10] Session Summary (E/F/G Query Prompt Structure Normalization)
- What was done: Upgraded `prompts/query_generation/gen_e_v1.md`, `gen_f_v1.md`, `gen_g_v1.md` to the same structural control template used by A/B/C/D, adding full sections for answerability/query-type control, forbidden patterns, output contract/schema, and internal self-check.
- Key decisions: Kept strategy-specific generation logic distinct (`E: EN->EN query`, `F: KO->KO->EN final`, `G: KO->KO final`) and preserved existing runtime-facing output fields to avoid parser breakage.
- Issues encountered: None in prompt assets themselves; existing runtime query response schema remains broader/shared and may over-require `query_ko` for English-final strategy E.
- Next steps: Validate E/F/G output stability with one controlled generation batch and inspect schema-driven retry behavior for E.

## [2026-05-10] Session Summary (Query Generation Prompts F/G Added)
- What was done: Added new query-generation prompt assets `prompts/query_generation/gen_f_v1.md` and `prompts/query_generation/gen_g_v1.md` for KR-source synthetic strategies.
- Key decisions: Kept prompt family/versioning pattern consistent with existing `gen_[a-e]_v1` assets and separated `F` (KR->EN final query) vs `G` (KR final query) contracts.
- Issues encountered: None.
- Next steps: Validate prompt-output stability for `F/G` under the same source chunk set and compare strategy-specific retrieval behavior.

## [2026-05-09] Session Summary (Selective Rewrite Prompt v2 Intent-Locked Expansion Policy)
- What was done: Revised `prompts/rewrite/selective_rewrite_v2.md` so rewrite generation is treated as intent-preserving query expansion with explicit topic-substitution prohibition.
- Key decisions: Preserved existing runtime payload schema and business logic (top memory candidates, anchor injection, selective adoption) and strengthened only prompt guardrails.
- Issues encountered: None.
- Next steps: Validate prompt-only effect on bad rewrite cases under fixed snapshot/dataset and compare adoption/quality metrics.

## [2026-05-08] Session Summary (Model Catalog Allowlist Added for Admin Runtime Options)
- What was done: Added `configs/app/model_catalog.yml` to define allowlisted runtime options for Admin (`llm_providers`, `llm_models`, `dense_embedding_models`, `retriever_modes`, `rewrite_failure_policies`) and default parameter ranges (`retrieval_top_k`, `rerank_top_n`, `rewrite_threshold`, `retriever_candidate_pool_k`).
- Key decisions: Kept catalog schema flat/simple YAML so backend can reject non-allowlisted runtime selections deterministically while returning metadata (`status/availability/reason`) through `/api/admin/console/runtime/options`.
- Issues encountered: None.
- Next steps: Extend catalog entries/status by environment policy (for example, temporarily disabled models with explicit reason) without frontend constant changes.

## [2026-05-06] Session Summary (Selective Rewrite Prompt v2 Terminology-Hint Policy)
- What was done: Updated `prompts/rewrite/selective_rewrite_v2.md` to accept `terminology_hints` input and explicitly preserve hint tokens verbatim when intent-compatible.
- Key decisions: Kept existing concise retrieval-query style constraints and added explicit prohibition against long pseudo-document final queries.
- Issues encountered: None.
- Next steps: Compare prompt-only effect with fixed snapshot/retriever/threshold to verify bad rewrite reduction and adoption quality.

## [2026-05-04] Session Summary (Configs README Current-State Sync)
- What was done: Updated `configs/README.md` to reflect active experiment/prompt usage (admin batch presets, `gen_e_v1`, `selective_rewrite_v2` priority, source presets) instead of scaffold-stage wording.
- Key decisions: Preserved existing configuration principles (no hardcoding, reproducibility-first) while aligning examples to currently tracked files.
- Issues encountered: None.
- Next steps: Keep README synchronized when prompt families or experiment preset conventions are added/retired.

## [2026-05-02] Session Summary (Admin Source Preset Additions)
- What was done: Added source preset files under `configs/app/sources/` for `arahansa-github-io-docs-spring` and `docs-python-org-ko-3-14` to support admin pipeline collect targets with explicit allow-prefix and deny-pattern defaults.
- Key decisions: Kept the same schema/field conventions as existing source presets (`start_urls`, `allow_prefixes`, `deny_url_patterns`, delay/depth metadata) for compatibility with source catalog sync.
- Issues encountered: None.
- Next steps: Verify each preset through one scoped collect run and refine depth/pattern bounds based on crawl noise.

## [2026-04-28] Session Summary (English Query Generation Prompt + Language-Aware Rewrite/Gating Prompts)
- What was done: Added `configs/prompts/query_generation/gen_e_v1.md` for English-native synthetic query generation, rewrote `selective_rewrite_v2.md` to accept `query_language`, and generalized `quality_gate_v1.md` scoring from `korean_naturalness` to `naturalness`.
- Key decisions: Reused existing prompt families and versions where possible to keep runtime lookup simple; English support was added by prompt contract expansion rather than a second rewrite prompt family.
- Issues encountered: None.
- Next steps: Sample-check English `E` outputs and revise prompt wording if retrieval traces show over-generic short queries.

## [2026-04-28] Session Summary (Selective Rewrite Prompt v2: Intent-Preservation + Memory-Augmented Retrieval)
- What was done: Revised `prompts/rewrite/selective_rewrite_v2.md` to explicitly enforce intent-preserving rewrite generation while using `top_memory_candidates` as constrained augmentation hints. Added strict memory-conflict handling, candidate-level intent consistency checks, and compact retrieval-oriented query phrasing rules.
- Key decisions: Kept prompt asset id/version unchanged (`selective_rewrite_v2`) for runtime compatibility, and strengthened behavior via instruction policy rather than pipeline logic changes.
- Issues encountered: None.
- Next steps: Re-run same-snapshot selective rewrite experiments and compare adoption/quality deltas by prompt revision with fixed threshold and retriever settings.

## [2026-04-19] Session Summary (Rewrite Prompt v2 Added)
- What was done: Added `configs/prompts/rewrite/selective_rewrite_v2.md` for retrieval-optimized rewrite generation and kept `selective_rewrite_v1.md` as compatibility fallback.
- Key decisions: v2 emphasizes lexical anchor preservation for hash-embedding retrieval characteristics while preserving candidate schema/labels.
- Issues encountered: None.
- Next steps: Validate prompt quality with category-specific rewrite case samples and adjust guardrails if drift/noise appears.

---

## [2026-04-13] Session Summary
- What was done: 설정 파일 전체(`app`, `experiments`, `prompts`)를 파악해 디렉토리 문서를 생성했다.
- Key decisions: 실험 재현성 중심으로 설정 목적을 정리하고 전략별 프롬프트 분리를 명시했다.
- Issues encountered: 없음.
- Next steps: 실험 프리셋 또는 프롬프트 버전이 추가/변경되면 문서 구조를 즉시 동기화한다.

---

## Notes
- Keep this file concise
- Only record important changes
