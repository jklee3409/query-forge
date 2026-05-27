# progress.md

## [2026-05-26] Session Summary (RAG Rewrite Defaults)
- What was done: Updated `/admin/rag-tests` defaults so the runtime-hydrated threshold is `0.05`, anchor injection starts disabled, non-selective rewrite displays as raw-only instead of rewrite-always, and rebuilt the production React bundle.
- Key decisions: Kept the existing RAG request payload shape and Gemini model selection unchanged; backend config generation is the source of truth for banning `rewrite_always` in final evaluation runs.
- Issues encountered: Targeted `npx eslint src/pages/RagPage.jsx --quiet` and `npm run build` passed.
- Next steps: Browser-smoke a fresh RAG form load after rebuilding/serving the updated Admin bundle.

## [2026-05-26] Session Summary (RAG Dataset Language and Snapshot Filter)
- What was done: Updated `/admin/rag-tests` so selecting an EN dataset such as `postgresql_en_short_user_80` sets `evalQueryLanguage=en`, filters method chips by that language, and hides gating snapshots whose method language is incompatible.
- Key decisions: Replaced the old `datasetKey.endsWith('_en')` heuristic with dataset `queryLanguage` plus a safer key/name fallback, and reused the same method-language rule for method selection, snapshot dropdowns, and snapshot compatibility labels.
- Issues encountered: Targeted `npx eslint src/pages/RagPage.jsx --quiet` passed. Full `npm run lint -- --quiet` still fails on the pre-existing `vite.config.js` `process` global issue.
- Next steps: Browser-smoke PostgreSQL KR/EN dataset switching once the backend serving `/api/admin/console/rag/datasets` has been restarted.

## [2026-05-26] Session Summary (RAG Anchor Quality UI)
- What was done: Added structured Anchor Evaluation sections to RAG run detail, including per-detail anchor tables and run-level anchor quality cards, and added Anchor Quality as a separate comparison metric group.
- Key decisions: UI reads normalized backend anchor rows/summaries and renders guarded empty states for legacy runs instead of dumping JSON.
- Issues encountered: Targeted `npm exec eslint -- src/pages/RagPage.jsx` passed with the existing two hook dependency warnings.
- Next steps: Browser-smoke a completed RAG run with anchor rows and an old run without rows in light/dark mode.

## [2026-05-25] Session Summary (RAG Runtime Defaults Hydration)
- What was done: Changed `/admin/rag-tests` form hydration so threshold, retrieval Top-K, rerank Top-N, retriever mode defaults, candidate pool, and fusion weights come from backend runtime options instead of local hardcoded defaults.
- Key decisions: The form starts with empty values until runtime options load; omitted request values are handled by backend catalog defaults, keeping GUI and server behavior aligned.
- Issues encountered: ESLint initially treated `useServerDefault` as a React hook; helper was renamed and targeted lint passed with existing hook dependency warnings.
- Next steps: Browser-smoke the RAG form to confirm the catalog defaults display correctly after load.

## [2026-05-25] Session Summary (RAG Rewrite Threshold Default)
- What was done: Changed `/admin/rag-tests` form initialization so the selective rewrite threshold displays `0.02` by default and rebuilt the production React bundle served by the backend.
- Key decisions: Kept the existing slider/input range and request payload field unchanged; the default now matches backend/catalog policy.
- Issues encountered: Targeted ESLint passed with existing `RagPage.jsx` hook dependency warnings. `npm run build` passed.
- Next steps: Browser-smoke a fresh RAG form load to confirm the default threshold is visible.

## [2026-05-25] Session Summary (RAG History Method and Duration Display)
- What was done: Changed `/admin/rag-tests` history generation tags to show the method code first (`A method`, `C method`) and added a completed-run elapsed display mode to `RemainingEta` that renders KST start time above actual duration.
- Key decisions: Scoped the elapsed display to RAG history by opt-in props so Synthetic/Gating/LLM job ETA rendering keeps existing behavior.
- Issues encountered: Targeted ESLint passed with the existing `RagPage.jsx` hook dependency warnings. `npm run build` passed.
- Next steps: Visual-smoke completed and running RAG rows in the Spring domain workspace at smaller widths.

## [2026-05-20] Session Summary (RAG Rewrite Settings Simplification)
- What was done: Simplified `/admin/rag-tests` advanced rewrite settings by removing the rewrite retrieval merge strategy selector and request payload field.
- Key decisions: Added operator copy explaining that synthetic memory is only LLM rewrite context, is not searched or merged directly, and final metrics use either raw retrieval or adopted rewritten-query retrieval.
- Issues encountered: No frontend build/lint was run because the task explicitly disallowed broad build/test work; static grep and diff checks confirmed the removed payload/control references.
- Next steps: Browser-smoke the RAG form later to verify the simplified explanation fits the current layout.

