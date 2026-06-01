# progress.md

## Overview
Evaluation dataset artifact changes for `data/eval/`.

## [2026-06-01] Session Summary (Cross-Domain KO Rewrite Challenge 80)
- What was done: Added `spring_kr_rewrite_challenge_80.jsonl`, `postgresql_kr_rewrite_challenge_80.jsonl`, and `kubernetes_kr_rewrite_challenge_80.jsonl`.
- Key decisions: Reused grounded anchor-gap Korean query surfaces from the existing domain-specific short-user artifacts and preserved all expected doc/chunk IDs and answer key points.
- Issues encountered: Validation passed for all three files with 80 rows each, zero ASCII-anchor Korean queries, and 59/21 single/multi distribution.
- Next steps: Generate paired English challenge files by translating only the Korean query surfaces.

## [2026-06-01] Session Summary (Cross-Domain EN Rewrite Challenge 80)
- What was done: Added `spring_en_rewrite_challenge_80.jsonl`, `postgresql_en_rewrite_challenge_80.jsonl`, and `kubernetes_en_rewrite_challenge_80.jsonl`.
- Key decisions: Translated only the Korean challenge query into `user_query_en`, left `user_query_ko` empty for English rows, and preserved all grounding fields in the same order.
- Issues encountered: Validation passed with 80 rows per file, zero Hangul in query fields, and zero KO/EN grounding mismatches.
- Next steps: Use the paired files for controlled Korean-vs-English challenge comparisons.

## [2026-06-01] Session Summary (Spring KR Rewrite Probe C 9)
- What was done: Added `spring_kr_rewrite_probe_c_9.jsonl` as a 9-item C-memory-aligned probe slice generated from Spring KR V6 grounding.
- Key decisions: Kept all expected doc/chunk IDs and answer key points from the source V6 rows; only the user query surface is Korean-only with English/API anchors removed.
- Issues encountered: Validation passed with 9 rows, zero ASCII-anchor query surfaces, and `single:5` / `multi:4`.
- Next steps: Use dataset key `spring_kr_rewrite_probe_c_9` for C compact-anchor rewrite evaluation.

## [2026-06-01] Session Summary (Spring KR Rewrite Challenge 30)
- What was done: Prepared `spring_kr_rewrite_challenge_30.jsonl` as an additive dataset generated from Spring KR V6 grounding with English/API anchors removed from the Korean query surface.
- Key decisions: This is a rewrite-effect challenge set, not a replacement for the canonical V6 short-user control dataset.
- Issues encountered: Generated artifact validation passed with 30 rows, zero ASCII-anchor query surfaces, and `single:20` / `multi:10`.
- Next steps: Use explicit A/C Spring full-gating snapshots for retrieval and answer evaluation.

## [2026-05-27] Session Summary (Spring/PostgreSQL Anchor-Translated Short-User 80)
- What was done: Added separate anchor-translated KR short-user artifacts for Spring and PostgreSQL: `spring_kr_anchor_translated_short_user_test_80.jsonl` and `postgresql_kr_anchor_translated_short_user_test_80.jsonl`.
- Key decisions: Preserved the existing source artifacts/datasets and copied retrieval-aware grounding unchanged; only `sample_id`, `user_query_ko`, and audit metadata were changed for the new variants.
- Issues encountered: None; builder validation passed with `single:59` / `multi:21` per dataset and zero ASCII query surfaces.
- Next steps: Use dataset keys `spring_kr_anchor_translated_short_user_80` and `postgresql_kr_anchor_translated_short_user_80` for anchor-effect RAG reruns.

## [2026-05-27] Session Summary (Kubernetes KR Anchor-Translated Surface)
- What was done: Updated `kubernetes_kr_short_user_test_80.jsonl` so Korean short-user queries preserve intent while translating/paraphrasing English technical anchors into Korean.
- Key decisions: Preserved all sample IDs, expected grounding fields, KO/EN paired order, and target-method distribution; EN companion metadata now points to the revised paired Korean query text.
- Issues encountered: None; builder validation passed for 80 KO and 80 EN rows.
- Next steps: Rerun retrieval evaluation to compare against the earlier code-mixed KR baseline.

## [2026-05-27] Session Summary (Kubernetes KO/EN Short-User 80)
- What was done: Added paired Kubernetes short-user eval artifacts `kubernetes_kr_short_user_test_80.jsonl` and `kubernetes_en_short_user_test_80.jsonl`, then upserted DB datasets `87f74f10-1e61-5c56-84f9-f70a87fba424` / `e0445e9e-7ed3-58aa-8ce1-a32d06d44a11`.
- Key decisions: Matched the Spring/PostgreSQL 80-item structure with `short_user`, `test`, `single:59` / `multi:21`, shared expected doc/chunk IDs, and English-source chunk grounding; KR uses A/C target-method tagging and EN uses E.
- Issues encountered: Initial validation caught KO rows with all-English surfaces; those were revised to include Korean short-user wording while preserving source anchors.
- Next steps: Use dataset keys `kubernetes_kr_short_user_80` and `kubernetes_en_short_user_80` for snapshot-pinned Kubernetes RAG comparisons.

