# Processed Data

This directory stores normalized section-level JSONL derived from raw HTML pages.

## Expected file

- `spring_docs_sections.jsonl`
- optional dry-run outputs such as `spring_docs_sections_dry_run.jsonl`

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
```

## Notes

- Boilerplate inside the article body such as breadcrumbs and pagination is removed.
- Paragraphs, lists, tables, code blocks, and admonitions are preserved as structured blocks.
- Output is section-level so later chunking and glossary extraction can operate without reparsing HTML.

