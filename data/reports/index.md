# index.md

## Directory Overview
`data/reports/`는 pipeline과 보조 스크립트가 생성하는 평가 요약과 audit 산출물을 저장합니다.

## Structure
- `README.md`: 리포트 디렉터리 개요
- `python_kr_eval_dataset_80_audit_2026-05-12.json`: Python KR KO/EN short-user 80 평가셋 생성 audit
- `spring_method_compressed_eval_80_audit_2026-05-20.json`: Spring A/B/C/D/E method-compressed stress eval 생성 audit
- `spring_kr_rewrite_challenge_30_audit_2026-06-01.json`: Spring KR rewrite challenge 30 생성 audit
- `spring_kr_rewrite_probe_c_9_audit_2026-06-01.json`: Spring KR C-memory rewrite probe 9 생성 audit
- `rewrite_challenge_80_ko_audit_2026-06-01.json`: Spring/PostgreSQL/Kubernetes KO rewrite challenge 80 생성 audit
- `answer_*`, `retrieval_*`: RAG 실행별 answer/retrieval 결과 산출물

## Responsibilities
- 평가 데이터셋 생성과 실험 실행의 검증 결과를 보존합니다.
- 같은 dataset/snapshot 조건에서 성능과 품질 지표를 추적할 수 있게 합니다.

## Notes
- 대량 실행 결과는 기본적으로 ignore되며, 재현성에 필요한 기준 리포트만 `.gitignore` 예외로 관리합니다.
