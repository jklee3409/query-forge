# Infra

`infra/`는 로컬 개발과 운영 보조에 필요한 Docker, SQL 메모를 모아 두는 디렉터리다.

## 디렉터리 구성

```text
docker/  컨테이너 관련 메모
sql/     운영 보조 SQL과 inspection 메모
```

## 참고

- 실제 로컬 실행 진입점은 루트의 `docker-compose.yml`이다.
- PostgreSQL 컨테이너 설명은 `infra/docker/postgres/README.md`를 참고하면 된다.
