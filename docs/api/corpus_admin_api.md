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
  - filters: `product_name`, `version_label`, `source_id`, `document_id`, `section_heading_keyword`, `chunk_keyword`, `run_id`, `active_only`, `limit`, `offset`
- `GET /documents/{documentId}`
- `GET /documents/{documentId}/sections`
  - filters: `section_heading_keyword`, `run_id`
- `GET /documents/{documentId}/chunks`
  - filters: `chunk_keyword`, `run_id`, `limit`, `offset`

## Chunks

- `GET /chunks`
  - filters: `product_name`, `version_label`, `source_id`, `document_id`, `chunk_keyword`, `run_id`, `active_only`, `limit`, `offset`
- `GET /chunks/{chunkId}`
- `GET /chunks/{chunkId}/neighbors`

## Glossary

- `GET /glossary`
  - filters: `product_name`, `version_label`, `source_id`, `term_type`, `keep_in_english`, `run_id`, `active_only`, `keyword`, `limit`, `offset`
- `GET /glossary/{termId}`
- `GET /glossary/{termId}/evidence`
- `GET /glossary/preview/top-terms`
  - filters: `limit`, `product_name`, `term_type`, `keep_in_english`

## Preview

- `GET /documents/{documentId}/preview/raw-vs-cleaned`
- `GET /documents/{documentId}/preview/chunk-boundaries`

## Example calls

```bash
curl "http://localhost:8080/api/admin/corpus/documents?product_name=spring-framework&active_only=true"
curl "http://localhost:8080/api/admin/corpus/chunks?document_id=doc_test_1"
curl "http://localhost:8080/api/admin/corpus/glossary?term_type=annotation&keep_in_english=true"
curl "http://localhost:8080/api/admin/corpus/documents/doc_test_1/preview/chunk-boundaries"
```
