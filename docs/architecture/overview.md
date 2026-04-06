# Architecture Overview

Stage `2-3B` includes the baseline repository, preprocessing pipeline, PostgreSQL corpus persistence layer, and admin backoffice UI.

## Current modules

- `backend`: Spring Boot runtime shell with Flyway-backed schema management, corpus admin read/mutation APIs, pipeline orchestration APIs, and Thymeleaf admin UI
- `pipeline`: offline CLI for collection, normalization, chunking, glossary extraction, glossary-only extraction, and PostgreSQL import
- `configs`: externalized application, experiment, and prompt files
- `data`: artifact roots for reproducible runs
- `docs`: project notes and future experiment records

## Planned runtime flow

1. Admin UI starts a pipeline run through Spring Boot orchestration.
2. Spring Boot invokes Python CLI steps asynchronously and records run/step/log metadata in PostgreSQL.
3. Offline pipeline builds corpus artifacts and imports them into `corpus_*` tables.
4. Backend exposes admin APIs/UI for corpus inspection and later user-facing retrieval flows.
5. Database stores corpus artifacts, experiments, traces, and evaluation outcomes.

This document is intentionally high-level until later implementation stages.
