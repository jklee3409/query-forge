# index.md

## Directory Overview
아키텍처, API, UI, 실험 결과를 정리하는 프로젝트 문서 디렉토리입니다.

---

## Structure
- `README.md`: 문서 디렉토리 개요
- `api/README.md`, `api/admin_pipeline_api.md`, `api/corpus_admin_api.md`, `api/rag_api.md`: HTTP API 문서
- `architecture/README.md`, `architecture/overview.md`, `architecture/corpus_storage.md`, `architecture/pipeline_orchestration.md`: 시스템 구조 문서
- `ui/README.md`, `ui/admin_backoffice.md`: 관리자 UI 문서
- `experiments/README.md` 및 `latest_report.md`, `latest_answer_report.md`, `dataset_design.md`, `monitoring_trace.md`, `best_rewrite_cases.md`, `bad_rewrite_cases.md`, `first_baseline_template.md`: 실험 설계/결과 문서

---

## Responsibilities
- 구현 상태를 설명 가능한 형태로 문서화
- API/아키텍처/실험 근거를 한 곳에서 관리
- 연구 결과 재현 및 추적을 위한 보고 체계 제공

---

## Key Flows
- 기능 구현 변경 시 대응 문서(architecture/api/ui)를 업데이트
- 실험 실행 후 `experiments/`에 결과 리포트 누적
- 운영/검증 관점에서 문서 간 교차 참조를 유지

---

## Notes
- Update this file when structure or responsibilities change

---

## [2026-04-15] Additions
- `report/`: RAG 품질 테스트 비교 리포트 저장 경로 (`rag_quality_ac_comparison_short_user_2026-04-15.md`, raw metric snapshot JSON).