## [2026-05-20] Session Summary (Admin Entry Button)
- What was done: Updated the chat surface Admin console button to navigate to `/admin` instead of `/admin/pipeline`.
- Key decisions: Kept all Admin workspace routes and page components unchanged; `/admin` remains the Domain Atlas entry page.
- Issues encountered: Targeted `npm exec eslint -- src/pages/ChatPage.jsx` and `npm run build` passed.
- Next steps: Smoke-test the button from `http://localhost:5173/`.

## [2026-05-20] Session Summary (Domain Source Membership UI)
- What was done: Added a Domain Atlas Source Membership panel that loads domain details, lists linked corpus sources, and supports attaching/detaching sources through the domain Admin API.
- Key decisions: Kept the map node click as workspace navigation, and added a separate Sources action in the domain registry list to avoid accidental navigation while editing memberships.
- Issues encountered: `npm exec eslint -- src/pages/DomainHomePage.jsx` passed. `npm run build` passed and refreshed backend-served React assets.
- Next steps: Browser-smoke the membership panel with migrated domain/source data.

## [2026-05-20] Session Summary (Domain Scoped Pipeline Calls)
- What was done: Updated `/admin/domains/:domainKey/pipeline` calls so dashboard, run history, source creation, URL auto-registration, and pipeline execution include the selected `domainId`.
- Key decisions: Legacy global `/admin/pipeline` remains unscoped by sending `domainId: null`, while domain workspace payloads now stay inside the selected technical-document domain.
- Issues encountered: Targeted `npm exec eslint -- src/pages/PipelinePage.jsx` passed with the existing hook-dependency warning. `npm run build` passed and refreshed backend-served React assets.
- Next steps: Add attach/detach controls for existing corpus sources on the domain home/workspace UI.

## [2026-05-20] Session Summary (Domain Scoped Workspace Calls)
- What was done: Passed the selected domain summary `domainId` from the Admin shell into Pipeline, Synthetic, Gating, and RAG pages. Domain workspace pages now attach `domain_id` to scoped list APIs and include `domainId` in Synthetic/Gating/RAG run payloads.
- Key decisions: Domain pages wait for the domain summary before mounting operation pages, avoiding an initial unscoped fetch in the workspace route. Legacy global routes remain unscoped.
- Issues encountered: Targeted ESLint passed with existing hook-dependency warnings. `npm run build` passed and refreshed backend-served React assets.
- Next steps: Add GUI controls for attaching existing/new corpus sources to a newly created empty domain.

## [2026-05-20] Session Summary (Domain Workspace and Prompt Studio Shell)
- What was done: Replaced `/admin` entry with a Domain Atlas page, added domain workspace routes under `/admin/domains/:domainKey/*`, added a selected-domain banner, and implemented `/admin/prompts` Prompt Studio for shared A-G/rewrite prompt bindings.
- Key decisions: Existing Pipeline/Synthetic/Gating/RAG pages are reused under the domain workspace first; strict domain API filtering remains a separate wiring phase.
- Issues encountered: Targeted ESLint for changed frontend files passed. `npm run build` passed and refreshed backend-served React assets.
- Next steps: Inject `domain_id` into existing Admin API calls and backend execution requests.

## [2026-05-20] Session Summary (RAG Rewrite Threshold Default)
- What was done: Updated `/admin/rag-tests` form initialization so the rewrite threshold defaults to `0.05`, matching the backend Admin RAG default and relaxed short-user adoption profile.
- Key decisions: UI request shape and existing rewrite/multi-source toggles were kept unchanged.
- Issues encountered: No frontend build was run; the change is a single default-value update.
- Next steps: Browser-smoke a new RAG form load to confirm the displayed threshold matches backend defaults.

## [2026-05-20] Session Summary (Anchor Normalization Full-Scope Launch)
- What was done: Updated `/admin/pipeline` Anchor 정규화 Dry-run launch so it confirms and submits a full active-anchor request instead of reusing current anchor filters or sending `limit=500`.
- Key decisions: Kept the review modal, candidate decisions, approval, rejection, and history deletion behavior unchanged.
- Issues encountered: `npx eslint src/pages/PipelinePage.jsx` passed with the existing hook dependency warning. `npm run build` passed and refreshed backend-served React assets.
- Next steps: Browser-smoke the full-scope dry-run button and use `anchor-normalize-7d079b88` as the verified full-scope history row.

## [2026-05-19] Session Summary (Multi-source Anchor Tracker)
- What was done: Reworked the `/admin/pipeline` multi-source anchor history block into a tracker that loads the latest build automatically, shows status/count/version/runtime-policy summaries, renders relation-type breakdown chips, polls every 15 seconds only while a build is active, and exposes build/retry controls.
- Key decisions: Kept existing corpus admin APIs and RAG multi-source hint settings unchanged. The tracker uses the already persisted build-run summary payload and avoids continuous polling after completion for low-spec laptops.
- Issues encountered: `npx eslint src/pages/PipelinePage.jsx` passed with the existing hook dependency warning. `npm run build` passed and regenerated backend-served React assets.
- Next steps: Browser-smoke the tracker on `/admin/pipeline` with the completed build run and verify the failure retry button path if a failed run is later present.

