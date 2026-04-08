# Query Forge: A/C 1차 End-to-End 실험 결과

본 문서는 2026-04-08에 수행한 **단일 문서 기반 A/C 비교 실험**의 전체 결과를 정리한다.  
요청에 따라 실패가 반복된 방식(B/D)은 이번 1차 실험에서 스킵했고, LLM 호출 수를 최소화하기 위해 문서 1개와 소규모 질의 수로 실행했다.

---

## 1) 실험 범위와 제한

- 대상 문서: `doc_9cda44a0542f929e` (Method Security)
- 문서 수: 1개
- 생성 방식: **A, C만 수행**
- 생성 입력 청크: 각 방식 `limit_chunks=3`
- 합성 질의 생성 결과:
  - A: 3건
  - C: 5건
- 게이팅: 각 방식 1회 실행 (총 2회)
- 평가셋: 단일 문서 기반 수동 구축 6문항 (`e2e_ac_doc1_v1`)
- RAG 평가 모드: `raw_only`, `selective_rewrite`

> 참고: 로컬 Ollama(`llama3.2:latest`)를 사용했으며, 결과 품질은 상용 모델 대비 보수적으로 해석해야 한다.

---

## 2) 실행 이력 (핵심 run)

- `e2e_gen_a`: `63da919f-f461-4d01-bd32-05da56e4f841`
- `e2e_gen_c`: `c38d752d-0f93-4c95-abfb-6684cffe8f1f`
- `e2e_gate_a`: `418dc6b4-9c64-4327-8eb4-3d7f58b0c108`
- `e2e_gate_c`: `b4337d47-34fe-4475-95aa-9e0628792377`
- `e2e_eval_a`: `1c350e37-0afd-4865-9862-b665f3b1ea96`
- `e2e_eval_c`: `7c78e435-c6ea-487e-a488-b905d3de1ee2`

---

## 3) A/C 프롬프트 예시 (각 1개)

### A 방식 프롬프트 예시 (`configs/prompts/query_generation/gen_a_v1.md`)

```text
Strategy A:
English original -> English extractive summary -> English synthetic query -> Korean translation.

Rules:
1. Generate concise and realistic English user question first.
2. Translate to Korean while preserving technical terms in English form.
```

### C 방식 프롬프트 예시 (`configs/prompts/query_generation/gen_c_v1.md`)

```text
You generate Korean synthetic user queries with SAP flow.

Inputs:
- original_chunk_en
- extractive_summary_en
- extractive_summary_ko
- glossary_terms_keep_english
```

---

## 4) 합성 질의 생성/게이팅 결과

### 생성 결과

| 방식 | 생성 수 | query_type 분포 | answerability 분포 |
| --- | ---: | --- | --- |
| A | 3 | definition 1, comparison 1, reason 1 | single 2, far 1 |
| C | 5 | procedure 4, comparison 1 | single 2, near 1, far 2 |

### 게이팅 생존 결과 (full_gating, 1회씩)

| 방식 | 입력 | 최종 승인 | 생존율 |
| --- | ---: | ---: | ---: |
| A | 3 | 3 | 100% |
| C | 5 | 5 | 100% |

추가 진단(저장된 `rejection_reasons` 토큰 빈도):

- A: `korean_ratio_low` 3회, `copy_ratio_high` 1회
- C: `length_out_of_range` 4회, `token_count_out_of_range` 4회, `korean_ratio_low` 2회

> 이번 1차 실험은 안정 실행을 위해 게이팅 스위치를 보수적으로 설정했고(`final_score_threshold=0.0`), 탈락 없이 통과시켰다.  
> 따라서 위 reason은 탈락 사유가 아니라 품질 경고 신호로 해석한다.

---

## 5) RAG 품질 비교 결과 (A vs C)

평가셋: `e2e_ac_doc1_v1` (6문항, 단일 문서 수동 구축)

### A 방식 (`e2e_eval_a`)

| 모드 | Recall@5 | Hit@5 | MRR@10 | nDCG@10 | Rewrite 수용률 | Rewrite 거절률 | Avg confidence delta |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| raw_only | 0.3333 | 0.3333 | 0.2222 | 0.3200 | 0.0000 | 0.0000 | 0.0000 |
| selective_rewrite | 0.1667 | 0.1667 | 0.1667 | 0.1667 | 0.5000 | 0.5000 | +0.0180 |

- selective rewrite 적용 시 MRR/nDCG가 하락했다.
- `bad_rewrite_rate` = 0.6667 (적용된 rewrite 중 성능 악화 비율이 높음)

### C 방식 (`e2e_eval_c`)

