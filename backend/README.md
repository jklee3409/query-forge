# Backend

`backend/`는 Query-Forge의 Spring Boot control plane입니다. Python pipeline을 다시 구현하지 않고, PostgreSQL 스키마와 pipeline command를 운영 API로 연결합니다. Admin Console, Corpus Admin, Domain Workspace, Prompt Studio, RAG API, LLM job orchestration, Flyway migration이 모두 이 디렉터리에서 제공됩니다.

Backend의 핵심 책임은 재현 가능한 실험 조건을 강제하는 것입니다. Synthetic query는 A/B/C/D/E/F/G 전략별 raw table로 분리해 저장하고, gating과 RAG test는 선택된 generation batch, gating snapshot, eval dataset, retriever/rewrite config를 명시적으로 검증합니다. Runtime 선택지는 `configs/app/model_catalog.yml`을 읽어 allowlist 기반으로 제공하며, catalog 밖의 model, retriever mode, retrieval backend, rewrite policy는 요청 단계에서 거부합니다.

## 구현 범위

| 영역 | 현재 역할 |
| --- | --- |
| Admin Console API | synthetic generation batch, quality gating batch, RAG test run, runtime option, chunk embedding materialization, LLM job 상태 제어를 제공합니다. |
| Corpus Admin API | source/document/section/chunk/glossary 조회, preview, anchor extraction, anchor normalization review, multi-source anchor relation build, anchor eval을 제공합니다. |
| Pipeline Admin API | collect, normalize, chunk, glossary, import, full-ingest를 backend-managed subprocess로 실행하고 run/step/log 상태를 기록합니다. |
| Domain Admin API | `tech_doc_domain` 기반 workspace를 생성하고 source membership, domain summary, domain-scoped 실행 필터링을 지원합니다. |
| Prompt Admin API | prompt asset 조회, DB-backed revision 생성, active binding 변경, binding validation을 제공합니다. |
| Online RAG API | `/api/chat/ask`, `/api/rewrite/preview`, `/api/queries/{id}/trace`, eval report 조회, reindex/experiment command endpoint를 제공합니다. |
| DB Migration | Flyway `V1`부터 `V43`까지 schema, seed, prompt catalog, domain, anchor, DB ANN, RAG eval persistence를 관리합니다. |
| React Static Serving | `frontend` production bundle을 `src/main/resources/static/react/`에 두고 `/admin/*` 경로로 서빙합니다. |

## 디렉터리 구조

```text
src/main/java/io/queryforge/backend/
  QueryForgeApplication.java
  admin/
    console/     synthetic, gating, RAG test, runtime options, LLM jobs
    corpus/      corpus/source/document/chunk/glossary/anchor 관리
    domain/      기술 문서 domain workspace 관리
    pipeline/    Python pipeline subprocess 실행과 artifact materialization
    prompt/      prompt asset/revision/binding 관리
  common/        application layer logging 같은 공통 concern
  rag/           online RAG, rewrite preview, experiment report API
  ui/            React Admin route forwarding

src/main/resources/
  application.yml
  db/migration/  PostgreSQL/Flyway schema history
  static/react/  Admin Console production bundle

src/test/java/io/queryforge/backend/
  admin/         Admin Console, Corpus, Pipeline, UI integration tests
  rag/           RAG controller/service tests
```

## 주요 API 경로

`/api/admin/pipeline/*`는 corpus ingest 단계를 실행하고, `/api/admin/corpus/*`는 import된 corpus와 anchor를 검수합니다. `/api/admin/console/*`는 synthetic query, quality gating, RAG test, runtime options, LLM job을 다룹니다. `/api/admin/domains/*`는 domain workspace를 관리하고, `/api/admin/prompt-assets`와 `/api/admin/prompt-bindings`는 Prompt Studio의 저장소 역할을 합니다. 온라인 질의 표면은 `/api/chat/ask`와 `/api/rewrite/preview`를 중심으로 동작합니다.

Admin RAG 실행은 synthetic-backed 조건에서 explicit `sourceGatingBatchId`를 요구합니다. 기본 평가는 synthetic memory를 최종 검색어로 쓰지 않고, rewrite prompt의 예시와 anchor hint로만 사용합니다. `raw_only`와 selective rewrite candidate retrieval을 같은 dataset/snapshot/retriever 조건에서 비교하고, 선택적 rewrite 결과와 latency payload를 DB와 `data/reports/` 산출물에 남깁니다.

## 실행

루트 디렉터리에서 실행할 때는 다음 명령을 사용합니다.

```powershell
.\backend\gradlew.bat -p .\backend bootRun
```

`backend/` 디렉터리 안에서는 다음처럼 실행할 수 있습니다.

```powershell
.\gradlew.bat bootRun
```

기본 서버 포트는 `8080`이며, datasource와 config root는 `application.yml`의 `POSTGRES_*`, `QUERY_FORGE_*`, `PROMPT_ROOT`, `EXPERIMENT_ROOT` 환경 변수로 조정합니다.

## 테스트

```powershell
.\backend\gradlew.bat -p .\backend test
```

테스트는 JUnit 5, Spring Boot Test, Testcontainers PostgreSQL을 사용합니다. 로컬 Docker가 필요할 수 있으므로 저사양 환경에서는 전체 테스트보다 변경 범위에 맞는 integration test를 먼저 실행하는 것이 안전합니다.

## 운영 메모

Backend persistence는 Spring Data JPA가 아니라 Spring JDBC, `NamedParameterJdbcTemplate`, Flyway 중심입니다. `synthetic_queries_raw_all`은 전략별 raw table을 읽기 위한 union view이고, write path는 반드시 A-G 전략 table allowlist를 거칩니다. DB ANN 평가에서는 `chunk_embeddings`의 `halfvec(384)`와 HNSW index를 사용하며, online RAG의 hash memory embedding path와 Admin dense/DB-ANN evaluation path는 분리되어 있습니다.
