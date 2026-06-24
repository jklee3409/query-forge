# progress.md

## Overview
High-level progress tracking for the `docs` directory.

## [2026-06-24] Session Summary (RAG Java Source-of-Truth Migration Phase 6F)
- What was done: Recorded that Phase 6F used `rag-java-source-of-truth-migration-guide.md` for the persistence boundary audit and retrieval-only eval readiness check.
- Key decisions: The guide document itself was not changed; no eval endpoint, DB schema, Python eval path, `/ask` response behavior, answer generation movement, `createOnlineQuery` movement, or `insertAnswer` movement was introduced.
- Validation: Backend compile, focused adapter/service tests, agentic boundary test, requested targeted RAG regression command, and final diff whitespace check passed.
- Issues encountered: The guide remains untracked in the current working tree and was left untouched as instructed.
- Next steps: Phase 7A can design the retrieval-only endpoint boundary around execution services plus `persistPolicy.NONE`, with explicit handling for skipping online root and answer persistence.

## [2026-06-24] Session Summary (RAG Java Source-of-Truth Migration Phase 6E)
- What was done: Recorded that Phase 6E used `rag-java-source-of-truth-migration-guide.md` as the boundary document while moving only backend agentic decision/metadata writes behind the persistence adapter.
- Key decisions: The guide document itself was not changed; no `/ask` response behavior, answer generation, answer storage, online query root creation, eval endpoint, DB schema, or Python eval path change was introduced.
- Validation: Backend compile, focused adapter/service tests, and requested targeted RAG regression command passed.
- Issues encountered: The guide remains untracked in the current working tree and was left untouched as instructed.
- Next steps: Continue later phases without broadening `TRACE_ONLY`, generic `ONLINE_QUERY`, or eval endpoint work into Phase 6E.

## [2026-06-24] Session Summary (RAG Java Source-of-Truth Migration Phase 6D)
- What was done: Recorded that Phase 6D used `rag-java-source-of-truth-migration-guide.md` as the boundary document while moving only backend agentic rewrite/memory/candidate log writes behind the persistence adapter.
- Key decisions: The guide document itself was not changed; no eval endpoint, DB schema, Python eval path, or `/ask` response behavior change was introduced.
- Validation: Backend compile, focused adapter/service tests, requested targeted RAG regression command, and `git diff --check` passed.
- Issues encountered: The guide remains untracked in the current working tree and was left untouched as instructed.
- Next steps: Continue Phase 6 with the remaining allowed agentic side-effect work without moving answer generation, answer storage, or decision/metadata writes prematurely.

## [2026-06-23] Session Summary (RAG Java Source-of-Truth Migration Phase 1 Follow-up)
- What was done: Recorded that Phase 1 proceeded as backend `/ask` characterization tests against the existing migration guide.
- Key decisions: The guide document itself was not changed; no eval endpoint, DB schema, or production runtime behavior was introduced.
- Issues encountered: Remaining test limitations are documented in backend/root progress and final task output.
- Next steps: Continue using `rag-java-source-of-truth-migration-guide.md` as the phase boundary document for Phase 2.

## [2026-05-20] Session Summary (Domain Scoped Admin Runtime Wiring)
- What was done: Recorded the implementation phase that threads selected domain IDs through existing Admin runtime APIs and domain workspace GUI calls.
- Key decisions: Kept prompt assets global above domains; only execution/list artifacts are domain scoped in this phase.
- Issues encountered: Backend compile, frontend targeted ESLint, and frontend build passed.
- Next steps: Document deeper pipeline domain propagation once import/materialization jobs are wired.

## [2026-05-20] Session Summary (Domain Workspace and Prompt Studio UI)
- What was done: Added frontend Domain Atlas, domain workspace routing, selected-domain banner, and Prompt Studio UI for global prompt bindings.
- Key decisions: Reused existing operation pages inside the new domain route first, leaving strict domain-scoped API calls for the next implementation phase.
- Issues encountered: Targeted frontend ESLint and `npm run build` passed. Backend-served React assets were regenerated.
- Next steps: Wire domain IDs through existing Pipeline/Synthetic/Gating/RAG API calls and backend runtime requests.

## [2026-05-20] Session Summary (Domain and Prompt Admin APIs)
- What was done: Implemented backend Admin APIs for technical document domains and global prompt asset/binding management.
- Key decisions: Kept API implementation separate from existing Synthetic/Gating/RAG runtime wiring to preserve small reviewable phases.
- Issues encountered: Backend `compileJava` passed; DB migration execution was skipped by instruction to avoid unnecessary local DB work.
- Next steps: Build the frontend Domain Home/Workspace and Prompt Studio shells.

