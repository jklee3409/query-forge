# Query-Forge

> Synthetic Query Memory and Retrieval-Aware Query Rewrite Framework for Cross-Lingual RAG

Query-Forge는 **한국어 개발자 질의로 영어 기술 문서를 검색할 때 발생하는 Cross-Lingual Retrieval Mismatch를 줄이기 위한 RAG 연구 프로젝트**입니다. 짧고 압축된 한국어 질의, code-mixed 표현, 번역된 기술 개념이 영어 공식 문서의 API 이름, 설정 키, 클래스명, 어노테이션, 명령어, 섹션 제목과 잘 맞지 않을 때 retrieval 품질이 어떻게 흔들리는지 측정하고, 이를 synthetic query memory와 selective rewrite로 개선할 수 있는지 검증합니다.

예를 들어 사용자는 다음처럼 묻습니다.

```text
트랜잭션 롤백 안됨
시큐리티 로그인 유지
JPA N+1 해결
쿠버네티스 파드 재시작 원인
```

하지만 근거 문서는 대개 다음과 같은 영어 anchor를 중심으로 작성됩니다.

```text
@Transactional
rollbackFor
SessionCreationPolicy
EntityGraph
CrashLoopBackOff
```

이 간극을 단순 번역이나 embedding model 교체만으로 해결하기는 어렵습니다. Query-Forge는 영어 또는 한국어 원문 기술 문서에서 검색 친화적인 synthetic query를 만들고, 품질 검증을 거친 query를 memory snapshot으로 고정한 뒤, 사용자 질의를 재작성할 때 few-shot 예시와 anchor hint로 활용합니다. 중요한 점은 synthetic query를 최종 검색어로 그대로 대체하지 않는다는 것입니다. 기본 RAG 평가 경로는 raw query와 LLM rewrite candidate를 같은 조건에서 비교하고, retrieval evidence가 좋아질 때만 rewrite를 선택합니다.

---

## 연구 목표

프로젝트는 production chatbot보다 **통제 가능한 RAG 실험 환경**에 가깝습니다. 목표는 하나의 corpus, dataset, gating snapshot, retrieval config를 고정한 상태에서 다음 질문을 반복 측정하는 것입니다.

1. 한국어 또는 code-mixed 사용자 질의는 영어 기술 문서 retrieval에서 어떤 실패 패턴을 만드는가?
2. A/B/C/D/E synthetic query generation 전략은 memory와 rewrite 품질에 서로 다른 영향을 주는가?
3. 한국어 원문 corpus에서는 F/G 확장 전략이 어떤 baseline 또는 transfer 조건을 제공하는가?
4. Quality gating은 부정확하거나 retrieval utility가 낮은 synthetic query를 실제로 줄이는가?
5. Query rewrite는 언제 raw query보다 나아지고, 언제 raw query를 유지해야 하는가?
6. Anchor 보존, latency, answer grounding까지 포함했을 때 end-to-end RAG 품질은 어떻게 변하는가?

---

## 고정 파이프라인

프로젝트의 핵심 pipeline 순서는 다음과 같이 고정되어 있습니다.

```text
collect
  -> preprocess
  -> chunk
  -> glossary
  -> import
  -> generate-queries
  -> gate-queries
  -> build-memory
  -> build-eval-dataset
  -> eval-retrieval
  -> eval-answer
```

Spring Boot backend와 React Admin Console은 이 pipeline을 운영하기 위한 control plane이고, Python pipeline이 실제 corpus 처리, query generation, gating, memory build, retrieval/answer evaluation을 수행합니다. Admin에서 만든 `admin_gen_*`, `admin_gate_*`, `admin_eval_*` experiment config는 runtime artifact로 취급하며, 재현 가능한 실험 조건은 명명된 preset 또는 DB run record와 `data/reports/` 산출물로 남깁니다.

---

## Synthetic Query Strategies

A/B/C/D/E는 영어 원문 기술 문서 실험의 core strategy family입니다. 전략의 핵심 정의는 query 표면 문체가 아니라 **generation methodology**입니다.

