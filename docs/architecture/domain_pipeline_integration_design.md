# 기술 문서 도메인 기반 파이프라인 통합 설계

작성일: 2026-05-20

## 1. 목적

이번 설계의 목표는 Query Forge의 모든 관리자 기능을 하나의 `기술 문서 도메인` 아래로 엄격히 묶는 것이다. 도메인은 Spring, Python처럼 사용자가 관리하는 기술 문서 지식 공간이며, 문서 수집/정제/청킹/Anchor 추출, 합성 질의 생성, Quality Gating, Memory Snapshot, RAG 품질 테스트, 평가 데이터셋이 모두 이 도메인에 속해야 한다.

합성 질의 생성 방식 A/B/C/D/E/F/G와 이 방식들이 사용하는 프롬프트 자산은 도메인 상위의 전역 개념으로 유지한다. RAG 품질 테스트의 쿼리 재작성 프롬프트도 도메인에 종속되지 않는 공통 자산이다. 단, 실제 생성 배치, 쿼리 재작성 실행 이력, RAG 테스트 결과물은 항상 특정 도메인에 속한다.

## 2. 현재 프로젝트 구조 파악

### Backend

`backend`는 Spring Boot 기반 관리자 API와 RAG API, Flyway 마이그레이션, Python pipeline 실행 오케스트레이션을 담당한다.

핵심 경로는 다음과 같다.

- `admin/console`: 합성 질의 생성, Quality Gating, RAG 테스트, LLM Job 관리
- `admin/pipeline`: collect, normalize, chunk, glossary, import 실행
- `admin/corpus`: source, document, chunk, glossary, anchor 관리
- `rag`: 온라인 chat/RAG API
- `resources/db/migration`: PostgreSQL/Flyway 스키마

현재 도메인에 가까운 구분은 `source_id`, `product_name`, `eval_dataset.metadata`, `source_product`, config JSON에 분산되어 있다. `AdminConsoleService`에는 Spring/Python source allowlist와 method scope가 Java 상수로 하드코딩되어 있다.

### Frontend

`frontend`는 React + Vite 관리자 콘솔이다.

핵심 화면은 다음과 같다.

- `App.jsx`: 관리자 shell, route 선택, chat surface 이동
- `PipelinePage.jsx`: source/document/chunk/anchor/pipeline 관리
- `SyntheticPage.jsx`: generation method, source 선택, batch 실행/조회
- `GatingPage.jsx`: gating 실행, funnel/result 조회
- `RagPage.jsx`: dataset/snapshot/rewrite/retriever/RAG run 관리

현재 `/admin`은 Pipeline Monitor로 진입한다. Synthetic/RAG 화면도 Spring/Python method/source scope를 자체 상수로 재구현하고 있다.

### Pipeline

`pipeline`은 Python CLI 기반 end-to-end 연구 파이프라인이다.

고정 순서는 AGENTS 규칙에 따라 유지되어야 한다.

```text
collect -> preprocess -> chunk -> glossary -> import
-> generate-queries -> gate-queries -> build-memory
-> build-eval-dataset -> eval-retrieval -> eval-answer
```

현재 pipeline은 `source_id` 또는 `source_ids` 필터를 일부 지원한다. 하지만 도메인 ID 자체는 존재하지 않으며, retrieval/eval 범위는 `source_product`와 expected doc fallback으로 추론한다.

## 3. 현재 데이터 상태

로컬 DB 기준 기술 문서 source 분포는 다음과 같다.

| 후보 도메인 | source_id | product_name | active docs | active chunks |
|---|---|---:|---:|---:|
| Spring | spring-boot-reference | spring-boot | 85 | 249 |
| Spring | spring-data-commons-reference | spring-data-commons | 26 | 52 |
| Spring | spring-data-jpa-reference | spring-data-jpa | 110 | 224 |
| Spring | spring-framework-reference | spring-framework | 461 | 914 |
| Spring | spring-security-reference | spring-security | 189 | 551 |
| Python | docs-python-org-ko-3-14 | python | 36 | 344 |
| 제외 후보 | arahansa-github-io-docs-spring | docs_spring | 0 | 0 |

따라서 초기 backfill 기준의 실제 도메인은 다음 두 개로 본다.

- `spring`: 위 5개 Spring reference source
- `python`: `docs-python-org-ko-3-14`

