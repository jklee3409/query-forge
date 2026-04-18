# AGENTS.md

## 0. Purpose

This document defines strict rules and research constraints for AI agents (Codex) working on the query-forge project.

Agents must follow these rules to ensure:
- consistent architecture
- correct research implementation
- reproducible experiment results

## 1. Project Goal

The project is a research-driven RAG system with:
- synthetic query generation (A/B/C/D strategies)
- quality gating pipeline
- memory construction
- evaluation dataset generation
- RAG evaluation (retrieval + answer)

Agents must not change the fundamental pipeline flow.

## 2. Core Pipeline Flow

The following order must be preserved:

collect → preprocess → chunk → glossary → import  
→ generate-queries → gate-queries → build-memory  
→ build-eval-dataset → eval-retrieval → eval-answer

## 3. Research Constraints

### 3.1 Synthetic Query Separation

Synthetic queries must be stored in separate tables for each strategy:
- synthetic_queries_raw_a
- synthetic_queries_raw_b
- synthetic_queries_raw_c
- synthetic_queries_raw_d

Agents must not merge these tables into a single structure.

Any processing step must explicitly specify the target strategy table or target strategy set.

### 3.2 Selective Quality Gating

Quality gating must support filtering by generation strategy.

Examples:
- only A queries
- only B queries
- A + C combined

Agents must implement strategy-based filtering before gating.

### 3.3 Adjustable Gating Weights

All gating stages must support dynamic configuration.

Examples include:
- Rule Filter: text length threshold
- Utility: Top-K scoring
- LLM Self Evaluation: per-criteria score threshold

These parameters must be:
- injected dynamically per request
- configurable from the Admin GUI

Hardcoding these values is strictly prohibited.

### 3.4 Evaluation Dataset Structure

Evaluation datasets must contain:
- user query
- ground truth answer

Optional metadata may include:
- purpose
- evaluation intent
- target strategy
- query type

Example:

```json
{
  "query": "...",
  "answer": "...",
  "purpose": "comparison",
  "target_strategy": ["A"]
}
```

The exact schema for purpose and evaluation intent is not fully fixed yet.

Agents must not enforce a strict mandatory taxonomy for purpose until the user defines it explicitly.

## 3.5 Evaluation Dataset (CRITICAL - RETRIEVAL AWARE)

Evaluation datasets in this project are NOT simple QA datasets.

They MUST be retrieval-aware datasets that include document and chunk grounding.

Each evaluation item MUST include:

- user_query_ko
- expected_doc_ids
- expected_chunk_ids
- expected_answer_key_points

Agents MUST NOT generate evaluation datasets that only contain question and answer.

---

### Dataset Structure (MANDATORY)

Each item MUST follow JSONL structure:

{
"sample_id": "...",
"split": "test",
"user_query_ko": "...",
"dialog_context": {},
"expected_doc_ids": ["..."],
"expected_chunk_ids": ["..."],
"expected_answer_key_points": ["..."],
"query_category": "...",
"difficulty": "...",
"single_or_multi_chunk": "...",
"source_product": "...",
"source_version_if_available": "..."
}

---

### A/B/C/D Method-Aware Dataset (MANDATORY)

The dataset MUST support evaluation of A/B/C/D synthetic query generation methods.

This means:

- Same document/chunk MUST be used to generate DIFFERENT query styles
- Queries MUST vary depending on A/B/C/D strategy

---

### Method-Specific Query Characteristics

A:
- translated-style Korean queries (English-first artifacts allowed)

B:
- natural Korean developer queries

C:
- structured, precise, multi-step queries
- may require multi-chunk reasoning

D:
- code-mixed queries (Korean + English + technical terms)

---

### Additional Required Field

Each dataset item MUST include:

- "target_method": "A | B | C | D"

Optional:
- "evaluation_focus": ["translation", "grounding", "naturalness", ...]

---

### Grounding Constraint (STRICT)

- All queries MUST be answerable from corpus
- expected_doc_ids MUST be correct
- expected_chunk_ids MUST be correct
- expected_answer_key_points MUST reflect chunk content

NO hallucination is allowed.

---

### Dataset Quality Rules

Agents MUST:

- avoid duplicate questions
- avoid trivial paraphrasing
- generate realistic developer queries
- include both single-chunk and multi-chunk cases

---

### Prohibited

Agents MUST NOT:

- generate QA-only datasets
- ignore chunk grounding
- ignore A/B/C/D differences

---

## 3.6 RAG End-to-End Quality Evaluation (CRITICAL)

This section defines the mandatory evaluation methodology for the RAG system.

Agents MUST follow this procedure to ensure:
- reproducible experiments
- fair comparison between strategies
- measurable impact of each pipeline stage