| Strategy | Generation Flow | 연구 가설 | 출력/역할 |
| --- | --- | --- | --- |
| A | EN Document -> EN Extractive Summary -> EN Synthetic Query -> KO Synthetic Query | 영어 evidence에서 먼저 intent를 만들면 한국어 자연화 전에 기술 의미와 anchor를 더 잘 보존할 수 있다. | Korean query, 영어 문서에 대한 anchor-fidelity 조건 |
| B | EN Document -> KO Translation -> KO Summary -> KO Synthetic Query | 한국어로 이해된 evidence에서 query를 만들면 자연스러운 한국어 개발자 질의에 가까워진다. | Korean query, translation-mediated 조건 |
| C | EN Document + KO Summary -> KO Synthetic Query | 영어 evidence와 한국어 이해 맥락을 함께 쓰면 anchor fidelity와 Korean usability를 균형 있게 얻을 수 있다. | Korean retrieval-oriented query |
| D | EN Document (+ summary context) -> KO Query -> Code-Mixed Query | 한국어 문장 구조에 영어 기술 용어를 남긴 code-mixed query가 실제 개발자 검색 방식에 가깝다. | Korean/code-mixed query |
| E | EN Document -> EN Extractive Summary -> EN Synthetic Query | 번역 효과를 제거한 영어 질의는 영어 corpus retrieval의 baseline 또는 upper-bound 역할을 한다. | English query |

F/G는 한국어 원문 corpus 확장 전략입니다. core A/B/C/D/E를 대체하지 않고, 한국어 source에서 출발하는 실험에만 사용합니다.

| Strategy | Generation Flow | 연구 목적 |
| --- | --- | --- |
| F | KR Document -> KR Summary -> KR Query -> EN Query | 한국어 원문에서 형성된 intent를 영어 retrieval 환경으로 transfer할 수 있는지 평가합니다. |
| G | KR Document -> KR Summary -> KR Query | 번역 단계를 제거한 한국어 source / 한국어 query baseline을 제공합니다. |

DB 저장 구조도 전략 분리를 따릅니다. 현재 schema는 `synthetic_queries_raw_a`부터 `synthetic_queries_raw_g`까지 물리 table을 분리하고, 조회용으로만 `synthetic_queries_raw_all` union view를 사용합니다. Gating, memory build, evaluation은 target strategy 또는 strategy set을 명시적으로 받아야 합니다.

---

## Quality Gating

Quality gating은 synthetic query를 memory로 쓰기 전에 품질과 utility를 검증하는 단계입니다. 현재 구현은 generation batch와 strategy를 기준으로 gating 대상을 고르고, stage별 설정을 request 단위로 주입합니다.

| Stage | 현재 구현 |
| --- | --- |
| Rule Filter | 길이, token 수, copy ratio, 한국어 비율, code-mixed 한국어 비율 같은 threshold를 동적으로 적용합니다. |
| LLM Self-Evaluation | fluency, adequacy, answerability, naturalness 등 prompt 기반 점수를 기록합니다. |
| Retrieval Utility | target document/chunk가 top-k 또는 top-10 retrieval에 들어오는지 평가합니다. |
| Diversity Dedup | 같은 chunk나 document에 유사 query가 몰리는 현상을 줄입니다. |
| Final Score | utility, LLM score, novelty weight를 조합해 accepted/rejected를 결정합니다. |

Admin GUI는 gating stage flag, rule threshold, gating weight, utility weight, retriever mode, dense embedding model을 조정할 수 있습니다. Runtime 선택지는 `configs/app/model_catalog.yml`에서 관리되며, backend는 catalog에 없는 model, retriever mode, retrieval backend, rewrite policy, rewrite profile을 `400 Bad Request`로 거부합니다.

---

## Memory와 Query Rewrite

Accepted synthetic query는 `memory_entries`로 materialize됩니다. Memory row에는 원 synthetic query, target document/chunk, generation strategy, gating snapshot, score, glossary/canonical anchor metadata, embedding 정보가 함께 저장됩니다. Snapshot은 실험 조건입니다. 같은 dataset이라도 A-only snapshot, C-only snapshot, ungated snapshot, full-gating snapshot은 서로 다른 실험 조건으로 취급합니다.

기본 rewrite 흐름은 다음과 같습니다.

```text
Raw User Query
  -> Raw Query Retrieval
  -> Synthetic Memory Lookup
  -> Few-shot Examples / Rewrite Hints
  -> LLM Rewrite Candidate Generation
  -> Raw vs Candidate Retrieval Comparison
  -> Selective Rewrite Decision
  -> Final Retrieval
```

Synthetic memory는 최종 검색어가 아니라 rewrite 예시와 retrieval-oriented hint입니다. 최종 evaluation query는 raw query 또는 LLM이 생성한 rewrite candidate 중 하나입니다. Admin RAG 기본 실행은 `raw_only`와 selective rewrite mode를 비교하며, legacy `rewrite_always`나 direct memory retrieval은 명시적인 ablation 조건으로만 다룹니다.

