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
- `src/pages/ChatPage.jsx`, `ChatSettingsPage.jsx`, `PipelinePage.jsx`, `SyntheticPage.jsx`, `GatingPage.jsx`, `RagPage.jsx`: 관리자 화면 페이지
- `src/components/AdminUi.jsx`, `Common.jsx`, `LlmJobsTable.jsx`: admin card/section/metric/dialog primitives and shared UI components
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
- Chat Settings now exposes an `Agentic Multi-Query` toggle backed by `chat_runtime_config.metadata.agenticMultiQueryEnabled`, and the live Chat runtime strip shows agentic on/off beside router on/off.
- `/` Chat now preserves structured API error metadata from `requestJson` and maps `GEMINI_SERVICE_UNAVAILABLE` responses from `/api/chat/ask` to the dedicated Gemini failure copy while leaving readiness/configuration and generic failure messages unchanged.
- Chat Settings now exposes a `Query Strategy Router 사용` toggle backed by `chat_runtime_config.metadata.routerEnabled`, and the live Chat runtime strip shows router on/off for demo verification.
- `/` Chat now presents the answer flow as a production-style result surface: spinner while asking, final LLM answer first, original and rewritten/final query focus cards, and disclosure sections for synthetic memory, anchor hints, rewrite candidates, and retrieved context using the same structured visual language as the Admin RAG detail modal.
- Chat ask failures are now sanitized on the frontend so backend stack/detail text is not shown to end users; readiness/configuration problems get a short actionable message and all other ask failures fall back to a generic retry message.
- Domain `Chat Settings` now paginates `Config Provenance` in three-card slices, adds page controls for recent history, and correctly spaces the Domain Readiness, Selected Snapshots, and Config Provenance sections by making the page container honor vertical gaps.
- Domain `Chat Settings` now gives the readiness/snapshot/provenance panels more vertical breathing room and renders Config Provenance as structured change cards with source pills, linked RAG run badges, and changed-field chips instead of a dense table row.
- Domain `Chat Settings` keeps runtime option labels in English and highlights each synthetic batch's accepted query count as a badge.
- Domain `Chat Settings` now displays `full gating`, shows generation strategy choices as concise labels such as `A안`, and keeps readiness/selected-snapshot status badges aligned with their section headings.
- Admin shell sidebar now scrolls independently on desktop and mobile widths, so domain workspace navigation remains reachable with long menus.
- Domain `Chat Settings` now uses compact Korean-labeled controls for chat mode, synthetic query batch snapshots, generation strategies, rewrite policy, and retrieval runtime selection.
- Domain `Chat Settings` can select multiple compatible completed snapshots for one chat config, saves `sourceGatingBatchIds`, and `/` Chat shows the selected snapshot count plus applied config arrays so operators can verify the live rewrite memory scope.
- Domain `Chat Settings` and `/` Chat now show per-domain readiness from the backend: active config, selected snapshot set/source gating runs, domain/strategy mismatch flags, accepted gated query and memory counts, chunk embedding materialization, active prompt binding, retrieval tuple, and blocking reasons. Live Chat disables `Ask` when rewrite-backed chat is not ready.
- Domain `Chat Settings` now exposes the live chat retrieval tuple: retrieval backend (`local` / `db_ann`), dense embedding model, retriever mode, candidate pool, and Dense/BM25/Technical fusion weights. The Chat page shows the active backend/model/mode so promoted Admin RAG retrieval settings are visible during live use.
- Domain `Chat Settings` now shows recent config provenance rows, including manual vs Apply-to-Chat source, operator, source RAG run ID, and changed field names for auditability.
- `/admin/rag-tests` completed run rows now expose `Apply to Chat`, which copies the selected RAG run into the persistent per-domain chat runtime config and opens a result modal with an `Edit Chat Settings` link so the copied values remain directly editable.
- `/` Chat surface now requires a domain selection, loads the persisted domain chat runtime config, and shows raw query, rewritten query, final query, applied config, rewrite candidates, retrieved chunks, and memory candidates. Domain workspace Admin navigation now includes `Chat Settings` for pinning live chat mode, generation strategies, completed snapshot, compact/detailed rewrite profile, anchor injection, session context, and retrieval sizing per domain.
- `/admin/rag-tests` eval dataset detail modal now uses the dataset name as the title and renders every query as structured cards. RAG run history loads the full list and supports first/previous/direct/next/last page movement; RAG run detail dropdowns show miss-target badges, latency metrics are displayed in seconds, and raw-query metric deltas use green/red emphasis.
- `/admin/rag-tests` now exposes rewrite-only LLM model selection and a runtime-driven rewrite profile selector. `compact_anchor` keeps the existing compact anchor path, while `detailed_intent` requests self-contained detailed query expansion before optional anchor injection.
- `/admin/pipeline` no longer renders the Anchor Eval section. `/admin/synthetic-queries` domain workspaces use domain source language for method availability (`en` -> A/B/C/D/E, `ko` -> F/G), and `/admin/rag-tests` detail query dropdowns show rewrite applied/skipped badges while de-duplicating repeated sample rows.
- `/admin/rag-tests` RAG run detail modal now uses the configured test name in the title instead of a shortened DB UUID, uses the shared custom searchable dropdown for `질의 분석 보기`, and no longer renders the sticky `최상단으로` action.
- `/admin/rag-tests` now supports deleting non-default evaluation datasets, trims noisy comparison-table helper text, removes Hallucination Rate/Answer Relevance from the detailed comparison metrics, and renders RAG run details one query at a time through a `질의 분석 보기` dropdown.
- `/admin/rag-tests` detail cards now keep rewrite-skipped samples' LLM rewrite candidates and recommended synthetic candidates visible by default, cap recommended-candidate tags to three, simplify canonical anchor metadata, remove the Rewrite Anchor Analysis Grounding column, and hide completed ETA `KST`/progress-count clutter.
- `/admin/rag-tests` now hydrates RAG runtime defaults from backend runtime options (`model_catalog.yml`), including threshold, retrieval/rerank Top-N, retriever mode defaults, candidate pool, and fusion weights instead of keeping separate frontend constants.
- `/admin/rag-tests` now initializes the selective rewrite threshold at `0.05`, aligned with backend/catalog defaults, and starts rewrite anchor injection disabled so anchors are opt-in grounding hints.
- `/admin/rag-tests` now describes the prompt-only synthetic-memory rewrite flow: raw retrieval baseline, synthetic memory lookup for LLM examples only, LLM final query generation, raw-vs-rewrite adoption, and final evaluation from one retrieval result set. The rewrite retrieval merge strategy control and payload field were removed.
- Domain Atlas now includes a Source Membership panel: registry rows expose a Sources action, selected domains show linked corpus sources, and operators can attach or detach existing sources through the domain Admin API.
- `/admin/domains/:domainKey/pipeline` now passes the selected `domainId` into dashboard/run history queries, source upsert/URL auto-registration, and pipeline execution payloads so pipeline operations stay scoped to the selected domain.
- `/admin/domains/:domainKey/*` workspace pages now wait for the selected domain summary and pass `domain_id` into Pipeline, Synthetic, Quality Gate, and RAG list/run calls. Legacy global Admin routes remain unscoped.
- `/admin/pipeline` Anchor 정규화 Dry-run now launches against the full active-anchor scope by default, ignoring the current Anchor table filters and omitting the previous 500-row limit while preserving manual review before approval.
- `/admin/pipeline` now has a Multi-source Anchor Tracker that auto-loads the latest relation-build run, summarizes status/counts/version/runtime policy/relation-type breakdown, polls only while a build is active, and provides a build/retry action without changing the backend relation-build contract.
- `/admin/pipeline` Anchor normalization history now exposes an `이력 삭제` action backed by the corpus admin delete API, with confirmation copy clarifying that already-applied canonical values are not reverted. Candidate row decisions now explain that `승인` is only available for safe `would_update` rows, while conflict/invalid rows must remain pending or be skipped.
- `/admin/pipeline` Anchor normalization detail modal now uses Korean-first review copy, fixes the previously corrupted title literal, shows workflow guidance and disabled approval reasons, renders decision summary badges, guards unsaved close, keeps the `변경 없음 표시` toggle aligned as a horizontal check-pill, and improves current/proposed/conflict table readability in dark mode.
- `/admin/pipeline` Anchors now includes a `Multi-source Build` action and relation build-history table for precomputing current active-anchor relationships without editing synthetic query data.
- `/admin/rag-tests` now includes a `multi-source hints` rewrite toggle that submits `multiSourceAnchorExpansionEnabled` only when rewrite anchor injection is active.
- `/admin/rag-tests` now filters generation strategy chips and completed snapshot options by both dataset scope and eval query language: English eval queries show E/F, while Korean eval queries show A/B/C/D/G where the dataset scope allows them.
- `/admin/pipeline` Anchors section now exposes `Anchor 정규화 Dry-run` plus review history/detail controls. The detail modal supports candidate-level `approve`/`skip` decisions, bulk decision save, and save-and-approve so the dry-run completes first and operators review all candidates afterward.
- `/admin/synthetic-queries` now makes the selected synthetic generation strategy and chunk sampling option more prominent. Strategy A/B/C/D/E source selection is scoped to the five Spring reference sources, F/G is scoped to `docs-python-org-ko-3-14`, and the all-allowed-sources option submits one batch request while backend/pipeline config applies the scoped `source_ids` filter. `arahansa-github-io-docs-spring` is hidden from the UI and excluded from request construction.
- `/admin/rag-tests` now shows selected comparison runs in a fixed bottom dock with pill-style selected items and clear/remove actions. RAG run detail disclosures render metric contribution, recommended synthetic memory candidates, rewrite candidates, and retrieved chunks as theme-aware structured cards instead of raw JSON blocks.
- `/admin/synthetic-queries` strategy cards now auto-slide overflowing generation-flow chips inside the card and consistently use `KR` labels. Strategy B is displayed as `EN Doc -> KR Doc -> KR Summary -> KR Query`, and strategy F as `KR Doc -> KR Summary -> KR Query -> EN Query`; this is a frontend-only display correction.
- `/admin/quality-gating` now uses a runtime-console information architecture instead of a single dense form. The page separates source/preset/model launch context, Rule/LLM/Utility/Diversity gate network, Retriever capabilities, and active config summary; utility scoring is grouped into Top-K Retrieval, Document Consistency, and Penalty/Bonus. The change is frontend-only and preserves existing gating request payload keys and backend API contracts.
- Admin shell now has an explicit production-grade theme system: `index.html` bootstraps system/default theme before React mounts, `App.jsx` persists `query-forge-theme` in localStorage, and the topbar exposes a Light/Dark toggle. `src/styles.css` defines centralized semantic tokens such as `background-primary`, `surface-elevated`, `surface-muted`, `border-subtle`, `text-primary`, `accent-primary`, and `accent-glow`.
- The visual system has been refreshed toward a calm AI operations console style. Dark mode now uses layered navy/graphite surfaces for cards, forms, controls, tables, modals, dropdowns, selected states, and pagination; native number/select/checkbox/radio/range controls are restyled through shared CSS rules. The sidebar uses refined spacing, mixed KR/EN developer-oriented naming, and a subtle `AI Ops Core` presence indicator.
- Admin UI polishing pass: `/admin/synthetic-queries` strategy cards now show only strategy code, active state, compact one-line flow, prompt version, and query count; verbose descriptions/tag clusters were removed. Shared selected-state tokens now prevent dark-mode light-background/white-text collisions across strategy selectors, dropdown options, segmented controls, check pills, source cards, compare checkboxes, and linked compare rows.
- Admin copy is now Korean-first across the shell and recently redesigned Synthetic/RAG/Gating/shared controls, using consistent terms such as 질의, 재작성, 검색, 배치, 전략, 스냅샷, 평가. Run actions use success styling and destructive actions use danger styling.
- `/admin/synthetic-queries` now renders generation methods as strategy cards with visual flow chips, prompt/status metadata, and quick query counts. Batch history now uses timeline-style job cards with progress bars, ETA, retry context, client-side search/filter/sort, and a confirm dialog for delete.
- `/admin/rag-tests` run creation now uses a sectioned Experiment Builder layout: Experiment Overview, Rewrite Strategy, Retrieval Config, collapsed Advanced Options, fusion balance bar, rewrite threshold slider, and a sticky run-summary preview. Existing request payload fields and validation rules are unchanged.
- Shared admin UI primitives were added in `src/components/AdminUi.jsx` for section headers, metric cards, strategy flows, progress metrics, batch cards, experiment sections, config summaries, balance bars, empty states, and confirm dialogs.
- `/admin/synthetic-queries` run form now resolves method dropdown from context-aware backend options (`/api/admin/console/synthetic/methods?source_id=...&source_document_id=...`), so Spring sources expose `A~E` and Python KR source exposes `F/G` only.
- `/admin/rag-tests` 실행 상세 모달은 샘플별 `원본 질의`와 `최종 재작성 합성 질의`를 기본 화면에 우선 노출하고, 나머지 지표/후보/청크 로그는 드롭다운(Disclosure)으로 접어서 확인하는 구조로 재구성되었다.
- Admin pages now use section-scoped lazy loading for secondary data areas: `/admin/pipeline` Anchors, `/admin/synthetic-queries` LLM jobs, `/admin/quality-gating` LLM jobs, and `/admin/rag-tests` rewrite logs + LLM jobs are fetched on demand instead of at initial mount.
- `/admin/pipeline` now includes a paginated `Anchors` section with document/chunk/keyword filtering, and document/chunk filters use a custom searchable dropdown UI (`SelectDropdown`) instead of native select boxes.
- `/admin/rag-tests` now has a `Test Name` input wired to backend `runName`, defaults `Retrieval Top-K` to `10`, and shows configured/legacy-stable names in run history and comparison UI instead of relying on generic Run A/Run B labels.
- `/admin/rag-tests` retriever controls now use fixed mode presets: dense model is read-only (`intfloat/multilingual-e5-small`), Dense Required/Hash Fallback/Cohere Rerank checkboxes are removed, candidate pool is fixed at `50`, and mode weights are BM25 `0/1/0`, Dense `1/0/0`, Hybrid `0.60/0.32/0.08`.
- `/admin/rag-tests` now exposes `Eval Query Language` so the same runtime can evaluate Korean or English dataset variants, and dataset preview resolves `userQueryEn` when the selected dataset is English.
- Admin shell synthetic-page copy now reflects `A/B/C/D/E` strategy coverage while the strategy picker itself remains DB-driven.
- `/admin/synthetic-queries` batch history now shows generation ETA context (`generated/target`, `sec per query`, `ETA`, LLM job/item state) and supports terminal-batch delete action.
- Admin UI now uses reusable ETA primitives (`src/lib/eta.js`, `src/components/RemainingEta.jsx`) to render remaining-time projections consistently across Synthetic batch history, Gating batch history, RAG run history, and LLM async job tables.
- `/admin/rag-tests` and `/admin/quality-gating` now expose retriever ranking controls for BM25 Only, Dense Only, and Hybrid modes, including dense model, dense-required/fallback, rerank, candidate-pool, and fusion-weight settings.
- Admin retriever controls default to Hybrid + `intfloat/multilingual-e5-small`, dense required, hash fallback disabled, and Cohere rerank enabled so generated experiments are reproducible by ranking mode.
- `/admin/rag-tests` now defaults rewrite threshold to `0.10` and single-run detail shows a raw-vs-query-rewrite/synthetic-memory comparison table when `raw_only` is present; synthetic-free baseline detail remains baseline-only.
- `/admin/rag-tests` compare workspace now applies consistent duration conversion in cards and summary values (`ms -> s -> m+s`), removes raw ms subtext from workspace cards, and uses KST-based compact time display (`YYYY-MM-DD HH:mm`) via shared `fmtTime`.
- `/admin/rag-tests` detailed comparison table was additionally tuned for dense backoffice analysis: stronger typography hierarchy, tighter row spacing, centered `Delta / Change` + `Result` judgment alignment, and consistent `ms -> s -> m+s` display formatting for performance metrics.
- RAG 실험 비교 워크스페이스의 상단 run 카드, winner summary, metric 카드 UI를 판단 중심으로 재정렬하고, 성능 지표를 `ms`/`s` 자동 변환 + 해석형 delta(`x faster/slower`, `% change`)로 표시하도록 개선했다.
- RAG 상세 비교 테이블을 섹션 인지형(`Retrieval/Answer/Performance`)으로 재구성하고, 해석형 Delta/Result 칩, KPI 강조 행, 가독성 높은 숫자 포맷(천 단위 + ms/s 병기)을 적용해 실험 간 판단 속도를 개선했다.
- Gating result view supports `methodCode` filter and `limit/offset` pagination UI.
- Admin status badges now support `warning` tone for pipeline runs/steps to distinguish partial-success executions from full-success.
- Gating run Rule stage supports configurable Korean-ratio input (`ruleMinKoreanRatio`).
- RAG run form supports snapshot-based evaluation through `sourceGatingBatchId`, listing all completed snapshots and validating source run/preset/method compatibility at run-time.
- `/admin/rag-tests` now supports explicit retrieval backend selection (`local` / `db-ann`), shows chunk-embedding readiness for the selected dense model, and can trigger chunk-embedding materialization before a `db-ann` run.
- RAG run form now distinguishes `official` vs `exploratory` discipline, with official bundled comparison controls (`gating_effect` / `rewrite_effect`) and explicit snapshot identity payloads.
- RAG run detail now renders the redesigned Performance section from run-level latency metrics only: `avg_query_eval_total_latency_ms`, `avg_final_rewrite_latency_ms`, `avg_pure_rewrite_latency_ms`, with per-metric sample-count basis.
- RAG run detail now renders DB-backed Rewrite Anchor Analysis rows and run-level Anchor Quality cards; comparison tables include an Anchor Quality metric group with precision, grounded/risky rates, supported rewrite rate, and useful/risky/unsupported counts.
- Legacy RAG results that do not contain the new latency payload are rendered with a guarded fallback message (`Legacy result (new latency metrics unavailable)`) instead of `NaN`/`undefined` values.
- RAG page now includes option-meaning helper text, snapshot-method deduplication lock, and two-run visual comparison charts for quality/performance test review.
- RAG compare area now uses the same three latency metrics in both overview cards and the integrated quality/performance comparison table.
- RAG run form supports `Synthetic-free baseline` mode that disables snapshot/method/gating/rewrite controls and sends baseline-only payload fields to backend.
- Global admin theme/layout has been refreshed to a modern production-style dashboard aesthetic via `App.jsx` + `styles.css`.
- Gating funnel summary cards support method-based filtering (`전체 + DB 등록 전략 코드`).

---

## Notes
- Update this file when structure or responsibilities change
