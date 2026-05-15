# progress.md

## Overview
High-level progress tracking for the project.

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
