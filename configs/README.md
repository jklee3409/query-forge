# Configs

`configs/`는 Query-Forge의 실행 조건을 코드 밖에서 관리하는 디렉터리입니다. Source preset, chunking 규칙, model/runtime allowlist, experiment preset, prompt asset이 여기에 모여 있으며, backend와 pipeline 모두 이 값을 읽어 재현 가능한 실험을 구성합니다.

프로젝트의 중요한 원칙은 runtime option과 prompt를 코드에 숨기지 않는 것입니다. Admin에서 선택 가능한 LLM provider/model, dense embedding model, retriever mode, retrieval backend, rewrite failure policy, rewrite query profile, parameter range는 `configs/app/model_catalog.yml`이 기준입니다. Backend는 이 catalog를 읽어 UI option을 제공하고, catalog 밖의 요청을 400으로 거부합니다.

## 구조

```text
app/
  application.yml             pipeline/app 기본 경로 설정
  application-docker.yml      Docker profile datasource 설정
  chunking.yml                chunk length, overlap, relation, anchor/glossary 추출 설정
  model_catalog.yml           Admin runtime option allowlist와 기본값
  sources/*.yaml              수집 source preset
experiments/
  gen_*.yaml                  generation preset
  e2e_*.yaml                  end-to-end eval preset
  rule_only/full_gating/...   gating/rewrite comparison preset
  FINAL_*.yaml                고정 비교용 runtime preset
prompts/
  query_generation/           gen_a_v1부터 gen_g_v1까지 전략별 prompt
  summary_extraction/         extractive/KO summary prompt
  translation/                EN->KO chunk translation prompt
  self_eval/                  quality gate LLM self-eval prompt
  rewrite/                    selective rewrite prompt family
```

## Source Presets

현재 source preset은 Spring reference, Python Korean docs, PostgreSQL/PostGIS, Kubernetes, 한국어 Spring community 문서를 포함합니다. Source preset은 수집 가능성을 뜻하지만, synthetic generation 허용 범위와 항상 같지는 않습니다. Unscoped Admin synthetic generation은 A-E를 Spring reference source 집합으로, F/G를 `docs-python-org-ko-3-14`로 제한하며, `arahansa-github-io-docs-spring`은 source catalog에는 존재하지만 synthetic generation에서는 거부됩니다. Domain workspace에서는 domain source membership과 source language policy가 허용 method를 결정합니다.

## Prompt Assets

Query generation prompt는 A-G 전략의 methodology 차이를 보존합니다. A는 영어 summary 후 Korean naturalization, B는 KO translation/summary path, C는 English evidence와 Korean summary context 조합, D는 code-mixed developer query, E는 English-native baseline, F/G는 Korean-source 확장 조건입니다.

Rewrite prompt는 `selective_rewrite_v3`, `selective_rewrite_v2`, `selective_rewrite_v1`, `selective_rewrite_en_v1`, `selective_rewrite_detailed_intent_v1` 계열을 포함합니다. 최신 prompt는 synthetic memory를 복사할 query가 아니라 retrieval-oriented example과 anchor hint로 다루며, `retrieval_context`, `domain_context`, `canonical_anchor_hints`, `multi_source_anchor_hints` 같은 runtime 입력을 받을 수 있습니다.

## Experiment Presets

`configs/experiments/`에는 사람이 관리하는 재현 preset과 Admin이 생성한 runtime artifact가 함께 있을 수 있습니다. `admin_gen_*`, `admin_gate_*`, `admin_eval_*`, `admin_materialize_*` 형태는 실행 시점 artifact에 가깝고, 장기적으로 비교할 조건은 의미 있는 이름의 preset으로 승격해 관리하는 것이 좋습니다.

## 운영 원칙

설정 변경은 pipeline 결과와 DB run record의 해석을 바꿉니다. Source preset을 바꾸면 corpus artifact와 import 결과가 바뀌고, prompt asset을 바꾸면 synthetic query나 rewrite candidate의 의미가 바뀌며, model catalog를 바꾸면 Admin GUI와 backend validation이 동시에 달라집니다. 따라서 설정 변경은 관련 실험 결과와 함께 기록하고, 공식 비교에서는 dataset, snapshot, retriever, rewrite, anchor config를 고정해야 합니다.
