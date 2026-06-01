# index.md

## Directory Overview
`scripts/`는 로컬 개발, pipeline 실행, 데이터셋 생성/검증 같은 반복 작업을 자동화하는 보조 스크립트 디렉터리입니다.

## Structure
- `README.md`: 스크립트 사용 개요
- `bootstrap-local.ps1`: 로컬 bootstrap 보조
- `dev-up.ps1`, `dev-down.ps1`: Docker 개발 환경 시작/종료
- `run-backend.ps1`: backend 실행
- `pipeline.ps1`: pipeline CLI 실행 wrapper
- `expand_short_user_dataset.py`: Spring short-user dataset 재생성/확장
- `rebuild_short_user_dataset_from_synthetic.py`: synthetic 후보 기반 short-user dataset 재생성
- `build_short_user_en_dataset.py`: Spring 영어 short-user companion dataset 생성
- `build_python_kr_eval_datasets.py`: Python KR KO/EN paired short-user dataset 생성
- `build_kubernetes_eval_datasets.py`: Kubernetes KO anchor-translated / EN paired short-user dataset 생성
- `build_anchor_translated_eval_datasets.py`: Spring/PostgreSQL KO anchor-translated short-user dataset 생성
- `build_spring_rewrite_challenge_dataset.py`: Spring KR V6 grounding을 복사해 영어/API anchor가 제거된 rewrite challenge/probe dataset 생성
- `build_method_compressed_eval_datasets.py`: A/B/C/D/E accepted synthetic queries to compressed Spring stress eval datasets
- `repair_spring_short_user_eval_pair.py`: paired Spring KR/EN short-user eval dataset in-place grounding repair
- `repair_postgresql_kubernetes_eval_pairs.py`: paired PostgreSQL/Kubernetes KR/EN short-user eval dataset in-place grounding repair
- `audit_eval_grounding_strictness.py`: Spring/PostgreSQL/Kubernetes KR/EN eval pair strict grounding audit
- `audit_short_user_dataset.py`: Spring short-user dataset 구조/grounding 감사
- `refine_short_user_dataset.py`: Spring short-user dataset 수동 정제 + DB/JSONL 동기화
- `verify_eval_dataset_origin.py`: eval dataset 출처/grounding 검증

## Responsibilities
- 반복 실행 절차를 표준화합니다.
- 평가 데이터셋과 DB 등록을 재현 가능한 형태로 제공합니다.
- 운영자가 같은 입력 조건으로 artifact를 다시 만들 수 있게 합니다.

## Key Flows
- Spring short-user 영어 companion 생성: `python scripts/build_short_user_en_dataset.py`
- Python KR KO/EN paired dataset 생성: `python scripts/build_python_kr_eval_datasets.py`
- Kubernetes KO anchor-translated / EN paired dataset 생성: `python scripts/build_kubernetes_eval_datasets.py`
- Spring/PostgreSQL KO anchor-translated dataset 생성: `python scripts/build_anchor_translated_eval_datasets.py`
- Spring KR rewrite challenge dataset 생성: `python scripts/build_spring_rewrite_challenge_dataset.py --variant challenge_30`
- Spring KR C-memory rewrite probe dataset 생성: `python scripts/build_spring_rewrite_challenge_dataset.py --variant probe_c_9`
- Spring method-compressed stress eval dataset generation: `python scripts/build_method_compressed_eval_datasets.py`
- Spring paired KR/EN short-user grounding repair: `python scripts/repair_spring_short_user_eval_pair.py`
- PostgreSQL/Kubernetes paired KR/EN short-user grounding repair: `python scripts/repair_postgresql_kubernetes_eval_pairs.py`
- Spring/PostgreSQL/Kubernetes strict grounding audit: `python scripts/audit_eval_grounding_strictness.py`
- Spring short-user dataset 감사: `python scripts/audit_short_user_dataset.py`
- Spring short-user dataset 정제: `python scripts/refine_short_user_dataset.py --pre-audit-report <audit_report>`
- eval dataset 검증: `python scripts/verify_eval_dataset_origin.py --dataset-id <dataset_id>`

## Notes
- 새 스크립트가 DB를 수정하면 `--skip-db` 같은 안전 옵션이 필요한지 검토합니다.
