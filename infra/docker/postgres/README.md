# PostgreSQL Container

루트 `docker-compose.yml`에서 사용하는 PostgreSQL 컨테이너는 `pgvector/pgvector:pg16` 이미지를 기반으로 한다.

## 현재 구성

- 컨테이너 이름: `query-forge-postgres`
- 기본 DB: `query_forge`
- 기본 사용자: `query_forge`
- 기본 포트: `5432`
- volume: `postgres-data`

## 특징

- pgvector 확장을 지원하는 이미지를 사용한다.
- `pgcrypto`, `pg_trgm` 같은 추가 확장은 Flyway migration에서 활성화한다.
- 스키마의 source of truth는 백엔드 migration 파일이다.

## 관련 파일

- 루트 Compose: `docker-compose.yml`
- 백엔드 migration: `backend/src/main/resources/db/migration/`
- 운영 SQL 메모: `infra/sql/`
