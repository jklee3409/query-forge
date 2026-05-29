# Query Forge

한국어 사용자 질의로 영어 기술 문서를 검색하는 RAG 실험 환경. synthetic query generation, quality gating, synthetic memory, query rewrite가 retrieval/answer 품질에 미치는 영향을 같은 corpus, dataset, snapshot 조건에서 비교한다. 문서 수집, 전처리, chunking, glossary/anchor 추출, synthetic query 생성, gating, memory build, retrieval/answer evaluation까지 하나의 재현 가능한 pipeline과 Admin Console로 관리한다.

한국어 개발자 질의는 영어 기술 문서의 표현과 잘 맞지 않는 경우가 많다. 사용자는 짧은 한국어, code-mixed 표현, 축약된 증상, 번역된 기술 개념을 입력한다. 반면 근거 문서는 영어 API 이름, 설정 key, class/interface, annotation, command, section title 중심으로 구성된다. 이런 mismatch 때문에 raw query만으로 정답 chunk를 안정적으로 찾기 어렵고, 단순 번역 과정에서도 의도나 기술 anchor가 손실될 수 있다.

## 연구 목표

한국어 질의를 영어 기술 문서 RAG에 더 잘 맞게 만들면서도 원래 의도를 보존하는 것을 목표로 한다.

- 한국어, code-mixed, 영어 사용자 질의의 retrieval 품질을 정량 비교한다.
- A/B/C/D/E synthetic query 전략이 synthetic memory와 rewrite에 주는 영향을 분리해 측정한다.
- quality gating이 부정확하거나 retrieval utility가 낮은 synthetic query를 줄이는지 검증한다.
- rewrite가 raw query보다 항상 낫다는 가정을 두지 않고, raw baseline과 selective rewrite를 같은 dataset/snapshot 조건에서 비교한다.

## 연구 접근

전체 pipeline 순서는 고정되어 있다.

```text
collect -> preprocess -> chunk -> glossary -> import
-> generate-queries -> gate-queries -> build-memory
-> build-eval-dataset -> eval-retrieval -> eval-answer
```

### Synthetic Query Generation

전략별 raw table을 분리해 저장한다. A-E는 영어 기술 문서 RAG 실험의 중심 전략이며, F/G는 한국어 원문 문서용 확장 경로다.

| 전략 | 구현 의도 | 출력 언어 |
| --- | --- | --- |
| A | 영어 evidence에서 `query_en`을 먼저 만들고 의미를 보존한 한국어 query를 생성하는 anchor-fidelity 전략 | KO + EN |
| B | 영어 chunk를 한국어로 번역하고 한국어 summary를 만든 뒤 한국어 개발자 질의를 생성하는 Korean-native 전략 | KO |
| C | troubleshooting, 설정 원인, 운영 흐름 등 실제 개발 상황 신호를 강화하는 전략 | KO |
| D | 같은 intent를 한국어형과 code-mixed형으로 나누어 언어 style만 비교하는 ablation 전략 | KO + code-mixed |
| E | 영어 기술 문서 evidence에서 직접 영어 개발자 검색 질의를 생성하는 baseline/비교 전략 | EN |
| F/G | 한국어 원문 문서에서 각각 영어 질의(F), 한국어 질의(G)를 만드는 확장 전략 | EN / KO |

DB에는 `synthetic_queries_raw_a`부터 `synthetic_queries_raw_g`까지 전략별 table이 있고, 조회에는 `synthetic_queries_raw_all` union view를 사용한다. 전략별 효과를 섞지 않고 실험하기 위한 구조다.

### Quality Gating

gating은 generation batch와 전략을 명시적으로 선택한 뒤 실행한다. 구현된 stage는 다음과 같다.

- Rule filter: 길이, token 수, copy ratio, 한국어 비율 등 동적 threshold 검사
- LLM self-evaluation: fluency, adequacy, answerability 등 prompt 기반 자체 평가
- Retrieval utility: target chunk/doc이 retrieval top-k 안에 들어오는지 기반 점수화
- Diversity dedup: 같은 chunk/doc에 너무 유사한 query가 몰리는 현상 완화
- Final score: utility, LLM, novelty weight를 조합해 최종 승인 여부 결정

Admin GUI에서 rule threshold, gating weight, utility score weight, retriever mode, dense embedding model을 request 단위로 주입한다. runtime 선택지는 `configs/app/model_catalog.yml`을 기준으로 관리된다.

### Synthetic Memory와 Query Rewrite

