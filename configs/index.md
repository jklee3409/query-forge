# index.md

## Directory Overview
프로젝트 실행/실험/프롬프트 설정을 코드 외부에서 관리하는 설정 디렉토리입니다.

---

## Structure
- `README.md`: 설정 디렉토리 사용 가이드
- `app/application.yml`, `app/application-docker.yml`: 애플리케이션 공통 및 Docker 프로파일 설정
- `app/chunking.yml`: 문서 청킹 파라미터
- `app/model_catalog.yml`: Admin runtime options/model allowlist 카탈로그 (`llm_providers`, `llm_models`, `dense_embedding_models`, `retrieval_backends`, `retriever_modes`, `rewrite_failure_policies`, `default_parameter_ranges`)
- `app/sources/*.yaml`: 수집 대상 소스 정의(Spring Boot/Data/Framework/Security, PostgreSQL current docs, PostGIS docs)
- `experiments/*.yaml`: generation/gating/eval 실험 프리셋(`gen_*`, `e2e_*`, `rule_*`, `rewrite_*` 등)
- `prompts/query_generation/gen_[a-g]_v1.md`: 전략별 합성 질의 프롬프트
- `prompts/summary_extraction/*.md`: 요약/한국어 요약 프롬프트
- `prompts/self_eval/quality_gate_v1.md`: 게이팅 자기평가 프롬프트
- `prompts/rewrite/selective_rewrite_v1.md`, `prompts/rewrite/selective_rewrite_v2.md`, `prompts/rewrite/selective_rewrite_v3.md`, `prompts/rewrite/selective_rewrite_en_v1.md`: 선택적 리라이트 프롬프트 버전
- `prompts/translation/translate_chunk_en_to_ko_v1.md`: 번역 프롬프트

---

## Responsibilities
- 하드코딩 없이 파이프라인 실행값과 실험 조건을 주입
- 전략(A/B/C/D/E/F/G) 및 평가 모드별 재현 가능한 실험 정의 제공
- LLM 프롬프트 버전 관리와 교체 지점 제공

---

## Key Flows
- `pipeline/cli.py`가 `app/` 설정과 `experiments/` 프리셋을 로드
- generation/gating/eval 단계에서 필요한 `prompts/` 자산을 매핑
- 실험명 기준으로 동일 설정 재실행 및 결과 비교 수행

---

## Recent Notes
- `prompts/rewrite/selective_rewrite_v3.md` now consumes runtime `domain_context` so Korean technical terms are rewritten into active-domain English documentation terms, with few-shots for Spring, PostgreSQL, and Kubernetes short queries.
- `prompts/rewrite/selective_rewrite_v3.md` now accepts `candidate_policy`: `raw_standalone` ignores synthetic memory/anchor hints but can use raw-retrieval evidence terms, while `memory_expanded` may use only trusted evidence-compatible memory anchors.
- `prompts/rewrite/selective_rewrite_v3.md` is the active lightweight Korean/code-mixed rewrite prompt with simplified inputs, maximum two candidates, no required `intent_risk`, and three compact few-shot examples for safe hybrid retrieval query rewriting.
- `app/sources/postgresql-docs-current.yaml` and `app/sources/postgis-docs-current.yaml` define the official English PostgreSQL-domain corpus sources used for the 2026-05-26 collection/import run.
- `app/model_catalog.yml` now owns Admin RAG retriever mode defaults (`retriever_mode_defaults`) and rewrite memory candidate pool defaults, so frontend controls and backend omitted-field behavior are both hydrated from runtime options.
- Rewrite prompts now accept `retrieval_context` so the LLM sees actual retrieval backend, vector store, retriever mode, embedding model, fusion weights, top-K, and candidate-pool values before generating candidates.
- `prompts/rewrite/selective_rewrite_en_v1.md` is now version `v3`, with retrieval-context guidance, synthetic-example-first query expansion, and cautious optional anchor use.
- `prompts/rewrite/selective_rewrite_v2.md` is now version `v5`, with a global technical-document rewrite contract that prioritizes compatible synthetic query examples for retrieval structure and avoids forced anchor injection.
- `app/model_catalog.yml` now defaults `rewrite_threshold` to `0.05`, matching Admin backend/frontend RAG defaults.
- selective rewrite prompts now use sanitized memory rows (`synthetic_query`, target title/section, glossary/canonical anchors, evidence summary) and require candidate metadata fields `preserved_raw_terms`, `added_anchors`, `source_memory_index`, and `intent_risk`.
- selective rewrite prompts now state that `top_memory_candidates` are synthetic query examples / compatible retrieval-anchor context only; they must not be copied wholesale or used as direct retrieval queries.
- selective rewrite v2 is now metadata version `v3`, preserving the runtime schema/labels while prioritizing English technical-document anchor overlap for Korean/code-mixed queries.
- selective rewrite v2 and English rewrite prompts now accept optional `multi_source_anchor_hints`, with lower-priority drift safeguards so expanded anchors cannot override raw-query intent.
- selective rewrite v2 prompt now accepts optional `canonical_anchor_hints` and treats them as compact intent-compatible canonical/normalized anchor preservation hints.
- `prompts/rewrite/selective_rewrite_en_v1.md` is the English-native rewrite prompt for `query_language=en`; Korean/code-mixed runs continue to use the existing `selective_rewrite_v2` -> `v1` path.
- Strategy B query generation prompt `gen_b_v1` is now version `v5` and emits query-only JSON (`query_ko`, `query_type`, `answerability_type`); `translated_chunk_ko` and `extractive_summary_ko` are fixed upstream inputs.
- `experiments/strategy_b_smoke.yaml` is the controlled B smoke preset: one Spring source, one chunk, one forced `code_mixed` query, explicit B payload bounds, and a B-specific translation output-token budget.
- selective rewrite v2 prompt now consumes `terminology_hints` and enforces verbatim preservation for intent-compatible technical tokens.
- selective rewrite v2 keeps intent preservation, but the extra intent-locked query-expansion/topic-substitution guard was rolled back so synthetic memory anchors can contribute more strongly in short-query retrieval experiments.

## Notes
- Update this file when structure or responsibilities change