---

### 3.6.1 Evaluation Scope

RAG evaluation MUST be conducted at three levels:

1. Synthetic Query Quality
2. Retrieval Performance
3. End-to-End RAG Performance

---

### 3.6.2 Synthetic Query Quality Evaluation

Agents MUST evaluate generated queries BEFORE gating using:

- fluency
- adequacy
- answerability
- copy ratio
- diversity

This stage validates whether queries are natural, grounded, and useful.

---

### 3.6.3 Quality Gating Evaluation

Agents MUST compare the following conditions:

- ungated queries
- rule-based filtered queries
- fully gated queries (Rule + LLM + Utility + Diversity)

Evaluation metrics:

- Recall@5
- Hit@5
- MRR@10
- nDCG@10

Agents MUST NOT skip intermediate stages.

---

### 3.6.4 Snapshot-Based Evaluation (MANDATORY)

In this project, RAG evaluation MAY be conducted using snapshot-based memory states.

A snapshot is defined as:

- a fixed set of synthetic queries
- after a specific generation + gating pipeline
- stored as a memory state

Examples:

- A-only snapshot
- C-only snapshot
- A+C mixed snapshot
- gated vs ungated snapshot

Agents MUST treat each snapshot as an independent experimental condition.

Each snapshot MUST:

- have a unique identifier
- record generation strategy (A/B/C/D)
- record gating configuration
- record creation timestamp or batch id

When running RAG evaluation, agents MUST:

- explicitly select the snapshot
- use the snapshot as retrieval memory
- NOT mix snapshots unless explicitly intended

---

### 3.6.5 Query Rewrite Evaluation

For each snapshot, agents MUST evaluate:

- raw query retrieval
- snapshot-based memory retrieval
- snapshot + rewrite
- snapshot + selective rewrite

Agents MUST:

- compare rewrite vs non-rewrite
- verify improvement through metrics
- NOT assume rewrite is always beneficial

Selective rewrite MUST be treated as the final strategy.

---

### 3.6.6 Final RAG Evaluation (END-TO-END)

Agents MUST evaluate full pipeline configurations:

Baseline:
- raw query + dense retrieval

Experiments:
- snapshot-based memory retrieval
- gated memory retrieval
- rewrite strategies
- selective rewrite

Evaluation MUST include:

Retrieval metrics:
- Recall@5
- MRR@10
- nDCG@10

Answer-level evaluation:
- correctness
- grounding
- hallucination rate

---

### 3.6.7 Experiment Design Rules (MANDATORY)

Agents MUST:

- ensure evaluation consistency by:
  - using the SAME evaluation dataset OR
  - controlling the SAME snapshot
- isolate only ONE variable per experiment
- log all configurations
- store results in `data/reports/`

Agents MUST NOT:

- mix multiple variables at once
- change dataset or snapshot unintentionally
- perform ungrounded evaluation

---

### 3.6.8 Dataset Requirements

Evaluation MUST use datasets defined in Section 3.5.

Agents MUST ensure:

- all queries are answerable from corpus
- expected_doc_ids are correct
- expected_chunk_ids are correct

---

### 3.6.9 Logging and Reproducibility

Each evaluation run MUST record:

- snapshot_id
- generation_strategy (A/B/C/D)
- gating_config
- memory_size
- retrieval_config
- rewrite_config
- evaluation metrics

All results MUST be reproducible and comparable.

---

## 3.7 RAG End-to-End Performance Evaluation (CRITICAL)

RAG evaluation in this project MUST include performance measurements together with quality metrics.

Agents MUST measure and record at least:

- end-to-end run latency (`total_duration_ms`)
- stage-level latency:
  - `build-memory`
  - `eval-retrieval`
  - `eval-answer`
- retrieval latency by mode (at least avg and p95)
- rewrite latency overhead versus `raw_only` baseline

Agents MUST ensure:

- performance metrics are stored with run results (`metrics_json`) for later comparison
- quality and performance are compared on the same dataset/snapshot condition
- only one primary variable is changed per experiment when drawing conclusions

Agents MUST NOT:

- report quality improvements without corresponding latency/cost context
- compare runs with different datasets/snapshots as if performance were equivalent

---

## 4. Execution and Documentation Rules

### 4.1 Root Progress Tracking

All work performed by Codex must be recorded in the root `progress.md`.

On every new session, the agent must read `progress.md` before starting work.

The root `progress.md` must:
- remain concise
- record only major progress and important decisions
- avoid overly long narrative descriptions

### 4.2 Directory-Level Documentation

Every actively used directory must contain:
- `index.md` for directory role and structure
- `progress.md` for local code change history

