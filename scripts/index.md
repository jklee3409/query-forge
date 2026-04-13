# index.md

## Directory Overview
로컬 개발 환경 초기화와 파이프라인/백엔드 실행을 보조하는 스크립트 디렉토리입니다.

---

## Structure
- `README.md`: 스크립트 사용 개요
- `bootstrap-local.ps1`: 로컬 부트스트랩(환경 준비, import, 실행 보조)
- `dev-up.ps1`: 개발용 인프라 기동
- `dev-down.ps1`: 개발용 인프라 정리
- `run-backend.ps1`: 백엔드 실행 스크립트
- `pipeline.ps1`: 파이프라인 CLI 실행 래퍼
- `import_corpus.sh`: Linux/macOS용 corpus import 보조 스크립트

---

## Responsibilities
- 반복적인 개발/실험 실행 절차를 단순화
- 로컬 환경 기동/종료 동작 표준화
- 운영체제별 실행 진입점을 제공

---

## Key Flows
- 초기 세팅: `bootstrap-local.ps1`
- 개발 시작/종료: `dev-up.ps1` -> 작업 -> `dev-down.ps1`
- 백엔드/파이프라인 개별 실행: `run-backend.ps1`, `pipeline.ps1`
- 비-Windows 환경에서 import만 수행할 때 `import_corpus.sh` 사용

---

## Notes
- Update this file when structure or responsibilities change
