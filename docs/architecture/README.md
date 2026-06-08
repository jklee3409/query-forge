# Architecture Docs

`docs/architecture/`는 Query-Forge의 구조와 실행 방식을 설명합니다. 이 프로젝트는 일반적인 chatbot architecture보다 corpus pipeline, strategy-separated synthetic storage, snapshot-based memory, selective rewrite, grounded evaluation을 어떻게 결합하는지가 중요합니다.

## 문서 목록

| 문서 | 역할 |
| --- | --- |
| `overview.md` | backend, frontend, pipeline, configs, data, infra의 전체 연결 구조를 요약합니다. |
| `corpus_storage.md` | PostgreSQL corpus schema, source/document/section/chunk/glossary relation, import 흐름을 설명합니다. |
| `pipeline_orchestration.md` | Spring Boot가 Python pipeline command를 subprocess로 실행하고 run/step/log/artifact를 추적하는 방식을 설명합니다. |
| `domain_pipeline_integration_design.md` | `tech_doc_domain`, source membership, domain-scoped synthetic/gating/RAG/eval 흐름의 설계와 운영 제약을 설명합니다. |

## 읽는 기준

Corpus와 DB 관계를 먼저 이해해야 한다면 `corpus_storage.md`를 읽고, Admin Console에서 버튼을 눌렀을 때 어떤 Python command와 DB record가 생기는지 보려면 `pipeline_orchestration.md`를 읽습니다. Domain Atlas와 domain workspace가 source/method/dataset 범위를 어떻게 제한하는지 보려면 `domain_pipeline_integration_design.md`가 기준입니다.

## 핵심 아키텍처 원칙

Synthetic raw storage는 A/B/C/D/E/F/G 전략별 table로 분리됩니다. Quality gating은 strategy와 generation batch를 명시적으로 고르고, memory build는 snapshot을 독립 실험 조건으로 다룹니다. RAG rewrite는 memory retrieval result를 최종 query로 대체하지 않고, LLM rewrite candidate를 생성하기 위한 example/hint로 사용합니다. Retrieval/answer evaluation은 같은 grounded eval dataset과 같은 snapshot 조건에서 raw vs rewrite를 비교해야 합니다.
