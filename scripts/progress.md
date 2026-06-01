# progress.md

## Overview
High-level progress tracking for the project.

## [2026-06-01] Session Summary (Spring Rewrite Probe Variant)
- What was done: Extended `scripts/build_spring_rewrite_challenge_dataset.py` with `--variant challenge_30|probe_c_9` and added a 9-item C-memory-aligned rewrite probe dataset variant.
- Key decisions: Kept the probe additive and grounded to Spring KR V6 expected docs/chunks; it isolates Korean-only anchor-gap queries where C synthetic memory should be able to recover removed English/API anchors.
- Issues encountered: `py_compile`, challenge dry-run validation, probe dry-run validation, probe DB upsert, and bounded DB verification passed.
- Next steps: Run C compact-anchor retrieval evaluation against dataset `spring_kr_rewrite_probe_c_9` and compare raw-only vs selective rewrite.

## [2026-06-01] Session Summary (Spring Rewrite Challenge Builder)
- What was done: Added `scripts/build_spring_rewrite_challenge_dataset.py` to create a separate 30-item Spring KR rewrite challenge dataset from active V6 grounding while removing English/API anchor surfaces from the user query.
- Key decisions: Preserved the source V6 expected doc/chunk IDs and answer key points; the new dataset is additive and does not replace canonical V6.
- Issues encountered: Script py_compile, dry-run validation, DB upsert, and bounded DB verification passed.
- Next steps: Run snapshot-pinned A/C rewrite evaluation against dataset `spring_kr_rewrite_challenge_30`.

## [2026-05-30] Session Summary (PostgreSQL/Kubernetes Eval Pair Repair and Strict Audit)
- What was done: Added `scripts/repair_postgresql_kubernetes_eval_pairs.py` to repair existing PostgreSQL and Kubernetes KR/EN short-user datasets in place, and added `scripts/audit_eval_grounding_strictness.py` to validate Spring/PostgreSQL/Kubernetes active DB datasets together.
- Key decisions: Kept all dataset IDs unchanged, rebuilt answer key points from current corpus chunks, forced EN rows to keep `user_query_ko` empty, paired KR/EN grounding exactly, and strengthened PostgreSQL low-signal queries by prefixing the expected chunk section/title anchor.
- Issues encountered: PostgreSQL had 43 noisy overlap key point samples and EN rows with non-empty `user_query_ko`; Kubernetes had 21 noisy overlap key point samples and the same EN field issue. Both repairs passed hard validation after update.
- Next steps: Use the generated strictness report before interpreting future RAG metrics; continuation chunk counts remain informational because the corpus chunk text itself contains overlap context.

## [2026-05-30] Session Summary (Spring KR/EN Short-User Eval Pair Repair)
- What was done: Added `scripts/repair_spring_short_user_eval_pair.py` to repair the existing Spring KR/EN short-user datasets in place, rebuilding active KR rows from the refined `test-short-user-*` set, pairing EN rows with identical grounding, and regenerating answer key points without overlap/version boilerplate.
- Key decisions: Kept dataset IDs unchanged (`b2d47254-8655-4c9c-81ac-7615677ec5bd` / `8f0d6e0f-6f9e-4d64-9b07-f4e8ce5ebec0`), updated active sample bindings/metadata instead of creating new datasets, and fixed high-risk samples 010/014/026 with inspected Spring-domain target chunks.
- Issues encountered: The current active KR dataset had reverted to older `v4-test-short-user-*` rows; the repair restored grounded `test-short-user-*` active rows while leaving historical sample rows available for old result references.
- Next steps: Run snapshot-controlled RAG tests against dataset version `v6-2026-05-30` and keep comparisons to earlier `v4`/`v5` runs version-separated.

## [2026-05-27] Session Summary (Spring/PostgreSQL Anchor-Translated Builder)
- What was done: Added `scripts/build_anchor_translated_eval_datasets.py` to clone active Spring/PostgreSQL KR short-user datasets into separate anchor-translated Korean variants and upsert them to DB.
- Key decisions: The builder reads source datasets by fixed IDs, preserves grounding fields, writes deterministic JSONL artifacts, and fails if translated `user_query_ko` contains ASCII letters or duplicates.
- Issues encountered: None; `py_compile` and dry-run validation passed before DB upsert.
- Next steps: Re-run this script when the source KR datasets are intentionally curated and the anchor-translated copies need to be refreshed.

