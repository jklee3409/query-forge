# Pipeline Scaffold

This directory is reserved for offline processing stages that will be implemented incrementally.

- `collectors/`: Spring documentation ingestion
- `preprocess/`: normalization and cleaning
- `generation/`: SAP-style synthetic query generation
- `gating/`: rule, LLM, utility, diversity gating
- `embeddings/`: embedding jobs
- `datasets/`: training and evaluation dataset builders
- `eval/`: retrieval and answer evaluation
- `common/`: shared helpers

`cli.py` currently exposes placeholder commands only.