`arahansa-github-io-docs-spring`은 현재 active corpus가 없고 synthetic generation에서도 disallow 처리되어 있으므로 초기 도메인에는 넣지 않는다. 필요하면 `archived` 또는 별도 `legacy` 도메인 후보로 남긴다.

합성 질의 method는 현재 A-G가 전역 method로 존재한다.

- Spring 계열: A/B/C/D/E
- Python KR source 계열: F/G

이 매핑은 현재 코드 상수로 유지되지만, 목표 구조에서는 DB의 domain method policy로 이동한다.

## 4. 현재 DB 구조와 엔티티 관계

### Corpus 계층

현재 corpus 저장소는 다음 관계를 갖는다.

```text
corpus_sources
  -> corpus_documents
      -> corpus_sections
      -> corpus_chunks
          -> chunk_embeddings
          -> corpus_chunk_relations

corpus_glossary_terms
  -> corpus_glossary_aliases
  -> corpus_glossary_evidence
```

문서와 청크는 `source_id`, `product_name`으로만 범위가 구분된다. `domain_id`는 없다.

### Synthetic Query 계층

AGENTS 규칙에 따라 raw synthetic query는 method별 물리 테이블로 분리되어 있다.

```text
synthetic_query_generation_method
  -> synthetic_query_generation_batch
      -> synthetic_queries_raw_a
      -> synthetic_queries_raw_b
      -> synthetic_queries_raw_c
      -> synthetic_queries_raw_d
      -> synthetic_queries_raw_e
      -> synthetic_queries_raw_f
      -> synthetic_queries_raw_g
          -> synthetic_query_registry
              -> synthetic_queries_gated
              -> synthetic_query_gating_result
```

`synthetic_queries_raw_all`은 read-only union view 역할을 한다. 이 구조는 절대 단일 raw table로 병합하지 않는다.

### Gating/Memory 계층

```text
quality_gating_batch
  -> synthetic_query_gating_result
  -> synthetic_queries_gated
      -> memory_entries
```

현재 gating batch는 generation batch/run ID로 출처를 추적하지만 도메인 FK가 없다. Memory는 `product`와 metadata snapshot key로 구분된다.

### Eval/RAG 계층

```text
eval_dataset
  -> eval_dataset_item
      -> eval_samples

rag_test_run
  -> rag_test_run_config
  -> rag_test_result_summary
  -> rag_test_result_detail
```

`eval_samples`는 `expected_doc_ids`, `expected_chunk_ids`, `source_product`, `query_language`를 갖는다. 도메인 구분은 dataset metadata와 sample source_product에서 추론된다.

### Job/Experiment 계층

```text
experiments
  -> experiment_runs

llm_job
  -> llm_job_item
```

`llm_job`은 generation/gating/rag FK를 선택적으로 가진다. 도메인 기준으로 Job을 빠르게 필터링할 수 있는 컬럼은 없다.

### Prompt Asset 계층

```text
prompt_assets
  -> query_generation prompts: gen_a_v1 ~ gen_g_v1
  -> rewrite prompts: selective_rewrite_v1, selective_rewrite_v2, selective_rewrite_en_v1
  -> quality/summary/translation prompts
```

현재 `prompt_assets`는 `prompt_family`, `prompt_name`, `version`, `content_path`, `content_hash`, `is_active`, `metadata`를 가진 전역 catalog다. Pipeline의 합성 질의 생성은 `configs/prompts/query_generation/gen_[a-g]_v1.md`를 로드하고 등록한다. RAG 품질 테스트의 selective rewrite는 언어별로 프롬프트 파일을 직접 로드한다.

프롬프트는 여러 기술 문서 도메인이 공유해야 하므로 `domain_id`를 부여하지 않는다. 대신 batch/run/result에는 실제 사용한 `prompt_asset_id`, version, hash를 snapshot으로 남겨 재현성을 보장해야 한다.

## 5. 현재 구조의 문제

현재 구현은 기능별로 source/product/dataset을 잘 추적하지만, `기술 문서 도메인`이 first-class entity가 아니다.

주요 문제는 다음과 같다.

