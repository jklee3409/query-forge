# index.md

## Directory Overview
로컬 개발/운영 보조 스크립트를 모아 두는 디렉터리입니다.

---

## Structure
- `README.md`: 스크립트 사용 개요
- `bootstrap-local.ps1`: 로컬 부트스트랩(환경 준비, corpus import, 실행 보조)
- `dev-up.ps1`: 개발용 Docker(Postgres) 기동
- `dev-down.ps1`: 개발용 Docker 리소스 정리
- `run-backend.ps1`: Spring Boot 실행
- `pipeline.ps1`: `pipeline/cli.py` 명령 실행 래퍼
- `import_corpus.sh`: Linux/macOS용 corpus import 보조
- `expand_short_user_dataset.py`: short-user 평가셋 80문항 전체를 코퍼스 chunk 기반 신규 질의로 재생성 + 매핑 감사 + DB 반영
- `verify_eval_dataset_origin.py`: eval_dataset 샘플 출처(build-eval-dataset / corpus_grounded_new_query) 및 synthetic 중복 지표 검증

---

## Responsibilities
- 반복되는 실행 절차를 단순화
- 로컬 환경 기동/종료 작업 표준화
- 데이터셋/파이프라인 운영 작업의 재현성 확보

---

## Key Flows
- 초기 세팅: `bootstrap-local.ps1`
- 개발 시작/종료: `dev-up.ps1` -> 작업 -> `dev-down.ps1`
- 백엔드/파이프라인 실행: `run-backend.ps1`, `pipeline.ps1`
- short-user 평가셋 재생성/감사: `python scripts/expand_short_user_dataset.py`
- 기본/임의 평가셋 출처 검증: `python scripts/verify_eval_dataset_origin.py --dataset-id <dataset_id>`

---

## Notes
- 구조 또는 책임이 바뀌면 이 문서를 갱신