## Overview
High-level progress tracking for the project.

## [2026-05-19] Session Summary (Anchor Normalization History Delete UI)
- What was done: Added an `이력 삭제` action to `/admin/pipeline` Anchor normalization history and wired it to the backend delete endpoint with a confirmation message explaining that applied canonical values are not reverted. Candidate decision cells now show inline guidance for why conflict/invalid rows do not expose `승인`.
- Key decisions: Kept approval/rejection/save APIs unchanged. The approve option remains available only for `would_update` candidates because backend validation applies only approved safe changes.
- Issues encountered: `npx eslint src/pages/PipelinePage.jsx` passed with the existing hook dependency warning.
- Next steps: Browser-smoke the history table actions and the row decision helper text in dark mode with pending/conflict/invalid candidates.

## [2026-05-19] Session Summary (Anchor Normalization Review Modal UX)
- What was done: Reworked the Anchor normalization detail/review modal in `PipelinePage.jsx` with Korean-first copy, fixed the corrupted title literal, added operator guidance, status/decision label mapping, workflow sections, approval-disabled reason text, summary badges, safer reject/reset handling, corrected the `변경 없음 표시` check-pill layout, and compact code-style current/proposed values with conflict explanations.
- Key decisions: Kept the existing review/approve/reject API contract unchanged and handled readability in the React layer. Long anchor values now use ellipsis with full values in `title`, preserving the actual API data instead of hiding broken text.
- Issues encountered: Local backend API was unavailable on 8080/8081, so live response encoding could not be sampled. `npx eslint src/pages/PipelinePage.jsx src/lib/format.js` passed with the pre-existing hook dependency warning in `PipelinePage.jsx`.
- Next steps: Smoke-test the modal with a real pending normalization run in dark mode and rebuild the backend-served static bundle only when a frontend build is permitted.

## [2026-05-19] Session Summary (Multi-source Anchor Admin Controls)
- What was done: Added a `Multi-source Build` action and build-history table to `/admin/pipeline` Anchors, plus a `multi-source hints` toggle in `/admin/rag-tests`. Rebuilt the production React bundle served by the backend.
- Key decisions: UI calls backend relation-build APIs only and does not edit anchors or synthetic queries client-side. RAG submission enables multi-source hints only when rewrite and anchor injection are both enabled.
- Issues encountered: Targeted `npx eslint src/pages/PipelinePage.jsx src/pages/RagPage.jsx` passed with only pre-existing hook dependency warnings; `npm run build` passed and refreshed backend static assets.
- Next steps: Browser-smoke the Anchors build button after V33 is applied and verify the RAG run detail/tags show the multi-source setting.

## [2026-05-19] Session Summary (RAG Method Language Filtering)
- What was done: Updated `/admin/rag-tests` method chips and snapshot filtering so English eval queries expose English synthetic methods (`E/F`) and Korean eval queries expose Korean/code-mixed methods (`A/B/C/D/G`) within the selected dataset scope.
- Key decisions: Mirrored backend validation in the UI and kept existing dataset-scope logic, snapshot selectors, and request payload fields unchanged.
- Issues encountered: `npx eslint src/pages/RagPage.jsx` passed with the existing two hook dependency warnings; `npm run build` refreshed the backend-served React bundle.
- Next steps: Browser-smoke dataset/language switching to confirm selected methods and snapshot lists update immediately.

## [2026-05-19] Session Summary (Anchors Normalization Dry-Run UI)
- What was done: Added `Anchor 정규화 Dry-run` action to `/admin/pipeline` Anchors section and added a normalization history table with detail, approve, and reject controls.
- Key decisions: UI calls backend review APIs only; it does not perform client-side normalization or direct DB updates. Approval is disabled when a run reports conflict/invalid candidates.
- Issues encountered: Targeted `npx eslint src/pages/PipelinePage.jsx` passed with 0 errors and 1 pre-existing hook dependency warning.
- Next steps: Browser-smoke the flow after V32 is applied to the target DB.

## [2026-05-19] Session Summary (Admin RAG Canonical Anchor Detail)
- What was done: Updated `/admin/rag-tests` detail rendering so memory candidate cards display canonical anchor metadata when present: canonical form, alias/normalized alias, confidence, resolution status, language, term type, canonical term ID, and scoring-vs-review counts.
- Key decisions: UI consumes existing backend detail payloads only; no new runtime option lists, merge/review actions, or client-side canonical mapping rules were added.
- Issues encountered: Targeted `npx eslint src/pages/RagPage.jsx` completed with 0 errors and the existing 2 hook dependency warnings.
- Next steps: Browser-smoke a completed RAG run containing `canonical_anchors` metadata to tune density if needed.