## [2026-05-26] Session Summary (PostgreSQL EN Short-User 80)
- What was done: Added `postgresql_en_short_user_test_80.jsonl` and upserted DB dataset `020a93c4-0465-5655-b681-a5799a98fd15` / key `postgresql_en_short_user_80` as the English companion to `862642e6-10bd-538d-9ba8-5de7f1f26d3c`.
- Key decisions: Preserved the original 80-row structure, expected doc/chunk IDs, answer key points, split, difficulty, and `single:59` / `multi:21` distribution; translated the active PostgreSQL KR query surface into English-only short-user equivalents and set `query_language=en`, `target_method=E`.
- Issues encountered: The EN file initially reflected the obsolete English-fragment KR surface; it was corrected after `862642e6-10bd-538d-9ba8-5de7f1f26d3c` became the source of truth. Validation confirmed 80 paired rows, no Hangul in EN fields, and zero grounding mismatches.
- Next steps: Use dataset key `postgresql_en_short_user_80` for E-method PostgreSQL RAG comparisons under explicit snapshot settings.

## [2026-05-26] Session Summary (PostgreSQL Low-Signal Query Revision)
- What was done: Updated `postgresql_kr_short_user_test_80.jsonl` so each query is a short low-signal Korean code-mixed query derived from its expected PostgreSQL chunk text, and synchronized the DB-managed dataset rows.
- Key decisions: Preserved `query_language=ko`, `user_query_ko`, `user_query_en=null`, `short_user`, `test`, and all expected grounding fields to remain structurally aligned with Spring KR Short User Eval 80.
- Issues encountered: Raw BM25 local metrics now align with Spring level: PostgreSQL `Recall@5=0.4625`, `Hit@5=0.5250`, `MRR@10=0.3931`, `nDCG@10=0.4105`; Spring reference was `0.4625`, `0.5250`, `0.3640`, `0.3968`.
- Next steps: Use this version for PostgreSQL RAG experiments and run answer-level evaluation only under an explicit snapshot condition.

## [2026-05-26] Session Summary (PostgreSQL KR Short-User 80)
- What was done: Added `postgresql_kr_short_user_test_80.jsonl` with 80 Korean short-user queries grounded to active PostgreSQL-domain chunks.
- Key decisions: Matched the active Spring KR short-user DB structure with `query_language=ko`, `short_user`, `test` split, `single:59` / `multi:21`, and current `expected_doc_ids` / `expected_chunk_ids`.
- Issues encountered: None; validation confirmed 80 JSONL rows and 101 expected chunk references all exist in the active PostgreSQL domain.
- Next steps: Use dataset ID `862642e6-10bd-538d-9ba8-5de7f1f26d3c` with explicit PostgreSQL A/C gating snapshots for RAG evaluation.

## [2026-05-20] Session Summary (Spring Method-Compressed Stress 80)
- What was done: Added five method-specific compressed Spring stress eval files: `spring_method_a_compressed_eval_80_ko.jsonl`, `spring_method_b_compressed_eval_80_ko.jsonl`, `spring_method_c_compressed_eval_80_ko.jsonl`, `spring_method_d_compressed_eval_80_ko.jsonl`, and `spring_method_e_compressed_eval_80_en.jsonl`.
- Key decisions: Each file has 80 retrieval-aware items derived from accepted synthetic queries in the corresponding current DB gating batch. Queries are short compressed anchors, while expected doc/chunk IDs and answer key points remain grounded to corpus chunks.
- Issues encountered: None in final validation; all files contain 80 rows.
- Next steps: Use the dataset keys with explicit source gating snapshots for controlled RAG tests.

## [2026-05-12] Session Summary (Python KR Short-User 80 KO/EN)
- What was done: Added paired Python Korean-document evaluation datasets, `python_kr_short_user_test_80_ko.jsonl` and `python_kr_short_user_test_80_en.jsonl`, with 80 short-user queries each.
- Key decisions: Used the same `docs-python-org-ko-3-14` corpus chunks for paired KO/EN samples, set target methods `G` and `F`, and kept retrieval-aware fields aligned with existing short-user datasets.
- Issues encountered: Terminal output may render Korean as mojibake in PowerShell, but the JSONL files are UTF-8 and Python validation confirms correct Unicode content.
- Next steps: Use dataset IDs `dfbadf26-0ab6-4b95-890e-5196dddc62cc` and `0d29df79-3920-40b2-b7ff-897eac5544fa` for F/G snapshot-controlled RAG evaluation.

---

## Notes
- Keep this file concise.
