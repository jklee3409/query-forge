# progress.md

## Overview
High-level progress tracking for the `docs` directory.

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