## [2026-05-15] Session Summary (Synthetic All-Allowed Single Submit)
- What was done: Changed `/admin/synthetic-queries` generation submit logic so the "all allowed sources" option builds one request body instead of mapping allowed sources into multiple parallel POSTs. Selected source/document runs still submit a single scoped request.
- Key decisions: Kept the existing source allowlist UI and hints, but moved all-sources scoping responsibility to the backend `source_ids` config instead of frontend fan-out.
- Issues encountered: `npm run build` passed and regenerated backend static assets. `npx eslint src/pages/SyntheticPage.jsx` passed; full `npm run lint` remains blocked by the existing `vite.config.js` `process` no-undef and unrelated hook warnings.
- Next steps: Browser-smoke B strategy with "all allowed sources" and confirm one toast/batch/job is created.

## [2026-05-13] Session Summary (RAG Compare Readability Polish)
- What was done: Updated `/admin/rag-tests` comparison UI so top summary cards omit verbose meta text, latency fast/slow results get direct tone coloring, detailed comparison table sections are separated by spacer rows and group-tinted borders, and dark-mode secondary text tokens are brighter.
- Key decisions: UI/CSS-only refinement plus small JSX rendering changes. Existing metric extraction, delta math, run selection, API calls, and backend contracts were preserved.
- Issues encountered: `npm run build` passed and regenerated backend-served React assets.
- Next steps: Browser-smoke dark-mode compare with two completed runs and verify `답변 품질` remains visually distinct from `검색 품질` and `성능`.

## [2026-05-13] Session Summary (Pipeline Monitor Execution Toolbar Spacing)
- What was done: Updated `/admin/pipeline` so the execution-control toolbar has an explicit top margin below the source picker, and changed the 전체 실행 button to `button--success` to match the topbar `Run Retrieval Eval` action color.
- Key decisions: UI-only adjustment in `PipelinePage.jsx` and `styles.css`; the existing `triggerPipeline('full_ingest')` API flow and other stage buttons were preserved.
- Issues encountered: `npm run build` passed and regenerated backend-served React assets.
- Next steps: Browser-smoke the Pipeline Monitor at desktop and narrower widths to confirm the new spacing remains balanced.

## [2026-05-13] Session Summary (Synthetic Strategy Flow Slider + KR Label Fix)
- What was done: Updated `/admin/synthetic-queries` strategy flow labels so `KO` is consistently shown as `KR`, corrected B to `EN Doc -> KR Doc -> KR Summary -> KR Query`, and corrected F to `KR Doc -> KR Summary -> KR Query -> EN Query`. Strategy flow chips now measure overflow and automatically slide within the card when the full pipeline exceeds the available width.
- Key decisions: Frontend display-only change. Synthetic method IDs, backend method metadata, request payloads, generation strategy semantics, and pipeline logic were unchanged.
- Issues encountered: None during implementation.
- Next steps: Visual-smoke strategy cards at desktop and narrow widths to confirm overflow flows remain readable without manual horizontal scrolling.

## [2026-05-13] Session Summary (Admin Sidebar Spacing + AI Ops Core Emphasis)
- What was done: Increased the sidebar nav icon-to-label gap, enlarged the PF/SQ/GT/RG-style icon boxes slightly, and amplified the `AI Ops Core` presence block with a larger signal tile, stronger glow, richer surface, and clearer typography.
- Key decisions: CSS-only frontend polish. No route, API, payload, or runtime behavior changed.
- Issues encountered: `npm run build` passed and refreshed backend static React assets.
- Next steps: Visual-smoke the sidebar at desktop width and verify long labels still fit after the wider icon/text spacing.

## [2026-05-13] Session Summary (Quality Gating Runtime Context Spacing Polish)
- What was done: Refined the `/admin/quality-gating` Runtime Context panel spacing by introducing grouped context sections (`Source Selection`, `Strategy Runtime`) and CSS rules that keep same-group controls compact even when the panel stretches vertically.
- Key decisions: UI-only spacing adjustment. Existing state, submit handler, API payload, runtime options, and gating execution behavior were preserved.
- Issues encountered: `npm run build` passed and refreshed backend static React assets.
- Next steps: Visual-smoke Runtime Context with multiple batch options and confirm the selected preset/LLM controls no longer inherit oversized vertical gaps.

## [2026-05-13] Session Summary (Quality Gating Launch Placement Polish)
- What was done: Repositioned the `/admin/quality-gating` Launch panel into the right sidecar after Retriever and Active Config, and adjusted the console grid so the execution action sits at the right-column bottom on desktop while remaining responsive on narrower widths.
- Key decisions: UI layout-only change. Existing form state, submit handler, request payload, runtime options, and gating execution behavior were left unchanged.
- Issues encountered: `npm run build` passed and refreshed backend static React assets.
- Next steps: Visual-smoke the Gating console at desktop and responsive breakpoints with the attached reference layout in mind.

