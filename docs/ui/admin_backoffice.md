# Admin Backoffice

현재 백오피스는 React 단일 앱 기반으로 동작하며, backend API를 호출해 pipeline 운영과 실험 실행을 수행한다.

## 목적

- 수집/정제/청킹/용어 추출/적재 파이프라인을 웹에서 제어
- synthetic generation, quality gating, RAG 테스트를 같은 운영 콘솔에서 반복 실행
- corpus/anchor/실험 결과를 조회하고 이슈를 빠르게 추적

## 화면 목록

- `/admin/pipeline`
  - 파이프라인 실행/재시도/취소, run 로그, 문서/청크/용어/앵커/anchor-eval 운영
- `/admin/synthetic-queries`
  - A/B/C/D/E 전략 synthetic 배치 실행, 목록/상세 조회
- `/admin/quality-gating`
  - 게이팅 배치 실행, 퍼널/결과 조회, method/batch 필터링
- `/admin/rag-tests`
  - 스냅샷 선택, retriever/rewrite 조건 설정, RAG 테스트 실행/비교

## UI 설계 포인트

- 좌측 내비게이션 + 상단 메타 헤더 + 작업 영역 구조
- run/status 중심 데이터 테이블과 상세 모달 조합
- 긴 ID/UUID는 축약 표시와 상세 drill-down 제공
- 파이프라인/게이팅/RAG는 비교 중심 카드와 표를 함께 사용

## 상호작용

- 모든 실행 액션은 JSON API 호출 후 toast 알림과 리스트 갱신으로 피드백 제공
- 진행 중 상태(`queued/running`)는 polling으로 자동 갱신
- 필터/선택 상태는 페이지 컨텍스트별 로컬 상태로 유지
- RAG 비교 화면은 quality + performance 지표를 동시 표시

## 현재 제한

- 모바일 최적화보다 desktop-first 운영 콘솔에 초점을 맞춤
- 실시간 로그 스트리밍은 SSE/WebSocket이 아니라 polling 기반
- legacy 경로(`/admin/sources`, `/admin/runs`, `/admin/experiments` 등)는 `/admin/pipeline`으로 리다이렉트된다