## [2026-05-27] Session Summary (Kubernetes Anchor-Translated Builder)
- What was done: Updated `build_kubernetes_eval_datasets.py` to generate v2 Kubernetes KR short-user queries with English technical anchors translated/paraphrased into Korean surfaces.
- Key decisions: Left source English query specs intact for pairing/audit, added a reviewed KO anchor-translated query list, and kept DB upsert behavior paired for KR and EN datasets.
- Issues encountered: None; `py_compile` passed and the builder regenerated/upserted both Kubernetes eval datasets successfully.
- Next steps: Use the same script for future Kubernetes dataset refreshes so anchor-translated KR surfaces remain reproducible.

## [2026-05-27] Session Summary (Kubernetes Eval Dataset Builder)
- What was done: Added `scripts/build_kubernetes_eval_datasets.py` to generate paired Kubernetes KO/EN short-user 80 JSONL files and upsert both datasets into DB tables.
- Key decisions: The builder resolves expected docs/chunks from active `kubernetes-docs-current` corpus chunks at runtime, preserves a `single:59` / `multi:21` distribution, and keeps KO/EN sample grounding paired.
- Issues encountered: KO validation caught all-English surfaces in a few code-mixed queries; those prompts were revised to include Korean wording.
- Next steps: Re-run the script if the Kubernetes corpus is refreshed, then use explicit Kubernetes snapshots for RAG evaluation.

## [2026-05-20] Session Summary (Method-Compressed Eval Dataset Builder)
- What was done: Added `scripts/build_method_compressed_eval_datasets.py` to build five 80-item Spring method-compressed eval datasets from accepted synthetic queries in existing A/B/C/D/E gating batches.
- Key decisions: The builder keeps A/B/C/D/E source identity explicit, selects `far`/`near` multi-chunk rows first, compresses source synthetic query text into short anchor-style user queries, writes JSONL artifacts, emits an audit report, and upserts DB eval dataset/sample/item rows.
- Issues encountered: Target-total validation was adjusted to honor `--target-total` so reduced smoke runs produce accurate audit status.
- Next steps: Use the generated dataset keys for snapshot-pinned RAG runs instead of replacing the canonical V5 short-user datasets.

## [2026-05-20] Session Summary (Spring Short-User EN Pairing Refresh)
- What was done: Updated `scripts/build_short_user_en_dataset.py` to use manually paired English short-user queries for the 80 refined KR samples, enforce source/override count parity, write `v2-2026-05-20`, and upsert the EN dataset to DB.
- Key decisions: The script keeps the same grounded doc/chunk IDs from the KR source rows and records `paired_user_query_ko` metadata for auditability.
- Issues encountered: None; running the script generated 80 EN rows and upserted dataset `8f0d6e0f-6f9e-4d64-9b07-f4e8ce5ebec0`.
- Next steps: Re-run this script whenever the KR refined JSONL changes so EN pairing remains exact.

## [2026-05-12] Session Summary (Python KR Eval Dataset Builder)
- What was done: Added `scripts/build_python_kr_eval_datasets.py` to generate paired KO/EN Python Korean-document short-user 80 JSONL files, write an audit report, and upsert both datasets into DB tables.
- Key decisions: Hardcoded only the manually reviewed short-user query specs while resolving actual `expected_doc_ids` and `expected_chunk_ids` from the current `docs-python-org-ko-3-14` corpus at runtime.
- Issues encountered: psycopg direct connections return tuple rows by default, so the chunk fetch path uses explicit tuple destructuring.
- Next steps: Reuse this script when the Python KR corpus is refreshed, then compare F/G under fixed snapshot and dataset IDs.

## [2026-04-28] Session Summary (English Short-User 80 Builder)
- What was done: Added `scripts/build_short_user_en_dataset.py` to generate `data/eval/human_eval_short_user_test_80_en.jsonl` and optionally upsert the separate English dataset (`human_eval_short_user_80_en`) into DB tables.
- Key decisions: Kept DB writes optional via `--skip-db` so the artifact can be generated before applying the new Flyway migration, and stored paired-sample metadata linking each English row back to the existing Korean short-user 80 set.
- Issues encountered: The source Korean short-user prompts contain mojibake in terminal output, so the English companion queries are regenerated from grounded technical terms and answer hints rather than literal text translation.
- Next steps: Run the script without `--skip-db` in the target environment after Flyway `V21` is applied.

