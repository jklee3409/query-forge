# progress.md

## Overview
High-level progress tracking for the project.

## [2026-05-30] Session Summary (PostgreSQL/Kubernetes Short-User Grounding Repair)
- What was done: Updated existing PostgreSQL datasets `862642e6-10bd-538d-9ba8-5de7f1f26d3c` / `020a93c4-0465-5655-b681-a5799a98fd15` to `v2-2026-05-30`, updated existing Kubernetes datasets `87f74f10-1e61-5c56-84f9-f70a87fba424` / `e0445e9e-7ed3-58aa-8ce1-a32d06d44a11` to `v3-2026-05-30`, and wrote strictness reports under `data/reports/`.
- Key decisions: KR remains authoritative for each domain; EN copies identical expected doc IDs, chunk IDs, and answer key points with English-only query fields. PostgreSQL query surfaces now include the expected section/title anchor to avoid fragment-only ambiguity.
- Issues encountered: PostgreSQL/Kubernetes artifacts contained overlap-context answer key points and EN rows had non-empty `user_query_ko`; after repair, row counts, KR/EN grounding, missing chunk, doc mismatch, domain mismatch, and noisy key point checks all pass.
- Next steps: Treat these versions as new evaluation baselines and keep prior metrics version-separated.

## [2026-05-30] Session Summary (Spring Short-User KR/EN Grounding Repair)
- What was done: Updated existing DB datasets `b2d47254-8655-4c9c-81ac-7615677ec5bd` and `8f0d6e0f-6f9e-4d64-9b07-f4e8ce5ebec0` in place to version `v6-2026-05-30`, rewrote the EN JSONL companion, and wrote `data/reports/spring_short_user_eval_pair_repair_2026-05-30.json`.
- Key decisions: KR is the authoritative repaired dataset; EN copies identical expected doc IDs, chunk IDs, and answer key points with only English query surfaces. Removed overlap-context and Spring Data version-boilerplate answer key points and repaired the 010/014/026 grounding targets after inspecting the current Spring corpus.
- Issues encountered: None after validation; active rows are 80/80, KR/EN grounding mismatch is 0, missing chunk count is 0, and chunk-to-doc mismatch is 0.
- Next steps: Treat `v6-2026-05-30` as a new evaluation version when interpreting later RAG runs; do not compare directly to prior `v4`/`v5` metrics without calling out the dataset repair.

## [2026-05-27] Session Summary (Spring/PostgreSQL Anchor-Translated Eval Copies)
- What was done: Added `data/eval/spring_kr_anchor_translated_short_user_test_80.jsonl` and `data/eval/postgresql_kr_anchor_translated_short_user_test_80.jsonl`, then upserted separate DB datasets `44282405-1ea1-5f78-bf85-6270724ee475` and `8a08c160-e4cd-5ce0-9f5c-640c51b6d887`.
- Key decisions: Kept the original Spring/PostgreSQL KR datasets untouched while cloning grounding metadata and translating only English anchor tokens in Korean query text.
- Issues encountered: Validation confirmed 80 rows per artifact, no duplicate translated queries, no ASCII letters in `user_query_ko`, and unchanged grounding fields.
- Next steps: Run snapshot-controlled RAG tests against the new anchor-translated dataset keys when comparison is needed.

## [2026-05-27] Session Summary (Kubernetes KR Anchor Translation)
- What was done: Regenerated `kubernetes_kr_short_user_test_80.jsonl` with English technical anchors translated/paraphrased into Korean query surfaces and synchronized the DB-managed dataset.
- Key decisions: Kept expected doc/chunk IDs, answer key points, sample IDs, paired EN dataset identity, and single/multi distribution unchanged.
- Issues encountered: None; validation passed and DB verification confirmed 80 active KR rows with zero ASCII anchor tokens.
- Next steps: Use this revised KR dataset for an anchor-effect Kubernetes baseline rerun.

## [2026-05-27] Session Summary (Kubernetes KO/EN Eval Datasets)
- What was done: Created Kubernetes KO/EN short-user eval JSONL artifacts under `data/eval/` and registered matching DB datasets `87f74f10-1e61-5c56-84f9-f70a87fba424` and `e0445e9e-7ed3-58aa-8ce1-a32d06d44a11`.
- Key decisions: Preserved the Spring/PostgreSQL 80-item evaluation shape with identical KO/EN grounding, `single:59` / `multi:21`, and source chunk-derived answer key points from `kubernetes-docs-current`.
- Issues encountered: Validation required revising all-English KO query surfaces into Korean code-mixed short-user queries.
- Next steps: Pair these datasets with explicit Kubernetes snapshots before running RAG evaluation.

