# Pipeline Scaffold

This directory is reserved for offline processing stages that are being implemented incrementally.

- `collectors/`: Spring documentation ingestion
- `preprocess/`: normalization, chunking, and glossary extraction
- `generation/`: SAP-style synthetic query generation
- `gating/`: rule, LLM, utility, diversity gating
- `embeddings/`: embedding jobs
- `datasets/`: training and evaluation dataset builders
- `eval/`: retrieval and answer evaluation
- `common/`: shared helpers

Implemented commands in the current stage:

- `collect-docs`
- `preprocess` for HTML-to-section normalization
- `chunk-docs` for heading-aware chunking, glossary extraction, and chunk neighbor SQL generation
- `import-corpus` for idempotent PostgreSQL corpus import with run history

Later stages remain placeholders.
