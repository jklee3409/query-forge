# progress.md

## Overview
High-level progress tracking for the project.

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
