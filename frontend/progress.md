# progress.md

## Overview
High-level progress tracking for the project.

## [2026-04-28] Session Summary (RAG Test Default Preset Re-tune for Baseline-Comparable Ops)
- What was done: Updated `/admin/rag-tests` default form state in `RagPage.jsx` so new runs start with `retrieverMode=bm25_only` and `rewriteThreshold=0.14` (`threshold` field), with fallback preset normalization defaulting to BM25 mode.
- Key decisions: Kept existing fixed-mode preset behavior/read-only retriever weights and changed only the initial defaults to match current operator comparison strategy.
- Issues encountered: None.
- Next steps: Validate one new default run from GUI and confirm payload contains BM25-only config plus updated threshold.

## [2026-04-28] Session Summary (Gating Batch History Preset+Retriever Badge Tags)
- What was done: Updated `/admin/quality-gating` batch history table to render preset as icon-style tag badges that include both `gatingPreset` and retriever mode (`retrieverMode`) instead of plain preset text.
- Key decisions: Reused existing token-badge visual language to keep consistency with stage/reason chips and avoid new component churn.
- Issues encountered: Existing JSX literal `->` warnings remain from unrelated option labels; build still completes successfully.
- Next steps: UI-smoke recent gating history rows and confirm badge tooltips/labels correctly show `preset` and `retriever`.

## [2026-04-28] Session Summary (English Eval Language Controls + E Strategy Copy)
- What was done: Updated Admin RAG form state/payload to include `evalQueryLanguage`, auto-default it from dataset key (`*_en` => `en`), and show English dataset preview text when dataset items expose `userQueryEn/queryLanguage`. Updated admin shell copy so synthetic generation subtitle now reflects `A/B/C/D/E`.
- Key decisions: Kept method selection UI dynamic from backend method rows instead of hardcoding strategy buttons; the only explicit strategy-label change in RAG was removing the static `A/B/C/D` suffix from the selection label.
- Issues encountered: Existing `GatingPage.jsx` JSX `->` warnings are still present and unrelated to this task.
- Next steps: Operator-smoke one English dataset selection path and confirm preview/run payloads show `evalQueryLanguage=en`.

## [2026-04-21] Session Summary (RAG Test Names + Fixed Retriever Presets)
- What was done: Updated `/admin/rag-tests` with a `Test Name` input, changed default `retrieval_top_k` from `20` to `10`, and replaced editable dense/fallback/rerank retriever toggles with mode-driven fixed presets. Rebuilt the production React bundle into backend static resources.
- Key decisions: Dense model is always read-only (`intfloat/multilingual-e5-small`); BM25/Dense/Hybrid selection now auto-applies candidate pool `50`, Cohere rerank off, hash fallback off, and mode-specific weights (`0/1/0`, `1/0/0`, `0.60/0.32/0.08`). RAG compare/history labels prefer configured test names and use stable legacy fallback names for old auto labels.
- Issues encountered: Vite still warns about existing literal `->` option labels in `GatingPage.jsx`; build succeeds.
- Next steps: UI-smoke named BM25/Dense/Hybrid runs and confirm compare cards/table headers use the configured names.

## [2026-04-21] Session Summary (Admin Retriever Mode Controls)
- What was done: Added retriever mode controls to `/admin/rag-tests` and `/admin/quality-gating`, including BM25/Dense/Hybrid mode, dense model, dense-required/fallback toggles, Cohere rerank toggle, candidate pool, and fusion weights. Rebuilt the production React bundle into backend static resources.
- Key decisions: Kept the controls close to existing retrieval/utility configuration rather than redesigning page flow. Defaults match backend/pipeline reproducibility settings: Hybrid, `intfloat/multilingual-e5-small`, dense required, fallback off, rerank on.
- Issues encountered: Vite still warns about existing literal `->` option labels in `GatingPage.jsx`; build succeeds.
- Next steps: UI-smoke Admin run creation for one BM25, one Dense, and one Hybrid experiment and compare generated YAML.

## [2026-04-20] Session Summary (RAG Detail Raw-vs-Rewrite View)
- What was done: Updated `/admin/rag-tests` form default `rewrite_threshold` to `0.10` and added a single-run detail comparison table that shows `raw_only` next to the selected query-rewrite or synthetic-memory mode.
- Key decisions: Synthetic-free baseline detail remains baseline-only; synthetic-backed runs show same-run raw vs synthetic mode metrics plus rewrite diagnostics when available.
- Issues encountered: Existing unrelated `GatingPage.jsx` JSX warnings for literal `->` labels still appear during build.
- Next steps: UI-smoke a newly completed synthetic rewrite run and verify the modal comparison table makes raw/rewrite deltas obvious.

