# progress.md

## Overview
High-level progress tracking for the project.

---

## [2026-04-13] Session Summary
- What was done: 프런트엔드 페이지/컴포넌트/API 유틸 구조를 정리해 디렉토리 문서를 생성했다.
- Key decisions: 구현 범위를 `pages`, `components`, `lib` 3축으로 정리했다.
- Issues encountered: `node_modules`는 의존성 산출물로 문서 구조 대상에서 제외했다.
- Next steps: 화면 추가/삭제 또는 API 계층 변경 시 구조 문서를 즉시 갱신한다.

---

## [2026-04-13] Session Summary (Gating Result UX)
- What was done: Updated `GatingPage.jsx` to add method filter (`A/B/C/D`) for per-query gating result lookup and added pagination controls for result browsing.
- Key decisions: Adopted offset pagination with fixed page size (`20`) and `hasNext` detection from `limit = pageSize + 1` response rows.
- Issues encountered: Existing page text had mixed encoding artifacts; this session prioritized stable runtime behavior/API linkage over label cleanup.
- Next steps: Validate UI behavior with multi-page batches and confirm method filter interacts correctly with selected gating batch context.

## [2026-04-13] Session Summary (Gating Rule Ratio + Funnel Filter UX)
- What was done: Added Rule-stage Korean ratio input (`최소 한글 비중`), clarified token labels, and added funnel filter controls for `전체/A/B/C/D` in `GatingPage.jsx`.
- Key decisions: Kept the existing run form structure and injected the new parameter as `ruleMinKoreanRatio` without changing other payload contracts.
- Issues encountered: Funnel filter required separate state and apply action to avoid coupling with result-table pagination/filter lifecycle.
- Next steps: Run GUI verification for filter transitions and confirm funnel cards update correctly per method.

## [2026-04-14] Session Summary (RAG Snapshot Selection UX)
- What was done: Extended `RagPage.jsx` with `Gating Snapshot` selector and added `sourceGatingBatchId` to RAG test run payload.
- Key decisions: Snapshot candidates are filtered to completed batches with `sourceGatingRunId`, selected method compatibility, and effective preset compatibility.
- Issues encountered: When `gatingApplied=false`, stale non-`ungated` snapshot selection can fail validation; UI now computes effective preset and auto-clears invalid selection.
- Next steps: Smoke-test `Auto (latest matching)` and explicit snapshot reruns to confirm deterministic retrieval/answer comparison.

---

## Notes
- Keep this file concise
- Only record important changes
