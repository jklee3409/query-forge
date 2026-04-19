# progress.md

## Overview
High-level progress tracking for the project.

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
