# Monitoring and Trace

## 1. 목적

온라인 질의응답에서 rewrite 선택 과정과 retrieval/rerank 결과를 전부 추적해, 다음을 운영적으로 확인한다.

- rewrite가 실제로 채택되었는지
- 채택 근거(confidence delta, threshold)가 타당한지
- 채택 후 성능 악화(bad rewrite)가 발생하는지
- 단계별 지연(latency)이 허용 범위인지

## 2. 저장 테이블

- `online_queries`
  - raw query, final query, rewrite_applied, raw score, threshold, selected/rejected reason, latency breakdown
- `rewrite_candidates`
  - 후보 query, confidence, score breakdown, adopted 여부, rejection reason
- `retrieval_results`
  - raw/candidate/eval 스코프별 top-k 결과
- `rerank_results`
  - rerank 상위 결과
- `answers`
  - 최종 답변과 인용 chunk/doc

## 3. 필수 저장 항목 매핑

요구 항목과 실제 저장 위치는 아래와 같다.

- raw query: `online_queries.raw_query`
- session context snapshot: `online_queries.session_context_snapshot`
- memory top-N: `online_queries.memory_top_n`
- rewrite candidates: `rewrite_candidates.*`
- candidate prompts/versions: `rewrite_candidates.prompt_*` (확장 가능)
- candidate retrieval top-k docs: `rewrite_candidates.retrieval_top_k_docs`
- candidate scores: `rewrite_candidates.confidence_score`, `score_breakdown`
- raw score: `online_queries.raw_score`
- selected rewrite: `online_queries.selected_rewrite_candidate_id`
- selected/rejected reason: `online_queries.selected_reason`, `rejected_reason`, `rewrite_candidates.rejected_reason`
- threshold: `online_queries.threshold`
- latency by stage: `online_queries.latency_breakdown`

## 4. 운영 점검 루틴

1. `/admin/experiments`에서 최신 run과 평가 요약 확인
2. 사용자 UI에서 질문 실행 후 `online_query_id` 확보
3. `/api/queries/{id}/trace`로 상세 trace 확인
4. `bad_rewrite_cases.md`와 raw 대비 delta를 함께 검토

## 5. 핵심 모니터링 지표

- rewrite adoption rate
- bad rewrite rate
- delta MRR / delta nDCG
- category별 Recall@5, MRR@10, nDCG@10
- p95 latency by mode

## 6. 확장 포인트

- rewrite 후보별 prompt version 고정 저장 강화
- latency 임계치 알람(예: p95 2초 초과)
- bad rewrite 사례 자동 태깅(원인 분류: 짧은 질의/후속 질의/code-mixed)
