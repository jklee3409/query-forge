# index.md

## Directory Overview
Root directory for the Query Forge project, which manages a research-oriented RAG pipeline and related backend/frontend services.

---

## Structure
- `.codex/`: agent rules and Codex-related workspace files
- `backend/`: admin UI and server-side application resources
- `frontend/`: frontend application source
- `pipeline/`: end-to-end data/research pipeline implementation
- `configs/`: prompts and experiment configuration
- `data/`: datasets and generated artifacts
- `docs/`: project documentation assets
- `infra/`: infrastructure and deployment-related files
- `scripts/`: utility scripts for development/operations
- `docker-compose.yml`: local service orchestration
- `Makefile`: common development commands
- `README.md`: project introduction and usage guide

---

## Responsibilities
- Maintain the end-to-end project layout and integration points
- Preserve the required research pipeline flow and strategy separation
- Provide shared configuration, scripts, and execution entry points

---

## Key Flows
- Prepare data and configs, then run pipeline stages in fixed order: collect -> preprocess -> chunk -> glossary -> import -> generate-queries -> gate-queries -> build-memory -> build-eval-dataset -> eval-retrieval -> eval-answer
- Keep synthetic query strategies (A/B/C/D) separated and apply strategy-aware quality gating
- Track major work decisions in root `progress.md`

---

## Notes
- Update this file when structure or responsibilities change