When modifying code in a directory, the agent must also update:
- that directory's `progress.md`
- that directory's `index.md` if structure, role, or implemented features changed

### 4.3 README Rules

The root `README.md` must include:
- overall project objective
- research overview
- research details
- methodology
- end-to-end project flow

Each major directory should have its own `README.md` describing its implementation details.

All `README.md` files must be written in Korean.
Technical terms may remain in English.
README content should be written in descriptive narrative form, not as fragment-only bullet lists.

### 4.4 Code Modification Rules

Before making changes, agents must review both the frontend and backend code paths related to the requirement.

Agents must:
- inspect the existing implementation across frontend and backend before editing code
- modify only the code that is necessary to satisfy the requirement
- avoid unnecessary rewrites, large-scale refactors, or speculative additions
- prefer reusing existing code, extending existing modules, or integrating with the current structure
- keep changes minimal, targeted, and consistent with the current architecture

Agents must not replace working code with entirely new implementations unless the existing structure clearly cannot satisfy the requirement.

## 5. Repository Map (Agent Quick Index)

This section is a table-of-contents map so agents can understand the project structure quickly.
For each directory, it lists core features, key methods (or entry points), and related markdown document paths.

### 5.1 Root (`/`)
- Core features: Integrates the full research pipeline (`collect` -> `eval-answer`) with backend, frontend, and infra.
- Key methods/entry points:
  - `pipeline/cli.py::main`, `pipeline/cli.py::build_parser`
  - `backend/src/main/java/io/queryforge/backend/QueryForgeApplication.java`
- Documentation paths:
  - `README.md`
  - `index.md`
  - `progress.md`

### 5.2 `.codex/`
- Core features: Codex agent rules, progress logs, and agent-facing indexes.
- Key methods/entry points: No runtime application methods (operations/rules documentation directory).
- Documentation paths:
  - `.codex/AGENTS.md`
  - `.codex/index.md`
  - `.codex/progress.md`

### 5.3 `backend/` (Spring Boot)
- Core features: Admin Console API, Pipeline Admin API, RAG API, LLM job orchestration, DB migrations.
- Key methods/entry points:
  - `RagController.ask`, `previewRewrite`, `queryTrace`, `runExperimentCommand`
  - `RagService.ask`, `previewRewrite`, `reindex`, `readEvalReport`
  - `PipelineAdminService.startCollect`, `startNormalize`, `startChunk`, `startGlossary`, `startImport`, `startFullIngest`
  - `AdminConsoleService.runSyntheticGeneration`, `runGating`, `runRagTest`
  - `LlmJobService.createGenerationJob`, `createGatingJob`, `createRagTestJob`, `pauseJob`, `resumeJob`, `cancelJob`, `retryJob`
  - `DocumentArtifactStoreService.materialize*`, `persist*`
  - `RagRepository.findTopChunksByEmbedding`, `findMemoryTopN`, `createOnlineQuery`, `insertRerankResults`
- Documentation paths:
  - `backend/README.md`
  - `backend/index.md`
  - `backend/progress.md`

### 5.4 `pipeline/` (Python)
- Core features: Collection, preprocessing, chunking, glossary extraction, import, synthetic generation, gating, memory build, eval dataset build, retrieval eval, answer eval.
- Key methods/entry points:
  - `cli.py::main` (full pipeline command dispatcher)
  - `collectors/spring_docs_collector.py::collect_documents`
  - `preprocess/normalize_docs.py::normalize_documents`
  - `preprocess/chunk_docs.py::build_chunks_and_glossary`
  - `preprocess/extract_glossary.py::build_glossary_only`
  - `loaders/import_corpus_to_postgres.py::run_import`
  - `generation/synthetic_query_generator.py::run_generation`, `run_generation_from_env`
  - `gating/quality_gating.py::run_quality_gating`, `run_quality_gating_from_env`
  - `memory/build_memory.py::run_memory_build`, `run_memory_build_from_env`
  - `datasets/build_eval_dataset.py::run_eval_dataset_builder`, `run_eval_dataset_builder_from_env`
  - `eval/retrieval_eval.py::run_retrieval_eval`, `eval/answer_eval.py::run_answer_eval`
  - `eval/runtime.py::retrieve_top_k`, `run_selective_rewrite`
  - `common/experiment_config.py::load_experiment_config`, `common/llm_client.py::load_stage_config`
- Documentation paths:
  - `pipeline/README.md`
  - `pipeline/index.md`
  - `pipeline/progress.md`

