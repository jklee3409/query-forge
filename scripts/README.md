# Scripts

`scripts/`는 로컬 개발과 운영 반복 작업을 보조하는 스크립트 모음입니다.  
PowerShell 중심으로 구성되어 있고, 일부 Linux/macOS 보조 스크립트를 함께 둡니다.

## 스크립트 목록

- `bootstrap-local.ps1`  
  Docker PostgreSQL 준비, Python 환경 구성, corpus import, 백엔드 실행 전 준비를 한 번에 처리합니다.
- `dev-up.ps1`  
  로컬 개발용 Docker(Postgres)를 기동합니다.
- `dev-down.ps1`  
  로컬 개발용 Docker 리소스를 정리합니다.
- `run-backend.ps1`  
  Spring Boot 백엔드를 실행합니다.
- `pipeline.ps1`  
  `pipeline/cli.py` 명령 실행을 간단히 래핑합니다.
- `import_corpus.sh`  
  Linux/macOS 환경에서 corpus import를 실행할 때 사용하는 보조 스크립트입니다.
- `expand_short_user_dataset.py`  
  short-user 평가셋 80문항 전체를 코퍼스 chunk 기반 신규 질의로 재생성하고, 매핑 감사 + DB/리포트 파일을 함께 갱신합니다.
- `verify_eval_dataset_origin.py`  
  지정한 `eval_dataset`의 샘플 출처(`build-eval-dataset`/`corpus_grounded_new_query`)와 synthetic 중복 지표를 검증합니다.

## 권장 사용 방식

로컬 초기 세팅은 `bootstrap-local.ps1`를 우선 사용하고, 이후에는 목적에 맞는 개별 스크립트를 실행하는 흐름을 권장합니다.
