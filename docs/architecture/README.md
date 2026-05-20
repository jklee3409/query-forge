# Architecture Docs

`docs/architecture/`는 시스템 구조와 실행 흐름을 설명하는 문서를 보관한다.

## 문서 목록

- `overview.md`: 현재 저장소의 전체 구조와 구현 범위 요약
- `corpus_storage.md`: PostgreSQL `corpus_*` 스키마와 import 관계 설명
- `pipeline_orchestration.md`: Spring Boot가 Python pipeline을 실행하고 추적하는 방식 설명
- `domain_pipeline_integration_design.md`: 기술 문서 도메인 기반으로 corpus, synthetic query, gating, anchor, eval/RAG 이력을 분리하는 통합 설계

## 읽는 순서

전체 개요가 필요하면 `overview.md`부터 읽는다. DB 구조가 필요하면 `corpus_storage.md`, 관리자 실행 구조가 필요하면 `pipeline_orchestration.md`, 도메인 기반 통합 개편이 필요하면 `domain_pipeline_integration_design.md`를 읽는다.