승인된 gated query는 `memory_entries`로 materialize된다. memory row에는 원 synthetic query, target doc/chunk, generation strategy, gating snapshot, score, glossary/canonical anchor metadata, embedding 정보가 기록된다. snapshot은 `quality_gating_batch`와 `source_gating_batch_id`로 고정되며, RAG 평가에서 non-baseline synthetic-backed run은 명시적인 snapshot ID를 요구한다.

rewrite는 synthetic memory를 최종 검색어로 그대로 대체하지 않는다. raw query retrieval evidence와 snapshot-backed memory candidates를 LLM prompt context/few-shot 예시로 넣고, LLM이 만든 candidate 중 하나를 선택하거나 raw query를 유지한다. `selective_rewrite`는 raw 손실 guard, confidence delta, anchor 보존, memory target alignment 등을 보고 rewrite 적용 여부를 결정한다. anchor injection과 multi-source anchor expansion은 기술 anchor 보존 효과를 분석할 때 사용하는 선택 옵션이다.

### Retrieval/Answer Evaluation

`eval-retrieval`은 `raw_only`, `selective_rewrite`, `selective_rewrite_with_session`, legacy `rewrite_always`, `memory_only_*` ablation mode를 지원한다. 기본 rewrite 평가 경로에서는 raw/memory/rewrite 결과를 병합하지 않고, 최종 query 하나로 retrieval을 다시 수행한다.

`eval-answer`는 retrieved context에서 extractive answer를 구성하고 answer-level metric을 계산한다. 결과는 DB run ledger, `data/reports/`, `docs/experiments/latest_report.md`, `docs/experiments/latest_answer_report.md`에 기록된다.

## 대상 도메인과 데이터셋

source config와 eval artifact로 확인된 범위만 적었다. 로컬 DB에 import된 행 수는 환경마다 달라질 수 있어 단정하지 않는다.

### 확인된 문서 source

| 구분 | source_id | 문서 출처 | 비고 |
| --- | --- | --- | --- |
| Spring 공식 영어 문서 | `spring-boot-reference` | `https://docs.spring.io/spring-boot/reference/` | canonical Spring domain |
| Spring 공식 영어 문서 | `spring-framework-reference` | `https://docs.spring.io/spring-framework/reference/` | canonical Spring domain |
| Spring 공식 영어 문서 | `spring-security-reference` | `https://docs.spring.io/spring-security/reference/` | canonical Spring domain |
| Spring 공식 영어 문서 | `spring-data-jpa-reference` | `https://docs.spring.io/spring-data/jpa/reference/` | canonical Spring domain |
| Spring 공식 영어 문서 | `spring-data-commons-reference` | `https://docs.spring.io/spring-data/commons/reference/` | canonical Spring domain |
| PostgreSQL 영어 문서 | `postgresql-docs-current` | `https://www.postgresql.org/docs/current/` | 확장/eval artifact 확인 |
| PostGIS 영어 문서 | `postgis-docs-current` | `https://postgis.net/docs/` | PostgreSQL 계열 보조 corpus |
| Kubernetes 영어 문서 | `kubernetes-docs-current` | `https://kubernetes.io/docs/` | 확장/eval artifact 확인 |
| Python 한국어 문서 | `docs-python-org-ko-3-14` | `https://docs.python.org/ko/3.14/` 일부 page | F/G 전략용 한국어 원문 확장 |
| 한국어 Spring community 문서 | `arahansa-github-io-docs-spring` | `https://arahansa.github.io/docs_spring/` | config는 있으나 canonical Spring synthetic source에서는 제외 |

### 확인된 평가 데이터

`data/eval/`에는 retrieval-aware JSONL dataset이 있다. 각 sample은 `query_language`, `user_query_ko` 또는 `user_query_en`, `expected_doc_ids`, `expected_chunk_ids`, `expected_answer_key_points`, `target_method` 등을 포함한다.

대표 artifact는 Spring method별 compressed eval 80개, Spring anchor-translated short-user eval, PostgreSQL KR/EN short-user eval, Kubernetes KR/EN short-user eval, Python KR-source KO/EN eval, human short-user 40/80 eval이다.

## 시스템 아키텍처

```text
configs/app + configs/prompts
        |
        v
Python pipeline  <---->  PostgreSQL + pgvector
        |                         ^
        v                         |
data/raw, data/processed, data/reports
        ^
        |
Spring Boot backend / Admin API
        |
        v
React Admin Console + Chat surface
```

