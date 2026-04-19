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

## [2026-04-19] Session Summary (Short User Eval Dataset Expanded to 80)
- What was done: Added `data/eval/human_eval_short_user_test_80.jsonl` and updated dataset ID `b2d47254-8655-4c9c-81ac-7615677ec5bd` to 80 active items (base 40 + new 40), with audit summary stored at `data/reports/short_user_dataset_80_audit_2026-04-19.json`.
- Key decisions: New 40 questions were generated only from current corpus-grounded synthetic short-user candidates and kept the same retrieval-aware fields (`expected_doc_ids`, `expected_chunk_ids`, `expected_answer_key_points`) as the 40 baseline.
- Issues encountered: Post-expansion lexical overlap warnings remained for a small subset of short prompts, but structural mapping checks stayed clean (no missing/mismatched chunk-doc mapping).
- Next steps: Use the 80-item set in the next rewrite-effect A/C comparison run and monitor whether warning-tagged prompts need manual wording refinement.

## [2026-04-19] Session Summary (Short User Eval Dataset 80 Full Regeneration)
- What was done: Replaced `data/eval/human_eval_short_user_test_80.jsonl` with a fully regenerated 80-item set built from currently collected/cleaned/chunked corpus (`corpus_chunks`) and wrote audit summary to `data/reports/short_user_dataset_80_regenerated_audit_2026-04-19.json`.
- Key decisions: Switched from synthetic-candidate reselection to chunk-first query generation so each item is new while keeping retrieval-aware structure and deterministic `expected_doc_ids`/`expected_chunk_ids`.
- Issues encountered: Early generations produced unnatural token artifacts; generator term filters were tightened and rerun until structural issues were zero and synthetic text overlap was zero.
- Next steps: Run rewrite-effect A/C comparison with the regenerated 80 set and perform spot manual QA on edge technical terms.

---

## Notes
- Keep this file concise
- Only record important changes
