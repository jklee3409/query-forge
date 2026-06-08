# API Docs

`docs/api/`는 Query-Forge backend의 HTTP API 문서를 모아 둡니다. 실제 source of truth는 `backend/src/main/java/io/queryforge/backend/**/controller`이지만, 이 디렉터리는 운영자가 endpoint의 목적과 사용 맥락을 빠르게 찾기 위한 안내 역할을 합니다.

## 문서와 구현 대응

| 영역 | 구현 controller | 문서 |
| --- | --- | --- |
| Corpus Admin | `CorpusAdminController` | `corpus_admin_api.md` |
| Pipeline Admin | `PipelineAdminController` | `admin_pipeline_api.md` |
| Online RAG / Experiment API | `RagController` | `rag_api.md` |
| Admin Console | `AdminConsoleController` | 이 README와 backend README를 함께 참고 |
| Domain Admin | `DomainAdminController` | 현재는 controller와 architecture/domain 문서를 기준으로 확인 |
| Prompt Admin | `PromptAdminController` | 현재는 controller와 frontend Prompt Studio 구현을 기준으로 확인 |

## 현재 API 표면

Corpus Admin은 source, run, document, section, chunk, glossary, preview, anchor extraction, anchor normalization, multi-source anchor build, anchor eval을 제공합니다. Pipeline Admin은 collect, normalize, chunk, glossary, import, full-ingest, retry, cancel, run/step/log 조회를 제공합니다. Admin Console은 synthetic methods/batches/queries/stats, runtime options, chunk embedding status/materialization, gating batches/funnel/results, RAG datasets/tests/compare/details, rewrite logs, LLM jobs를 제공합니다.

Online RAG API는 `/api/chat/ask`, `/api/rewrite/preview`, `/api/queries/{id}/trace`, `/api/experiments/{runId}/summary`, `/api/eval/retrieval`, `/api/eval/answer`, `/api/admin/reindex`, `/api/admin/experiments/run`을 중심으로 합니다. React Admin bundle은 API 문서가 아니라 `backend/ui/ReactUiController`에서 `/admin/*` 경로로 서빙됩니다.

## 작성 원칙

API 문서를 갱신할 때는 endpoint 목록만 나열하지 말고, 어떤 실험 단계와 재현성 제약에 연결되는지 함께 설명해야 합니다. 특히 RAG test API는 dataset, query language, target method, explicit snapshot, retriever config, rewrite profile, synthetic-free baseline 여부가 결과 해석에 직접 영향을 주므로 request/response field의 의미를 분명히 남겨야 합니다.
