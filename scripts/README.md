# Scripts

`scripts/`는 로컬 개발과 운영 반복 작업을 보조하는 스크립트 모음입니다. PowerShell 기반 개발 흐름과 Python 데이터셋 보조 작업을 함께 둡니다.

주요 스크립트는 다음과 같습니다.

- `bootstrap-local.ps1`: Docker PostgreSQL, Python 환경, corpus import, backend 실행 준비를 한 번에 처리합니다.
- `dev-up.ps1` / `dev-down.ps1`: 로컬 개발용 Docker 리소스를 시작하고 정리합니다.
- `run-backend.ps1`: Spring Boot backend를 실행합니다.
- `pipeline.ps1`: `pipeline/cli.py` 명령 실행을 보조합니다.
- `expand_short_user_dataset.py`: 기존 short-user 평가셋을 corpus chunk 기반으로 재생성하고 DB와 리포트를 갱신합니다.
- `rebuild_short_user_dataset_from_synthetic.py`: synthetic 후보를 기반으로 short-user 80문항을 재구성합니다.
- `build_short_user_en_dataset.py`: 기존 Spring short-user 80문항의 영어 companion dataset을 생성합니다.
- `build_python_kr_eval_datasets.py`: 한글 Python 문서 도메인의 KO/EN paired short-user 80 평가셋을 생성하고 DB에 등록합니다.
- `build_kubernetes_eval_datasets.py`: Kubernetes 문서 도메인의 KO/EN paired short-user 80 평가셋을 생성하고 DB에 등록합니다.
- `build_anchor_translated_eval_datasets.py`: 기존 Spring/PostgreSQL KR short-user 80문항을 유지한 채 영어 anchor를 한국어로 의도 번역한 별도 평가셋을 생성하고 DB에 등록합니다.
- `build_rewrite_challenge_eval_datasets.py`: Spring/PostgreSQL/Kubernetes grounded short-user 80을 복사해 영어/API anchor가 제거된 별도 KO rewrite challenge dataset을 생성하고 DB에 등록합니다.
- `build_rewrite_challenge_en_eval_datasets.py`: KO rewrite challenge dataset의 한글 질의만 영어로 번역해 1대1 paired EN companion dataset을 생성하고 DB에 등록합니다.
- `verify_eval_dataset_origin.py`: 지정한 eval dataset의 출처와 chunk grounding을 점검합니다.

새 스크립트를 추가할 때는 실행 목적, 입력/출력, DB 쓰기 여부를 이 문서와 `index.md`에 함께 기록합니다.
