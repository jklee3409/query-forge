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
- `/admin/rag-tests` now defaults rewrite threshold to `0.10` and single-run detail shows a raw-vs-query-rewrite/synthetic-memory comparison table when `raw_only` is present; synthetic-free baseline detail remains baseline-only.
- `/admin/rag-tests` compare workspace now applies consistent duration conversion in cards and summary values (`ms -> s -> m+s`), removes raw ms subtext from workspace cards, and uses KST-based compact time display (`YYYY-MM-DD HH:mm`) via shared `fmtTime`.
- `/admin/rag-tests` detailed comparison table was additionally tuned for dense backoffice analysis: stronger typography hierarchy, tighter row spacing, centered `Delta / Change` + `Result` judgment alignment, and consistent `ms -> s -> m+s` display formatting for performance metrics.
- RAG 실험 비교 워크스페이스의 상단 run 카드, winner summary, metric 카드 UI를 판단 중심으로 재정렬하고, 성능 지표를 `ms`/`s` 자동 변환 + 해석형 delta(`x faster/slower`, `% change`)로 표시하도록 개선했다.
- RAG 상세 비교 테이블을 섹션 인지형(`Retrieval/Answer/Performance`)으로 재구성하고, 해석형 Delta/Result 칩, KPI 강조 행, 가독성 높은 숫자 포맷(천 단위 + ms/s 병기)을 적용해 실험 간 판단 속도를 개선했다.
- Gating result view supports `methodCode` filter and `limit/offset` pagination UI.
- Gating run Rule stage supports configurable Korean-ratio input (`ruleMinKoreanRatio`).
- RAG run form supports snapshot-based evaluation through `sourceGatingBatchId`, listing all completed snapshots and validating source run/preset/method compatibility at run-time.
- RAG run form now distinguishes `official` vs `exploratory` discipline, with official bundled comparison controls (`gating_effect` / `rewrite_effect`) and explicit snapshot identity payloads.
- RAG run detail now exposes retrieval per-mode payload (`retrieval_by_mode`) instead of relying only on single collapsed summary values.
- RAG page now includes option-meaning helper text, snapshot-method deduplication lock, and two-run visual comparison charts for quality/performance test review.
- RAG compare chart now uses vertical metric cards, and test-history compare selection uses labeled custom checkbox controls for clearer state visibility.
- RAG compare area now includes an integrated quality + performance table (delta view) using run-level metrics (`metrics_json.performance`).
- RAG run form supports `Synthetic-free baseline` mode that disables snapshot/method/gating/rewrite controls and sends baseline-only payload fields to backend.
- Global admin theme/layout has been refreshed to a modern production-style dashboard aesthetic via `App.jsx` + `styles.css`.
- Gating funnel summary cards support method-based filtering (`전체/A/B/C/D`).

---

## Notes
- Update this file when structure or responsibilities change
