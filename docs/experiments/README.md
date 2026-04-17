# Experiments

`docs/experiments/`는 실험 설계 근거, 자동 리포트, 리라이트 사례 분석 문서를 모아 둔 디렉터리다.

## 문서 목록

- `dataset_design.md`
  - Corpus/Synthetic/Human eval 데이터셋 설계와 split 근거
- `latest_report.md`
  - retrieval 지표 요약, 카테고리별 표, latency 표
- `latest_answer_report.md`
  - answer-level 지표 요약
- `bad_rewrite_cases.md`
  - rewrite 채택 후 성능이 악화된 사례
- `best_rewrite_cases.md`
  - rewrite 채택 후 성능이 개선된 사례
- `monitoring_trace.md`
  - 온라인 trace 저장 구조 및 운영 점검 절차
- `langfuse_event_schema.md`
  - Langfuse tracing schema (required tags/metadata), sampling policy, and quota guardrails
- `first_baseline_template.md`
  - 첫 baseline 결과 기록 템플릿

## 자동 생성 파일

아래 문서는 파이프라인 실행 시 자동 갱신된다.

- `latest_report.md`
- `latest_answer_report.md`
- `bad_rewrite_cases.md`
- `best_rewrite_cases.md`

## 관련 산출물 경로

- `data/reports/retrieval_summary_*.json|csv`
- `data/reports/retrieval_by_category_*.csv`
- `data/reports/latency_*.csv`
- `data/reports/answer_summary_*.json|csv`
