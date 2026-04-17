# progress.md

## Overview
High-level progress tracking for the project.

## [2026-04-17] Session Summary (Selective Domain Data Reset Execution)
- What was done: Executed DB reset for synthetic generation, quality gating, RAG quality-test artifacts, and LLM job state/history using transactional SQL against local Postgres container.
- Key decisions: Preserved corpus collection/preprocess/chunk domain data (`corpus_documents`, `corpus_chunks`, related corpus tables) and retained `query_embeddings` rows for `owner_type='chunk'` only.
- Issues encountered: No script file was added; reset was executed directly through `docker exec ... psql` to complete immediate operator request.
- Next steps: Consider adding a reusable reset script in `scripts/` with explicit include/exclude table sets for repeatable operations.

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