### 5.5 `frontend/` (React + Vite)
- Core features: Admin console UI (pipeline/synthetic queries/gating/RAG tests) and chat UI.
- Key methods/entry points:
  - `src/App.jsx::App`, `AdminApp`, `navigate`
  - `src/pages/PipelinePage.jsx::triggerPipeline`, `showDocumentDetail`
  - `src/pages/SyntheticPage.jsx::executeRun`, `loadQueries`, `openQueryDetail`
  - `src/pages/GatingPage.jsx::runGating`, `loadFunnel`, `loadResults`
  - `src/pages/RagPage.jsx::runRag`, `openRunDetail`, `openRewriteDetail`
  - `src/pages/ChatPage.jsx::ask`, `reindex`
  - `src/lib/api.js::requestJson`, `queryString`, `toNumber`
  - `src/lib/hooks.js::usePolling`
- Documentation paths:
  - `frontend/README.md`
  - `frontend/index.md`
  - `frontend/progress.md`

### 5.6 `configs/`
- Core features: App settings (`app/`), experiment presets (`experiments/`), and prompt asset versioning (`prompts/`).
- Key methods (consumers):
  - `pipeline/common/experiment_config.py::load_experiment_config`
  - `pipeline/common/llm_client.py::load_stage_config`
  - `pipeline/common/prompt_assets.py::parse_prompt_asset`, `load_and_register_prompt`
- Documentation paths:
  - `configs/README.md`
  - `configs/index.md`
  - `configs/progress.md`
  - `configs/prompts/query_generation/gen_a_v1.md`
  - `configs/prompts/query_generation/gen_b_v1.md`
  - `configs/prompts/query_generation/gen_c_v1.md`
  - `configs/prompts/query_generation/gen_d_v1.md`
  - `configs/prompts/rewrite/selective_rewrite_v1.md`
  - `configs/prompts/self_eval/quality_gate_v1.md`
  - `configs/prompts/summary_extraction/extractive_summary_v1.md`
  - `configs/prompts/summary_extraction/summarize_ko_v1.md`
  - `configs/prompts/translation/translate_chunk_en_to_ko_v1.md`

### 5.7 `data/`
- Core features: Storage for raw/processed/synthetic/eval/reports artifacts.
- Key methods/entry points: No standalone runtime methods; read/write is handled by `pipeline/*` stages.
- Documentation paths:
  - `data/README.md`
  - `data/index.md`
  - `data/progress.md`
  - `data/raw/README.md`
  - `data/processed/README.md`
  - `data/synthetic/README.md`
  - `data/eval/README.md`
  - `data/reports/README.md`

### 5.8 `docs/`
- Core features: API/architecture/UI/experiment documentation.
- Key methods/entry points: No runtime methods (project documentation repository).
- Documentation paths:
  - `docs/README.md`
  - `docs/index.md`
  - `docs/progress.md`
  - `docs/api/README.md`
  - `docs/api/admin_pipeline_api.md`
  - `docs/api/corpus_admin_api.md`
  - `docs/api/rag_api.md`
  - `docs/architecture/README.md`
  - `docs/architecture/overview.md`
  - `docs/architecture/corpus_storage.md`
  - `docs/architecture/pipeline_orchestration.md`
  - `docs/ui/README.md`
  - `docs/ui/admin_backoffice.md`
  - `docs/experiments/README.md`
  - `docs/experiments/dataset_design.md`
  - `docs/experiments/monitoring_trace.md`
  - `docs/experiments/latest_report.md`
  - `docs/experiments/latest_answer_report.md`
  - `docs/experiments/first_baseline_template.md`
  - `docs/experiments/best_rewrite_cases.md`
  - `docs/experiments/bad_rewrite_cases.md`

### 5.9 `infra/`
- Core features: Docker/PostgreSQL operational notes and SQL support docs.
- Key methods/entry points: No runtime methods (operations documentation focused).
- Documentation paths:
  - `infra/README.md`
  - `infra/index.md`
  - `infra/progress.md`
  - `infra/docker/README.md`
  - `infra/docker/postgres/README.md`
  - `infra/sql/README.md`

### 5.10 `scripts/`
- Core features: Local bootstrap, backend runner, and pipeline command wrappers.
- Key methods/entry points:
  - `bootstrap-local.ps1::Ensure-Venv`, `Wait-ForPostgres`, `Start-BackendIfNeeded`, `Resolve-CorpusArtifacts`, `Import-Corpus`
  - `pipeline.ps1` (wraps `pipeline/cli.py` commands)
  - `run-backend.ps1` (runs `backend/gradlew.bat bootRun`)
  - `dev-up.ps1`, `dev-down.ps1` (docker compose up/down)
  - `import_corpus.sh` (`python pipeline/cli.py import-corpus`)
- Documentation paths:
  - `scripts/README.md`
  - `scripts/index.md`
  - `scripts/progress.md`
