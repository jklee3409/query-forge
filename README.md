# Query Forge

Research-oriented RAG scaffold for improving Korean retrieval and answer quality over English Spring technical documentation.

This repository currently contains stage `2-3B` deliverables up to chunking/glossary import, pipeline orchestration, and corpus admin backoffice UI:

- repository skeleton
- local infrastructure with Docker Compose
- Spring Boot backend with corpus admin API, pipeline orchestration API, and Thymeleaf backoffice UI
- Python pipeline scaffold with collection, normalization, chunking, glossary extraction, glossary-only extraction, and PostgreSQL import
- PostgreSQL + pgvector schema bootstrap via Flyway

Later stages will fill in document collection, preprocessing, chunking, synthetic query generation, quality gating, memory building, selective rewrite, evaluation, and UI.

## Repository layout

```text
backend/              Spring Boot API and future UI
pipeline/             Python offline pipeline
infra/                Docker-related notes and SQL helpers
configs/              External app / experiment / prompt configuration
data/                 Raw, processed, synthetic, eval, and report artifacts
docs/                 Architecture, experiments, and API documents
scripts/              PowerShell helpers for local Windows execution
```

## Quick start

1. Copy `.env.example` to `.env`.
2. Start PostgreSQL with pgvector:
   - `docker compose up -d postgres`
3. Run backend locally:
   - `backend\\gradlew.bat -p backend bootRun`
4. Verify health:
   - `http://localhost:8080/actuator/health`

## Scope boundary for this stage

- Implemented: collector, normalization, heading-aware chunking, glossary extraction, corpus import, corpus admin read/mutation API, pipeline orchestration API, admin GUI, scaffold, infra, schema
- Not implemented yet: synthetic generation, gating, memory build, rewrite, user retrieval, reranking, answer generation

## Notes

- Application and experiment settings are expected outside code under `configs/`.
- Prompt files are tracked under `configs/prompts/`.
- Flyway migrations under `backend/src/main/resources/db/migration/` are the schema source of truth.
- Admin UI starts at `http://localhost:8080/admin`.
