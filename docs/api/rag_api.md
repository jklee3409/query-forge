# RAG API

## 1. POST `/api/chat/ask`

온라인 질의응답 엔드포인트. selective rewrite 포함 전체 흐름을 실행한다.

요청 예시:

```json
{
  "query": "Spring Security 인증 필터 체인 순서가 뭐야?",
  "sessionId": "session-1",
  "sessionContext": {
    "previous_user_question": "로그인 설정은 했어",
    "previous_assistant_summary": "기본 보안 설정은 완료됨"
  },
  "mode": "selective_rewrite",
  "retrievalTopK": 20,
  "rerankTopN": 5,
  "memoryTopN": 5,
  "rewriteCandidateCount": 3,
  "rewriteThreshold": 0.05,
  "gatingPreset": "full_gating"
}
```

응답 필수 필드:

- `answer`
- `finalQueryUsed`
- `rawQuery`
- `rewriteApplied`
- `rewriteCandidates`
- `retrievedDocs`
- `rerankedDocs`
- `latencyBreakdown`

## 2. POST `/api/rewrite/preview`

실제 답변 생성 없이 rewrite 후보와 후보 confidence를 미리 확인한다.

## 3. GET `/api/queries/{id}/trace`

온라인 질의 1건의 full trace를 반환한다.

- raw/final query
- memory top-N
- rewrite candidates + 점수 + 채택 여부
- retrieval/rerank 결과
- latency breakdown

## 4. GET `/api/experiments/{runId}/summary`

특정 experiment run의 메타/지표 요약을 조회한다.

## 5. GET `/api/eval/retrieval`

최신 retrieval 평가 JSON 요약을 반환한다.

## 6. GET `/api/eval/answer`

최신 answer-level 평가 JSON 요약을 반환한다.

## 7. POST `/api/admin/reindex`

chunk/memory 임베딩 재색인.

요청:

```json
{
  "reindexChunks": true,
  "reindexMemory": true
}
```

## 8. POST `/api/admin/experiments/run`

관리자 GUI에서 파이프라인 단계를 실행한다.

요청:

```json
{
  "command": "generate-queries",
  "experiment": "gen_c"
}
```

허용 command:

- `generate-queries`
- `gate-queries`
- `build-memory`
- `build-eval-dataset`
- `eval-retrieval`
- `eval-answer`