## [2026-05-13] Session Summary (Quality Gating Runtime Console UX Redesign)
- What was done: Rebuilt `/admin/quality-gating` presentation as a runtime-console layout: left runtime context/launch rail, center gate network, and right retriever/active-config sidecar. Added local form primitives (`ControlField`, `SelectField`, `GateCard`, `ScoreGroup`, `CapabilityChip`), Top-K/Document/Penalty utility score grouping, stage-linked dim/active states, pipeline chips, config summary, and a stronger queued launch button.
- Key decisions: Frontend-only UX/UI change. Existing request body keys, state values, API endpoints, runtime option loading, gating stage flags, score thresholds, retriever config fields, and history/result/funnel data flows were preserved.
- Issues encountered: `npm run build` passed and refreshed the production React bundle in backend static resources. `npm run lint` remains blocked by the existing `vite.config.js` `process` no-undef error plus pre-existing hook dependency warnings.
- Next steps: Smoke-test the Gating page with real method/runtime options and completed batches in dark mode, including BM25-only disabled Dense controls and inactive stage field dimming.

## [2026-05-13] Session Summary (AI Console Theme System + Visual Polish)
- What was done: Added explicit light/dark theme selection with localStorage persistence, early system-theme detection in `index.html`, smooth theme transitions, and a topbar theme toggle. Rebuilt the admin shell visual language around semantic design tokens, dark layered surfaces, custom form controls, refined sidebar rhythm, and a subtle AI Ops Core presence indicator.
- Key decisions: Kept all API helpers, request payload fields, data flow, experiment/snapshot semantics, and page execution logic unchanged. Styling changes are centralized in `styles.css` through semantic tokens and a final design-system normalization layer so legacy page classes inherit the same hierarchy.
- Issues encountered: `npm run build` passed and refreshed generated static React assets in backend resources. `npm run lint` still fails on the existing `vite.config.js` `process` no-undef error and pre-existing hook dependency warnings.
- Next steps: Browser-smoke `/admin/pipeline`, `/admin/synthetic-queries`, `/admin/quality-gating`, and `/admin/rag-tests` in both light/dark mode with live data.

## [2026-05-13] Session Summary (Admin UI Polish: Strategy Density, Dark Selected States, Korean Copy)
- What was done: Reduced `/admin/synthetic-queries` strategy cards to compact operator-facing essentials, replaced verbose names/descriptions/tag clusters with one-line flow text, and localized admin shell/Synthetic/RAG/Gating/shared UI copy to Korean-first terminology. Added common selected-state tokens and semantic success/danger button variants for dark-mode-safe active controls.
- Key decisions: UI-only polishing. Existing API calls, form state keys, request payloads, strategy meanings, source-scoped method restrictions, snapshot requirements, and evaluation/gating/rewrite logic were left unchanged.
- Issues encountered: `npm run build` passed. `npm run lint` still fails on the existing `vite.config.js` `process` no-undef rule and hook-dependency warnings.
- Next steps: Dark-mode browser smoke on strategy selector, segmented controls, dropdown selected items, compare checkboxes, linked compare rows, and delete/run buttons.

## [2026-05-13] Session Summary (Admin Console UI/UX Modernization)
- What was done: Added reusable admin UI primitives (`AdminUi.jsx`) and redesigned `/admin/synthetic-queries` with strategy-card flow visualization, modern generation builder controls, metric cards, client-side batch search/filter/sort, timeline-style batch job cards, progress bars, ETA display, and confirm dialog delete UX. Reworked `/admin/rag-tests` run form into a sectioned Experiment Builder with overview, rewrite, retrieval, advanced options, fusion balance bar, threshold slider, and run-summary preview.
- Key decisions: Kept existing React page/container structure, API calls, request payload fields, strategy semantics, source-scoped method restrictions, snapshot requirements, and RAG validation flow unchanged. No new runtime dependency was added.
- Issues encountered: `npm run build` passed. `npm run lint` remains blocked by existing `vite.config.js` `process` no-undef plus pre-existing hook dependency warnings.
- Next steps: Browser-smoke both redesigned admin pages with live method/batch/snapshot data and refine density if operators need more compact cards.

