# Corpus Admin API

Base path: `/api/admin/corpus`

## Sources

- `GET /sources`
  - returns configured sources plus active/total document counts and version stats

## Runs

- `GET /runs`
  - filters: `run_id`, `run_status`, `run_type`, `limit`, `offset`
- `GET /runs/{runId}`
  - returns run summary and ordered step history

## Documents

- `GET /documents`
  - filters: `product_name`, `version_label`, `source_id`, `document_id`, `section_heading_keyword`, `chunk_keyword`, `search`, `run_id`, `active_only`, `limit`, `offset`
- `GET /documents/{documentId}`
- `GET /documents/{documentId}/sections`
  - filters: `section_heading_keyword`, `run_id`
- `GET /documents/{documentId}/chunks`
  - filters: `chunk_keyword`, `search`, `run_id`, `limit`, `offset`

## Chunks

- `GET /chunks`
  - filters: `product_name`, `version_label`, `source_id`, `document_id`, `chunk_keyword`, `search`, `code_presence`, `min_token_len`, `max_token_len`, `run_id`, `active_only`, `limit`, `offset`
- `GET /chunks/{chunkId}`
- `GET /chunks/{chunkId}/neighbors`

## Glossary

- `GET /glossary`
  - filters: `product_name`, `version_label`, `source_id`, `term_type`, `keep_in_english`, `run_id`, `active_only`, `keyword`, `limit`, `offset`
- `GET /glossary/{termId}`
- `GET /glossary/{termId}/evidence`
- `GET /glossary/preview/top-terms`
  - filters: `limit`, `product_name`, `term_type`, `keep_in_english`

## Anchors

- `GET /anchors`
  - filters: `document_id`, `chunk_id`, `keyword`, `active_only`, `limit`, `offset`
- `POST /anchors/extract`
  - body: `documentIds`, `chunkIds`, optional extraction scope/options
- `POST /anchors/eval/runs`
  - body: `sourceId`, `documentIds`, `chunkIds`, `sampleSize`, `candidateLimit` 등
- `GET /anchors/eval/runs`
- `GET /anchors/eval/runs/{runId}`
- `POST /anchors/eval/runs/{runId}/labels`
- `POST /anchors/eval/runs/{runId}/recompute`

## Preview

- `GET /documents/{documentId}/preview/raw-vs-cleaned`
- `GET /documents/{documentId}/preview/chunk-boundaries`

## Mutations

- `PATCH /sources/{sourceId}`: source enabled 상태 수정
- `POST /sources`: source upsert
- `POST /sources/auto-register`: source 자동 등록
- `PATCH /glossary/{termId}`: term 정책/설명 수정
- `POST /glossary/{termId}/aliases`: alias 생성
- `DELETE /glossary/aliases/{aliasId}`: alias 삭제

## Example calls

```bash
curl "http://localhost:8080/api/admin/corpus/documents?product_name=spring-framework&search=bean&active_only=true"
curl "http://localhost:8080/api/admin/corpus/chunks?document_id=doc_test_1&code_presence=true"
curl "http://localhost:8080/api/admin/corpus/anchors?document_id=doc_test_1&limit=20"
curl "http://localhost:8080/api/admin/corpus/documents/doc_test_1/preview/chunk-boundaries"
```
