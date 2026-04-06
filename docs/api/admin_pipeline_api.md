# Admin Pipeline API

## Run Execution API

### `POST /api/admin/pipeline/collect`
source 선택 후 collector 실행

### `POST /api/admin/pipeline/normalize`
raw JSONL -> section JSONL 정제 실행

### `POST /api/admin/pipeline/chunk`
section JSONL -> chunk/relation/glossary artifact 실행

### `POST /api/admin/pipeline/glossary`
section JSONL -> glossary only 실행

### `POST /api/admin/pipeline/import`
artifact -> PostgreSQL import 실행

### `POST /api/admin/pipeline/full-ingest`
collect -> normalize -> chunk -> glossary -> import 전체 실행

요청 예시:

```json
{
  "sourceIds": ["spring-framework-reference", "spring-boot-reference"],
  "documentIds": [],
  "dryRun": false,
  "createdBy": "admin-ui",
  "triggerType": "api"
}
```

응답 예시:

```json
{
  "runId": "9eb8c5d6-31f4-4f6a-9db2-3f0c1f9c1c8d",
  "runType": "full_ingest",
  "runStatus": "queued",
  "message": "파이프라인 실행을 큐에 등록했습니다."
}
```

## Run Control API

### `POST /api/admin/pipeline/runs/{runId}/retry`
기존 run scope/config를 기준으로 새 run 재실행

### `POST /api/admin/pipeline/runs/{runId}/cancel`
현재 run cancel 요청

## Run Query API

### `GET /api/admin/pipeline/dashboard`
대시보드 요약 통계

### `GET /api/admin/pipeline/runs`
run 목록 조회

query params:

- `run_id`
- `run_status`
- `run_type`
- `limit`
- `offset`

### `GET /api/admin/pipeline/runs/{runId}`
run + step 상세

### `GET /api/admin/pipeline/runs/{runId}/steps`
step 목록만 조회

### `GET /api/admin/pipeline/runs/{runId}/logs`
step별 stdout/stderr excerpt 조회

## Corpus Mutation API

### `PATCH /api/admin/corpus/sources/{sourceId}`

```json
{
  "enabled": false
}
```

### `PATCH /api/admin/corpus/glossary/{termId}`

```json
{
  "keepInEnglish": true,
  "active": true,
  "descriptionShort": "Reviewed by admin."
}
```

### `POST /api/admin/corpus/glossary/{termId}/aliases`

```json
{
  "aliasText": "application-yaml",
  "aliasLanguage": "en",
  "aliasType": "kebab"
}
```

### `DELETE /api/admin/corpus/glossary/aliases/{aliasId}`

alias 삭제