## [2026-05-13] Session Summary (RAG UI DB-ANN Backend + Materialization Readiness)
- What was done: Extended `/admin/rag-tests` to consume backend-provided `retrievalBackends`, added `local/db-ann` backend selection, surfaced chunk-embedding readiness via `/api/admin/console/rag/chunk-embeddings/status`, and added a materialization trigger that enqueues the backend `materialize-chunk-embeddings` job. LLM job filtering now includes chunk-embedding materialization jobs.
- Key decisions: Kept the existing RAG form layout and added readiness/materialization as an additive state block that appears only when `db-ann` is selected.
- Issues encountered: Existing mixed-encoding strings in `RagPage.jsx` limited text-surface cleanup, so the implementation focused on behavior and state wiring.
- Next steps: Browser-smoke the `db-ann` operator flow and confirm readiness refresh after materialization job completion.

## [2026-05-13] Session Summary (RAG Performance UI Redesign + Legacy Result Guard)
- What was done: Reworked `/admin/rag-tests` Performance rendering to use only the new latency metrics (`avg_query_eval_total_latency_ms`, `avg_final_rewrite_latency_ms`, `avg_pure_rewrite_latency_ms`) with ms+seconds formatting and sample-count basis. Removed dependence on old representative/rewrite-overhead latency fields in detail modal, compare overview, and latest summary cards.
- Key decisions: Legacy runs without the new latency payload render a clear fallback message (`Legacy result (new latency metrics unavailable)`) instead of attempting lossy reconstruction from removed fields.
- Issues encountered: Existing mixed-encoding literals in `RagPage.jsx` caused one build-time parse error after the block replacement; fixed by normalizing the affected latest-summary card labels.
- Next steps: UI-smoke one fresh run and one historical run together in compare mode to confirm the legacy fallback and new latency cards read clearly for operators.

## [2026-05-11] Session Summary (Synthetic Batch Delete Action + ETA Exposure)
- What was done: Updated `SyntheticPage.jsx` to add generation-batch delete action (calls `DELETE /api/admin/console/synthetic/batches/{batchId}`) and to surface live generation ETA/context using backend-provided fields (`targetQueryCount`, `estimatedSecondsPerQuery`, `estimatedRemainingSeconds`, LLM job/item status + retry state) inside batch history rows.
- Key decisions: Kept existing batch-history table structure and integrated ETA/delete as additive row details to minimize UI churn under mixed-encoding source constraints.
- Issues encountered: Existing localized literals are partially mixed-encoding; new behavior wiring was applied with minimal text-surface edits.
- Next steps: UI smoke against active F/G generation runs to verify ETA refresh accuracy and delete-guard behavior for running batches.

## [2026-05-11] Session Summary (Synthetic Batch History Real-time Refresh)
- What was done: Updated `SyntheticPage.jsx` to poll `/api/admin/console/synthetic/batches` every 3 seconds while any generation batch is in `planned/queued/running` status, so batch history count/status refreshes automatically.
- Key decisions: Kept existing manual refresh button and data model unchanged; added polling only for active-generation windows to minimize network overhead.
- Issues encountered: None.
- Next steps: Validate UI behavior with concurrent generation jobs and tune polling interval if operator feedback requests faster/slower refresh.

## [2026-05-10] Session Summary (Synthetic Method Dropdown Source-Scope Restriction)
- What was done: Updated `SyntheticPage.jsx` to load source-scoped method options for the synthetic run form and keep selected method validity when source/source-document selection changes. Added `fetchSyntheticMethods(...)` helper in `src/lib/api.js` to call `/api/admin/console/synthetic/methods` with optional `source_id/source_document_id/dataset_id`.
- Key decisions: Kept method inventory table/filter (`methods`) unchanged and applied scope restriction only to run-execution dropdown (`runMethods`) so existing monitoring views do not regress.
- Issues encountered: None.
- Next steps: Reuse scoped method option API in dataset-bound pages when KR Python evaluation dataset selection is activated.

## [2026-05-09] Session Summary (RAG Run Detail Modal Query-Focused UX)
- What was done: Reworked `/admin/rag-tests` run-detail modal so each sample prominently shows only `원본 질의` and `최종 재작성 합성 질의` by default. Moved metric contribution, recommended synthetic candidates, rewrite candidate logs, and retrieved chunk payloads behind disclosure dropdowns. Added dedicated modal styling for scan-first query comparison readability.
- Key decisions: Kept existing backend detail API contract and transformed only frontend rendering/styling, using progressive disclosure to reduce JSON noise in the primary workflow.
- Issues encountered: None after implementation; frontend production build completed successfully.
- Next steps: Operator smoke on real run histories to calibrate disclosure label wording/order based on review workflow preference.

## [2026-05-08] Session Summary (Runtime Options Catalog Consumption for Retriever/Rewrite Dropdowns)
- What was done: Updated `/admin/quality-gating` and `/admin/rag-tests` so retriever mode and rewrite failure policy dropdown values come from `/api/admin/console/runtime/options` payload only, and removed hardcoded fallback option arrays in runtime state initialization.
- Key decisions: Kept existing retriever preset behavior (`weights/candidate pool`) while changing only option-source wiring to server-driven lists.
- Issues encountered: None.
- Next steps: UI-smoke with catalog-updated mode/policy lists to confirm dropdown rendering and submit validation behavior.