## [2026-04-19] Session Summary (RAG Compare Workspace Duration Conversion Fix + Time UX)
- What was done: Corrected compare-workspace duration display in `RagPage.jsx` so performance metrics consistently render with converted values (`<1000ms: ms`, `<60000ms: s`, `>=60000ms: m+s`) and removed raw pre-conversion ms subtext from workspace metric cards.
- Key decisions: Kept API/data/delta calculation unchanged; switched workspace duration formatters to reuse table-grade conversion helpers and applied the same policy to top summary cards (`Latest Total Duration`, `Latest Avg Latency`) and run-history metric summary text.
- Issues encountered: Existing unrelated `GatingPage.jsx` warnings remain outside this scope.
- Next steps: Validate visual readability for long-running datasets and tune wording density in compare cards if additional performance metrics are added.

## [2026-04-19] Session Summary (RAG Detailed Table: Typography + Delta/Result Alignment)
- What was done: Further refined only the detailed compare table in `RagPage.jsx`/`styles.css` for backoffice scanning: larger readable text hierarchy, reduced row/cell vertical padding, section header spacing cleanup, centered `Delta / Change` and `Result` judgment columns, and stronger KPI row readability.
- Key decisions: Preserved existing metric extraction and delta computation; improved presentation layer only. Duration-like values now use the same table display policy (`ms` under 1000, `s` over 1000, `m+s` over 60000) for A/B values and delta detail text.
- Issues encountered: Existing unrelated JSX warning lines in `GatingPage.jsx` remain out of scope.
- Next steps: Validate dense-table readability on real operator screens and tune section/header spacing if additional metrics are added.

## [2026-04-19] Session Summary (Compare Workspace Metric Card Readability Upgrade)
- What was done: Updated `RagPage.jsx` compare workspace card UI to improve one-glance A/B judgment: run info card readability, winner summary latency interpretation, and section metric cards with decision-first hierarchy.
- Key decisions: Added workspace-only formatter helpers for duration/unit conversion and interpreted delta semantics (`x faster/slower`, `% higher/lower`, `No change`) without changing metric source/calculation.
- Issues encountered: Existing project contains unrelated warnings in `GatingPage.jsx`; this task touched only compare workspace rendering and styles.
- Next steps: Validate card density at operator screen sizes and calibrate ratio threshold (`x` vs `%`) if team prefers stricter semantics.

## [2026-04-19] Session Summary (RAG Detailed Compare Table: Section-Aware Decision UX)
- What was done: Refactored `RagPage.jsx` detailed comparison table rendering to section-aware grouped bodies (`Retrieval/Answer/Performance`), comparison-focused columns (`구분/지표/Run A/Run B/Delta-Change/Result`), interpreted delta text (`▲/▼ + magnitude + meaning`), result chips, KPI badge emphasis, and readable run header labels.
- Key decisions: Kept existing `extractRunMetrics`, metric definitions, and delta formula (`B - A`, `deltaRate`) unchanged; added presentation helpers (`table number format`, `delta/result formatter`, `group summary`) and compare-table-only CSS enhancements in `styles.css`.
- Issues encountered: Existing app has unrelated JSX warnings in `GatingPage.jsx` from literal `->` option text; this task did not modify that page.
- Next steps: UI-smoke with real run pairs on `/admin/rag-tests` and fine-tune delta/result copy (KR/EN) based on operator preference.

## [2026-04-19] Session Summary (Gating Result Filter Label Realignment)
- What was done: Updated `GatingPage.jsx` result filter option labels to explicitly reflect stage-transition semantics: `Rule 통과 -> LLM 탈락`, `LLM 통과 -> Utility 탈락`, `Utility 통과 -> Diversity 탈락`, `Diversity 통과 -> Final 탈락`, while keeping `Rule 탈락` and `전체 통과`.
- Key decisions: Changed labels only; `pass_stage` values and filter wiring remain unchanged to avoid API coupling/regression.
- Issues encountered: None.
- Next steps: Confirm operator readability and optionally rename `전체 통과` to `Final 통과` for stricter wording consistency.

