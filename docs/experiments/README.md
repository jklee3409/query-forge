# Experiments

`docs/experiments/`는 Query-Forge의 실험 설계, 모니터링, 최신 리포트, rewrite 사례 분석을 보관합니다. 이 디렉터리의 문서는 단순 결과 모음이 아니라, dataset/snapshot/retriever/rewrite/anchor 조건을 어떻게 통제해야 하는지 설명하는 연구 기록입니다.

## 문서 목록

| 문서 | 역할 |
| --- | --- |
| `dataset_design.md` | Corpus/Synthetic/Human eval dataset 설계와 split, grounding 원칙을 설명합니다. |
| `monitoring_trace.md` | 온라인 trace 저장 구조와 운영 점검 절차를 설명합니다. |
| `langfuse_event_schema.md` | Langfuse tracing schema, required tags/metadata, sampling, quota guardrail을 정의합니다. |
| `langfuse_dashboard_template.md` | RAG quality와 performance를 함께 보는 dashboard template입니다. |
| `canonical_anchor_backfill_dry_run.md` | canonical anchor mapping dry-run, manual review, version pinning, no-overwrite policy를 설명합니다. |
| `first_baseline_template.md` | 첫 baseline 결과를 기록하기 위한 템플릿입니다. |
| `latest_report.md` | 최근 retrieval/RAG metric, category, latency 요약입니다. |
| `latest_answer_report.md` | 최근 answer-level metric 요약입니다. |
| `best_rewrite_cases.md` | rewrite 채택 후 개선된 사례를 정리합니다. |
| `bad_rewrite_cases.md` | rewrite 채택 후 악화된 사례를 정리합니다. |

## 자동 갱신 문서

`latest_report.md`, `latest_answer_report.md`, `best_rewrite_cases.md`, `bad_rewrite_cases.md`는 pipeline 또는 분석 스크립트 실행 결과로 갱신될 수 있습니다. 현재 작업 트리에 이 파일들이 이미 수정되어 있을 수 있으므로, 문서 정리 작업과 실험 산출물 갱신을 섞지 않는 것이 안전합니다.

## 관련 artifact

`data/reports/`에는 retrieval summary/detail, answer summary/detail, latency, audit, rewrite case 산출물이 저장됩니다. 실험 문서는 이 artifact를 사람이 읽을 수 있는 형태로 해석합니다. 공식 결론을 낼 때는 하나의 변수만 바꾸고, dataset과 snapshot을 고정하며, retrieval metric과 answer metric, latency/cost context를 함께 확인해야 합니다.
