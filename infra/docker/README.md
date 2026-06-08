# Docker Notes

`infra/docker/`는 Query-Forge에서 사용하는 컨테이너 실행 메모를 정리합니다. 현재 상세 문서는 PostgreSQL 중심이며, 실제 Compose 정의는 루트 `docker-compose.yml`에 있습니다.

## Compose 기준

기본 개발 흐름은 다음 명령으로 PostgreSQL만 띄우는 방식입니다.

```powershell
docker compose up -d postgres
```

Backend 컨테이너까지 Compose로 실행하려면 `app` profile을 사용합니다.

```powershell
docker compose --profile app up -d
```

Backend service는 `./configs`를 read-only로, `./data`를 writable volume으로 mount합니다. Prompt root와 experiment root는 container 내부 `/app/configs/prompts`, `/app/configs/experiments`로 전달됩니다.

## 하위 문서

`postgres/README.md`는 pgvector 기반 PostgreSQL 컨테이너의 DB 이름, 사용자, 포트, volume, extension 적용 방식을 설명합니다.