- 새 도메인을 추가하려면 source config, backend allowlist, frontend allowlist, dataset scope heuristic을 함께 수정해야 한다.
- RAG/eval/runtime은 제품명 기반 추론과 expected doc fallback을 사용한다.
- generation batch, gating batch, memory snapshot, RAG run이 같은 도메인인지 DB 레벨에서 강제되지 않는다.
- Admin GUI는 작업 영역이 먼저 나오고, 도메인 선택이 선행되지 않는다.
- 빈 도메인을 생성하고 이후 source를 추가하는 workflow가 없다.
- 합성 질의 생성 프롬프트와 RAG 쿼리 재작성 프롬프트가 전역 공통 자산이라는 점은 DB catalog에 일부 남아 있지만, 이를 조회/수정/활성화/rollback하는 관리자 GUI가 없다.

## 6. 목표 개념 모델

목표 개념은 다음과 같다.

```text
Synthetic Generation Method (global)
  -> A/B/C/D/E/F/G method catalog

Prompt Asset (global)
  -> query_generation: gen_a_v1 ~ gen_g_v1
  -> rag_rewrite: selective_rewrite_v2, selective_rewrite_v1, selective_rewrite_en_v1
  -> quality/summary/translation prompt assets

Tech Document Domain
  -> sources
  -> corpus documents/sections/chunks/glossary/anchors
  -> generation batches and raw synthetic queries
  -> gating batches and gated queries
  -> memory snapshots
  -> eval datasets/samples
  -> RAG test runs/results
```

즉, method와 prompt asset은 전역이고 batch/result/history는 도메인 소유다. Domain Workspace에서는 현재 적용 중인 전역 prompt asset을 표시할 수 있지만, prompt 자체의 편집과 활성화는 도메인 상위의 공통 관리자 화면에서 수행한다.

## 7. 제안 DB 설계

### 신규 테이블

```sql
CREATE TABLE tech_doc_domain (
    domain_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    domain_key TEXT NOT NULL UNIQUE,
    display_name TEXT NOT NULL,
    description TEXT,
    primary_language TEXT,
    source_language TEXT,
    status TEXT NOT NULL DEFAULT 'active',
    metadata_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_by TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CHECK (status IN ('active', 'archived'))
);
```

```sql
CREATE TABLE tech_doc_domain_source (
    domain_id UUID NOT NULL REFERENCES tech_doc_domain(domain_id) ON DELETE CASCADE,
    source_id TEXT NOT NULL REFERENCES corpus_sources(source_id) ON DELETE CASCADE,
    source_role TEXT NOT NULL DEFAULT 'primary',
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (domain_id, source_id),
    UNIQUE (source_id),
    CHECK (source_role IN ('primary', 'supplemental', 'legacy'))
);
```

```sql
CREATE TABLE tech_doc_domain_method_policy (
    domain_id UUID NOT NULL REFERENCES tech_doc_domain(domain_id) ON DELETE CASCADE,
    method_code TEXT NOT NULL REFERENCES synthetic_query_generation_method(method_code),
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    default_query_language TEXT,
    metadata_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (domain_id, method_code)
);
```

`synthetic_query_generation_method`는 계속 전역 catalog로 둔다.

### 전역 Prompt Asset/Binding 설계

기존 `prompt_assets`는 전역 catalog로 유지한다. 다만 관리자 GUI에서 프롬프트를 조회하고 수정하려면 파일 경로와 hash만으로는 부족하므로 다음 보강이 필요하다.

```sql
ALTER TABLE prompt_assets
    ADD COLUMN IF NOT EXISTS storage_backend TEXT NOT NULL DEFAULT 'file',
    ADD COLUMN IF NOT EXISTS content_body TEXT,
    ADD COLUMN IF NOT EXISTS parent_prompt_asset_id UUID REFERENCES prompt_assets(prompt_asset_id),
    ADD COLUMN IF NOT EXISTS updated_by TEXT,
    ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    ADD CONSTRAINT ck_prompt_assets_storage_backend
        CHECK (storage_backend IN ('file', 'db'));
```

초기에는 기존 `configs/prompts` 파일을 `file` backend로 등록한다. GUI에서 새 버전을 만들거나 수정한 프롬프트는 `db` backend로 저장하고, 실행 runtime은 active binding이 가리키는 asset의 `content_body`를 우선 사용한다. 파일 기반 fallback은 유지한다.

프롬프트 사용처와 활성 버전은 별도 binding으로 분리한다.

```sql
CREATE TABLE prompt_asset_binding (
    binding_key TEXT PRIMARY KEY,
    prompt_family TEXT NOT NULL,
    active_prompt_asset_id UUID NOT NULL REFERENCES prompt_assets(prompt_asset_id),
    fallback_prompt_asset_ids JSONB NOT NULL DEFAULT '[]'::jsonb,
    description TEXT,
    metadata_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    updated_by TEXT,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
```

