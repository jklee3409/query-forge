# index.md

## Directory Overview
`scripts/`는 로컬 개발/운영 반복 작업을 자동화하는 보조 스크립트 모음이다.

---

## Structure
- `README.md`: 스크립트 사용 개요
- `bootstrap-local.ps1`: 로컬 부트스트랩(환경 준비, corpus import, 실행 보조)
- `dev-up.ps1`: Docker(Postgres) 실행
- `dev-down.ps1`: Docker 리소스 정리
- `run-backend.ps1`: Spring Boot 실행
- `pipeline.ps1`: `pipeline/cli.py` 실행 래퍼
- `import_corpus.sh`: Linux/macOS corpus import 보조
- `expand_short_user_dataset.py`: short-user 80문항 재생성/감사 + DB 반영
- `verify_eval_dataset_origin.py`: eval dataset 출처 검증(build-eval-dataset vs synthetic 중복)
- `rebuild_short_user_dataset_from_synthetic.py`: synthetic 후보 랜덤 샘플 기반 short-user 80문항 재구성 + DB/JSONL/리포트 반영

---

## Responsibilities
- 반복 실행 절차를 표준화한다.
- 로컬 실행 및 점검 흐름을 단순화한다.
- 데이터셋/리포트 관리용 운영성 스크립트를 제공한다.

---

## Key Flows
- 초기 세팅: `bootstrap-local.ps1`
- 개발 시작/종료: `dev-up.ps1` -> 작업 -> `dev-down.ps1`
- 백엔드/파이프라인 실행: `run-backend.ps1`, `pipeline.ps1`
- short-user 확장/감사: `python scripts/expand_short_user_dataset.py`
- eval dataset 출처 검증: `python scripts/verify_eval_dataset_origin.py --dataset-id <dataset_id>`
- synthetic 랜덤 재구성: `python scripts/rebuild_short_user_dataset_from_synthetic.py --dataset-id <dataset_id>`

---

## Notes
- 구조나 책임이 바뀌면 이 문서를 함께 갱신한다.

