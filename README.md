# Query Forge

Query Forge는 영문 Spring 기술 문서를 기반으로 한국어 질의 중심 RAG 시스템을 구축하기 위한 연구용 저장소다. 현재 기준으로는 문서 수집, 정제, chunking, glossary 추출, PostgreSQL 적재, 그리고 이를 실행·검수하는 관리자 백오피스까지 구현되어 있다.

## 현재 구현 범위

- Spring 공식 문서 HTML 수집
- HTML 정제와 section 단위 구조화
- heading-aware chunking, glossary 추출
- PostgreSQL `corpus_*` 스키마와 idempotent import
- Spring Boot 기반 corpus admin API
- Spring Boot 기반 pipeline orchestration API
- `/admin` 백오피스 UI

아직 구현되지 않은 범위는 synthetic query generation, quality gating, query rewrite, retrieval, rerank, answer generation, 평가 자동화다.

## 저장소 구조

```text
backend/   Spring Boot API, Admin UI, Flyway migration, 테스트
configs/   앱 설정, source 정의, 실험 설정, 프롬프트
data/      raw / processed / synthetic / eval / reports 산출물
docs/      아키텍처, API, UI, 실험 문서
infra/     Docker, SQL, 운영 메모
pipeline/  Python 수집·정제·chunking·import 파이프라인
scripts/   로컬 실행과 운영 보조 스크립트
```

각 디렉터리의 상세 설명은 해당 위치의 `README.md`를 참고하면 된다.

## 빠른 시작

### 사전 준비

- Docker Desktop 또는 Docker Engine
- Java 21
- Python 3.12 이상

### 한 번에 구동

현재 저장소 상태에서 PostgreSQL 준비, Python 환경 구성, corpus import, Spring Boot 실행까지 한 번에 처리하려면 아래 스크립트를 사용한다.

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\bootstrap-local.ps1
```

기본 동작은 다음과 같다.

1. `.env`가 없으면 `.env.example`을 복사해 생성
2. `.venv` 생성 후 `pipeline` 의존성 설치
3. `docker compose up -d postgres` 실행
4. PostgreSQL health check 대기
5. Spring Boot를 백그라운드로 실행하고 Flyway migration 완료 대기
6. corpus 산출물을 PostgreSQL로 import
7. `/admin` 접속 가능 상태까지 확인

실제 수집 결과가 아직 없다면 저장소에 포함된 dry-run 샘플 corpus를 사용해 초기 데이터를 적재한다.

### 옵션 예시

```powershell
# dry-run 샘플 대신 실제 산출물이 있으면 자동으로 사용
powershell -ExecutionPolicy Bypass -File .\scripts\bootstrap-local.ps1 -CorpusMode auto

# import 없이 DB와 서버만 올리기
powershell -ExecutionPolicy Bypass -File .\scripts\bootstrap-local.ps1 -CorpusMode none

# 부팅 후 관리자 화면 열기
powershell -ExecutionPolicy Bypass -File .\scripts\bootstrap-local.ps1 -OpenBrowser
```

## 수동 실행

### PostgreSQL만 먼저 실행

```powershell
docker compose up -d postgres
```

### 백엔드 실행

```powershell
.\backend\gradlew.bat -p .\backend bootRun
```

### 파이프라인 예시

```powershell
python .\pipeline\cli.py collect-docs --limit 10
python .\pipeline\cli.py preprocess --input data/raw/spring_docs_raw_dry_run.jsonl --output data/processed/spring_docs_sections.jsonl
python .\pipeline\cli.py chunk-docs --input data/processed/spring_docs_sections.jsonl
python .\pipeline\cli.py import-corpus --raw-input data/raw/spring_docs_raw_dry_run.jsonl --sections-input data/processed/spring_docs_sections_dry_run.jsonl
```

## 주요 접속 경로

- 관리자 UI: `http://localhost:8080/admin`
- 헬스 체크: `http://localhost:8080/actuator/health`

## 문서 읽기 순서

1. `README.md`
2. `backend/README.md`
3. `pipeline/README.md`
4. `docs/README.md`
5. 필요 시 `docs/api`, `docs/architecture`, `docs/ui` 세부 문서
