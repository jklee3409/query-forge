# First Baseline Template

아래 템플릿은 첫 baseline 실험 결과를 동일 포맷으로 기록하기 위한 문서다.

## 1. 실행 정보

- 실행 일시:
- 실행자:
- git commit:
- 실험 키:
- 데이터 버전(corpus run id):
- 메모리 구성:
  - generation strategy: A / B / C / C+D
  - gating preset: ungated / rule_only / rule_plus_llm / full_gating

## 2. 실행 커맨드

```bash
make generate-queries EXPERIMENT=<gen_exp>
make gate-queries EXPERIMENT=<gate_exp>
make build-memory EXPERIMENT=<gate_exp>
make build-eval-dataset EXPERIMENT=<eval_exp>
make eval-retrieval EXPERIMENT=<eval_exp>
make eval-answer EXPERIMENT=<eval_exp>
```

## 3. Retrieval 결과 요약

| mode | Recall@5 | Hit@5 | MRR@10 | nDCG@10 | adoption_rate | bad_rewrite_rate |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| raw_only |  |  |  |  |  |  |
| memory_only_ungated |  |  |  |  |  |  |
| memory_only_gated |  |  |  |  |  |  |
| rewrite_always |  |  |  |  |  |  |
| selective_rewrite |  |  |  |  |  |  |
| selective_rewrite_with_session |  |  |  |  |  |  |

## 4. Category별 결과 요약

- general_ko:
- troubleshooting:
- short_user:
- code_mixed:
- follow_up:

## 5. Answer-level 결과 요약

| metric | value |
| --- | ---: |
| keyword_overlap |  |
| answer_relevance |  |
| faithfulness |  |
| context_precision |  |
| context_recall |  |
| rewrite_adoption_rate |  |

## 6. 해석

- gated vs ungated memory 차이:
- no/always/selective rewrite 차이:
- A/B/C 전략 차이:
- single vs multi 난이도 차이:
- context-free vs follow-up 차이:

## 7. 문제 사례

- bad rewrite 대표 사례:
- retrieval 실패 대표 사례:
- 응답 신뢰도 이슈:

## 8. 다음 액션

- 튜닝 우선순위 1:
- 튜닝 우선순위 2:
- 튜닝 우선순위 3:
