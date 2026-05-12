# Reports

`data/reports/`는 평가 실행 요약, audit 결과, retrieval/answer 세부 결과처럼 실험 해석에 필요한 산출물을 저장하는 디렉터리입니다. 대부분의 대용량 실행 결과는 local artifact로 다루지만, 기준 데이터셋 생성 audit처럼 재현성에 직접 필요한 파일은 명시적으로 보존합니다.

현재 Python KR 평가셋 생성 결과는 `python_kr_eval_dataset_80_audit_2026-05-12.json`에 기록되어 있습니다. 이 파일은 한글/영어 각 80개 sample 수, single/multi 분포, 중복 및 grounding 검증 결과를 담습니다.