초기 binding은 다음처럼 seed한다.

| binding_key | 용도 |
|---|---|
| `query_generation.A` | A 방식 합성 질의 생성 프롬프트 |
| `query_generation.B` | B 방식 합성 질의 생성 프롬프트 |
| `query_generation.C` | C 방식 합성 질의 생성 프롬프트 |
| `query_generation.D` | D 방식 합성 질의 생성 프롬프트 |
| `query_generation.E` | E 방식 합성 질의 생성 프롬프트 |
| `query_generation.F` | F 방식 합성 질의 생성 프롬프트 |
| `query_generation.G` | G 방식 합성 질의 생성 프롬프트 |
| `rag_rewrite.ko` | 한국어/code-mixed RAG 쿼리 재작성 프롬프트 |
| `rag_rewrite.en` | 영어 RAG 쿼리 재작성 프롬프트 |

`tech_doc_domain_method_policy`는 도메인별 method 허용 여부만 결정한다. 어떤 prompt asset을 method가 사용하는지는 `prompt_asset_binding`에서 전역으로 결정한다. 도메인별 prompt override는 초기 범위에서 제외하고, 필요할 경우 별도 명시적 override 테이블로 확장한다.

### 주요 기존 테이블에 추가할 domain_id

엄격한 분리를 위해 아래 테이블에는 `domain_id`를 추가한다.

- corpus: `corpus_sources`, `corpus_runs`, `corpus_documents`, `corpus_sections`, `corpus_chunks`, `corpus_glossary_terms`, `corpus_glossary_evidence`, `chunk_generation_asset`, `chunk_embeddings`
- synthetic/gating/memory: `synthetic_query_generation_batch`, `synthetic_queries_raw_a~g`, `quality_gating_batch`, `synthetic_queries_gated`, `synthetic_query_gating_result`, `memory_entries`
- eval/rag: `eval_dataset`, `eval_samples`, `eval_dataset_item`, `rag_test_run`, `rag_test_result_detail`
- job/anchor: `llm_job`, `anchor_normalization_run`, `anchor_normalization_candidate`, `canonical_anchor_relation_run`, `canonical_anchor_relation`, `anchor_eval_run`

초기 마이그레이션은 nullable로 추가하고 backfill 검증 후 `NOT NULL` 또는 service-level required로 단계 전환한다.

### 핵심 불변 조건

DB와 service validation은 다음을 보장해야 한다.

- 하나의 active source는 하나의 domain에만 속한다.
- document/section/chunk/glossary는 parent source 또는 document와 같은 domain이어야 한다.
- generation batch는 반드시 domain을 가진다.
- raw query row는 generation batch와 같은 domain이어야 한다.
- gating batch는 source generation batch/run과 같은 domain이어야 한다.
- memory entry는 source gated query와 같은 domain이어야 한다.
- eval dataset item의 sample은 dataset과 같은 domain이어야 한다.
- RAG run은 dataset, source gating batch, method policy가 모두 같은 domain이어야 한다.
- retrieval/eval은 domain_id 필터 없이 전체 corpus를 검색하지 않는다.
- prompt asset과 prompt binding은 도메인에 종속되지 않는다.
- batch/run/result가 참조하는 prompt asset은 실행 시점의 asset ID, version, hash를 보존한다.

## 8. Backend 설계

### 신규 Domain API

신규 controller는 다음 형태가 적절하다.

```text
GET    /api/admin/domains
POST   /api/admin/domains
GET    /api/admin/domains/{domainId}
PATCH  /api/admin/domains/{domainId}
POST   /api/admin/domains/{domainId}/sources
DELETE /api/admin/domains/{domainId}/sources/{sourceId}
GET    /api/admin/domains/{domainId}/summary
```

`summary`는 Home 화면을 위해 source/document/chunk/anchor/raw/gated/memory/dataset/rag count를 반환한다.

### 신규 Global Prompt API

프롬프트는 도메인 상위 공통 자산이므로 Domain API와 별도의 전역 API를 둔다.