## [2026-05-08] Session Summary (GatingPage Interrupted-State Recovery + Runtime Options Wiring Completion)
- What was done: Finalized runtime options integration for Admin forms and fixed interrupted `GatingPage.jsx` merge state. `loadLlmJobs` was normalized to a single fetch/filter path, gating run submit now derives `selectedMethodCodes` from selected generation batches, validates `llmModel`/dense model inputs in submit flow, and sends multi-batch payload (`generationBatchIds`, `methodCodes`, `llmModel`) consistently.
- Key decisions: Kept LLM job list loading read-only and moved model validation to run-submission path to avoid unrelated section load failures.
- Issues encountered: Mid-patch duplication had introduced redeclared `selectedMethodCodes` and undefined variable reference in submit payload.
- Next steps: Operator smoke on `/admin/quality-gating` multi-batch selection + `/admin/rag-tests` explicit snapshot selection path.

## [2026-05-05] Session Summary (Admin Sections Lazy/On-Demand Loading)
- What was done: Refactored admin pages to reduce eager data loading on initial mount. `PipelinePage.jsx` now lazy-loads Anchors and Anchor Eval run history; Anchor filter document options are fetched when the document dropdown is opened (`SelectDropdown` `onOpen`). `SyntheticPage.jsx`, `GatingPage.jsx`, and `RagPage.jsx` now defer LLM job fetches until the user explicitly loads that section.
- Key decisions: Kept API compatibility and existing behavior patterns by reusing current endpoints and query params; introduced only UI-layer `loaded/loading` states and section-scoped refresh logic.
- Issues encountered: Existing `GatingPage.jsx` Vite/esbuild warnings for literal `->` option labels remain pre-existing and unrelated; production build still succeeds.
- Next steps: Validate real operator workflows for lazy-loaded sections and adjust default auto-refresh scope if more immediate visibility is needed.

## [2026-05-04] Session Summary (Frontend Documentation Realignment)
- What was done: Replaced template-style `frontend/README.md` with project-specific 운영 문서 and updated `frontend/index.md` wording for strategy/method filtering to match DB-driven method handling.
- Key decisions: Kept docs focused on current route structure (`/admin/pipeline|synthetic-queries|quality-gating|rag-tests`) and existing UI responsibilities/API integration.
- Issues encountered: None.
- Next steps: When page contracts or route keys change, update README/index in the same PR to avoid operator confusion.

## [2026-05-02] Session Summary (Pipeline Anchor Pagination Section + Custom Dropdown Filters)
- What was done: Added a new `Anchors` section to `/admin/pipeline` with server-driven pagination and filter form (`document`, `chunk`, `keyword`). Implemented custom dropdown UI component `src/components/SelectDropdown.jsx` and integrated it for document/chunk filtering instead of native select controls.
- Key decisions: Kept filtering state local to `PipelinePage.jsx` and reused existing corpus document/chunk option APIs while delegating anchor filtering/pagination to the new backend endpoint.
- Issues encountered: None.
- Next steps: If document/chunk option counts increase, add incremental option loading or virtualization inside the custom dropdown menu.

## [2026-05-02] Session Summary (Pipeline Warning Status Badge Support)
- What was done: Updated frontend status normalization and badge styling to support `warning` state for admin pipeline run/step statuses (`src/lib/format.js`, `src/styles.css`).
- Key decisions: Kept existing status-badge component contract unchanged and mapped `warning` as an additive tone so all existing pages keep working without API shape changes.
- Issues encountered: None.
- Next steps: After backend restart, verify newly created warning-classified runs render amber warning badges in `/admin/pipeline`.

## [2026-05-02] Session Summary (Pipeline Anchor Eval Select-All + Help Text)
- What was done: Updated `PipelinePage.jsx` Anchor Eval form to add quick scope actions (`전체 문서 선택`, `전체 청크 선택`) and wired them to existing selected document/chunk state. Added field hints clarifying `Sample Size` (평가할 chunk 샘플 수) and `Candidate Limit` (샘플당 anchor 후보 최대 수).
- Key decisions: Reused current scope-loading flow (`handleAnchorEvalDocumentSelection` -> chunk reload) so selecting all documents still refreshes chunk options through the same path; no backend/API schema changes were introduced.
- Issues encountered: None.
- Next steps: If operator feedback requires it, add a complementary “전체 해제” action and list filtering for very large scopes.