## [2026-04-19] Session Summary (Synthetic Candidate Random 80 Rebuilder Added)
- What was done: Added `scripts/rebuild_short_user_dataset_from_synthetic.py` to rebuild dataset `b2d47254-8655-4c9c-81ac-7615677ec5bd` by randomly sampling 80 rows from `synthetic_queries_raw_all`, compressing them into short/lazy Korean user queries, and updating DB + JSONL + report outputs.
- Key decisions: Preserved existing eval schema and DB contracts; only presentation/query-text layer was changed while keeping source chunk grounding intact.
- Issues encountered: First pass produced particle-only compressed prompts (`에서 ...`) from noisy token extraction; stopword/template rules were refined for better prompt readability.
- Next steps: Add optional quality-threshold hooks (e.g., minimum anchor-token count) for stricter automatic rejection before commit.

## [2026-04-19] Session Summary (Short-User Dataset Full Regeneration + Eval Origin Verifier)
- What was done: Reworked `scripts/expand_short_user_dataset.py` from synthetic-candidate expansion to full corpus-grounded regeneration (80 items total, single/multi balance, mapping audit, DB upsert) and added `scripts/verify_eval_dataset_origin.py` for dataset-origin diagnostics.
- Key decisions: Regeneration now blocks direct synthetic query text reuse (`synthetic_query_exact_overlap=0`) and uses chunk-first grounding (`expected_doc_ids` / `expected_chunk_ids`) to keep retrieval-aware guarantees.
- Issues encountered: Several intermediate generations produced low-naturalness terms; term filters/templates were iteratively tightened until audit remained structurally clean and overlap warnings stayed controlled.
- Next steps: Add optional manual-review allowlist/denylist hook to enforce stricter natural-language quality for edge terms before DB commit.

## [2026-04-17] Session Summary (Selective Domain Data Reset Execution)
- What was done: Executed DB reset for synthetic generation, quality gating, RAG quality-test artifacts, and LLM job state/history using transactional SQL against local Postgres container.
- Key decisions: Preserved corpus collection/preprocess/chunk domain data (`corpus_documents`, `corpus_chunks`, related corpus tables) and retained `query_embeddings` rows for `owner_type='chunk'` only.
- Issues encountered: No script file was added; reset was executed directly through `docker exec ... psql` to complete immediate operator request.
- Next steps: Consider adding a reusable reset script in `scripts/` with explicit include/exclude table sets for repeatable operations.

## [2026-04-19] Session Summary (Short-User Dataset Audit/Expansion Script Added)
- What was done: Added `scripts/expand_short_user_dataset.py` to automate (1) chunk-mapping audit for base short-user eval set and (2) expansion from 40 to 80 items with DB upsert + dataset metadata refresh + audit report output.
- Key decisions: Script enforces structural grounding checks (chunk existence and chunk->doc consistency) and selects additional prompts only from current corpus-grounded `synthetic_queries_raw_all` candidates.
- Issues encountered: Initial generation produced over-short prompts and hard-failed on lexical-overlap checks; script was refined to strengthen query-quality filtering and treat low-overlap as warning, not structural failure.
- Next steps: Reuse the script for future 80->N expansion with adjusted `--target-total` and keep warning-sample manual review in loop.

## [2026-05-13] Session Summary (Short-User 80 Audit + Refinement Scripts)
- What was done: Added `scripts/audit_short_user_dataset.py` for dependency-aware short-user dataset auditing and `scripts/refine_short_user_dataset.py` for deterministic query/chunk refinement, JSONL rewrite, DB upsert, and per-sample change reporting.
- Key decisions: The refinement script reads the frozen snapshot report `data/reports/short_user_current_dump_2026-05-13.json`, preserves existing synthetic provenance metadata, and only retargets `expected_chunk_ids` through explicit override tables backed by inspected chunk text.
- Issues encountered: Runtime compatibility depends on DB row metadata and active dataset bindings, so the script updates `eval_samples`, `eval_dataset_item`, and `eval_dataset` together instead of editing only the JSONL file.
- Next steps: Keep the override tables and generated report (`data/reports/short_user_dataset_80_refined_2026-05-13.json`) synchronized when future manual curation rounds adjust additional samples.

---

## [2026-04-13] Session Summary
- What was done: 실행 보조 스크립트 전체를 기준으로 디렉토리 문서를 생성했다.
- Key decisions: 부트스트랩, 인프라 제어, 백엔드 실행, 파이프라인 실행, OS별 import 보조로 역할을 분리했다.
- Issues encountered: 없음.
- Next steps: 신규 스크립트 추가 시 `Structure`와 `Key Flows`를 즉시 갱신한다.

---

## Notes
- Keep this file concise
- Only record important changes