```text
GET    /api/admin/prompt-assets
GET    /api/admin/prompt-assets/{promptAssetId}
POST   /api/admin/prompt-assets
POST   /api/admin/prompt-assets/{promptAssetId}/revisions
PATCH  /api/admin/prompt-assets/{promptAssetId}
POST   /api/admin/prompt-assets/{promptAssetId}/deactivate

GET    /api/admin/prompt-bindings
GET    /api/admin/prompt-bindings/{bindingKey}
PATCH  /api/admin/prompt-bindings/{bindingKey}
POST   /api/admin/prompt-bindings/{bindingKey}/validate
```

관리 대상의 최소 범위는 다음이다.

- `query_generation.A` ~ `query_generation.G`: A/B/C/D/E/F/G 합성 질의 생성 방식 프롬프트
- `rag_rewrite.ko`: 한국어/code-mixed RAG 품질 테스트 쿼리 재작성 프롬프트
- `rag_rewrite.en`: 영어 RAG 품질 테스트 쿼리 재작성 프롬프트

`validate`는 front matter, required metadata, 출력 schema 지시문, forbidden pattern 같은 정적 검사를 수행한다. 실제 LLM smoke test는 별도 action으로 확장한다.

### 기존 API의 domain scope 추가

초기 구현은 기존 endpoint를 유지하고 `domain_id` query/body field를 추가하는 방식이 낮은 위험이다.

예시는 다음과 같다.

- `GET /api/admin/corpus/sources?domain_id=...`
- `GET /api/admin/corpus/documents?domain_id=...`
- `POST /api/admin/pipeline/full-ingest` body에 `domainId`
- `GET /api/admin/console/synthetic/methods?domain_id=...`
- `POST /api/admin/console/synthetic/batches/run` body에 `domainId`
- `POST /api/admin/console/gating/batches/run` body에 `domainId`
- `GET /api/admin/console/rag/datasets?domain_id=...`
- `POST /api/admin/console/rag/tests/run` body에 `domainId`

장기적으로는 `/api/admin/domains/{domainId}/...` prefix로 정리할 수 있다. 다만 기존 화면과 테스트 변경량을 줄이려면 1차는 field 추가가 낫다.

### Service 변경

`AdminConsoleService`에서 제거해야 할 고정 상수는 다음이다.

- `SPRING_TECHDOC_METHOD_CODES`
- `PYTHON_KR_METHOD_CODES`
- `SPRING_TECHDOC_SOURCE_IDS`
- `PYTHON_KR_SOURCE_IDS`
- source/dataset heuristic 기반 scope 추론

대신 `DomainService`가 다음을 제공한다.

```text
resolveDomain(domainId)
listDomainSources(domainId)
listAllowedMethods(domainId, queryLanguage?)
validateSourceInDomain(domainId, sourceId)
validateDatasetInDomain(domainId, datasetId)
validateGenerationBatchInDomain(domainId, batchId)
validateGatingBatchInDomain(domainId, gatingBatchId)
```

runtime options는 기존 `model_catalog.yml` 기반 정책을 유지한다.

`PromptAssetService`는 전역으로 분리한다.

```text
listPromptAssets(family?, activeOnly?)
getPromptAsset(promptAssetId)
createPromptRevision(basePromptAssetId, contentBody, version, updatedBy)
updatePromptBinding(bindingKey, promptAssetId, fallbackPromptAssetIds)
resolvePromptBinding(bindingKey)
```

합성 질의 생성과 RAG rewrite runtime은 파일명을 직접 선택하지 않고 `PromptAssetService` 또는 pipeline의 동일한 binding resolver를 통해 active prompt를 결정한다. DomainService는 method 허용 여부만 판단하고 prompt 선택에는 관여하지 않는다.

## 9. Pipeline 설계

모든 Python experiment config에는 다음 키를 추가한다.

```yaml
domain_id: "..."
domain_key: "spring"
source_ids:
  - spring-boot-reference
  - spring-data-commons-reference
  - spring-data-jpa-reference
  - spring-framework-reference
  - spring-security-reference
```

단계별 변경은 다음과 같다.

