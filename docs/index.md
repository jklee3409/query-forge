# index.md

## Directory Overview
Project documentation hub for API specs, architecture, UI behavior, and experiment artifacts.

## Structure
- `README.md`: docs directory guide
- `api/`: backend/admin API references
- `architecture/`: system design and storage/orchestration docs
- `ui/`: admin/backoffice UI behavior docs
- `experiments/`: experiment design, baseline templates, monitoring docs, and Langfuse event schema
- `report/`: run comparison reports and metric snapshots

Key experiment docs:
- `experiments/dataset_design.md`
- `experiments/monitoring_trace.md`
- `experiments/langfuse_event_schema.md`
- `experiments/langfuse_dashboard_template.md`
- `experiments/canonical_anchor_backfill_dry_run.md`
- `experiments/first_baseline_template.md`
- `experiments/latest_report.md`
- `experiments/latest_answer_report.md`
- `experiments/best_rewrite_cases.md`
- `experiments/bad_rewrite_cases.md`

Key architecture docs:
- [Backend feature boundaries and type dependencies](architecture/backend_feature_boundaries.md)
- [Backend source inventory](architecture/backend_source_inventory.md)
- [RAG 공통 실행과 Spring AI 단계별 작업 설계](architecture/rag-work-plan/index.md)
- `architecture/domain_pipeline_integration_design.md`

Key migration docs:
- `rag-java-source-of-truth-migration-guide.md`
- `rag-agentic-no-write-eval-design.md`

## Responsibilities
- Keep implementation-aligned documentation for backend/frontend/pipeline behavior.
- Preserve reproducible experiment records and interpretation context.
- Provide operational references for debugging and monitoring workflows.

## Notes
- Update this file when documentation structure changes.
