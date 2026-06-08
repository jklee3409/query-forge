# Pipeline

`pipeline/`은 Query-Forge의 연구 실행 본체입니다. Spring Boot backend가 control plane이라면, 이 디렉터리는 문서 수집, 정제, chunking, glossary/anchor 추출, corpus import, synthetic query generation, quality gating, memory build, eval dataset import/build, retrieval eval, answer eval을 실제로 수행합니다.

Pipeline은 AGENTS에 정의된 고정 순서를 지켜야 합니다. 기본 흐름은 `collect -> preprocess -> chunk -> glossary -> import -> generate-queries -> gate-queries -> build-memory -> build-eval-dataset -> eval-retrieval -> eval-answer`입니다. 각 단계는 experiment YAML이나 CLI 인자로 실행 조건을 받고, DB와 `data/` artifact를 함께 사용합니다.

## CLI 명령

| 명령 | 역할 |
| --- | --- |
| `collect-docs` | `configs/app/sources`의 source preset을 읽어 HTML JSONL을 `data/raw/`에 저장합니다. |
| `preprocess` | raw HTML을 section JSONL로 정제하고 본문 구조를 보존합니다. |
| `chunk-docs` | section을 retrieval chunk, glossary, neighbor relation SQL, visualization으로 변환합니다. |
| `glossary-docs` | section 입력에서 glossary만 다시 추출합니다. |
| `extract-anchor-candidates` | chunk JSONL에서 anchor 후보를 추출하며 backend anchor re-extraction API와 같은 로직을 공유합니다. |
| `import-corpus` | raw/section/chunk/glossary artifact를 PostgreSQL corpus table에 upsert하고 domain propagation을 지원합니다. |
| `generate-queries` | A/B/C/D/E/F/G synthetic query를 strategy-specific raw table에 저장합니다. |
| `gate-queries` | rule, LLM self-eval, retrieval utility, diversity 기반 quality gating을 실행합니다. |
| `materialize-chunk-embeddings` | DB ANN 평가를 위한 `chunk_embeddings`를 materialize합니다. |
| `build-memory` | accepted synthetic query를 snapshot-aware `memory_entries`로 구성합니다. |
| `build-eval-dataset` | corpus-grounded eval dataset 후보를 생성합니다. |
| `import-eval-jsonl` | retrieval-aware JSONL eval dataset을 DB에 등록합니다. |
| `eval-retrieval` | raw/query rewrite/memory condition별 retrieval metric과 detail report를 생성합니다. |
| `eval-answer` | answer-level correctness, grounding, hallucination, latency payload를 평가합니다. |

## 소스 구조

```text
collectors/     source preset 기반 HTML collector
preprocess/     normalize, chunking, glossary, anchor candidate extraction
loaders/        corpus document/section/chunk/glossary/relation import
common/         experiment config, prompt assets, LLM client, retriever, embedding, anchor utilities
generation/     A-G synthetic query generation and raw-table writes
gating/         dynamic quality gating pipeline
memory/         memory snapshot build and chunk embedding materialization
datasets/       eval dataset builder and JSONL importer
eval/           retrieval eval, answer eval, runtime rewrite/retrieval logic, LLM stability runner
tests/          stage-level regression tests
```

## Synthetic, Gating, Memory

`generation/synthetic_query_generator.py`는 A-G 전략을 모두 지원합니다. A-E는 영어 기술 문서 core strategy family이고, F/G는 한국어 원문 corpus 확장 전략입니다. Raw write table은 `synthetic_queries_raw_a`부터 `synthetic_queries_raw_g`까지 분리되며, read path는 DB view `synthetic_queries_raw_all`을 사용합니다.

`gating/quality_gating.py`는 generation strategy와 generation batch를 기준으로 대상을 좁힌 뒤 rule threshold, LLM self-eval, retrieval utility, diversity score, final score를 계산합니다. `memory/build_memory.py`는 accepted query를 snapshot 단위로 materialize합니다. 기본 RAG 평가에서 memory는 최종 검색어가 아니라 selective rewrite의 few-shot example과 anchor hint로 쓰입니다.

## 실행 예시

```powershell
python .\pipeline\cli.py collect-docs --source-id spring-framework-reference --limit 10
python .\pipeline\cli.py preprocess --input data/raw/spring_docs_raw.jsonl --output data/processed/spring_docs_sections.jsonl
python .\pipeline\cli.py chunk-docs --input data/processed/spring_docs_sections.jsonl
python .\pipeline\cli.py import-corpus --raw-input data/raw/spring_docs_raw.jsonl --sections-input data/processed/spring_docs_sections.jsonl
python .\pipeline\cli.py generate-queries --experiment gen_c
python .\pipeline\cli.py gate-queries --experiment full_gating
python .\pipeline\cli.py materialize-chunk-embeddings --experiment admin_materialize_33e62928c20e
python .\pipeline\cli.py build-memory --experiment e2e_eval_c
python .\pipeline\cli.py eval-retrieval --experiment e2e_eval_c
python .\pipeline\cli.py eval-answer --experiment e2e_eval_c
```

## 의존성

`pipeline/pyproject.toml`은 Python 3.12 이상을 요구합니다. 주요 dependency는 `psycopg`, `PyYAML`, `requests`, `beautifulsoup4`, `sentence-transformers`, `kiwipiepy`, `spaCy`, `Stanza`, `YAKE`, `langfuse`, `testcontainers`입니다. 저사양 노트북에서는 전체 pipeline이나 embedding materialization을 한 번에 실행하지 말고, source, document, dataset, experiment를 명확히 좁혀 실행해야 합니다.