- collect: domain source 목록을 받아 해당 source만 수집한다.
- preprocess/chunk/glossary: artifact workspace를 `data/tmp/admin-runs/{runId}`로 유지하되 run/domain metadata를 기록한다.
- import: corpus rows에 `domain_id`를 저장하고 source/domain mismatch를 거부한다.
- generate-queries: `domain_id`를 필수로 받고 chunks를 domain으로 필터링한다. A/B/C/D/E/F/G 프롬프트는 `query_generation.{methodCode}` binding으로 해석한다.
- gate-queries: explicit generation batch/source identity는 유지하고, batch domain mismatch를 거부한다.
- build-memory: current snapshot과 domain이 일치하는 gated query만 memory로 만든다.
- build-eval-dataset/import-eval-jsonl: expected doc/chunk가 dataset domain 안에 있는지 검증한다.
- eval-retrieval/eval-answer: `domain_id`를 필수 검색 scope로 사용한다. Selective rewrite prompt는 query language에 따라 `rag_rewrite.ko` 또는 `rag_rewrite.en` binding으로 해석한다.
- anchor extract/normalize/relation build: domain별 anchor만 대상으로 한다.

Pipeline은 prompt binding을 먼저 DB에서 조회하고, DB 접근이 불가능한 로컬 실험에서는 기존 `configs/prompts` 파일 fallback을 사용한다. 실행 결과에는 기존처럼 `prompt_asset_id`, `prompt_version`, `prompt_hash`를 저장한다.

AGENTS의 고정 pipeline 순서와 A/B/C/D/E/F/G raw table 분리는 유지한다.

## 10. Frontend 관리자 GUI 설계

### 라우팅

라우팅은 다음 구조로 바꾼다.

```text
/admin
  -> DomainHomePage

/admin/domains/:domainKey
  -> DomainWorkspace

/admin/domains/:domainKey/pipeline
/admin/domains/:domainKey/synthetic-queries
/admin/domains/:domainKey/quality-gating
/admin/domains/:domainKey/rag-tests
/admin/domains/:domainKey/anchors
/admin/domains/:domainKey/datasets

/admin/prompts
/admin/prompts/query-generation
/admin/prompts/rag-rewrite

/admin/chat 또는 /
  -> ChatPage
```

기존 ChatPage는 관리자 첫 화면에서 분리한다.

`/admin/prompts`는 특정 도메인 선택 없이 접근 가능한 공통 관리자 영역이다. 사이드바에서는 `공통 설정` 또는 `Prompt Studio`로 분리하고, Domain Workspace 내부 메뉴와 시각적으로 구분한다.

### DomainHomePage

첫 화면은 기술 문서 도메인 목록만 보여준다.

표현 방식은 다음과 같다.

- Spring, Python 등 도메인이 floating node처럼 움직이는 mindmap/Jarvis-style canvas 영역
- 각 node는 domain name, source count, active docs/chunks, latest run status를 짧게 노출
- node 선택 시 해당 domain workspace로 이동
- `새 도메인 추가` action 제공
- 빈 도메인은 source/doc/chunk count 0인 zero-state node로 표시
- `prefers-reduced-motion`에서는 floating animation을 정지한다.

### DomainWorkspace

도메인 선택 후 모든 관리 화면 상단에는 선택한 도메인명이 고정 표시되어야 한다.

예시:

```text
Spring
Sources 5 | Docs 871 | Chunks 1,990 | Datasets 7 | Latest RAG completed
```

하위 페이지는 기존 페이지를 재사용하되 `domainId`를 prop/context로 주입한다.

- Pipeline: 해당 domain source/document만 수집/정제/청킹/import
- Synthetic: domain method policy에 허용된 method만 표시
- Gating: 해당 domain generation batch/snapshot만 표시
- RAG: 해당 domain dataset/snapshot/run만 표시
- Anchors: 해당 domain chunks/glossary/relations만 표시
- Datasets: 해당 domain eval dataset 관리

### Global Prompt Studio

도메인 상위에서 공유되는 prompt asset은 별도 화면에서 관리한다.

필수 화면은 다음 두 가지다.

- 합성 질의 생성 프롬프트: A/B/C/D/E/F/G method별 active prompt, version, hash, 마지막 수정자, 최근 사용 batch를 표시한다.
- RAG 쿼리 재작성 프롬프트: `rag_rewrite.ko`, `rag_rewrite.en` binding별 active prompt, fallback chain, 최근 RAG run 사용 이력을 표시한다.

편집 workflow는 다음을 제공한다.

- active prompt 조회
- 새 revision 생성
- draft validation
- diff/rollback
- binding 전환
- 최근 사용 이력과 영향 범위 확인

