# index.md

## Directory Overview
Spring Boot backend for Admin Console APIs, RAG APIs, pipeline command orchestration, and DB migrations.

---

## Structure
- `src/main/java/io/queryforge/backend/admin/console/*`: synthetic/gating/rag admin APIs and repositories
- `src/main/java/io/queryforge/backend/admin/pipeline/*`: pipeline orchestration and command runners
- `src/main/java/io/queryforge/backend/rag/*`: online RAG API/repository/service
- `src/main/resources/db/migration/*`: Flyway schema migrations
- `src/main/resources/static/react/*`: bundled admin UI assets

---

## Responsibilities
- Provide admin APIs for synthetic query generation, quality gating, and RAG test operations.
- Execute pipeline commands via backend-managed jobs.
- Maintain DB schema evolution through Flyway.
- Preserve strategy-separated synthetic raw storage (A/B/C/D) and split-aware read paths.

---

## Key Notes
- Legacy single-table `synthetic_queries_raw` is retired by migration `V17`.
- Read paths use `synthetic_queries_raw_all` (union view over `synthetic_queries_raw_a/b/c/d`).
- Write/provenance updates for synthetic raw rows are strategy-table specific.
- Admin gating result API supports strategy filtering via `method_code` and paging via `limit/offset`.
- Admin gating funnel API supports optional strategy filtering via `method_code` (`전체/A/B/C/D`).
- Admin gating config supports dynamic rule-level Korean ratio thresholds via request payload (`ruleMinKoreanRatio`).
- Admin RAG test run API supports optional snapshot binding via `sourceGatingBatchId` and validates it into fixed `source_gating_run_id`.
- Admin RAG test run API supports `syntheticFreeBaseline` exploratory mode (synthetic-free baseline), forcing raw-only evaluation semantics without snapshot/method selection.
- Synthetic-backed Admin RAG test configs include `raw_only`, `memory_only_gated`, `rewrite_always`, and the selected selective rewrite mode for same-dataset comparison; `rewrite_threshold` defaults to `0.10`.
- `RagService.ask` and `previewRewrite` now build rewrite candidates from prompt assets (`selective_rewrite_v2` preferred, `v1` fallback) via env-driven Gemini/OpenAI calls with heuristic fallback.
- Official RAG comparison runs enforce explicit snapshot identities and bundled conditions by comparison axis (`officialRun` + `officialComparisonType`).
- Official runs persist normalized reproducibility records in `rag_eval_experiment_record` (snapshot, strategy, gating/retrieval/rewrite config, dataset version, timestamp, metrics).
- RAG eval persistence FKs are aligned by migration `V18` (`memory_entries`/`retrieval_results`/`rerank_results` now reference `corpus_documents`/`corpus_chunks`).
- LLM job execution supports retry resume from completed items and command timeout control (`query-forge.admin.pipeline.experiment-command-timeout-seconds`).
- RAG detail row ingestion now preserves rewrite decision diagnostics in `metric_contribution` (`raw_confidence`, `best_candidate_confidence`, `confidence_delta`, `rewrite_reason`).
- RAG test finalization now persists performance metrics (`total_duration_ms`, stage durations, representative latency, rewrite overhead) under `metrics_json.performance` without changing retrieval/answer business logic.
- Admin RAG run deletion enforces full-cascade cleanup scope: run-linked rewrite logs, `llm_job` history, and linked `experiment_runs` artifacts (`eval_judgments`, `retrieval_results`, `rerank_results`, `online_queries`) are removed transactionally to prevent residual test history.
- Backend DB sessions now initialize with `Asia/Seoul` timezone baseline (`SET TIME ZONE 'Asia/Seoul'`), and newly created RAG run labels use compact KST time (`yyyy-MM-dd HH:mm`).