## [2026-04-19] Session Summary (Quality Gating Result Filter: All Stage Pass Options)
- What was done: Expanded `GatingPage.jsx` per-query result filter UI to expose full stage options for quality gating (`전체`, `탈락`, `Rule 통과`, `LLM 통과`, `Utility 통과`, `Diversity 통과`, `전체 통과`) and wired selected value to backend `pass_stage` query parameter.
- Key decisions: Reused existing result-filter lifecycle (`loadResults`, paging, apply filter) and injected only one additional field (`resultFilter.passStage`) to keep UI state change minimal.
- Issues encountered: Existing page includes mixed-encoding legacy text blocks, so modifications were limited to stable filter form and request parameter lines.
- Next steps: Validate UX copy consistency and decide whether to add tooltip/help text clarifying stage-sequential semantics (`passed_utility` implies rule+llm pass).

## [2026-04-18] Session Summary (RAG Quality+Performance Unified Compare UI)
- What was done: Extended `RagPage.jsx` metric extraction to read `metrics_json.performance` and added an integrated quality+performance comparison table (left/right/delta) for two selected runs.
- Key decisions: Kept existing quality bar chart for visual quick-scan and added tabular comparison for latency/overhead values that do not fit normalized 0-1 bars.
- Issues encountered: Existing localized text blocks include mixed encoding, so edits were focused on stable metric parsing/render paths.
- Next steps: Operator smoke-test with two completed runs to verify table rows for `total_duration_ms`, stage durations, and rewrite-overhead latency.

## [2026-04-18] Session Summary (Gating Top10 Control + Nested Payload)
- What was done: Updated `GatingPage.jsx` to expose `Target Top10 점수` input and switched gating run request body to nested `config` payload (`stageFlags/ruleConfig/gatingWeights/utilityScoreWeights/thresholds`).
- Key decisions: Kept existing control layout/default values and added only the Top10 score control required for utility stage tuning.
- Issues encountered: None.
- Next steps: Run admin GUI gating smoke with custom Top10 value and verify batch detail `stage_config_json` reflects the submitted payload.

## [2026-04-17] Session Summary (Synthetic Admin UX Cleanup + Checkbox Refresh)
- What was done: Reworked `SyntheticPage.jsx` run form for operator clarity: removed unused `소스 문서 버전`, renamed `최대 생성 질의` to `생성 개수`, changed random chunk option from checkbox to segmented `청크 선택 방식`, and fixed LLM model input to non-editable `gemini-2.5-flash-lite`.
- Key decisions: Kept backend API contract compatible by still sending `randomChunkSampling` (derived from UI mode) and forcing `llmModel` from frontend constant.
- Issues encountered: Existing admin pages contain mixed-encoding text regions, so functional edits were prioritized and validated through production build.
- Next steps: Run admin synthetic generation smoke for A/C/D (`생성 개수=1000`, random mode) and verify operator readability feedback on the new controls.

## [2026-04-17] Session Summary (Admin Checkbox Visual Unification)
- What was done: Updated global admin styles (`styles.css`) and control markup (`Common.jsx`, `RagPage.jsx`) to unify checkbox/toggle appearance with `check-pill` and `toggle-switch` patterns.
- Key decisions: Left existing behavior/validation logic intact while only changing visual controls and interaction affordance.
- Issues encountered: None.
- Next steps: Extend the same control pattern to remaining list-selection checkboxes if additional admin screens adopt binary controls.

## [2026-04-17] Session Summary (Synthetic Generator Random Sampling UX)
- What was done: Updated `SyntheticPage.jsx` run form/state/payload with `randomChunkSampling` and added a `Random Chunk Sampling` checkbox in Admin synthetic generation controls.
- Key decisions: Kept existing method-scoped run model (A/C/D individually), retained no-limit behavior when `limitChunks` is empty, and preserved max query input upper bound at `2000`.
- Issues encountered: Existing page has mixed-encoding localized labels, so edits were kept minimal around stable form blocks.
- Next steps: GUI smoke-test for `A/C/D` runs with `max_total_queries=1000`, no chunk limit, and random sampling enabled.

## [2026-04-17] Session Summary (RAG Stage-Cutoff Controls)
- What was done: Updated `RagPage.jsx` to expose stage-cutoff run controls (`Stage Cutoff` toggle + `Stage Cutoff Level`), added payload wiring (`stageCutoffEnabled`, `stageCutoffLevel`), and adjusted source snapshot option handling for full-gating cutoff mode.
- Key decisions: Snapshot compatibility check now uses `full_gating` when stage-cutoff is enabled, while existing official run and gating-effect flows remain unchanged.
- Issues encountered: Mixed-encoding text regions in the page required scoped edits focused on behavior and request wiring.
- Next steps: UI smoke-test for exploratory stage-cutoff run creation with/without source snapshot and confirm expected validation messages.