Anchor는 이 과정의 핵심 분석 단위입니다. Query-Forge는 user query, synthetic memory, glossary, corpus evidence에서 retrieval-critical technical term을 추출하고, normalization/canonical mapping/multi-source relation을 통해 rewrite candidate가 필요한 기술 anchor를 보존하는지 평가합니다. Anchor injection과 multi-source anchor hints는 opt-in grounding control이며, 원래 intent와 무관한 term을 추가하는 용도로 쓰지 않습니다.

---

## Evaluation

평가 dataset은 단순 question-answer 목록이 아닙니다. Retrieval, rewrite, anchor quality를 같은 기준으로 비교할 수 있도록 각 sample은 grounding metadata를 포함해야 합니다.

필수 구조는 다음과 같습니다.

```json
{
  "sample_id": "...",
  "split": "test",
  "target_method": "A",
  "query_language": "ko",
  "user_query_ko": "...",
  "user_query_en": null,
  "dialog_context": {},
  "expected_doc_ids": ["..."],
  "expected_chunk_ids": ["..."],
  "expected_answer_key_points": ["..."],
  "query_category": "...",
  "difficulty": "...",
  "single_or_multi_chunk": "...",
  "source_product": "...",
  "source_version_if_available": "..."
}
```

현재 `data/eval/`에는 Spring short-user KR/EN, Spring method-compressed A/B/C/D/E, Python KR-source KO/EN, PostgreSQL KR/EN, Kubernetes KR/EN, anchor-translated variants, rewrite challenge/probe dataset artifact가 있습니다. Dataset artifact와 runtime DB dataset은 별개일 수 있으므로, 실험 결과를 해석할 때는 dataset key/version과 snapshot ID를 함께 확인해야 합니다.

평가 지표는 다음 계층으로 나눕니다.

| Layer | Metrics |
| --- | --- |
| Retrieval | Recall@5, Hit@5, MRR@10, nDCG@10 |
| Rewrite | adoption rate, rejection rate, confidence delta, rewrite gain, bad rewrite rate |
| Anchor | supported/risky/unsupported anchor count, anchor precision, grounded rate, useful/risky rate |
| Answer | correctness, grounding, hallucination rate, faithfulness, context precision/recall |
| Performance | total/stage latency, retrieval latency, rewrite latency overhead, answer-eval latency payload |

최근 구현은 `eval-answer` 단계에서 sample-level latency를 기록하고, run-level `metrics_json.performance`에 `avg_query_eval_total_latency_ms`, `avg_final_rewrite_latency_ms`, `avg_pure_rewrite_latency_ms`와 sample count를 저장합니다. 이전 run에는 이 payload가 없을 수 있어 Admin UI는 legacy result로 표시합니다.

---

## 대상 Corpus와 Source Scope

`configs/app/sources/`에는 다음 source preset이 있습니다.

| 구분 | Source |
| --- | --- |
| Spring 공식 영어 문서 | `spring-boot-reference`, `spring-framework-reference`, `spring-security-reference`, `spring-data-jpa-reference`, `spring-data-commons-reference` |
| PostgreSQL/PostGIS 영어 문서 | `postgresql-docs-current`, `postgis-docs-current` |
| Kubernetes 영어 문서 | `kubernetes-docs-current` |
| Python 한국어 문서 | `docs-python-org-ko-3-14` |
| 한국어 Spring community 문서 | `arahansa-github-io-docs-spring` |

다만 source preset이 곧 모든 synthetic generation 경로에서 허용된다는 뜻은 아닙니다. 현재 backend guard는 unscoped Admin synthetic generation에서 A/B/C/D/E를 다섯 개 Spring reference source로 제한하고, F/G를 `docs-python-org-ko-3-14`로 제한합니다. `arahansa-github-io-docs-spring`은 source config는 존재하지만 synthetic generation에서는 명시적으로 거부됩니다. Domain workspace를 사용할 때는 domain source membership과 source language 정책에 따라 허용 source와 method가 결정됩니다.

PostgreSQL/PostGIS와 Kubernetes는 corpus/eval artifact와 source preset이 준비되어 있으며, PostgreSQL domain 실험 산출물도 `data/reports/`와 DB run 기록에 남아 있습니다. 로컬 DB에 실제로 import된 문서 수와 active snapshot은 환경마다 달라질 수 있으므로 README는 고정 row count를 진실로 주장하지 않습니다.

