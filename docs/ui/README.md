# UI Docs

`docs/ui/`는 Query-Forge Admin Console의 화면 구조와 운영 동선을 설명하는 문서 디렉터리입니다. 실제 구현은 `frontend/src/`에 있고, production bundle은 `backend/src/main/resources/static/react/`로 배포됩니다.

## 문서 목록

| 문서 | 역할 |
| --- | --- |
| `admin_backoffice.md` | Domain Atlas, Pipeline Monitor, Synthetic Query Studio, Quality Gate, Retrieval Eval, Prompt Studio, Chat Surface의 주요 동선과 UX 원칙을 설명합니다. |

## 현재 UI 구현과 연결

React Admin은 `/admin`을 Domain Atlas로 사용하고, 전역 또는 domain-scoped route에서 Pipeline/Synthetic/Gating/RAG 화면을 엽니다. `App.jsx`가 route parsing, domain workspace banner, theme, notification을 담당하며, 각 페이지는 backend API를 직접 호출합니다. `RagPage.jsx`는 explicit gating snapshot, official comparison, synthetic-free baseline, DB ANN materialization, rewrite/anchor detail, latency 표시를 가장 많이 다루는 화면입니다.

## 문서 갱신 기준

UI 문서는 화면 스크린샷보다 operator가 어떤 실험 조건을 선택하고 어떤 결과를 해석해야 하는지에 초점을 둡니다. 새로운 runtime option, domain policy, RAG comparison mode, prompt management 기능이 추가되면 `admin_backoffice.md`와 frontend README를 함께 갱신해야 합니다.
