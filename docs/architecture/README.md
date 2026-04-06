# Architecture Docs

`docs/architecture/`는 시스템 저장 구조와 실행 흐름을 설명하는 문서를 담는다.

## 문서 목록

- `overview.md`
  - 현재 저장소의 전체 구조와 구현 범위 요약
- `corpus_storage.md`
  - PostgreSQL `corpus_*` 스키마와 import 계층 설명
- `pipeline_orchestration.md`
  - Spring Boot가 Python 파이프라인을 실행·추적하는 방식 설명

## 읽는 순서

전체 개요가 필요하면 `overview.md`부터, DB 구조가 필요하면 `corpus_storage.md`, 관리자 실행 구조가 궁금하면 `pipeline_orchestration.md`를 읽으면 된다.
