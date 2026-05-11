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
- Preserve strategy-separated synthetic raw storage (A/B/C/D/E/F/G) and split-aware read paths.

---

## Key Notes
- Legacy single-table `synthetic_queries_raw` is retired by migration `V17`.
- Read paths use `synthetic_queries_raw_all` (union view over `synthetic_queries_raw_a/b/c/d/e/f/g`).
- Write/provenance updates for synthetic raw rows are strategy-table specific.
- Admin gating result API supports strategy filtering via `method_code` and paging via `limit/offset`.
- Admin gating funnel API supports optional strategy filtering via `method_code` (`전체 + DB 등록 전략 코드`).
- Admin gating config supports dynamic rule-level Korean ratio thresholds via request payload (`ruleMinKoreanRatio`).
- Pipeline run/step status now supports `warning` in addition to `success/failed/cancelled`, and warning backfill is applied via migration `V27`.
- Corpus admin exposes paginated anchor list API (`GET /api/admin/corpus/anchors`) with document/chunk scoped filters for pipeline-stage anchor monitoring.
- Corpus anchor re-extraction (`POST /api/admin/corpus/anchors/extract`) keeps the same evidence replacement/remap flow but now delegates candidate extraction to pipeline glossary logic via `pipeline/cli.py extract-anchor-candidates`, reducing duplicate extraction implementations across backend/pipeline.
- In mixed-scope anchor re-extraction requests, `documentIds` takes precedence over `chunkIds` so selected document(s) are reset/re-extracted document-wide and stale anchors are not left behind in unselected chunks.
- Anchor extraction/injection is used as rewrite grounding control: when Korean query rewriting over English technical-doc memory drops domain terms, anchors are injected to preserve technical intent and improve retrieval stability (`rewrite_anchor_injection_enabled` path).
- `/admin` 웹 경로는 React Admin 단일 앱 라우트(`/admin/pipeline`, `/admin/synthetic-queries`, `/admin/quality-gating`, `/admin/rag-tests`)로 통합되어 `static/react` 번들을 서빙한다.
- Admin synthetic supports English-native strategy `E`; backend defaults mark it as `query_language=en` and avoid Korean-ratio rule defaults in subsequent gating configs.
- Admin synthetic supports KR-source split strategies `F/G` with dedicated raw tables (`synthetic_queries_raw_f/g`); `F` defaults to `query_language=en`, `G` defaults to `query_language=ko`.
- Synthetic method selection now supports context-scoped API filtering via `/api/admin/console/synthetic/methods?source_id=...&source_document_id=...&dataset_id=...`.
- Backend enforces strategy scope guards: Spring technical-doc scope allows only `A~E`, Python KR scope allows only `F/G`, with synthetic run-time source validation and dataset-bound RAG method validation.
- Synthetic batch history now supports hard delete via `DELETE /api/admin/console/synthetic/batches/{batchId}` (linked `llm_job` rows + batch raw synthetic rows cleanup), generation retry-limit removal (`max_retries=-1` sentinel for generation jobs), and live ETA exposure from batch API fields (`targetQueryCount`, `estimatedSecondsPerQuery`, `estimatedRemainingSeconds`, job/item status).
- Eval sample storage now supports `user_query_en` plus `query_language`, and Admin RAG run requests persist `evalQueryLanguage` into experiment config for language-specific runtime loading.
- Admin RAG test run API supports optional snapshot binding via `sourceGatingBatchId` and validates it into fixed `source_gating_run_id`.
- Admin RAG test run API supports `syntheticFreeBaseline` exploratory mode (synthetic-free baseline), forcing raw-only evaluation semantics without snapshot/method selection.
- Admin RAG test run API accepts `runName` and persists it as `rag_test_run.run_label` plus experiment config `run_name`; migration `V20` assigns legacy auto-labeled RAG runs stable `Legacy RAG Test ###` names.
- Synthetic-backed Admin RAG test configs include `raw_only`, `memory_only_gated`, `rewrite_always`, and the selected selective rewrite mode for same-dataset comparison; `rewrite_threshold` defaults to `0.10`.
- Admin gating and RAG test APIs accept explicit retriever ranking config (`RetrieverConfigRequest`) for BM25 Only, Dense Only, and Hybrid ranking, persisted into generated experiment YAML and RAG experiment records.
- Admin RAG test retriever configs are normalized server-side by mode: BM25 (`0/1/0`), Dense (`1/0/0`), Hybrid (`0.60/0.32/0.08`), candidate pool `50`, fixed dense model `intfloat/multilingual-e5-small`, dense required for Dense/Hybrid, hash fallback off, and Cohere rerank off for clean mode comparison. Quality-gating keeps its existing configurable retriever request path.
- Admin runtime options (`GET /api/admin/console/runtime/options`) now read allowlisted provider/model/mode/policy metadata from `configs/app/model_catalog.yml`, expose range defaults, and reject out-of-catalog `llm_model` / `dense_embedding_model` / `retriever_mode` / `rewrite_failure_policy` selections with 400.
- Runtime option environment candidate parsing is null-safe; missing `QUERY_FORGE_*` env vars no longer trigger `NullPointerException` in runtime option responses.
- `RagService.ask` and `previewRewrite` now build rewrite candidates from prompt assets (`selective_rewrite_v2` preferred, `v1` fallback) via env-driven Gemini/OpenAI calls with heuristic fallback.
- Official RAG comparison runs enforce explicit snapshot identities and bundled conditions by comparison axis (`officialRun` + `officialComparisonType`).
- Official runs persist normalized reproducibility records in `rag_eval_experiment_record` (snapshot, strategy, gating/retrieval/rewrite config, dataset version, timestamp, metrics).
- RAG eval persistence FKs are aligned by migration `V18` (`memory_entries`/`retrieval_results`/`rerank_results` now reference `corpus_documents`/`corpus_chunks`).
- LLM job execution supports retry resume from completed items and command timeout control (`query-forge.admin.pipeline.experiment-command-timeout-seconds`).
- RAG detail row ingestion now preserves rewrite decision diagnostics in `metric_contribution` (`raw_confidence`, `best_candidate_confidence`, `confidence_delta`, `rewrite_reason`).
- RAG test finalization now persists performance metrics (`total_duration_ms`, stage durations, representative latency, rewrite overhead) under `metrics_json.performance` without changing retrieval/answer business logic.
- Admin RAG run deletion enforces full-cascade cleanup scope: run-linked rewrite logs, `llm_job` history, and linked `experiment_runs` artifacts (`eval_judgments`, `retrieval_results`, `rerank_results`, `online_queries`) are removed transactionally to prevent residual test history.
- Backend DB sessions now initialize with `Asia/Seoul` timezone baseline (`SET TIME ZONE 'Asia/Seoul'`), and newly created RAG run labels use compact KST time (`yyyy-MM-dd HH:mm`).
- Corpus admin now supports scoped anchor re-extraction via `POST /api/admin/corpus/anchors/extract` (document/chunk selection), with active anchor remapping for affected synthetic queries through `synthetic_query_anchor_link` (migration `V25`).
