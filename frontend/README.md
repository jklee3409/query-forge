# Frontend

`frontend/`는 Query-Forge의 React 19 + Vite Admin Console과 간단한 Chat surface를 구현하는 디렉터리입니다. 이 UI는 corpus ingest부터 synthetic query generation, quality gating, RAG evaluation, prompt management, domain workspace까지의 연구 운영 흐름을 한 화면 체계로 연결합니다.

Frontend는 실험 조건을 숨기지 않는 방향으로 설계되어 있습니다. 운영자는 dataset, strategy, source, gating snapshot, retriever mode, retrieval backend, rewrite profile, anchor option, latency 결과를 직접 확인하고 비교합니다. 특히 RAG 화면은 synthetic memory가 최종 검색어가 아니라 rewrite hint라는 점을 UI 문구와 실행 조건으로 명확히 드러냅니다.

## 구현 범위

| 화면 | 역할 |
| --- | --- |
| Domain Atlas | `tech_doc_domain` 목록, domain 생성, source membership 관리, domain-scoped workspace 진입점을 제공합니다. |
| Pipeline Monitor | source sync, collect/normalize/chunk/glossary/import/full-ingest 실행, run/step/log 조회, corpus preview와 anchor 관리 흐름을 제공합니다. |
| Synthetic Query Studio | A/B/C/D/E/F/G method 조회, domain/source/document scoped generation, allowed source fan-out, random chunk sampling, Strategy B Gemini Batch option, batch delete/retry/cancel/ETA를 제공합니다. |
| Quality Gate | generation batch 기반 gating 실행, rule/LLM/utility/diversity stage 설정, Korean ratio override, retriever config, funnel/result pagination, LLM job 상태 제어를 제공합니다. |
| Retrieval Eval | eval dataset, explicit gating snapshot, official comparison, synthetic-free baseline, DB ANN materialization, selective rewrite, anchor summary, query-level detail, run compare/delete를 제공합니다. |
| Prompt Studio | prompt asset, revision, active binding, validation endpoint를 관리합니다. |
| Chat Surface | `/api/chat/ask`와 `/api/admin/reindex`를 호출하는 온라인 RAG 점검 표면입니다. |

## 소스 구조

```text
src/
  App.jsx              admin route parsing, domain workspace shell, theme, notification
  main.jsx             React entry point
  styles.css           Admin visual system and responsive layout
  components/          공통 Admin UI, status/id badge, ETA, LLM job table, select control
  lib/                 API 호출, query string, ETA, formatting, polling hook
  pages/               DomainHome, Pipeline, Synthetic, Gating, RAG, PromptStudio, Chat
```

## API 연동 원칙

Frontend는 가능한 한 backend runtime options를 기준으로 선택지를 렌더링합니다. `src/lib/api.js`의 `requestJson`, `appendQuery`, `fetchSyntheticMethods`가 공통 호출 경로이며, `GET /api/admin/console/runtime/options` 응답이 retriever mode, retrieval backend, rewrite profile, model allowlist, default parameter range의 기준입니다. Domain workspace에서는 `domain_id` query parameter를 함께 전달해 source, generation batch, gating batch, RAG run history를 좁힙니다.

`SyntheticPage.jsx`에는 unscoped synthetic generation source guard가 있습니다. A-E는 Spring reference source 집합, F/G는 Python KR source로 좁히며, 한국어 Spring community source는 synthetic generation 대상에서 숨깁니다. 이 UI guard는 backend validation을 대체하지 않고, operator가 잘못된 조합을 고르기 전에 막는 보조 장치입니다.

## 개발 명령

```powershell
cd frontend
npm install
npm run dev
npm run build
npm run lint
```

`npm run build` 결과는 `dist/`에 생성됩니다. Backend와 통합 배포하려면 빌드 산출물을 `backend/src/main/resources/static/react/`로 반영해야 합니다. 현재 backend는 `/admin`, `/admin/*`, `/react/index.html` 계열 경로를 React bundle로 forward합니다.

## 운영 메모

라우팅은 React Router가 아니라 `App.jsx`의 path parsing과 `history.pushState`로 처리합니다. Admin route는 `/admin`, `/admin/pipeline`, `/admin/synthetic-queries`, `/admin/quality-gating`, `/admin/rag-tests`, `/admin/prompts`, `/admin/domains/{domainKey}/*` 형태입니다. UI 변경 시 backend static bundle을 갱신해야 실제 Spring Boot 서빙 화면에도 반영됩니다.
