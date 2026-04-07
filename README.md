# Query Forge

Query Forge는 다음 문제를 해결하기 위한 연구/서비스 프로젝트다.

- 공식 Spring 기술 문서는 영어 중심인데, 한국어 질의 데이터는 부족하다.
- 이때 한국어 합성 질의 생성 + 품질 게이팅 + 메모리 기반 selective rewrite를 연결해 retrieval/answer 품질을 개선해야 한다.
- 단순 데모가 아니라, 반복 실험/비교/모니터링이 가능한 실험형 운영 구조를 목표로 한다.

이 저장소는 현재 다음을 지원한다.

- 영문 Spring 문서 수집/정제/chunking/glossary 추출/DB 적재
- 합성 질의 생성 A/B/C + D(code-mixed 옵션)
- Quality Gating (`ungated`, `rule_only`, `rule_plus_llm`, `full_gating`)
- synthetic query memory 구축 및 임베딩 저장
- selective rewrite 기반 온라인 질의응답 API + 웹 UI
- retrieval/answer 평가 파이프라인과 자동 리포트 생성
- 관리자 GUI에서 실험 실행/평가 요약 확인

## 1. 프로젝트 목표 요약

핵심 연구 질문은 아래 4가지다.

1. 합성 질의 생성 전략 A/B/C(+D)가 retrieval 성능 차이를 만드는가?
2. ungated 대비 gated synthetic memory가 rewrite 품질에 유의미한 개선을 주는가?
3. `no rewrite` / `always rewrite` / `selective rewrite` 중 무엇이 안정적인가?
4. single/multi-chunk, code-mixed, follow-up 질의에서 어떤 전략이 강한가?

## 2. 엔티티 설계(요약)

### 2-1. 코퍼스 계층

- `corpus_sources`, `corpus_runs`, `corpus_run_steps`
- `corpus_documents`, `corpus_sections`, `corpus_chunks`, `corpus_chunk_relations`
- `corpus_glossary_terms`, `corpus_glossary_aliases`, `corpus_glossary_evidence`

### 2-2. 실험/질의 계층

- `experiments`, `experiment_runs`
- `prompt_assets` (프롬프트 버전/해시 관리)
- `synthetic_queries_raw`
- `synthetic_queries_raw_a`, `synthetic_queries_raw_b`, `synthetic_queries_raw_c`, `synthetic_queries_raw_d`
- `synthetic_queries_gated`
- `memory_entries`
- `query_embeddings` (chunk/synthetic/memory/online/rewrite/eval 공통 임베딩 저장)

### 2-3. 온라인 추적/평가 계층

- `online_queries`, `rewrite_candidates`
- `retrieval_results`, `rerank_results`, `answers`
- `eval_samples`, `eval_judgments`

## 3. 파이프라인 구현 절차

요구 순서(5~14단계)와 동일한 실제 구현 절차:

1. **합성 질의 생성**: A/B/C/D 전략 + SAP 구조 반영
2. **Quality Gating**: rule/llm/utility/diversity/final score
3. **Memory Build**: 승인 질의 메모리화 + 임베딩
4. **Backend Raw Retrieval**
5. **Backend Selective Rewrite**
6. **Rerank + Answer Generation**
7. **Eval Dataset Builder (Dev/Test 70/70)**
8. **Experiment Runner + Report Generator**
9. **UI + Monitoring**
10. **문서화**

## 4. SAP 기반 합성 질의 생성

### 전략 A
- 영어 원문 → 영어 extractive summary → 영어 질의 → 한국어 번역

### 전략 B
- 영어 원문 → 한국어 번역 → 한국어 summary → 한국어 질의

### 전략 C (기본)
- 영어 원문 → 영어 extractive summary → 한국어 summary
- 영어 원문 + 한국어 summary + glossary를 함께 사용해 한국어 질의 생성

### 전략 D (ablation)
- C 전략 기반으로 한국어 + code-mixed 질의를 함께 생성

모든 프롬프트는 `configs/prompts/**`에 분리 저장되며, 버전/해시는 `prompt_assets`에 기록된다.

## 5. Quality Gating 핵심 로직

게이팅 프리셋:

- `ungated`
- `rule_only`
- `rule_plus_llm`
- `full_gating`

적용 단계:

