# index.md

## Directory Overview
Codex 에이전트 작업 규칙과 세션 운영 기준을 관리하는 디렉토리입니다.

---

## Structure
- `AGENTS.md`: 프로젝트 전용 에이전트 제약, 파이프라인 순서, 문서화 규칙
- `index.md`: `.codex` 디렉토리 역할과 구조 문서
- `progress.md`: `.codex` 관련 변경 이력 요약

---

## Responsibilities
- 에이전트가 따라야 할 연구/구현 제약을 명시
- 파이프라인 고정 순서와 전략 분리 원칙(A/B/C/D)을 강제
- 세션별 문서화 규칙(루트/디렉토리 progress 추적) 제공

---

## Key Flows
- 작업 시작 시 `AGENTS.md`를 읽고 제약사항을 확인
- 코드 변경 시 해당 디렉토리 `index.md`, `progress.md` 동시 갱신
- 루트 `progress.md`에 주요 의사결정과 결과를 누적 기록

---

## Notes
- Update this file when structure or responsibilities change