---

## System Architecture

```text
configs/app, configs/prompts, configs/experiments
        │
        ▼
Python Pipeline CLI ───────────────┐
        │                          │
        ▼                          ▼
data/raw, data/processed,   PostgreSQL 16 + pgvector
data/eval, data/reports            ▲
        ▲                          │
        │                          │
Spring Boot Backend / Admin API ───┘
        │
        ▼
React Admin Console + Chat Surface
```

| Component | 현재 역할 |
| --- | --- |
| Python Pipeline | collector, normalize, chunk, glossary/anchor extraction, corpus import, synthetic generation A-G, quality gating, memory build, eval dataset import/build, retrieval/answer evaluation, chunk embedding materialization을 수행합니다. |
| Spring Boot Backend | Admin Console API, Pipeline Admin API, Corpus Admin API, Domain Admin API, Prompt Admin API, RAG API, LLM job orchestration, Flyway migration, React static bundle serving을 담당합니다. |
| React Admin Console | `/admin/pipeline`, `/admin/synthetic-queries`, `/admin/quality-gating`, `/admin/rag-tests`, Prompt/Domain workspace 화면을 제공합니다. |
| PostgreSQL + pgvector | corpus, split synthetic raw tables, gated query, memory entries, query/chunk embeddings, eval samples/results, prompt catalog, domain scope, LLM job 상태를 저장합니다. |
| Config Catalog | `model_catalog.yml`은 Admin runtime option allowlist와 defaults를 제공하고, prompt assets는 file/DB revision 및 active binding으로 관리됩니다. |
| Retrieval Runtime | BM25 only, dense only, hybrid, local retrieval, DB ANN retrieval, optional Cohere rerank fallback을 지원합니다. |
| LLM Runtime | Pipeline client는 Gemini, OpenAI-compatible, Groq, mock provider 경로를 지원합니다. Admin runtime catalog에는 현재 Gemini/OpenAI 계열 model allowlist가 등록되어 있습니다. |

Backend runtime은 Spring Data JPA가 아니라 Spring Web/JDBC/Flyway 중심입니다. Online `RagService.ask`는 hash memory embedding path를 사용하고, Admin dense/DB-ANN evaluation은 `memory_entries.query_embedding`과 `chunk_embeddings`를 사용하는 별도 경로로 분리되어 있습니다.

---

## 구현된 기능

| 영역 | 구현 상태 |
| --- | --- |
| Pipeline Admin | source sync, collect/normalize/chunk/glossary/import/full-ingest 실행, run/step/log 조회, warning 상태, cancel/retry, domain-scoped 실행과 import 후 domain propagation을 지원합니다. |
| Corpus Admin | source/document/section/chunk/glossary 조회, raw-vs-cleaned preview, chunk boundary preview, anchor list, scoped anchor re-extraction, anchor normalization dry-run/review/approve/delete, multi-source anchor relation build, anchor eval labeling/recompute를 제공합니다. |
| Domain Workspace | `tech_doc_domain`, domain-source membership, domain method policy를 통해 Spring/Python 같은 기술 문서 domain별로 source, dataset, generation, gating, RAG history를 필터링합니다. |
| Synthetic Query Studio | A/B/C/D/E/F/G method 조회, context-aware method filtering, source/document/domain scoped generation, source-unselected all-allowed-sources config, random chunk sampling, Strategy B segmented KO translation cache와 optional Gemini Batch mode, batch delete, retry/cancel 상태와 ETA 노출을 지원합니다. |
| Split Raw Storage | `synthetic_queries_raw_a`부터 `synthetic_queries_raw_g`까지 전략별 table을 유지하고, read path는 `synthetic_queries_raw_all` view를 사용합니다. |
| Quality Gate | generation batch 기반 gating, method filtering, stage flag/threshold/weight/retriever config, Korean-ratio override, top-10 utility scoring, funnel/result pagination, ETA 노출을 지원합니다. |
| Memory Build | snapshot별 stale memory cleanup, `memory_experiment_key`, source gating run filtering, synthetic-free baseline no-op, stage-cutoff exploratory memory build를 지원합니다. |
| RAG Test Lab | dataset/snapshot 선택, explicit `source_gating_batch_id`, synthetic-free baseline, official/exploratory discipline, `gating_effect`/`rewrite_effect` bundled comparison, stage-cutoff exploratory run, run delete, custom eval dataset delete를 지원합니다. |
| Retrieval/Answer Eval | `raw_only`와 selective rewrite mode 중심으로 retrieval/answer 평가를 수행하고, query language, method compatibility, dataset-aware corpus scope, raw retrieval cache, local/DB-ANN backend를 지원합니다. |
| Rewrite/Anchor Analysis | rewrite logs, memory candidates, LLM rewrite candidates, retrieved chunks, anchor injection, canonical anchor hints, multi-source anchor hints, `rag_rewrite_anchor_eval` 기반 anchor quality summary를 제공합니다. |
| Prompt Studio | prompt asset 조회, DB-backed revision 생성, active binding 관리, fallback prompt, validation endpoint를 제공합니다. |
| Runtime Options | `/api/admin/console/runtime/options`가 `model_catalog.yml` 기반 provider/model/retriever/retrieval backend/rewrite profile/default range를 반환하고, backend가 out-of-catalog 값을 거부합니다. |
| DB ANN | `chunk_embeddings` materialization/status API, `halfvec(384)` + HNSW index 기반 chunk retrieval, memory ANN lookup, hybrid lexical/technical candidate union을 지원합니다. |
| Online RAG API | `/api/chat/ask`, `/api/rewrite/preview`, `/api/queries/{id}/trace`, eval report 조회, admin reindex/experiment command endpoint가 있습니다. |
| Frontend Admin | React 19 + Vite 기반 backoffice로, RAG compare dock, query-by-query detail modal, ETA component, dark/light theme, domain-scoped pages, runtime-driven retriever defaults를 제공합니다. |

