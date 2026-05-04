# API 문서 안내

`docs/api/`는 현재 구현된 관리자 API와 이후 확장될 서비스 API 문서를 모아 두는 디렉터리다.

## 현재 구현 범위

### Corpus Admin API

- `GET /api/admin/corpus/sources`
- `GET /api/admin/corpus/runs`
- `GET /api/admin/corpus/documents`
- `GET /api/admin/corpus/chunks`
- `GET /api/admin/corpus/glossary`
- preview endpoint
- source enable/disable
- glossary patch, alias CRUD

상세 문서:

- `corpus_admin_api.md`

### Pipeline Admin API

- collect / normalize / chunk / glossary / import / full-ingest 실행
- run retry / cancel
- run / step / log 조회
- dashboard 통계 조회

상세 문서:

- `admin_pipeline_api.md`

### Admin Console API

- synthetic batch 실행/조회, query 상세 조회
- quality gating batch 실행/퍼널/결과 조회
- RAG test 실행/상세/비교, llm job 상태 제어

현재는 `backend/index.md`와 `backend/src/main/java/io/queryforge/backend/admin/console/controller/AdminConsoleController.java`를 기준으로 운영하며, 별도 상세 문서는 순차 확장한다.

### RAG / Experiments API

- `POST /api/chat/ask`
- `POST /api/rewrite/preview`
- `GET /api/queries/{id}/trace`
- `GET /api/experiments/{runId}/summary`
- `GET /api/eval/retrieval`
- `GET /api/eval/answer`
- `POST /api/admin/reindex`
- `POST /api/admin/experiments/run`

상세 문서:

- `rag_api.md`
