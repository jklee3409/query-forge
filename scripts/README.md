# Scripts

`scripts/`는 로컬 실행과 개발 보조용 스크립트를 모아 두는 디렉터리다. 현재 저장소는 PowerShell 중심으로 운영한다.

## 스크립트 목록

- `bootstrap-local.ps1`
  - Docker PostgreSQL 준비, Python 환경 구성, corpus import, Spring Boot 실행을 한 번에 처리
- `dev-up.ps1`
  - PostgreSQL 컨테이너 실행
- `dev-down.ps1`
  - 로컬 Docker 리소스 정리
- `run-backend.ps1`
  - Spring Boot 실행
- `pipeline.ps1`
  - `pipeline/cli.py` 명령을 간단히 호출
- `import_corpus.sh`
  - Linux/macOS 계열 셸에서 corpus import를 실행할 때 사용하는 보조 스크립트

## 권장 사용 방식

빠른 로컬 부팅은 `bootstrap-local.ps1`를 사용하고, 개별 단계 디버깅이 필요할 때만 나머지 스크립트를 직접 실행하는 방식이 가장 단순하다.