---

## 기술 스택

| Layer | Technology |
| --- | --- |
| Backend | Java 21, Spring Boot 3.3.5, Spring Web, AOP, Actuator, Validation, Spring JDBC, Flyway, PostgreSQL driver |
| Backend Test | JUnit 5, Spring Boot Test, Testcontainers PostgreSQL |
| Frontend | React 19, Vite 5, ESLint |
| Pipeline / ML | Python 3.12, psycopg 3, PyYAML, requests, BeautifulSoup, sentence-transformers, YAKE, kiwipiepy, spaCy, Stanza, Langfuse |
| Database | PostgreSQL 16, pgvector, pg_trgm, HNSW vector index, `halfvec(384)` chunk embeddings |
| Retrieval | BM25, dense retrieval, hybrid fusion, local retriever cache, DB ANN, optional Cohere rerank path |
| LLM | Gemini native/OpenAI-compatible path, OpenAI-compatible provider, Groq, mock provider |
| Infra | Docker Compose, Gradle wrapper, Makefile, PowerShell helper scripts |

---

## Project Structure

```text
backend/                  Spring Boot API, Admin orchestration, Flyway migrations, bundled React static assets
frontend/                 React Admin Console and Chat surface
pipeline/                 Document processing, query generation, gating, memory build, evaluation CLI
configs/app/              application settings, chunking config, model/runtime catalog, source presets
configs/prompts/          query generation, self-eval, translation, summary, rewrite prompt assets
configs/experiments/      reusable experiment YAML presets and generated admin runtime configs
data/raw/                 collected source artifacts
data/processed/           normalized sections, chunks, glossary artifacts
data/synthetic/           synthetic query and memory-related artifacts
data/eval/                retrieval-aware JSONL evaluation datasets
data/reports/             audit, retrieval, answer, RAG comparison reports
docs/                     API, architecture, UI, experiment documentation
infra/                    Docker, PostgreSQL, SQL operation notes
scripts/                  local bootstrap, backend runner, pipeline wrappers, dataset build/audit/repair scripts
```

각 주요 디렉터리는 `index.md`와 `progress.md`를 갖고 있으며, 구조나 역할이 바뀌면 해당 문서도 함께 갱신하는 것을 원칙으로 합니다.

---

## Getting Started

로컬 실행 전에는 `.env.example`을 참고해 DB와 LLM 관련 환경 변수를 준비합니다. LLM API key가 없으면 generation, LLM self-evaluation, rewrite 단계는 실패할 수 있지만, DB 기동이나 일부 mock/non-LLM 경로 점검은 가능합니다.

### 1. DB 실행

```bash
docker compose up -d postgres
```

또는 Makefile을 사용할 수 있습니다.

```bash
make up
```

Windows PowerShell에서는 helper script도 사용할 수 있습니다.