| 모드 | Recall@5 | Hit@5 | MRR@10 | nDCG@10 | Rewrite 수용률 | Rewrite 거절률 | Avg confidence delta |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| raw_only | 0.3333 | 0.3333 | 0.2222 | 0.3200 | 0.0000 | 0.0000 | 0.0000 |
| selective_rewrite | 0.3333 | 0.3333 | 0.2222 | 0.3200 | 0.0000 | 1.0000 | -0.0143 |

- selective rewrite가 전부 거절되어 raw와 동일 성능을 유지했다.
- 1차 결과 해석: **C는 보수적으로 raw 품질을 유지**, A는 일부 rewrite 채택으로 성능 변동이 컸다.

---

## 6) 테스트 데이터셋 구축 과정과 이유

이번 실험의 평가셋은 자동 생성이 아니라 단일 문서를 직접 읽고 수동으로 구성했다.

- dataset: `e2e_ac_doc1_v1`
- dataset_id: `55bd5f88-c6f3-4d83-9353-557ad63d56b8`
- 총 6문항 (dev 3, test 3)
- 분포:
  - query_category: procedure 4, comparison 1, reason 1
  - answerability: single 4, multi 2

구축 이유:

1. 1차 실험에서 빠르게 재현 가능한 최소 문항 수를 유지하면서도, single/multi와 유형 차이를 동시에 관찰하기 위해.
2. 합성 질의 기반 메모리의 효과를 보려면 정답 chunk가 명시된 문항이 필요해, 각 문항의 `expected_chunk_ids`를 수동 지정.
3. B/D 스킵 상황에서 A/C 비교만 우선 검증하기 위해 평가셋 규모를 의도적으로 작게 제한.

---

## 7) 관리자 GUI 그래프 반영

요구사항에 맞춰 관리자 화면에서 그래프 확인이 가능하도록 반영했다.

- 합성 질의 페이지: 방식별 생성량 그래프
- 게이팅 페이지: 게이팅 퍼널 그래프
- RAG 테스트 페이지: 핵심 지표(Recall/Hit/MRR/nDCG) 그래프

관련 수정 파일:

- `backend/src/main/resources/templates/admin/synthetic-queries.html`
- `backend/src/main/resources/templates/admin/quality-gating.html`
- `backend/src/main/resources/templates/admin/rag-tests.html`
- `backend/src/main/resources/static/admin/console.js`
- `backend/src/main/resources/static/admin/admin.css`

---

## 8) 실험 중 실패와 최소 수정 대응

실패 구간은 기존 기능 전체를 바꾸지 않고, 오류 지점만 최소 수정했다.

1. LLM 응답 JSON 파싱 실패/재시도 부족
   - `pipeline/common/llm_client.py`에 재시도/backoff 및 fallback 처리 강화
2. 생성 결과 빈 질의 응답
   - `pipeline/generation/synthetic_query_generator.py`에 추출 fallback/빈 질의 스킵 처리
3. 게이팅 INSERT placeholder mismatch
   - `pipeline/gating/quality_gating.py` SQL placeholder 정합성 수정
4. 평가 저장 시 FK 오류 (`retrieval_results.document_id_fkey`)
   - `pipeline/eval/retrieval_eval.py`에서 legacy FK 충돌을 피하도록 `document_id/chunk_id`는 NULL 저장하고 실제 ID는 metadata에 저장

---

## 9) 재현 커맨드 (A/C만)

```bash
# 1) A/C 생성
python pipeline/cli.py generate-queries --experiment e2e_gen_a
python pipeline/cli.py generate-queries --experiment e2e_gen_c

# 2) A/C 게이팅
python pipeline/cli.py gate-queries --experiment e2e_gate_a
python pipeline/cli.py gate-queries --experiment e2e_gate_c

# 3) 메모리 빌드
python pipeline/cli.py build-memory --experiment e2e_gate_a
python pipeline/cli.py build-memory --experiment e2e_gate_c

# 4) 평가
python pipeline/cli.py eval-retrieval --experiment e2e_eval_a
python pipeline/cli.py eval-retrieval --experiment e2e_eval_c
```

---

## 10) 1차 결론

단일 문서/소규모 호출 조건의 1차 실험에서:

- 생성량은 C가 A보다 많았고(5 vs 3), 문장 품질 경고는 C에서 더 많이 관찰됨.
- selective rewrite는 A에서 일부 채택되었지만 성능 하락 가능성이 컸고, C는 보수적으로 전부 거절되어 baseline 유지.
- 다음 단계는 동일 파이프라인에서 문항 수를 점진 확장하고, 게이팅 threshold를 실제 탈락이 발생하는 수준으로 올려 A/C 차이를 재검증하는 것이다.

