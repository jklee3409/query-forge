# Architecture Overview

Query Forge는 "영문 기술 문서 + 한국어/영어 질의 혼합" 상황에서 synthetic generation, quality gating, memory/rewrite, RAG 평가를 end-to-end로 검증하는 구조로 구현되어 있다.

## 1. 모듈 구성

- `backend` (Spring Boot)
  - Flyway 마이그레이션
  - 관리자 API(`corpus`, `pipeline`, `console`)
  - 사용자 채팅/RAG API
  - 파이프라인 명령 실행 오케스트레이션(`POST /api/admin/pipeline/*`)
  - React Admin 정적 번들 서빙(`/admin/*`)
- `pipeline` (Python CLI)
  - 문서 수집/정제/chunk/glossary/import
  - 합성 질의 생성(A/B/C/D/E)
  - quality gating 프리셋 실행
  - memory build
  - eval dataset 빌드 및 DB import
  - retrieval/answer 평가 및 리포트 생성
- `configs`
  - `configs/experiments/*.yaml`: 실험 옵션/프리셋
  - `configs/prompts/**`: summary/query/self_eval/rewrite 프롬프트
- `data`
  - 실행 산출물, 평가 리포트 CSV/JSON
- `docs`
  - 아키텍처/데이터셋/모니터링/리포트 문서

## 2. 데이터 계층

- 코퍼스 계층: `corpus_*` + shadow 테이블(`documents/sections/chunks/glossary_terms`)
- 실험 계층: `experiments`, `experiment_runs`, `prompt_assets`
- 합성 질의 계층:
  - 전략별 분리 저장 `synthetic_queries_raw_a/b/c/d/e`
  - 조회용 union view `synthetic_queries_raw_all`
  - `synthetic_queries_gated`
  - `memory_entries`
  - `query_embeddings`
- 온라인 추적 계층:
  - `online_queries`, `rewrite_candidates`
  - `retrieval_results`, `rerank_results`, `answers`
- 평가 계층: `eval_samples`, `eval_judgments`

## 3. 온라인 요청 흐름

1. `/api/chat/ask` 요청 수신
2. raw query 임베딩 생성 + memory top-N 검색
3. rewrite 후보 3개 생성 및 후보별 retrieval
4. raw/candidate confidence 비교 후 selective rewrite 채택
5. vector retrieval -> rerank -> answer 생성
6. trace(후보 점수, 선택/기각 이유, latency breakdown) 저장
7. answer + 근거 + trace 데이터 응답

온라인 경로는 gold document/gold answer를 사용하지 않는다.

## 4. 오프라인 실험 흐름

1. `generate-queries`: A/B/C/D/E 합성 질의 생성, 프롬프트 hash/version DB 기록
2. `gate-queries`: rule/llm/utility/diversity/final-score
3. `build-memory`: 승인 질의 memory table 구축 + 임베딩 저장
4. `build-eval-dataset`: retrieval-aware eval 샘플 생성(`query_language`, grounding 필드 포함)
5. `eval-retrieval`, `eval-answer`: 비교군/카테고리별 지표 계산
6. `docs/experiments/latest_report.md`, `latest_answer_report.md` 자동 갱신

## 5. GUI 제어 원칙

- 파이프라인 운영: `/admin/pipeline`
- 합성 생성: `/admin/synthetic-queries`
- 게이팅 운영: `/admin/quality-gating`
- RAG 테스트/비교: `/admin/rag-tests`

즉, 실험 실행과 성능 확인은 관리자 GUI에서 통제 가능하도록 구성된다.
