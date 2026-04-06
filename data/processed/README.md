# Processed Data

This directory stores normalized section-level JSONL and downstream chunking artifacts derived from raw HTML pages.

## Expected file

- `spring_docs_sections.jsonl`
- `chunks.jsonl`
- `glossary_terms.jsonl`
- `chunk_neighbors.sql`
- `chunking_visualization.md`
- optional dry-run inputs such as `spring_docs_sections_dry_run.jsonl`

## Record shape

Each line is a single normalized section with:

- `document_id`
- `section_id`
- `source_url`
- `product`
- `version_if_available`
- `title`
- `document_title`
- `section_title`
- `section_anchor`
- `section_path`
- `heading_hierarchy`
- `heading_level`
- `raw_text`
- `cleaned_text`
- `structural_blocks`
- `section_hash`
- `metadata`

## Example commands

```powershell
python pipeline/preprocess/normalize_docs.py --show-examples
python pipeline/cli.py preprocess --show-examples
python pipeline/preprocess/chunk_docs.py --input data/processed/spring_docs_sections_dry_run.jsonl --show-examples
python pipeline/cli.py chunk-docs --input data/processed/spring_docs_sections_dry_run.jsonl
```

## Notes

- Boilerplate inside the article body such as breadcrumbs and pagination is removed.
- Paragraphs, lists, tables, code blocks, and admonitions are preserved as structured blocks.
- `chunks.jsonl` stores overlap-aware retrieval chunks with previous/next linkage metadata.
- `chunk_neighbors.sql` inserts near/far relations into the `chunk_neighbors` table.
- `glossary_terms.jsonl` stores canonical technical terms for later Korean synthetic query generation.
