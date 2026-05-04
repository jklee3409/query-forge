# Frontend

`frontend/`는 Query Forge 관리자 콘솔 UI를 담당하는 React + Vite 애플리케이션이다. 운영자는 이 UI에서 pipeline 실행, synthetic generation, quality gating, RAG 테스트를 실행하고 결과를 비교한다.

현재 Admin 화면은 단일 앱 라우트 구조로 동작하며, 주요 경로는 `/admin/pipeline`, `/admin/synthetic-queries`, `/admin/quality-gating`, `/admin/rag-tests`다. API 호출은 `src/lib/api.js`를 통해 백엔드 `/api/admin/*`, `/api/chat/*` 엔드포인트와 통신한다.

## 구현 범위

- Pipeline 운영: run 실행/취소/재시도, 단계별 로그/상태 조회, anchor 목록/평가 실행
- Synthetic 운영: A/B/C/D/E 전략 생성 배치 실행, 질의 목록/상세 조회
- Gating 운영: 프리셋 실행, 퍼널/결과 조회, method/batch 기반 필터링
- RAG 테스트: snapshot 선택, retriever 모드 설정, rewrite 설정, 비교 리포트 조회
- 공통 UI: 상태 배지, ID badge, 상세 모달, polling 훅, 알림 토스트

## 개발 명령

```powershell
npm install
npm run dev
npm run build
npm run lint
```

빌드 산출물(`dist/`)은 백엔드 정적 리소스(`backend/src/main/resources/static/react/`)로 배포해 통합 운영할 수 있다.

## 참고

- 라우팅은 서버 사이드 라우터가 아닌 `App.jsx` 내부 경로 분기 방식으로 처리한다.
- 생성 전략, 데이터셋, 게이팅 배치 등 핵심 선택지는 가능하면 하드코딩 대신 백엔드 응답을 기준으로 렌더링한다.
