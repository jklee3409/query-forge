# Corpus Storage

## Goal

`2-3A` moves the normalized Spring corpus from file artifacts into PostgreSQL-managed tables so later stages can query and review the corpus through SQL and admin APIs.

## Storage Layers

### Source and run metadata

- `corpus_sources`: source definitions imported from `configs/app/sources/*.yml`
- `corpus_runs`: end-to-end ingest/import execution history
- `corpus_run_steps`: step-level status, metrics, and failure details

### Corpus hierarchy

- `corpus_documents`: document-level metadata, checksums, raw/cleaned text, active snapshot state
- `corpus_sections`: section hierarchy with heading order and structural counts
- `corpus_chunks`: retrieval-oriented chunk records with previous/next linkage and import run trace
- `corpus_chunk_relations`: `near`, `far`, `same_section`, `same_document` relations for downstream generation and inspection

### Glossary

- `corpus_glossary_terms`: canonical term master table
- `corpus_glossary_aliases`: normalized aliases / variant spellings
- `corpus_glossary_evidence`: document/chunk provenance for drill-down

## Import flow

1. Read source configs from `configs/app/sources`.
2. Read raw collector JSONL to recover `source_id`, `canonical_url`, and `collected_at`.
3. Read normalized sections JSONL and upsert `corpus_documents` + `corpus_sections`.
4. Read `chunks.jsonl` and upsert `corpus_chunks`.
5. Rebuild relation rows from chunk metadata and upsert `corpus_chunk_relations`.
6. Read `glossary_terms.jsonl`, generate aliases/evidence, and upsert glossary tables.
7. Record run and step summaries in `corpus_runs` and `corpus_run_steps`.

## Idempotency rules

- Stable IDs are reused from existing artifacts: `document_id`, `section_id`, `chunk_id`.
- Glossary IDs are deterministic UUIDv5-style hashes from normalized term/alias/evidence keys.
- Importers compare checksums or materialized field values before writing.
- Re-importing the same artifacts should yield `skipped` counts rather than row growth.

## Versioning and active snapshot

- `corpus_documents.is_active` marks the current active snapshot.
- `uq_corpus_documents_canonical_url_active` allows only one active document per canonical URL.
- When a new document with the same canonical URL is imported under a different `document_id`, the previous active document is marked inactive and linked through `superseded_by_document_id`.

## Search/index strategy

- Primary lookup: PK / FK indexes on document, section, chunk, term, evidence tables.
- Operational filters: source/version/run indexes.
- Text lookup: trigram GIN indexes on titles, cleaned text, chunk text, glossary canonical/alias.

## Preview support

- `raw-vs-cleaned`: reads `corpus_documents.raw_text` and `cleaned_text`
- `chunk-boundaries`: reconstructs chunk spans from ordered `corpus_chunks`
- `top-terms`: reads `corpus_glossary_terms` plus `corpus_glossary_evidence`