1. Rule filter: 길이/토큰/특수문자/복사율/한국어 비율
2. LLM self-eval(스키마 강제 JSON): grounded, answerable, user_like, korean_naturalness, copy_control
3. Retrieval utility test: target chunk 회수 점수
4. Diversity/Dedup: 임베딩 기반 유사 중복 제거
5. FinalScore: `0.50*Utility + 0.35*LLM + 0.15*Novelty`

## 6. 온라인 질의응답 흐름

백엔드 동작 순서:

1. 입력 질의 수신
2. selective rewrite 판단 (raw vs candidates confidence 비교)
3. vector retrieval (pgvector + hash embedding)
4. 후보 chunk 수집
5. rerank(시뮬레이션 Cohere)
6. 답변 생성
7. answer + 근거 + trace 반환/저장

온라인 rewrite는 gold document/answer를 사용하지 않는다.

## 7. 실행 방법

## 7-1. 필수 준비

- Docker
- Java 21
- Python 3.12+

```bash
docker compose up -d postgres
```

백엔드 테스트:

```bash
./backend/gradlew -p backend test
```

백엔드 실행:

```bash
./backend/gradlew -p backend bootRun
```

## 7-2. Make 기반 재현

```bash
make up
make collect-docs
make preprocess
make generate-queries EXPERIMENT=gen_c
make gate-queries EXPERIMENT=full_gating
make build-memory EXPERIMENT=full_gating
make build-eval-dataset EXPERIMENT=exp4
make eval-retrieval EXPERIMENT=exp4
make eval-answer EXPERIMENT=exp4
make run-backend
```

## 7-3. 샘플 스모크 테스트(소규모 fixture)

```bash
python pipeline/cli.py import-corpus \
  --raw-input pipeline/tests/fixtures/corpus_small/raw.jsonl \
  --sections-input pipeline/tests/fixtures/corpus_small/sections.jsonl \
  --chunks-input pipeline/tests/fixtures/corpus_small/chunks.jsonl \
  --glossary-input pipeline/tests/fixtures/corpus_small/glossary_terms.jsonl

python pipeline/cli.py generate-queries --experiment gen_c
python pipeline/cli.py gate-queries --experiment full_gating
python pipeline/cli.py build-memory --experiment full_gating
python pipeline/cli.py build-eval-dataset --experiment exp4
python pipeline/cli.py eval-retrieval --experiment exp4
python pipeline/cli.py eval-answer --experiment exp4
```

## 8. 주요 API

- `POST /api/chat/ask`
- `POST /api/rewrite/preview`
- `GET /api/queries/{id}/trace`
- `GET /api/experiments/{runId}/summary`
- `GET /api/eval/retrieval`
- `GET /api/eval/answer`
- `POST /api/admin/reindex`
- `POST /api/admin/experiments/run` (관리자 GUI 실행용)

## 9. UI

- 사용자 채팅 UI: `http://localhost:8080/`
- 관리자 백오피스: `http://localhost:8080/admin`
- 실험/평가 모니터링: `http://localhost:8080/admin/experiments`

관리자 화면에서 아래를 제어한다.

- 단계별 파이프라인 실행(생성/게이팅/메모리/평가)
- 실험 run 상태 확인
- retrieval/answer 평가 요약 비교
- 최신 리포트/사례 확인

## 10. 리포트 산출물

자동 생성:

- `data/reports/retrieval_summary_*.json,csv`
- `data/reports/retrieval_by_category_*.csv`
- `data/reports/latency_*.csv`
- `data/reports/answer_summary_*.json,csv`
- `docs/experiments/latest_report.md`
- `docs/experiments/latest_answer_report.md`
- `docs/experiments/bad_rewrite_cases.md`
- `docs/experiments/best_rewrite_cases.md`

참고 문서:

- `docs/architecture/overview.md`
- `docs/experiments/dataset_design.md`
- `docs/experiments/monitoring_trace.md`
- `docs/experiments/first_baseline_template.md`
- `docs/api/rag_api.md`

## 11. 환경 변수

`.env.example` 참고:

- `POSTGRES_*`
- `BACKEND_PORT`
- `OPENAI_API_KEY` (옵션)
- `COHERE_API_KEY` (옵션)
- `QUERY_FORGE_CONFIG_DIR`
- `PROMPT_ROOT`
- `EXPERIMENT_ROOT`

## 12. 제약/주의

- 온라인 rewrite에서 gold document/answer 사용 금지
- 프롬프트/실험 설정 하드코딩 금지 (`configs/**` 사용)
- ungated/gated 결과 혼합 평가 금지
- 문서 family split 누수 방지(데이터셋 분할 시)
