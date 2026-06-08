# PostgreSQL Container

`infra/docker/postgres/`는 루트 `docker-compose.yml`에서 사용하는 PostgreSQL service를 설명합니다. Query-Forge는 corpus, synthetic query, gating, memory, eval sample/result, prompt catalog, domain workspace, LLM job 상태를 PostgreSQL에 저장하므로 이 컨테이너가 로컬 실행의 기본 인프라입니다.

## 현재 Compose 설정

| 항목 | 값 |
| --- | --- |
| image | `pgvector/pgvector:pg16` |
| container | `query-forge-postgres` |
| database | `query_forge` 기본값, `POSTGRES_DB`로 변경 가능 |
| user | `query_forge` 기본값, `POSTGRES_USER`로 변경 가능 |
| port | `5432` 기본값, `POSTGRES_PORT`로 변경 가능 |
| volume | `postgres-data` |

## 확장과 schema

이미지는 pgvector를 포함합니다. `pgcrypto`, `pg_trgm`, vector index, `halfvec(384)` 기반 chunk embedding 구조, domain/prompt/anchor/RAG 관련 schema는 backend Flyway migration에서 적용합니다. 따라서 DB schema를 바꾸려면 ad hoc SQL보다 migration을 추가하는 것이 원칙입니다.

## 실행과 점검

```powershell
docker compose up -d postgres
docker compose logs -f postgres
```

DB가 정상 기동한 뒤 backend를 실행하면 Flyway가 migration을 적용합니다. 저사양 환경에서는 대량 corpus import나 chunk embedding materialization 전에 필요한 source/domain 범위를 먼저 좁히는 것이 안전합니다.
