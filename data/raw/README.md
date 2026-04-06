# Raw Data

This directory stores page-level raw HTML captures as JSONL.

## Expected file

- `spring_docs_raw.jsonl`
- optional dry-run outputs such as `spring_docs_raw_dry_run.jsonl`

## Record shape

Each line is a single fetched HTML page with:

- `document_id`
- `source_id`
- `source_url`
- `canonical_url`
- `versioned_url`
- `product`
- `version_if_available`
- `title`
- `language_code`
- `content_hash`
- `fetched_at`
- `html`
- `metadata`

## Example commands

```powershell
python pipeline/collectors/spring_docs_collector.py --limit 10 --show-examples
python pipeline/cli.py collect-docs --limit 10 --show-examples
```

## Notes

- Raw output keeps full HTML for reproducible normalization.
- Duplicate pages are removed using stable `document_id` and raw content hash.
- The `data/raw/` directory is ignored by git except for this README.

