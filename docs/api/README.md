# API Notes

API contracts are partially implemented through stage `2-3A`.

Planned endpoints include:

- `POST /api/chat/ask`
- `POST /api/rewrite/preview`
- `GET /api/queries/{id}/trace`
- `GET /api/experiments/{runId}/summary`
- `GET /api/eval/retrieval`
- `GET /api/eval/answer`
- `POST /api/admin/reindex`

Implemented in `2-3A`:

- corpus admin read APIs under `/api/admin/corpus/*`
- document/chunk/glossary preview endpoints

See `docs/api/corpus_admin_api.md` for details.

Implemented in `2-3B`:

- pipeline execution/control APIs under `/api/admin/pipeline/*`
- corpus mutation APIs for source enable toggle and glossary alias/policy edits

See `docs/api/admin_pipeline_api.md` for details.
