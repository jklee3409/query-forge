# index.md

## Directory Overview
관리자 콘솔용 React + Vite 프런트엔드 구현 디렉토리입니다.

---

## Structure
- `package.json`, `package-lock.json`, `vite.config.js`, `eslint.config.js`: 프런트엔드 빌드/개발 설정
- `index.html`: Vite 엔트리 HTML
- `public/favicon.svg`, `public/icons.svg`: 정적 자산
- `src/main.jsx`: 앱 부트스트랩
- `src/App.jsx`: 페이지 조합/라우팅 진입
- `src/pages/ChatPage.jsx`, `PipelinePage.jsx`, `SyntheticPage.jsx`, `GatingPage.jsx`, `RagPage.jsx`: 관리자 화면 페이지
- `src/components/Common.jsx`, `LlmJobsTable.jsx`: 공통 UI 컴포넌트
- `src/lib/api.js`, `format.js`, `hooks.js`: API 호출/포맷/커스텀 훅 유틸
- `src/styles.css`: 전역 스타일

---

## Responsibilities
- 관리자 기능용 웹 UI 제공
- 백엔드 admin/rag API와 연동해 실행 상태 및 결과를 시각화
- 공통 컴포넌트와 API 유틸을 통해 화면 로직을 재사용

---

## Key Flows
- 앱 시작: `main.jsx` -> `App.jsx`
- 페이지 단위로 API 호출(`lib/api.js`) 후 표/패널 컴포넌트 렌더링
- 파이프라인/합성/게이팅/RAG 화면에서 실행 결과를 조회 및 표시
- 빌드 결과는 백엔드 정적 리소스로 배포 가능

---

## Recent Update
- Gating result view supports `methodCode` filter and `limit/offset` pagination UI.
- Gating run Rule stage supports configurable Korean-ratio input (`ruleMinKoreanRatio`).
- RAG run form supports snapshot-based evaluation through `sourceGatingBatchId`, listing all completed snapshots and validating source run/preset/method compatibility at run-time.
- RAG page now includes option-meaning helper text, snapshot-method deduplication lock, and two-run visual comparison charts for quality/performance test review.
- Global admin theme/layout has been refreshed to a modern production-style dashboard aesthetic via `App.jsx` + `styles.css`.
- Gating funnel summary cards support method-based filtering (`전체/A/B/C/D`).

---

## Notes
- Update this file when structure or responsibilities change
