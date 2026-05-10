# Backend

`backend/`는 Spring Boot 기반의 운영 API 및 웹 엔트리포인트를 담당하는 모듈이다. 이 디렉터리는 corpus 관리, pipeline orchestration, synthetic/gating/RAG 운영 API, 온라인 RAG API, 그리고 DB 마이그레이션을 함께 제공한다.

## 주요 역할

- `GET/POST/PATCH/DELETE /api/admin/corpus/*` 계열로 소스/문서/청크/용어/앵커/anchor-eval 관리
- `POST /api/admin/pipeline/*` 계열로 collect -> import 실행 및 run 상태/로그 조회
- `GET/POST /api/admin/console/*` 계열로 synthetic generation, quality gating, RAG test 운영
- `POST /api/chat/ask`, `POST /api/rewrite/preview` 등 온라인 RAG API 제공
- Flyway로 PostgreSQL 스키마 이력 관리(`V1`~`V27`)
- React Admin UI 정적 번들을 `/react/index.html`로 서빙

## 디렉터리 구성

```text
src/main/java/io/queryforge/backend/
  admin/
    corpus/    corpus 관리 API, 서비스, 저장소
    pipeline/  파이프라인 실행 API, subprocess runner
    console/   synthetic/gating/rag 운영 API
  rag/         chat/trace/rewrite/reindex API, 서비스, 저장소
  ui/          React UI 라우트 controller

src/main/resources/
  db/migration/   Flyway migration
  static/react/   프런트엔드 빌드 산출물
  application.yml

src/test/
  java/       컨트롤러/서비스/통합 테스트
  resources/  fixture SQL, 테스트 설정
```

## 실행

```powershell
.\gradlew.bat -p . bootRun
```

루트에서 실행한다면 다음 명령도 가능하다.

```powershell
.\backend\gradlew.bat -p .\backend bootRun
```

## 테스트

```powershell
.\gradlew.bat -p . test
```

Testcontainers 기반 테스트가 포함되어 있으므로 Docker가 켜져 있어야 한다.

## 참고 사항

- 파이프라인 실행은 내부 큐 시스템 대신 `ProcessBuilder` 기반 subprocess 호출로 구현되어 있다.
- pipeline run/step 상태는 `queued/running/success/failed/cancelled/warning`을 사용한다.
- Anchor 재추출 API(`POST /api/admin/corpus/anchors/extract`)는 backend 내부 휴리스틱 대신 `python pipeline/cli.py extract-anchor-candidates`를 호출해 파이프라인 추출 로직과 동기화되어 있다.
- synthetic 전략은 A/B/C/D/E/F/G 분리 저장을 전제로 하며, 조회는 `synthetic_queries_raw_all` 뷰를 사용한다.