## [2026-04-17] Session Summary (RAG Synthetic-free Baseline Controls)
- What was done: Added `Synthetic-free baseline` toggle to `RagPage.jsx`, wired request payload field `syntheticFreeBaseline`, and applied UI guards to disable snapshot/method/gating/rewrite controls in baseline mode.
- Key decisions: Baseline mode now sends `methodCodes=[]`, `gatingApplied=false`, and `rewriteEnabled=false` to align with backend/pipeline synthetic-free execution semantics.
- Issues encountered: `RagPage.jsx` already had pending history-delete related edits in the same region, so baseline updates were merged without reverting unrelated changes.
- Next steps: Validate operator workflow for baseline on/off switching and confirm run history labels clearly expose baseline vs snapshot runs.

## [2026-04-15] Session Summary (RAG Compare Visualization + Selection UX)
- What was done: Refactored `RagPage.jsx` comparison chart from horizontal bar rows to vertical metric cards and replaced run-compare checkbox cells with a labeled custom selector (`선택` / `선택됨`).
- Key decisions: Kept metric source and compare-run behavior unchanged while improving scanability for side-by-side run review.
- Issues encountered: Existing default checkbox had low visual affordance in dense table context; custom control now shows explicit selected state text and colored check box.
- Next steps: Validate mobile/table readability with long run lists and confirm operator preference on vertical chart density.

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

## [2026-04-14] Session Summary (Snapshot List Visibility Fix)
- What was done: Updated `RagPage.jsx` so snapshot dropdown lists all completed gating batches, instead of hiding rows by current preset/method filter.
- Key decisions: Preserved safety by adding pre-submit validation (`sourceGatingRunId` 존재 여부 + `preset/method` 호환성) while exposing non-runnable/ incompatible snapshots with suffix labels.
- Issues encountered: Snapshot list freshness was limited to initial page load; added polling and refresh-path updates to reload gating batch rows.
- Next steps: Verify with real data that completed snapshot count in dropdown matches `/api/admin/console/gating/batches` response count.

## [2026-04-14] Session Summary (RAG UX + Backoffice Redesign)
- What was done: Rebuilt `RagPage.jsx` layout with clearer option semantics, snapshot/method deduplication logic, and a two-run comparison chart section for practical experiment review.
- Key decisions: When snapshot has a fixed `methodCode`, method selection is auto-locked to prevent redundant conflicting input; compatibility checks are enforced at submit-time.
- Issues encountered: Existing option labels made runtime behavior unclear, so field-level helper text now maps UI controls to actual experiment config keys (`rewrite_threshold`, `retrieval_top_k`, `rerank_top_n`).
- Next steps: Collect operator feedback on compare-chart readability and iterate metric set prioritization.

## [2026-04-14] Session Summary (Global Admin Visual Refresh)
- What was done: Updated `App.jsx` metadata/navigation labels and rebuilt `styles.css` theme for a modern production-style backoffice look and feel.
- Key decisions: Kept component/class contracts stable so Pipeline/Synthetic/Gating pages inherit visual improvements without API or layout logic regressions.
- Issues encountered: Needed to balance stronger visual identity with existing table-heavy workflows, so spacing/contrast/typography were tuned for dense operational screens.
- Next steps: Validate mobile sidebar behavior and table readability at smaller breakpoints with real admin traffic patterns.

## [2026-04-14] Session Summary (Official RAG Comparison Controls + Per-Mode Exposure)
- What was done: Extended `RagPage.jsx` with official run controls (`runDiscipline`, `officialComparisonType`), bundled gating snapshot selectors (`ungated/rule_only/full_gating`), request payload wiring (`comparisonGatingBatchIds`), and detail modal exposure for `retrieval_by_mode`.
- Key decisions: In official `gating_effect`, legacy single snapshot selector is disabled and dedicated three-snapshot bundle is enforced in UI before submit.
- Issues encountered: Existing file had mixed-encoding history, so controls were inserted with minimal surface-area changes and then validated by production build.
- Next steps: Operator smoke-test for official `rewrite_effect` and `gating_effect` workflows and verify explicit failure messages for missing/incompatible snapshots.

---

## Notes
- Keep this file concise
- Only record important changes