## [2026-05-01] Session Summary (Synthetic Detail: Active Anchor Mapping Exposure)
- What was done: Updated `/admin/synthetic-queries` detail modal (`SyntheticPage.jsx`) to display `mapped_anchors` returned from backend active anchor mapping (`synthetic_query_anchor_link`).
- Key decisions: Kept existing raw snapshot fields (`source_chunk`, `source_links`, `raw_output`) unchanged and added mapped-anchor visibility as additive debug information for anchor re-extraction validation.
- Issues encountered: None.
- Next steps: Add a dedicated admin action screen to trigger scoped anchor re-extraction and inspect per-query remap counts.

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

## [2026-05-11] Session Summary (Admin ETA Component + Cross-Page ETA Rendering)
- What was done: Added reusable ETA utilities (`src/lib/eta.js`) and a dedicated ETA display component (`src/components/RemainingEta.jsx`), then applied it to Synthetic batch history, Gating batch history, RAG run history, and shared LLM job table.
- Key decisions: Centralized duration/rate/progress formatting in one utility layer and used a single UI component with compact/default variants so ETA rendering remains consistent across admin surfaces.
- Issues encountered: Existing `LlmJobsTable.jsx` contained encoding-drifted labels; it was normalized and rebuilt while preserving job action behavior.
- Next steps: Collect operator feedback on ETA readability/density and tune compact mode spacing/labels if needed.

## [2026-05-13] Session Summary (RAG Dataset-Scoped Strategy Options)
- What was done: Added dataset-scoped option filtering in `src/pages/RagPage.jsx`: Python KR short-user eval datasets now show only F/G strategy chips and F/G gating snapshots, while Spring/default eval datasets show only A/B/C/D/E.
- Key decisions: Used dataset key/name/profile hints to mirror backend scope rules and sanitize stale selected methods/snapshots whenever the selected dataset changes.
- Issues encountered: Source snapshot selection previously listed all completed snapshots and relied on incompatible labels plus submit-time validation, which was too noisy for fixed-scope eval datasets.
- Next steps: Verify the form with Python KR KO/EN, Spring KR KO/EN, and `human_eval_default` datasets against real gating snapshot rows.

## [2026-05-13] Session Summary (RAG Compare Dock + Detail Cards)
- What was done: Updated `src/pages/RagPage.jsx` and `src/styles.css` so selected RAG compare runs appear in a fixed bottom dock with individual/clear removal actions, and RAG run details render metric contribution, memory candidates, rewrite candidates, and retrieved chunks as structured cards instead of raw JSON.
- Key decisions: Reused existing run labels/secondary labels and theme tokens; detail disclosure text now relies on semantic `text-primary`/`text-secondary` colors for dark-mode readability.
- Issues encountered: `npm run lint` is blocked by the existing `vite.config.js` `process` no-undef error; the touched `RagPage.jsx` file passes without new errors and only existing hook dependency warnings remain.
- Next steps: Verify the fixed dock does not obscure table pagination on small screens and confirm detail cards with large memory/chunk payloads remain scannable.

## [2026-05-13] Session Summary (Synthetic Strategy Source Scope UX)
- What was done: Updated `src/pages/SyntheticPage.jsx` so the selected synthetic generation strategy has a visible selected badge/summary, source options are filtered by A-E vs F/G scope, and "전체 허용 소스" sends only scoped source IDs. `arahansa-github-io-docs-spring` is filtered out before rendering and request construction.
- Key decisions: Used frontend allowlists matching backend source policy and fan out all-source runs into per-source POST requests because the existing API accepts a single `sourceId`.
- Issues encountered: Source-document mode remains single-source by design; the document selector is disabled until a scoped source is selected.
- Next steps: Smoke-test strategy switching, all-source launch request bodies, and chunk sampling/limit placeholder readability in dark mode.

---

## [2026-05-19] Session Summary (Anchor Normalization Review UX)
- What was done: Reworked the `/admin/pipeline` anchor normalization detail view into a candidate review modal with bulk decision save, quick safe-change approval marking, conflict/invalid skip marking, and save-and-approve action.
- Key decisions: The modal hides unchanged candidates by default so operators review only actionable dry-run results after the full candidate pass is complete.
- Issues encountered: `PipelinePage.jsx` targeted lint still reports only the pre-existing hook dependency warning.
- Next steps: Smoke-test the review modal against a real pending run after backend restart/Flyway migration.

---

## [2026-05-27] Session Summary (RAG Eval Lab Detail Cleanup)
- What was done: Added eval dataset delete controls, removed Hallucination Rate/Answer Relevance from RAG comparison metrics, removed comparison-table helper copy, and made RAG run detail show one query analysis at a time via dropdown.
- Key decisions: Rewrite-skipped details open candidate sections by default, recommended synthetic candidate tags are capped to three, anchor Grounding column is removed, and completed ETA cards no longer show `KST` or progress counts.
- Issues encountered: None; `npm run build` passed and refreshed the backend React bundle.
- Next steps: Smoke-test a completed RAG detail modal with rewrite-skipped samples in the browser.

---

## Notes
- Keep this file concise
- Only record important changes