Domain Workspace의 Synthetic/RAG 화면에서는 해당 실행이 사용할 전역 prompt binding과 active version을 read-only badge로 보여준다. 도메인별 실행 중 prompt를 직접 수정하지 않는다.

## 11. 마이그레이션 계획

### Phase 0. Global prompt catalog 정리

- 기존 `configs/prompts/query_generation/gen_a_v1.md` ~ `gen_g_v1.md` 등록 상태 확인
- `selective_rewrite_v2`, `selective_rewrite_v1`, `selective_rewrite_en_v1` 등록/seed
- `prompt_asset_binding` seed 추가
- Admin Prompt Studio API/UI skeleton 추가

### Phase 1. Domain 기반 추가와 backfill

- `tech_doc_domain`, `tech_doc_domain_source`, `tech_doc_domain_method_policy` 추가
- `spring`, `python` 도메인 seed
- 현재 source를 domain에 매핑
- 주요 테이블에 nullable `domain_id` 추가
- 기존 source/product/dataset metadata 기준으로 backfill

### Phase 2. Backend read/write scope 적용

- DomainService 추가
- corpus/pipeline/console/rag API에 domain filter 추가
- 생성/게이팅/RAG 실행 요청에서 domain mismatch validation
- 신규 row 생성 시 domain_id 저장

### Phase 3. Pipeline domain 필터 적용

- experiment config에 domain_id/domain_key 기록
- generate/gate/memory/eval runtime에서 domain 필터 적용
- eval dataset import 시 expected doc/chunk domain validation

### Phase 4. Admin GUI 개편

- `/admin`을 DomainHomePage로 변경
- `/admin/prompts`를 도메인 상위 공통 관리 화면으로 추가
- 기존 pages를 DomainWorkspace 하위로 이동
- domain context를 모든 API call에 주입
- ChatPage를 별도 route로 이동

### Phase 5. Strict mode 전환

- backfill 검증 완료 후 domain_id required 강화
- cross-domain FK/unique index 또는 trigger 적용
- source/product heuristic과 frontend hardcoded allowlist 제거
- 전체 테스트 및 운영 smoke

## 12. 테스트 전략

Backend:

- domain 생성, source 연결, 빈 도메인 summary
- cross-domain source/generation/gating/dataset/RAG 요청 400 검증
- synthetic method policy 기반 method listing
- Spring/Python 기존 flow regression

Pipeline:

- domain_id 없는 synthetic/gating/eval 실행 실패
- domain_id가 다른 chunk/dataset/memory 접근 차단
- A-G raw split table write 유지
- retrieval/eval이 domain 밖 chunk를 반환하지 않는지 확인

Frontend:

- `/admin` domain home 렌더링
- domain 선택 후 상단 domain name 유지
- source/batch/dataset/snapshot list가 domain별로 분리되는지 확인
- 빈 도메인에서 source 추가 안내와 실행 버튼 상태 확인
- mobile/desktop에서 floating node와 text overlap 없음 확인

DB:

- backfill 후 orphan domain_id 없음
- Spring/Python source count와 corpus count가 기존 결과와 일치
- cross-domain relation이 생기지 않음

## 13. 유지해야 할 연구 제약

- `synthetic_queries_raw_a/b/c/d/e/f/g`는 계속 분리한다.
- 합성 질의 method는 domain 소유가 아니라 전역 catalog다.
- Quality Gating은 method 필터링과 동적 config 주입을 유지한다.
- RAG 평가는 snapshot/source identity를 명시해야 한다.
- eval dataset은 retrieval-aware 구조를 유지하고 expected doc/chunk grounding을 검증한다.
- 성능 지표와 품질 지표는 같은 domain, 같은 dataset/snapshot 조건에서 비교한다.

## 14. 결론

현재 시스템은 이미 source, corpus, synthetic batch, gating snapshot, RAG run을 충분히 추적하고 있으므로 대규모 재작성보다는 `domain_id`를 중심으로 기존 관계를 묶는 방식이 가장 안전하다.

핵심은 `도메인 선택 -> 도메인 내부 작업공간 -> 도메인별 엄격한 API/DB 필터` 순서를 만드는 것이다. 이 구조가 들어가면 Spring과 Python은 같은 관리자 경험을 공유하면서도 데이터, batch, snapshot, eval result가 섞이지 않는다. 새 도메인은 빈 공간으로 시작하고 source를 추가한 뒤 동일한 pipeline flow를 따라 확장할 수 있다.
