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
- Use JDBC/Flyway/PostgreSQL as the backend persistence contract; Spring Data JPA is no longer part of the runtime dependency set.

---

## Key Notes
- Legacy single-table `synthetic_queries_raw` is retired by migration `V17`.
- Read paths use `synthetic_queries_raw_all` (union view over `synthetic_queries_raw_a/b/c/d/e/f/g`).
- Write/provenance updates for synthetic raw rows are strategy-table specific.
- Admin Console persistence is being decomposed behind the existing `AdminConsoleRepository` facade. `AdminSyntheticMethodRepository` owns synthetic method lookup SQL, `AdminConsoleDomainScopeRepository` owns domain/source/dataset scope validation SQL, `AdminEvalDatasetRepository` owns eval dataset/item management SQL, and `AdminConsoleStrategyTables` centralizes the A-G raw table allowlist.
- Pipeline Admin persistence now uses `NamedParameterJdbcTemplate` directly; the previous unused JPA entity/repository layer and JPA auditing config have been removed.
- Admin gating result API supports strategy filtering via `method_code` and paging via `limit/offset`.
- Admin gating funnel API supports optional strategy filtering via `method_code` (`전체 + DB 등록 전략 코드`).
- Admin gating config supports dynamic rule-level Korean ratio thresholds via request payload (`ruleMinKoreanRatio`).
- Pipeline run/step status now supports `warning` in addition to `success/failed/cancelled`, and warning backfill is applied via migration `V27`.
- Corpus admin exposes paginated anchor list API (`GET /api/admin/corpus/anchors`) with document/chunk scoped filters for pipeline-stage anchor monitoring.
- Corpus admin exposes anchor normalization dry-run/review APIs under `/api/admin/corpus/anchors/normalization-runs`; unbounded dry-run requests materialize all matching active anchors, explicit positive limits remain available for scoped calls, candidate-level `approve`/`skip` review decisions are saved in bulk, run approval applies only approved safe updates to `corpus_glossary_terms.canonical_form` / `normalized_form`, and run history can be hard-deleted without reverting already-applied canonical values.
- Corpus admin exposes multi-source anchor relation build APIs under `/api/admin/corpus/anchors/multi-source/build-runs`; builds populate additive relation tables for runtime lookup and do not mutate synthetic query text/data.
- Corpus anchor re-extraction (`POST /api/admin/corpus/anchors/extract`) keeps the same evidence replacement/remap flow but now delegates candidate extraction to pipeline glossary logic via `pipeline/cli.py extract-anchor-candidates`, reducing duplicate extraction implementations across backend/pipeline.
- In mixed-scope anchor re-extraction requests, `documentIds` takes precedence over `chunkIds` so selected document(s) are reset/re-extracted document-wide and stale anchors are not left behind in unselected chunks.
- Anchor extraction/injection is used as rewrite grounding control: when Korean query rewriting over English technical-doc memory drops domain terms, anchors are injected to preserve technical intent and improve retrieval stability (`rewrite_anchor_injection_enabled` path).
- `/admin` 웹 경로는 React Admin 단일 앱 라우트(`/admin/pipeline`, `/admin/synthetic-queries`, `/admin/quality-gating`, `/admin/rag-tests`)로 통합되어 `static/react` 번들을 서빙한다.
- Admin Console and Corpus Admin list/run APIs now accept optional `domain_id` filters for domain workspaces. Synthetic generation batches, quality-gating batches, and RAG test runs persist `domain_id`, include it in generated experiment configs, and reject cross-domain batch/dataset/source references when a domain is selected.
- Pipeline Admin now accepts `domainId` on run requests, persists `corpus_runs.domain_id`, filters dashboard/run history by `domain_id`, validates selected sources against the domain, attaches newly created corpus sources to the selected domain, forwards `--domain-id` to `import-corpus`, and propagates imported corpus rows back to that domain after import.
- Admin synthetic supports English-native strategy `E`; backend defaults mark it as `query_language=en` and avoid Korean-ratio rule defaults in subsequent gating configs.
- Admin synthetic supports KR-source split strategies `F/G` with dedicated raw tables (`synthetic_queries_raw_f/g`); `F` defaults to `query_language=en`, `G` defaults to `query_language=ko`.
- Synthetic method selection now supports context-scoped API filtering via `/api/admin/console/synthetic/methods?source_id=...&source_document_id=...&dataset_id=...`; domain workspaces use `tech_doc_domain.source_language` so English technical-doc domains expose A/B/C/D/E and Korean technical-doc domains expose F/G.
- Backend enforces strategy scope guards: English technical-doc scope allows `A~E`, Korean technical-doc scope allows `F/G`, with synthetic run-time source validation and dataset-bound RAG method validation.
- Synthetic run creation additionally enforces source allowlists: `A~E` are limited to the five Spring reference source IDs, `F/G` is limited to `docs-python-org-ko-3-14`, and `arahansa-github-io-docs-spring` is rejected for method listing/run creation.
- Domain-scoped synthetic generation resolves allowed source IDs from active `tech_doc_domain_source` membership when the selected method matches the domain source language; unscoped legacy calls still use the fixed Spring/Python allowlists.
- Admin-generated A/C configs now include bounded summary/query/translation output-token defaults so long English technical-doc chunks can complete through the same Admin generation pipeline without changing raw table layout.
- Source-unselected Admin synthetic runs are represented as one generation batch with method-scoped `source_ids` in the experiment config, so all-allowed-sources stays constrained without creating one job per source.
- Admin synthetic Strategy B configs persist B-only safe generation defaults: `llm_translation_max_output_tokens=2048` plus explicit B summary/query payload bounds, preventing the translation stage from inheriting the global 384-token cap while leaving other strategies unchanged.
- Admin synthetic can explicitly opt Strategy B into Gemini Batch mode through `llmExecutionMode=gemini_batch` and optional `geminiBatchInputMode=inline|jsonl`; omitted fields preserve the online generator path.
- Admin-generated LLM configs keep the Gemini fallback model pinned to `gemini-2.5-flash-lite` so large synthetic batches do not silently fall back to the higher-cost `gemini-2.5-flash`.
- Synthetic batch history now supports hard delete via `DELETE /api/admin/console/synthetic/batches/{batchId}` (linked `llm_job` rows + batch raw synthetic rows cleanup), generation retry-limit removal (`max_retries=-1` sentinel for generation jobs), and live ETA exposure from batch API fields (`targetQueryCount`, `estimatedSecondsPerQuery`, `estimatedRemainingSeconds`, job/item status).
- Synthetic generation LLM jobs preserve retry/cancel failure observations in JSON payloads: `llm_job.result_json`/`last_checkpoint` and `llm_job_item.checkpoint_json` keep `last_failure` and `previous_failures` snapshots with stderr/stdout, command summary, exit code, retry counts, and parsed failure category.
- Gating batch and RAG run list APIs now expose ETA fields as additive runtime projections (`targetQueryCount`/`estimatedSecondsPerQuery`/`estimatedRemainingSeconds` for gating, `totalStageCount`/`completedStageCount`/`estimatedSecondsPerStage`/`estimatedRemainingSeconds` for RAG), and LLM job rows now include generic ETA fields (`estimatedSecondsPerUnit`, `estimatedRemainingSeconds`).
- Eval sample storage now supports `user_query_en` plus `query_language`, and Admin RAG run requests persist `evalQueryLanguage` into experiment config for language-specific runtime loading.
- Admin RAG test run API supports optional snapshot binding via `sourceGatingBatchId` and validates it into fixed `source_gating_run_id`.
- Admin-generated default RAG rewrite runs now evaluate only `raw_only` plus rewrite modes; synthetic memory is recorded as prompt context for rewrite and `rewrite_memory_hint_retrieval_enabled=false` prevents memory-hint retrieval from entering default final retrieval.
- Admin RAG test run API enforces query-language/method compatibility: `E/F` require English eval queries, while `A/B/C/D/G` require Korean/code-mixed eval queries; generated configs record `rewrite_prompt_profile`.
- Admin RAG test run API supports `rewrite_query_profile` (`compact_anchor` default, `detailed_intent` detailed expansion) and optional `rewriteLlmModel`, which overrides only `llm_rewrite_model` while leaving generation/gating models unchanged.
- Domain-scoped Admin RAG test validation allows non-Spring/Python technical-document datasets when the dataset belongs to the selected domain; method validation then uses enabled `tech_doc_domain_method_policy` rows for that domain.
- Admin RAG test run API supports optional `multi_source_anchor_expansion_enabled`, writing bounded relation lookup settings so selective rewrite can pass `multi_source_anchor_hints` as low-priority prompt hints.
- Prompt catalog migration `V37` registers `selective_rewrite_v2` metadata version `v3` and binds `rag_rewrite.ko` to it while retaining v2/v1 fallbacks.
- Admin RAG test run API supports `syntheticFreeBaseline` exploratory mode (synthetic-free baseline), forcing raw-only evaluation semantics without snapshot/method selection.
- Admin RAG test run API accepts `runName` and persists it as `rag_test_run.run_label` plus experiment config `run_name`; migration `V20` assigns legacy auto-labeled RAG runs stable `Legacy RAG Test ###` names.
- Synthetic-backed Admin RAG test configs now include `raw_only` plus the selected selective rewrite mode only; `rewrite_always` is excluded from Admin operational/final evaluation configs. `rewrite_threshold` defaults to `0.05`, anchor injection defaults off, rewrite candidate count defaults to 2, and rewrite memory candidates use a default pool of 20 before reranking.
- Prompt catalog migration `V38` registers `selective_rewrite_v2` metadata version `v4` and binds `rag_rewrite.ko`; migration `V39` registers `selective_rewrite_en_v1` metadata version `v2`; migration `V41` registers cautious synthetic-example-first rewrite prompts (`selective_rewrite_v2` v5, `selective_rewrite_en_v1` v3); migration `V42` registers lightweight `selective_rewrite_v3` v1 and binds `rag_rewrite.ko` to it.
- Admin gating and RAG test APIs accept explicit retriever ranking config (`RetrieverConfigRequest`) for BM25 Only, Dense Only, and Hybrid ranking, persisted into generated experiment YAML and RAG experiment records.
- Admin RAG test retriever configs are normalized server-side by mode: BM25 (`0/1/0`), Dense (`1/0/0`), Hybrid (`0.60/0.32/0.08`), candidate pool `50`, fixed dense model `intfloat/multilingual-e5-small`, dense required for Dense/Hybrid, hash fallback off, and Cohere rerank off for clean mode comparison. Quality-gating keeps its existing configurable retriever request path.
- Admin runtime options now expose `defaultRetrieverMode` and `retrieverModeDefaults` from `configs/app/model_catalog.yml`; Admin RAG run creation uses catalog defaults for omitted threshold/top-K/rerank/retriever fields, keeping GUI and backend defaults aligned.
- Admin runtime options (`GET /api/admin/console/runtime/options`) now read allowlisted provider/model/mode/policy metadata from `configs/app/model_catalog.yml`, expose range defaults, and reject out-of-catalog `llm_model` / `dense_embedding_model` / `retriever_mode` / `rewrite_failure_policy` selections with 400.
- Online Chat runtime now has persistent per-domain config in `chat_runtime_config` with Admin APIs under `/api/admin/chat/config` and user-facing reads under `/api/chat/config`; live chat filters chunk retrieval by domain and synthetic memory by domain, generation strategy, gating preset, and selected source gating snapshot identity.
- Completed domain-scoped Admin RAG test runs can be copied into the persistent Chat runtime through `POST /api/admin/chat/config/apply-rag-run`; the mapper uses saved RAG run config fields and then reuses manual Chat Settings validation.
- Chat runtime config changes are recorded in append-only `chat_runtime_config_provenance` rows with previous/applied snapshots, changed fields, change source, source RAG run ID, and operator metadata.
- Chat runtime config now persists retrieval backend, dense embedding model, retriever mode, candidate pool, and fusion weights. Apply-to-Chat copies those values from completed Admin RAG runs, and live chat uses the configured tuple for chunk/memory retrieval instead of always using the hash-only path.
- Chat runtime config now stores `source_gating_batch_ids` / `source_gating_run_ids` as the selected snapshot set for live synthetic-memory retrieval while retaining singular primary fields for backward compatibility and Apply-to-Chat.
- Chat readiness APIs (`/api/chat/readiness`, `/api/admin/chat/readiness`) expose bounded per-domain health for active config, selected gating snapshot set, source gating runs, domain/strategy/preset mismatch, chunk embeddings, memory/query counts, prompt binding, retrieval tuple, and rewrite-blocking reasons.
- Admin runtime options now also expose `retrieval_backend` (`local`, `db_ann`), and `/api/admin/console/rag/chunk-embeddings/status` + `/materialize` provide explicit chunk-vector readiness/materialization flow for DB ANN evaluation.
- Admin RAG `db-ann` evaluation uses model-specific `chunk_embeddings` (HNSW over `halfvec(384)`) and validates full materialization before run creation; it does not trigger document recollection.
- Online `RagService.ask` keeps local hash retrieval available, but `db_ann` chat now reads model-specific `chunk_embeddings` and `memory_entries.query_embedding` when the domain config selects the Admin RAG dense backend.
- Runtime option environment candidate parsing is null-safe; missing `QUERY_FORGE_*` env vars no longer trigger `NullPointerException` in runtime option responses.
- Rewrite-stage API-key preflight for Admin RAG runs now supports Gemini aliases (`QUERY_FORGE_GEMINI_API_KEY`, `QUERY_FORGE_LLM_GEMINI_API_KEY`, `GEMINI_API_KEY`, `GOOGLE_API_KEY`) and `.env` fallback lookup when process env is not populated.
- Rewrite-stage preflight validation now runs before `rag_test_run` row creation, so 400 invalid requests do not leave `planned` run residues.
- `RagService.ask` and `previewRewrite` now build rewrite candidates from prompt assets (`selective_rewrite_v3` preferred, `v2`/`v1` fallback) via env-driven Gemini/OpenAI calls with heuristic fallback and a maximum of two candidates.
- Official RAG comparison runs enforce explicit snapshot identities and bundled conditions by comparison axis (`officialRun` + `officialComparisonType`).
- Official runs persist normalized reproducibility records in `rag_eval_experiment_record` (snapshot, strategy, gating/retrieval/rewrite config, dataset version, timestamp, metrics).
- RAG eval persistence FKs are aligned by migration `V18` (`memory_entries`/`retrieval_results`/`rerank_results` now reference `corpus_documents`/`corpus_chunks`).
- LLM job execution supports retry resume from completed items and command timeout control (`query-forge.admin.pipeline.experiment-command-timeout-seconds`).
- RAG detail row ingestion now preserves rewrite decision diagnostics in `metric_contribution` (`raw_confidence`, `best_candidate_confidence`, `confidence_delta`, `rewrite_reason`).
- Admin RAG finalization now writes `rewrite_applied=true` rewrite-anchor usage/evaluation rows to `rag_rewrite_anchor_eval`, with run/detail lookup APIs and DB-derived anchor quality summaries for detail and compare views.
- RAG test finalization now persists only the new latency payload under `metrics_json.performance`: `avg_query_eval_total_latency_ms`, `avg_final_rewrite_latency_ms`, `avg_pure_rewrite_latency_ms`, plus sample counts (`eval_sample_count`, `rewrite_sample_count`, `pure_rewrite_sample_count`, `excluded_sample_count`).
- Stored RAG retrieval summaries are sanitized before persistence so deprecated retrieval-side latency payloads such as `latency_summary` are no longer exposed through Admin RAG APIs.
- Admin RAG run deletion enforces full-cascade cleanup scope: run-linked rewrite logs, `llm_job` history, and linked `experiment_runs` artifacts (`eval_judgments`, `retrieval_results`, `rerank_results`, `online_queries`) are removed transactionally to prevent residual test history.
- Admin RAG eval datasets can be hard-deleted through `DELETE /api/admin/console/rag/datasets/{datasetId}` for non-default datasets; linked terminal RAG histories are removed through the existing run cleanup path, active runs block deletion, and detail rows are returned in sample-number order for modal review.
- Admin RAG dataset-item and run-history list APIs now support full-list reads when `limit` is omitted, while explicit positive `limit` values remain bounded for callers that need capped views.
- Backend DB sessions now initialize with `Asia/Seoul` timezone baseline (`SET TIME ZONE 'Asia/Seoul'`), and newly created RAG run labels use compact KST time (`yyyy-MM-dd HH:mm`).
- Corpus admin now supports scoped anchor re-extraction via `POST /api/admin/corpus/anchors/extract` (document/chunk selection), with active anchor remapping for affected synthetic queries through `synthetic_query_anchor_link` (migration `V25`).
