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
- Keep synthetic query strategies (A/B/C/D/E) separated and apply strategy-aware quality gating
- Promote a completed domain-scoped Admin RAG test run into the persistent Chat runtime via `Apply to Chat`, then tune copied values in the domain Chat Settings page
- Keep live chat retrieval parity with promoted Admin RAG runs by persisting backend/model/mode/fusion settings per domain
- Check per-domain Chat readiness before operating rewrite-backed live chat, including selected snapshot-set identity, source gating runs, memory/query counts, prompt binding, domain chunk embeddings, and retrieval tuple
- Allow Chat Settings to select multiple compatible completed source gating snapshots for one domain so live rewrite memory uses the union of those bounded snapshot IDs without crossing domain/strategy/preset guards
- Keep the Admin domain workspace usable for long workflows with scrollable sidebar navigation, compact Chat Settings controls, English runtime option labels, concise generation strategy labels, highlighted accepted-count badges, and readable readiness/status spacing
- Preserve Chat runtime change history in provenance rows so later version/rollback work can replay or inspect prior configs
- Track major work decisions in root `progress.md`

---

## Notes
- Update this file when structure or responsibilities change