- Offline pipeline: `pipeline/cli.py`가 collect, preprocess, chunk, glossary, import, query generation, gating, memory build, eval 단계를 실행한다.
- Backend/Admin Console: Spring Boot가 corpus/pipeline/console/domain/prompt/RAG API를 제공하고, Python pipeline command를 subprocess로 실행한다.
- Frontend: React + Vite single-page Admin Console이 `/admin/pipeline`, `/admin/synthetic-queries`, `/admin/quality-gating`, `/admin/rag-tests`, `/admin/prompts` 등을 제공한다.
- Database/vector store: PostgreSQL 16 + pgvector image를 사용한다. `query_embeddings`, `memory_entries.query_embedding`, `chunk_embeddings`는 `HALFVEC(384)`와 HNSW index를 사용한다.
- Retrieval: local BM25/dense/hybrid retriever, sentence-transformers `intfloat/multilingual-e5-small`, optional DB ANN backend(`postgresql-pgvector`), optional Cohere rerank path가 구현되어 있다.
- LLM: Gemini native/compatible, OpenAI-compatible, Groq, mock provider 경로가 있고, 기본 예시는 Gemini `gemini-2.5-flash-lite` 중심이다.

## 구현된 기능

| 영역 | 구현 상태 |
| --- | --- |
| Pipeline Admin | source sync, collect/normalize/chunk/glossary/import/full-ingest 실행, run/step/log 조회, cancel/retry |
| Corpus Admin | source/document/section/chunk/glossary 조회, raw-vs-cleaned preview, chunk boundary preview |
| Synthetic Query Studio | A-G method 조회, domain/source/document scoped generation, batch/job 상태 조회, query detail, prompt/output/provenance 조회 |
| Quality Gate | generation batch 기반 gating 실행, stage flag/threshold/weight/retriever config 조정, funnel/result 조회 |
| RAG Test | dataset/snapshot 선택, raw baseline, gating-effect, rewrite-effect, selective rewrite 실행, run detail/compare |
| Snapshot/Experiment | explicit `source_gating_batch_id`, `comparison_gating_batch_ids`, official/exploratory run discipline, `data/reports/` 산출 |
| Rewrite/Anchor 분석 | rewrite logs, memory candidates, rewrite candidates, retrieved chunks, canonical/multi-source anchor diagnostics |
| Prompt Studio | prompt assets/bindings 조회, revision 생성, active binding 변경, validate endpoint |
| DB ANN | chunk embedding materialization job, `chunk_embeddings` readiness check, DB ANN retrieval option |

## 기술 스택

| 계층 | 기술 |
| --- | --- |
| Backend | Java 21, Spring Boot 3.3, Spring Web/JDBC/JPA, Flyway, PostgreSQL driver |
| Frontend | React 19, Vite 5, ESLint |
| Pipeline/ML | Python 3.12, psycopg 3, PyYAML, requests, BeautifulSoup, sentence-transformers, YAKE, kiwipiepy, stanza/spaCy |
| Database | PostgreSQL 16, pgvector, pg_trgm, HNSW vector index |
| LLM/Rerank | Gemini, OpenAI-compatible, Groq, mock provider, optional Cohere rerank |
| Infra | Docker Compose, `pgvector/pgvector:pg16`, Gradle wrapper, Makefile, PowerShell helper scripts |

## 실행 방법

환경 변수는 `.env.example`을 기준으로 준비한다. LLM API key가 없으면 generation, LLM self-eval, rewrite 등 LLM 단계는 실패할 수 있다.

```bash
# DB 실행
docker compose up -d postgres

# 또는 Makefile 사용
make up
```

Backend:

```bash
./backend/gradlew -p backend bootRun
```

Windows PowerShell:

```powershell
.\backend\gradlew.bat -p .\backend bootRun
```

Frontend 개발 서버:

```bash
cd frontend
npm install
npm run dev
```

Pipeline 패키지 설치와 단계별 실행:

```bash
pip install -e ./pipeline

make collect-docs EXPERIMENT=scaffold
make preprocess EXPERIMENT=scaffold
make chunk-docs EXPERIMENT=scaffold
make glossary-docs EXPERIMENT=scaffold
make import-corpus EXPERIMENT=scaffold
make generate-queries EXPERIMENT=gen_a
make gate-queries EXPERIMENT=full_gating
make build-memory EXPERIMENT=e2e_eval_a
make eval-retrieval EXPERIMENT=e2e_eval_a
make eval-answer EXPERIMENT=e2e_eval_a
```

