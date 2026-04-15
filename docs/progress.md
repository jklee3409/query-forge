# progress.md

## Overview
High-level progress tracking for the project.

---

## [2026-04-13] Session Summary
- What was done: `docs` 하위 문서 집합(API/architecture/UI/experiments)을 점검하고 문서 인덱스를 생성했다.
- Key decisions: 문서 역할을 기능 문서와 실험 문서로 분리해 정리했다.
- Issues encountered: 없음.
- Next steps: 신규 API/실험 보고서가 추가될 때 `Structure` 목록을 동기화한다.

## [2026-04-15] Session Summary (A/C RAG Comparison Report for Short User Dataset)
- What was done: Added `docs/report/rag_quality_ac_comparison_short_user_2026-04-15.md` with a detailed comparison between baseline runs (`a280...`, `4de...`) and newly executed short-user runs (`cfb7...`, `2a89...`).
- Key decisions: Structured the report by AGENTS 3.6 discipline (single-variable isolation, snapshot-aware interpretation, reproducibility) and included raw metric snapshot JSON (`docs/report/rag_eval_short_user_40_data_2026-04-15.json`).
- Issues encountered: `human_eval_default` auto-sync refreshed totals after new sample insertion, so the report distinguishes current dataset totals from historical run-time sample counts.
- Next steps: Extend reporting with ungated/rule_only/full_gating comparison on the same short-user dataset.

---

## Notes
- Keep this file concise
- Only record important changes
