# progress.md

## Overview
High-level progress tracking for the project.

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
