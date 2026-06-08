# Infra

`infra/`는 Query-Forge의 로컬 개발과 운영 보조 문서를 모아 둔 디렉터리입니다. 실제 실행 진입점은 루트 `docker-compose.yml`, `Makefile`, `scripts/`에 있지만, Docker/PostgreSQL/SQL 관련 운영 메모는 이 디렉터리에 정리합니다.

## 구조

```text
docker/            컨테이너 실행과 구성 메모
  postgres/        pgvector PostgreSQL 컨테이너 설명
sql/               Flyway 밖에서 참고하는 inspection/ad hoc SQL 메모
```

## 현재 인프라 구성

루트 `docker-compose.yml`은 기본적으로 `postgres` service를 제공하고, `app` profile을 사용하면 backend service도 함께 빌드/실행할 수 있습니다. PostgreSQL은 `pgvector/pgvector:pg16` 이미지를 사용하며, DB 이름/사용자/비밀번호/포트는 `.env` 또는 환경 변수의 `POSTGRES_*` 값으로 조정합니다.

Schema 변경의 기준은 `backend/src/main/resources/db/migration/`의 Flyway migration입니다. `infra/sql/`은 운영 중 점검 query나 임시 분석 SQL을 보관하는 보조 위치이며, schema source of truth로 취급하지 않습니다.

## 운영 원칙

저사양 로컬 환경에서는 PostgreSQL, backend, frontend dev server, embedding materialization, 전체 pipeline을 동시에 무리하게 실행하지 않는 것이 좋습니다. 필요한 service만 켜고, DB query는 domain/source/dataset/run id로 범위를 좁혀 실행합니다.
