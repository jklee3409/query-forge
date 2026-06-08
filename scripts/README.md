# Scripts

`scripts/`는 로컬 개발, pipeline 실행, 평가 데이터셋 생성/검증, rewrite challenge 분석을 반복 가능하게 만드는 보조 스크립트 디렉터리입니다. PowerShell 스크립트는 개발 환경 실행을 돕고, Python 스크립트는 DB와 JSONL artifact를 함께 다루는 데이터셋/평가 보조 작업을 담당합니다.

## 로컬 실행 스크립트

| 스크립트 | 역할 |
| --- | --- |
| `bootstrap-local.ps1` | Docker PostgreSQL, Python 환경, corpus import, backend 실행 준비를 한 번에 처리합니다. |
| `dev-up.ps1`, `dev-down.ps1` | 로컬 Docker 리소스를 시작하고 정리합니다. |
| `run-backend.ps1` | Spring Boot backend를 실행합니다. |
| `pipeline.ps1` | `pipeline/cli.py` 명령 실행을 PowerShell에서 보조합니다. |
| `import_corpus.sh` | Unix shell 환경에서 corpus import를 호출하는 보조 wrapper입니다. |

## Eval Dataset 생성/수리

| 스크립트 | 역할 |
| --- | --- |
| `expand_short_user_dataset.py`, `rebuild_short_user_dataset_from_synthetic.py` | Spring short-user dataset을 확장하거나 synthetic 후보 기반으로 재구성합니다. |
| `audit_short_user_dataset.py`, `refine_short_user_dataset.py` | Spring short-user dataset의 grounding을 감사하고 수동 정제 결과를 JSONL/DB에 반영합니다. |
| `build_short_user_en_dataset.py` | Spring KR short-user 80의 영어 companion dataset을 생성합니다. |
| `build_python_kr_eval_datasets.py` | Python Korean docs domain의 KO/EN paired short-user dataset을 생성합니다. |
| `build_kubernetes_eval_datasets.py` | Kubernetes KO/EN paired short-user dataset을 생성합니다. |
| `build_anchor_translated_eval_datasets.py` | Spring/PostgreSQL KO anchor-translated short-user dataset을 생성합니다. |
| `build_method_compressed_eval_datasets.py` | A/B/C/D/E accepted synthetic query에서 Spring method-compressed stress eval dataset을 생성합니다. |
| `repair_spring_short_user_eval_pair.py`, `repair_postgresql_kubernetes_eval_pairs.py` | paired KR/EN dataset의 grounding을 in-place로 수리합니다. |
| `audit_eval_grounding_strictness.py`, `verify_eval_dataset_origin.py` | eval dataset의 출처와 chunk grounding을 검증합니다. |

## Rewrite Challenge와 Probe

| 스크립트 | 역할 |
| --- | --- |
| `build_spring_rewrite_challenge_dataset.py` | Spring KR V6 grounding 기반 rewrite challenge/probe dataset을 생성합니다. |
| `build_rewrite_challenge_eval_datasets.py` | Spring/PostgreSQL/Kubernetes KO rewrite challenge 80 dataset을 생성합니다. |
| `build_rewrite_challenge_en_eval_datasets.py`, `sync_final_rewrite_challenge_en_from_kr.py` | KO rewrite challenge와 대응되는 EN companion dataset을 생성/동기화합니다. |
| `rewrite_challenge_retrieval_probe.py` | rewrite challenge query surface의 raw DB-ANN hit@5를 probe합니다. |
| `rewrite_challenge_memory_probe.py` | synthetic memory target overlap을 strategy별로 probe합니다. |
| `rewrite_case_candidate_probe.py` | rewrite case report candidate를 expected eval target 기준으로 점수화합니다. |
| `apply_rewrite_challenge_calibration.py` | rewrite challenge calibration 결과를 적용하는 보조 스크립트입니다. |

## 운영 원칙

DB를 쓰는 스크립트는 입력 dataset, target dataset id/key, source domain, expected chunk 검증 결과를 명확히 남겨야 합니다. 가능하면 `--skip-db`나 dry-run 경로를 제공하고, write 작업 전에는 대상 row scope를 좁혀 확인합니다. 새 스크립트를 추가하면 이 README와 `scripts/index.md`에 목적, 입력/출력, DB write 여부를 함께 기록합니다.
