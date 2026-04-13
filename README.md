# Query Forge

Query Forge는 연구 중심 RAG(Retrieval-Augmented Generation) 시스템을 구축하기 위한 프로젝트다. 이 저장소는 문서 수집부터 전처리, synthetic query 생성, 품질 게이팅, memory 구성, 평가 데이터셋 구축, retrieval/answer 평가까지의 전 과정을 일관된 파이프라인으로 관리한다.

프로젝트의 핵심 목적은 단순 데모가 아니라, A/B/C/D 생성 전략을 분리해 실험하고 재현 가능한 방식으로 성능 차이를 검증하는 데 있다. 이를 위해 파이프라인 순서, 데이터 스키마, 실험 설정을 코드와 설정 파일로 명시하고, 운영 화면(Admin GUI)에서 주요 파라미터를 조정할 수 있도록 설계했다.

## 연구 개요

이 프로젝트는 synthetic query를 네 가지 방법론으로 생성한다. A는 번역체 성향의 한국어 질의, B는 자연스러운 한국어 개발자 질의, C는 구조적이고 다단계 추론을 유도하는 질의, D는 한국어와 영어 기술 용어가 섞인 code-mixed 질의를 다룬다. 각 전략은 실험 단계에서 서로 대체 가능한 옵션이 아니라, 비교 대상 자체로 취급된다.

연구 관점에서 중요한 점은 전략별 결과를 명확히 분리하고, 같은 코퍼스를 기준으로 질의 스타일만 바꿔 비교 가능하게 유지하는 것이다. 이 원칙은 생성 단계뿐 아니라 gating, memory, eval 단계까지 동일하게 적용된다.

## 연구 상세

synthetic query 원천 데이터는 `synthetic_queries_raw_a`, `synthetic_queries_raw_b`, `synthetic_queries_raw_c`, `synthetic_queries_raw_d`처럼 전략별 테이블로 분리해 저장한다. 단일 raw 테이블로 합치지 않는 이유는 전략 효과를 단계별로 추적하고, 실험 중간에 특정 전략만 선택적으로 재실행할 수 있게 하기 위해서다.

quality gating은 전략 선택을 먼저 수행한 뒤 적용한다. 즉 A만 게이팅하거나, B만 게이팅하거나, A와 C를 묶어 게이팅하는 시나리오를 모두 지원한다. 또한 게이팅 임계값과 가중치는 요청 단위로 동적으로 주입되며, Admin GUI에서 조정 가능한 값을 하드코딩하지 않는 것을 기본 원칙으로 한다.

평가 데이터셋은 일반적인 QA 쌍이 아니라 retrieval-aware 구조를 따른다. 각 샘플은 `user_query_ko`와 함께 `expected_doc_ids`, `expected_chunk_ids`, `expected_answer_key_points`를 포함해 정답의 근거 문서와 청크를 명시해야 한다. 이 제약은 hallucination을 줄이고, retrieval 품질과 answer 품질을 분리해 진단하기 위한 설계다.

## 방법론

방법론의 중심은 실험 설정 기반 실행이다. 프롬프트와 실험 파라미터는 `configs/`에서 관리하고, 실행 결과는 run 단위로 추적한다. 데이터베이스 스키마는 Flyway migration으로 버전 관리하며, 특히 전략 분리 저장 구조는 `V17__split_strategy_raw_tables_and_drop_legacy_raw.sql`을 통해 반영된다.

실행 계층은 Python 파이프라인과 Spring Boot 백엔드로 나뉜다. Python 파이프라인은 데이터/실험 처리의 본체를 담당하고, 백엔드는 Admin GUI와 실행 오케스트레이션, 실험 결과 조회 기능을 제공한다. 이 구조를 통해 연구 코드와 운영 인터페이스를 분리하면서도 실험 반복 비용을 줄인다.

## End-to-End 프로젝트 흐름

프로젝트의 고정 파이프라인 순서는 아래와 같으며, 단계 순서를 변경하지 않는 것을 원칙으로 한다.

```text
collect -> preprocess -> chunk -> glossary -> import
-> generate-queries -> gate-queries -> build-memory
-> build-eval-dataset -> eval-retrieval -> eval-answer
```

초기 다섯 단계는 코퍼스를 수집하고 검색 가능한 형태로 정규화하는 구간이다. 이후 단계는 synthetic query 생성과 품질 통제, memory 구성, 평가셋 구축, retrieval/answer 성능 측정으로 이어진다. 실험 보고서는 각 실행 결과를 바탕으로 전략별 성능 차이를 해석하는 방식으로 작성한다.

## 빠른 시작

로컬 실행은 PostgreSQL(pgvector) 준비 후 파이프라인 명령을 순차 실행하는 방식이 기본이다. 루트에서 `make up`으로 데이터베이스를 띄우고, `.env.example`을 참고해 API 키와 실험 설정을 구성한 뒤, `make generate-queries EXPERIMENT=<실험명>`처럼 단계별 명령을 실행하면 된다. 백엔드 Admin GUI가 필요하면 `make run-backend`로 서버를 실행해 상태와 결과를 확인한다.

## 디렉터리 안내

`pipeline/`은 연구 파이프라인 코드, `backend/`는 Admin API/UI와 DB migration, `configs/`는 실험/프롬프트 설정, `data/`는 산출물과 데이터셋, `docs/`는 문서화 자산, `infra/`는 인프라 및 SQL 관련 파일, `scripts/`는 보조 자동화 스크립트를 담당한다. 각 디렉터리의 구현 세부 사항은 해당 디렉터리의 `README.md`에서 확인할 수 있다.
