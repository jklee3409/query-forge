# Architecture Overview

Stage `2-1` defines only the baseline repository and infrastructure contract.

## Current modules

- `backend`: Spring Boot runtime shell with Flyway-backed schema management
- `pipeline`: offline CLI scaffold for future collectors, preprocessing, generation, gating, memory building, and evaluation
- `configs`: externalized application, experiment, and prompt files
- `data`: artifact roots for reproducible runs
- `docs`: project notes and future experiment records

## Planned runtime flow

1. Offline pipeline builds corpus, chunks, glossary, synthetic queries, and memory.
2. Backend serves user queries, selective rewrite, retrieval, reranking, and answer generation.
3. Database stores corpus artifacts, experiments, traces, and evaluation outcomes.

This document is intentionally high-level until later implementation stages.