```powershell
.\scripts\dev-up.ps1
```

### 2. Backend 실행

```bash
./backend/gradlew -p backend bootRun
```

Windows PowerShell:

```powershell
.\backend\gradlew.bat -p .\backend bootRun
```

또는:

```powershell
.\scripts\run-backend.ps1
```

Backend는 기본적으로 `http://localhost:8080`에서 실행되며, React production bundle이 준비되어 있으면 `/admin` 경로로 Admin Console을 서빙합니다.

### 3. Frontend 개발 서버 실행

```bash
cd frontend
npm install
npm run dev
```

Vite 개발 서버는 별도 포트에서 실행되며, production bundle을 backend static resource로 반영하려면 `npm run build`가 필요합니다.

### 4. Pipeline package 설치

```bash
pip install -e ./pipeline
```

`pipeline/pyproject.toml`은 Python 3.12 이상을 요구합니다. 저사양 로컬 환경에서는 `sentence-transformers`, spaCy, Stanza 같은 ML dependency가 부담될 수 있으므로, 필요한 단계만 좁혀 실행하는 것이 좋습니다.

### 5. Pipeline 단계 실행

Makefile wrapper:

```bash
make collect-docs EXPERIMENT=scaffold
make preprocess EXPERIMENT=scaffold
make chunk-docs EXPERIMENT=scaffold
make glossary-docs EXPERIMENT=scaffold
make import-corpus EXPERIMENT=scaffold
make generate-queries EXPERIMENT=gen_a
make gate-queries EXPERIMENT=full_gating
make build-memory EXPERIMENT=e2e_eval_a
make build-eval-dataset EXPERIMENT=e2e_eval_a
make eval-retrieval EXPERIMENT=e2e_eval_a
make eval-answer EXPERIMENT=e2e_eval_a
```

CLI 직접 호출:

```bash
python pipeline/cli.py generate-queries --experiment gen_a
python pipeline/cli.py gate-queries --experiment full_gating
python pipeline/cli.py materialize-chunk-embeddings --experiment admin_materialize
python pipeline/cli.py import-eval-jsonl --eval-test data/eval/human_eval_short_user_test_80.jsonl --keep-existing
```

PowerShell wrapper:

```powershell
.\scripts\pipeline.ps1 generate-queries -Experiment gen_a
```

### 6. Admin Console에서 실험 실행

일반적인 운영 흐름은 다음과 같습니다.

1. `/admin/pipeline`에서 source sync 또는 collect/import 상태를 확인합니다.
2. `/admin/synthetic-queries`에서 domain/source/method를 선택해 generation batch를 실행합니다.
3. `/admin/quality-gating`에서 generation batch와 gating preset을 선택하고 threshold/weight/retriever config를 고정합니다.
4. `/admin/rag-tests`에서 eval dataset, explicit gating snapshot, retrieval backend, rewrite profile을 선택해 RAG test를 실행합니다.
5. Compare view와 detail modal에서 raw vs rewrite, retrieved chunks, memory candidates, anchor quality, latency payload를 함께 확인합니다.

공식 비교 run은 명시적인 snapshot identity가 필요합니다. Synthetic-backed run에서 auto-latest snapshot에 기대는 방식은 현재 연구 규칙과 맞지 않습니다.

---

## 연구 포지셔닝

Query-Forge의 초점은 더 좋은 chatbot UI가 아니라 **검색 질의를 어떻게 바꿔야 영어 기술 문서 근거를 더 안정적으로 찾는가**입니다. 그래서 구현은 retriever, synthetic generation, gating, rewrite, anchor preservation, answer grounding, latency를 한 pipeline에서 함께 기록하도록 설계되어 있습니다.

실험 결과를 해석할 때는 항상 다음 조건을 함께 봐야 합니다.

- corpus source와 domain
- eval dataset key/version/query language
- generation strategy와 raw table
- gating preset과 `source_gating_batch_id`
- retrieval backend와 retriever mode
- rewrite profile, threshold, anchor injection 여부
- answer-eval 및 latency metric 생성 시점

이 조건이 다르면 같은 strategy 이름을 사용하더라도 직접 비교할 수 없습니다.

---

## 프로젝트 맥락

이 저장소는 경희대학교 졸업 연구 프로젝트로 개발되었습니다. 구현은 통제된 실험과 failure case 분석을 지원하기 위한 연구용 prototype이며, 운영 최적화가 완료된 production RAG service라고 주장하지 않습니다.
