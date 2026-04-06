# Architecture Overview

Stage `2-3A` includes the baseline repository, preprocessing pipeline, and PostgreSQL corpus persistence layer.

## Current modules

- `backend`: Spring Boot runtime shell with Flyway-backed schema management and corpus admin read APIs
- `pipeline`: offline CLI for collection, normalization, chunking, glossary extraction, and PostgreSQL import
- `configs`: externalized application, experiment, and prompt files
- `data`: artifact roots for reproducible runs
- `docs`: project notes and future experiment records

## Planned runtime flow

1. Offline pipeline builds corpus artifacts and imports them into `corpus_*` tables.
2. Backend exposes admin APIs for corpus inspection and later user-facing retrieval flows.
3. Database stores corpus artifacts, experiments, traces, and evaluation outcomes.

This document is intentionally high-level until later implementation stages.
