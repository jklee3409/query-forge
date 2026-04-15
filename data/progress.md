# progress.md

## Overview
High-level progress tracking for the project.

---

## [2026-04-13] Session Summary
- What was done: 데이터 디렉토리의 추적 대상(`raw/processed/synthetic/eval/reports`)과 런타임 생성 경로(`artifacts/logs/tmp`)를 문서화했다.
- Key decisions: 데이터 단계별 책임 분리를 기준으로 구조를 기록했다.
- Issues encountered: 런타임 산출물 디렉토리는 시점에 따라 내용이 크게 변동된다.
- Next steps: 데이터 저장 정책이 바뀌면 구조/흐름 설명을 우선 업데이트한다.

## [2026-04-15] Session Summary (Short User Eval Dataset 40 Added)
- What was done: Added `data/eval/human_eval_short_user_test_40.jsonl` (40 retrieval-aware short-user queries) and registered it as dataset key `human_eval_short_user_40` for Admin RAG test runs.
- Key decisions: Kept schema identical to existing eval sample format and mapped each item with grounded `expected_doc_ids` / `expected_chunk_ids` to preserve retrieval-eval compatibility.
- Issues encountered: New sample insertion also changes `human_eval_default` aggregate size because default dataset is auto-synced from `eval_samples`.
- Next steps: Expand short-user dataset with multi-turn and higher multi-chunk ratio while keeping snapshot-controlled comparability.

---

## Notes
- Keep this file concise
- Only record important changes