## [2026-05-20] Session Summary (Domain and Prompt Schema Implementation)
- What was done: Added the first implementation migration for the domain pipeline integration design: domain tables, prompt bindings, seed mappings, nullable `domain_id` columns, and deterministic backfill SQL.
- Key decisions: Kept this phase additive and nullable so backend/API/frontend migration can proceed before strict enforcement.
- Issues encountered: No DB migration was executed in this step to avoid unnecessary local DB load.
- Next steps: Implement backend Domain and Prompt admin APIs against the new schema.

## [2026-05-20] Session Summary (Global Prompt Management Design)
- What was done: Updated `docs/architecture/domain_pipeline_integration_design.md` to explicitly model shared prompt assets and bindings above technical document domains.
- Key decisions: Kept A/B/C/D/E/F/G query-generation prompts and RAG rewrite prompts as global assets, added a `prompt_asset_binding` concept, and scoped prompt editing to a domain-independent Admin Prompt Studio.
- Issues encountered: None. This was a documentation/design-only change.
- Next steps: When implementation starts, seed prompt bindings before domain workspace migration so Synthetic/RAG pages can show active global prompt versions.

## [2026-05-20] Session Summary (Domain Pipeline Integration Design)
- What was done: Added `docs/architecture/domain_pipeline_integration_design.md` covering current backend/frontend/pipeline structure, current DB entity relationships, live Spring/Python source distribution, and the proposed domain-first Admin/DB/pipeline integration design.
- Key decisions: Kept synthetic generation methods global and preserved split raw tables while proposing domain ownership for batches, corpus artifacts, anchors, datasets, memory snapshots, and RAG runs.
- Issues encountered: None. This was a documentation/design-only change.
- Next steps: Implement the design in phases: domain schema/backfill, backend validation/filtering, pipeline domain config, Admin domain home/workspace UI, then strict DB enforcement.

## [2026-05-19] Session Summary (Canonical Anchor Backfill Dry-Run Policy)
- What was done: Added `docs/experiments/canonical_anchor_backfill_dry_run.md` and linked it from the experiment/docs indexes.
- Key decisions: Documented dry-run-only report scope, version pins, manual review flow, snapshot/source identity requirements, and no-overwrite/no-DB-write rules without adding a pipeline tool.
- Issues encountered: No tests were run because this was a documentation-only change.
- Next steps: Add a read-only report writer only after the report schema and review policy are accepted.

## [2026-05-04] Session Summary (Docs Structure/API/UI Sync with Current Runtime)
- What was done: Updated docs across `docs/ui`, `docs/architecture`, `docs/api`, and `docs/experiments` to replace legacy route/structure assumptions with current runtime behavior (React admin routes, strategy `E`, anchor admin APIs, warning-aware orchestration model).
- Key decisions: Prioritized implementation-aligned corrections over broad editorial rewrites so the docs can be used as operational references immediately.
- Issues encountered: None.
- Next steps: Keep `docs/api/*` and `docs/ui/*` aligned whenever controller endpoints or admin routes change.

## [2026-04-18] Session Summary (Langfuse Dashboard Template + RAG Performance Guidance)
- What was done: Added `docs/experiments/langfuse_dashboard_template.md` and linked it from experiment/docs indexes for practical quality+performance monitoring setup.
- Key decisions: Focused the template on Query Forge field conventions (`purpose/stage/status` tags and metadata usage) plus rewrite-overhead watch.
- Issues encountered: None.
- Next steps: Validate dashboard panels against one real RAG test run after Langfuse tracing is enabled in staging.

## [2026-04-18] Session Summary (Langfuse Event Schema Documentation)
- What was done: Added [`docs/experiments/langfuse_event_schema.md`](/E:/dev_factory/univ/query-forge/docs/experiments/langfuse_event_schema.md) and linked it from `docs/experiments/README.md`.
- Key decisions: Standardized required tags/metadata, payload truncation policy, sampling defaults, and free-tier event caps.
- Issues encountered: Existing docs had mixed-encoding text in some files, so updates were kept narrowly scoped.
- Next steps: Add operational dashboard/query examples for error-rate, fallback-rate, and latency-by-purpose.

## [2026-04-15] Session Summary (A/C RAG Comparison Report for Short User Dataset)
- What was done: Added `docs/report/rag_quality_ac_comparison_short_user_2026-04-15.md` with baseline vs short-user run comparison and raw metric snapshot JSON.
- Key decisions: Organized report by AGENTS 3.6 discipline (single-variable isolation, snapshot-aware interpretation, reproducibility).
- Issues encountered: `human_eval_default` auto-sync changed totals after sample insertion, so the report separates current totals from run-time sample size.
- Next steps: Extend reporting to ungated/rule_only/full_gating comparison under the same short-user dataset.

## [2026-04-13] Session Summary
- What was done: Consolidated API/architecture/UI/experiments documentation and created docs indexing/progress files.
- Key decisions: Split implementation docs and experiment docs for maintainability.
- Issues encountered: None.
- Next steps: Keep `docs/index.md` structure list synchronized with newly added documentation files.

## Notes
- Keep this file concise.
- Record only major documentation changes.
