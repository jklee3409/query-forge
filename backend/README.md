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

API, 서비스, 저장소, 요청·응답 타입은 해당 기능의 패키지에 배치합니다. 기능 안에서 독립적인 책임은 하위 패키지로 나누고, 테스트는 같은 패키지 경로를 따릅니다.

```text
src/main/java/io/queryforge/backend/
  QueryForgeApplication.java
  admin/
    console/
      synthetic/     합성 질의 메서드, 배치, 결과
      gating/        품질 게이팅 배치와 결과
      evaluation/    RAG 실험, 데이터셋, 실행 이력, rewrite 진단
      embedding/     청크 임베딩 관리
      dashboard/     콘솔 집계
      jobs/          LLM 작업 큐, 실행, 결과 처리
      configuration/, runtime/, scope/, persistence/
    corpus/
      source/, run/, document/, glossary/
      anchor/        추출, 정규화, 다중 소스 관계, 평가
      persistence/
    domain/          도메인 정보, source membership, 대시보드
    pipeline/        dashboard/, artifact/, execution/, source/
    prompt/          asset/, binding/
  common/        호출 로깅
  rag/
    chat/        온라인 질의와 답변 조립
    runtime/     도메인별 설정, 실험 설정 승격, readiness
    retrieval/   도메인 검색과 reranking
      agentic/     멀티 쿼리 검색과 결과 병합
      memory/      합성 질의 메모리 검색
      rewrite/     재작성 후보 생성과 미리보기
      routing/     검색 전략 선택
    eval/        retrieval-only 평가와 리포트 조회
    experiment/  실험 명령 실행과 요약 조회
    trace/       검색 및 답변 trace 저장과 조회
    embedding/   dense/hash 임베딩과 재색인
    provider/    Spring AI gateway와 런타임 환경 값
    persistence/ RAG 저장소 공통 JDBC 유틸리티
  ui/            React Admin route forwarding

src/main/resources/
  application.yml
  db/migration/  PostgreSQL/Flyway schema history
  static/react/  Admin Console production bundle
```

기능별 구현 진입점과 타입 의존 관계는 [백엔드 기능 경계와 타입 분석](../docs/architecture/backend_feature_boundaries.md)을 참고합니다.

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

Spring AI PGVector를 사용하려면 embedding model을 설정하고 `QUERY_FORGE_SPRING_AI_VECTORSTORE_TYPE=pgvector`를 지정합니다.

## 테스트

```powershell
.\backend\gradlew.bat -p .\backend test
```

테스트는 JUnit 5, Spring Boot Test, Testcontainers PostgreSQL을 사용합니다. PostgreSQL 통합 테스트에는 Docker가 필요합니다. 코퍼스 앵커 재추출 테스트는 Python pipeline을 실행하므로 파이프라인 의존성이 설치된 Python을 사용합니다. 가상 환경을 사용하는 경우 `QUERY_FORGE_PYTHON`에 해당 실행 파일 경로를 지정합니다.

## 운영 메모

Backend persistence는 Spring Data JPA가 아니라 Spring JDBC, `NamedParameterJdbcTemplate`, Flyway 중심입니다. `synthetic_queries_raw_all`은 전략별 raw table을 읽기 위한 union view이고, write path는 반드시 A-G 전략 table allowlist를 거칩니다. DB ANN 평가에서는 `chunk_embeddings`의 `halfvec(384)`와 HNSW index를 사용하며, online RAG의 hash memory embedding path와 Admin dense/DB-ANN evaluation path는 분리되어 있습니다.
