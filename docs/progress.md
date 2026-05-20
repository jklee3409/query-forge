# progress.md

## Overview
High-level progress tracking for the `docs` directory.

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
