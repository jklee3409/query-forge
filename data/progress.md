# progress.md

## Overview
High-level progress tracking for the project.

## [2026-05-20] Session Summary (Spring KR Short-User Pair Restored)
- What was done: Restored DB dataset `b2d47254-8655-4c9c-81ac-7615677ec5bd` to the refined V5 `test-short-user-*` active samples and regenerated/upserted `data/eval/human_eval_short_user_test_80_en.jsonl` as a one-to-one English companion dataset.
- Key decisions: KR and EN datasets now share order, `expected_doc_ids`, and `expected_chunk_ids`; EN rows keep paired-sample metadata and concise English user queries that mirror the KR short-user intent.
- Issues encountered: Existing historical V4 sample rows were left in `eval_samples` for old result references, but they are no longer active in the canonical KR dataset.
- Next steps: Use the paired KR/EN datasets for language-gap comparisons under the same memory snapshot and retrieval backend.

## [2026-05-12] Session Summary (Python KR KO/EN Eval Dataset Assets)
- What was done: Added paired Python Korean-document short-user eval datasets under `data/eval/` and a generation audit report under `data/reports/`.
- Key decisions: Kept KO and EN datasets separate with paired sample IDs, `strategy_profile=python_kr`, and target methods `G`/`F` so F/G comparisons can use the same grounded chunks without mixing query languages.
- Issues encountered: `data/eval` and `data/reports` lacked directory-level `index.md`/`progress.md`; added them while updating README content.
- Next steps: Run snapshot-pinned F/G retrieval and answer evaluation using the new dataset IDs.

## [2026-04-28] Session Summary (English Short User 80 Asset Added)
- What was done: Added `data/eval/human_eval_short_user_test_80_en.jsonl` as the separate English short-user evaluation dataset paired to the existing Korean 80 set.
- Key decisions: Kept the same retrieval-aware grounding fields and assigned new English sample ids (`test-short-user-en-###`) plus paired-sample metadata instead of modifying the existing Korean dataset in place.
- Issues encountered: Source Korean short-user prompts include mojibake in terminal output, so the companion English queries were regenerated from technical terms plus grounded answer hints instead of direct literal translation.
- Next steps: Upsert the English dataset rows into the runtime DB with `scripts/build_short_user_en_dataset.py` after Flyway `V21` is applied.

## [2026-04-20] Session Summary (Memory Snapshot Data Cleanup)
- What was done: Removed 1,385 live `memory_entries` rows built from synthetic queries that are rejected in their recorded gating batch, removed the matching 1,385 memory `query_embeddings`, and later removed 6,273 orphan memory embeddings that no longer had a `memory_entries` owner row.
- Key decisions: Backfilled `memory_entries.metadata.memory_experiment_key` for the remaining 515 memory rows by joining `memory_build_run_id` to experiment metadata, so current data matches the new experiment-key isolation path.
- Issues encountered: Historical RAG result details can still reference deleted stale memory IDs, but future eval runs now rebuild isolated memory before scoring.
- Next steps: Use new RAG test runs for post-fix comparison instead of reinterpreting old detail rows generated before cleanup.

## [2026-04-19] Session Summary (Short User 80 Rebuilt from Synthetic Random Candidates)
- What was done: Replaced `data/eval/human_eval_short_user_test_80.jsonl` by rebuilding dataset `b2d47254-8655-4c9c-81ac-7615677ec5bd` from 80 randomly selected synthetic queries and wrote run summary to `data/reports/short_user_dataset_80_synthetic_compressed_2026-04-19.json`.
- Key decisions: Maintained retrieval-aware grounding fields and preserved sample-level chunk/doc linkage while changing only short-user query text style to compressed Korean prompts.
- Issues encountered: Initial compression pass contained low-information prompts; regenerated after stopword/template tightening.
- Next steps: Evaluate metric impact of compressed synthetic-derived query style in the next A/C comparison run.

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

## [2026-05-13] Session Summary (Short User Eval Dataset 80 Manual Grounding Refinement)
- What was done: Refined `data/eval/human_eval_short_user_test_80.jsonl` and active dataset `b2d47254-8655-4c9c-81ac-7615677ec5bd` using stronger Korean short-query wording, grounded `expected_answer_key_points`, and corrected `expected_chunk_ids`/`expected_doc_ids` for 20 misaligned samples. Added supporting reports `data/reports/short_user_dataset_80_audit_pre_refine_2026-05-13.json`, `data/reports/short_user_dataset_80_audit_post_refine_2026-05-13.json`, and `data/reports/short_user_dataset_80_refined_2026-05-13.json`.
- Key decisions: Preserved sample IDs, dataset ID/key, source synthetic provenance, and eval schema while allowing cross-doc retargeting only when inspected chunk text provided stronger retrieval grounding than the previous mapping.
- Issues encountered: The previous 80 set loaded successfully in pipeline code but still contained summary-only targets, overlap-context answer points, and a few outright wrong schema/section mappings, so refinement needed both query rewrites and chunk-level retargeting.
- Next steps: Use the refinement report for residual manual QA on compressed-query edge cases and keep future KO/EN companion datasets aligned to the updated Korean grounding.

---

## Notes
- Keep this file concise
- Only record important changes