CLI를 직접 호출할 수도 있다.

```bash
python pipeline/cli.py generate-queries --experiment gen_a
python pipeline/cli.py gate-queries --experiment full_gating
python pipeline/cli.py materialize-chunk-embeddings --experiment admin_materialize_...
```

TODO: 실제 실험 실행 순서는 사용할 source, API key, local DB 상태, Admin GUI에서 생성한 `admin_gen_*`, `admin_eval_*` config에 따라 달라진다. 여기서는 repository에 있는 공통 entry point만 보존한다.

## 프로젝트 구조

| 경로 | 역할 |
| --- | --- |
| `backend/` | Spring Boot API, Admin orchestration, Flyway migration, React static bundle |
| `frontend/` | React Admin Console과 Chat surface |
| `pipeline/` | 문서 수집/전처리/query generation/gating/memory/eval Python pipeline |
| `configs/app/` | source catalog, application 설정, model/runtime catalog, chunking 설정 |
| `configs/prompts/` | query generation, self-eval, translation, summary, rewrite prompt assets |
| `configs/experiments/` | 재현 가능한 pipeline/eval experiment YAML |
| `data/eval/` | retrieval-aware JSONL 평가 dataset |
| `data/reports/` | retrieval/answer eval 결과 산출물 |
| `docs/` | API, architecture, UI, experiment 문서 |
| `infra/` | Docker/PostgreSQL/SQL 운영 문서 |
| `scripts/` | local bootstrap, backend 실행, pipeline wrapper, dataset helper scripts |

## 평가 지표

Retrieval evaluation:

- `Recall@5`: expected chunk/doc 중 top-5에 포함된 비율
- `Hit@5`: expected chunk/doc이 하나라도 top-5에 포함되었는지
- `MRR@10`: 첫 expected hit의 reciprocal rank
- `nDCG@10`: expected chunk/doc의 순위 품질
- rewrite 관련: `adoption_rate`, `rewrite_rejection_rate`, `avg_confidence_delta`, `rewrite_gain_mrr`, `rewrite_gain_ndcg`, `bad_rewrite_rate`

Answer evaluation:

- `correctness`, `grounding`, `hallucination_rate`
- `keyword_overlap`, `answer_relevance`, `faithfulness`
- `context_precision`, `context_recall`

성능 지표:

- `avg_query_eval_total_latency_ms`
- `avg_final_rewrite_latency_ms`
- `avg_pure_rewrite_latency_ms`
- 각 latency metric의 sample count와 excluded count

현재 코드에서 평균 latency는 answer evaluation summary와 Admin RAG performance card에 반영된다. retrieval p95 같은 분포 지표는 README에서 구현된 것으로 주장하지 않는다.

## 연구 기여

production RAG 제품보다는 한국어 query와 영어 기술 문서 사이의 retrieval mismatch를 통제된 조건에서 반복 측정하기 위한 연구/prototype 환경에 가깝다.

- 같은 corpus와 grounded eval dataset에서 raw baseline, gated memory, rewrite를 비교할 수 있다.
- A/B/C/D/E 전략별 synthetic query를 table과 batch 단위로 분리해 ablation을 수행할 수 있다.
- Admin GUI에서 gating/rewrite/retrieval 파라미터를 조정하면서 DB snapshot과 config를 남겨 재현성을 확보한다.
- anchor/glossary/rewrite detail을 sample 단위로 열어 failure case를 분석할 수 있다.

## 제한 사항과 향후 작업

- 로컬 DB 상태에 따라 실제 import된 corpus/document/chunk 수가 달라진다. source config와 repository artifact 기준으로만 설명했다.
- 일부 오래된 문서/데이터 출력은 터미널 환경에서 인코딩이 깨져 보일 수 있다. root 문서는 새로 정리했지만 전체 문서 정리는 별도 작업이 필요하다.
- `eval-retrieval`은 rewrite latency를 detail row에 남기지만, run-level 성능 요약은 주로 `eval-answer`의 평균 latency에 집중되어 있다. p95 retrieval latency, stage-level cost summary는 향후 보강 대상이다.
- online chat 경로와 offline evaluation 경로의 retriever 구현은 목적이 다르며 완전히 동일한 실험 경로로 보장하지 않는다.
- `arahansa-github-io-docs-spring` source config는 존재하지만 canonical Spring synthetic 실험 source에서는 제외되어 있다.
- LLM API, embedding model 다운로드, DB ANN materialization은 외부 key와 로컬 리소스 상태에 영향을 받는다.