## [2026-05-26] Session Summary (PostgreSQL EN Eval Companion)
- What was done: Created the PostgreSQL EN short-user companion dataset artifact `data/eval/postgresql_en_short_user_test_80.jsonl` and registered DB dataset `020a93c4-0465-5655-b681-a5799a98fd15` / key `postgresql_en_short_user_80`.
- Key decisions: Kept the dataset paired to KR dataset `862642e6-10bd-538d-9ba8-5de7f1f26d3c` with identical grounding, category, difficulty, and single/multi structure; set the EN rows to English-only short-user equivalents with `query_language=en` and `target_method=E`.
- Issues encountered: The EN companion initially reflected an obsolete English-fragment query surface; it was corrected after the KR dataset was fixed, and validation confirmed 80 paired rows with no Hangul in EN fields and zero grounding mismatches.
- Next steps: Use this EN dataset with the PostgreSQL E full-gating snapshot when running English RAG evaluation.

## [2026-05-26] Session Summary (PostgreSQL Eval Query Degradation)
- What was done: Rewrote PostgreSQL short-user eval queries to low-signal Korean code-mixed short-user queries in the JSONL artifact and synchronized the active DB-managed dataset.
- Key decisions: Kept the 80-item retrieval-aware structure, expected doc/chunk IDs, and dataset ID unchanged; recorded before/after raw BM25 metrics in dataset metadata.
- Issues encountered: No temporary files were created; validation confirmed 80 rows, 101 grounded chunk references, no duplicate queries, no missing Hangul, and zero Latin anchors outside expected chunk text. Final PostgreSQL KR BM25 metrics are `Recall@5=0.4625`, `Hit@5=0.5250`, `MRR@10=0.3931`, `nDCG@10=0.4105`, close to the Spring KR reference.
- Next steps: Treat the degraded dataset as the PostgreSQL short-user baseline for future snapshot-controlled RAG comparisons.

## [2026-05-26] Session Summary (PostgreSQL KR Short-User Eval Dataset)
- What was done: Created PostgreSQL KR short-user eval artifact `data/eval/postgresql_kr_short_user_test_80.jsonl` and upserted matching DB rows under dataset `862642e6-10bd-538d-9ba8-5de7f1f26d3c` / key `postgresql_kr_short_user_80`.
- Key decisions: Followed the active Spring KR short-user structure, used short compressed Korean user queries, and grounded every item to current PostgreSQL-domain chunks with `single:59` / `multi:21`.
- Issues encountered: None; bounded DB validation found 80 active dataset items and 101 active PostgreSQL-domain chunk references.
- Next steps: Run snapshot-pinned A/C RAG tests against this dataset when evaluation is needed.

## [2026-05-26] Session Summary (PostgreSQL Corpus and Query/Gating Batches)
- What was done: Verified PostgreSQL domain DB state after collection/import: 1,644 documents, 2,147 chunks, and 36,682 glossary terms across `postgresql-docs-current` (1,144 docs / 1,466 chunks) and `postgis-docs-current` (500 docs / 681 chunks). Verified final generation batches `73a0cf15-59af-45af-ab32-12a3bb9f8b30` (A, `A-1000-260526`) and `023083fd-e3e0-4ad6-bbb2-926ce96539b9` (C, `C-1000-260526`) each contain 1,000 raw queries.
- Key decisions: Retained only the final completed A/C batches for the new PostgreSQL domain/version work; failed or cancelled same-version attempts were deleted within that domain scope only.
- Issues encountered: Final BM25-only `full_gating` batches completed as `1c80af8d-b993-4b88-8013-3fe7cf995bef` (A: 1,000 processed, 275 accepted, 725 rejected) and `3306f0cc-25c5-459f-b3dc-0e894e76e806` (C: 1,000 processed, 312 accepted, 688 rejected). Docker Desktop was recovered after a verification timeout and targeted DB checks passed.
- Next steps: Use the two PostgreSQL gating batch IDs as explicit snapshots for any later memory/RAG experiments.

## [2026-05-20] Session Summary (Method-Compressed Stress Eval Assets)
- What was done: Generated five Spring method-compressed stress eval JSONL artifacts under `data/eval/` and an audit report under `data/reports/`, then upserted the matching DB datasets.
- Key decisions: Kept each method as a separate dataset and preserved retrieval-aware fields from the source corpus chunks. The new assets are intentionally compressed and multi-chunk-heavy for stress testing, while the canonical V5 short-user datasets remain unchanged.
- Issues encountered: None after the final full run; each JSONL has 80 rows and all expected chunk references exist in DB.
- Next steps: Use these assets for A/B/C/D/E controlled RAG comparisons with explicit snapshot IDs.

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
