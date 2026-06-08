# Data

`data/`는 Query-Forge pipeline과 평가가 생성하거나 소비하는 artifact를 보관하는 작업 디렉터리입니다. 이 디렉터리는 DB의 전체 source of truth가 아니라, 수집/전처리/평가/리포트 재현을 위한 파일 기반 증거를 담습니다. 실제 runtime 상태는 PostgreSQL의 corpus, synthetic, gating, memory, eval, RAG table에 저장될 수 있으므로 README는 특정 row count를 고정된 사실로 주장하지 않습니다.

## 구조

```text
raw/          collector가 저장한 원본 HTML JSONL
processed/    section, chunk, glossary, chunk relation, visualization artifact
synthetic/    synthetic query/gating/memory export를 둘 수 있는 작업 공간
eval/         retrieval-aware JSONL 평가 데이터셋
reports/      retrieval/answer/latency/audit/rewrite case report
artifacts/    backend pipeline artifact store
logs/         backend/pipeline 실행 로그
tmp/          임시 작업 산출물
```

## 데이터 흐름

`collect-docs`는 `raw/`에 source별 HTML JSONL을 남깁니다. `preprocess`와 `chunk-docs`는 `processed/`에 section/chunk/glossary/relation artifact를 만들고, `import-corpus`가 이를 PostgreSQL에 적재합니다. Synthetic generation과 quality gating의 primary record는 DB에 저장되지만, 필요하면 `synthetic/` 아래에 export를 둘 수 있습니다. Retrieval-aware 평가 입력은 `eval/`에 JSONL로 보존하고, retrieval/answer 실행 결과와 audit 산출물은 `reports/`에 남깁니다.

## 현재 포함된 artifact 범위

현재 `raw/`와 `processed/`에는 Spring, Python Korean docs, Kubernetes 관련 artifact가 포함되어 있습니다. `eval/`에는 Spring short-user KR/EN, Spring method-compressed A/B/C/D/E stress dataset, Python KR-source KO/EN, PostgreSQL/Kubernetes KO/EN, anchor-translated variant, rewrite challenge/probe dataset이 있습니다. `reports/`에는 answer/retrieval summary/detail CSV/JSON, latency summary, dataset audit, rewrite case analysis가 누적됩니다.

## 운영 원칙

대용량 artifact는 로컬 상태와 실험 이력에 따라 달라질 수 있습니다. 새 dataset을 추가할 때는 JSONL 파일만 두는 것으로 끝내지 말고, 필요한 경우 DB `eval_dataset`, `eval_samples`, `eval_dataset_item` 등록 방식과 schema가 일치하는지 확인해야 합니다. RAG 결과를 해석할 때는 dataset key/version, source/gating snapshot, strategy, retriever config, rewrite profile, anchor config를 함께 확인합니다.
