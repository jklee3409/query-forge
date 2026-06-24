# Agentic No-Write Retrieval Eval Design

## 1. Background

The Java retrieval eval endpoint is the retrieval-only source of truth for Java-backed evaluation. Live `/api/chat/ask` already supports explicit and router-selected `AGENTIC_MULTI_QUERY` through the Java agentic path, but `/api/rag/eval/retrieval` still blocks forced and router-selected agentic execution.

Phase 11 opens agentic retrieval eval only after a no-write design exists. The target is Java-backed retrieval execution without answer generation, without online-query creation, and without trace-row persistence.

## 2. Current State

Code audit baseline:

- `AgenticExecutionRequest` is currently an inner record in `AgenticRetrievalService`, not a separate model file. Fields are `rawQuery`, `onlineQueryId`, `config`, `readiness`, `sessionContext`, `plannerMemoryHints`, `mode`, `rewriteQueryProfile`, `memoryPreset`, `retrievalTopK`, `rerankTopN`, `memoryTopN`, `candidateCount`, `threshold`, `maxSubqueries`, `rrfK`, and `finalTopK`.
- `AgenticExecutionResult` is also an inner record in `AgenticRetrievalService`. Fields are `plan`, `traces`, `persistedCandidates`, `mergedDocs`, `rewriteApplied`, `selectedReason`, `rejectedReason`, `planningLatencyMs`, `totalLatencyMs`, and `metadata`.
- `AgenticRetrievalService` calls `RagTracePersistenceService` with hardcoded `RagPersistPolicy.ONLINE_QUERY` for subquery raw retrieval, candidate retrieval, rewrite candidate creation, and candidate adoption. These calls pass `request.onlineQueryId()`.
- Candidate selection currently compares selected candidates by DB `rewriteCandidateId`; in no-write mode that ID would be null if adapter persistence returns the existing `NONE` result.
- `RagService.askAgentic(...)` creates the online query before agentic execution, calls `AgenticRetrievalService`, persists the final RRF rerank trace, calls `buildAnswer(...)`, calls `repository.insertAnswer(...)`, and writes decision/metadata/rewrite-log/memory/candidate logs with `ONLINE_QUERY`.
- `RagTracePersistenceService` returns `persisted=false` and status `skipped_none` for `NONE`. `TRACE_ONLY` is unsupported. `ONLINE_QUERY` phase-specific methods write DB trace rows and require a non-null `onlineQueryId`.
- `RagRetrievalEvalService` supports only `raw_only`, `selective_rewrite`, `anchor_aware_rewrite`, and `strategy_router`. It rejects forced `agentic_multi_query` with `unsupported_agentic_eval`.
- `RagRetrievalEvalService.executeStrategyRouter(...)` rejects router-selected `AGENTIC_MULTI_QUERY` with `unsupported_router_agentic_eval`.
- `RagRetrievalEvalService` rejects `answerGeneration=true`, `ONLINE_QUERY`, and `TRACE_ONLY`; successful responses are answer-free and return ordered `retrievedChunkIds`.
- `pipeline/eval/java_retrieval_client.py` defines `JAVA_RETRIEVAL_BLOCKED_FORCED_MODES = {"agentic_multi_query"}` and raises `unsupported_agentic_eval` before HTTP calls.
- `pipeline/eval/retrieval_eval.py` validates Java-backed modes and blocks `agentic_multi_query` before runtime client execution.
- `pipeline/eval/retrieval_eval_compare.py` excludes `agentic_multi_query` from comparison modes and raises `unsupported_agentic_eval`.

## 3. Problem Statement

Agentic live chat assumes online persistence identity. Agentic retrieval-only eval requires the same planner/subquery/retrieval/RRF behavior, but must run with `persistPolicy=NONE` and no `onlineQueryId`.

The blocker is not answer generation inside `AgenticRetrievalService`; it is the identity and persistence contract around the service. No-write eval needs stable in-memory identities for subqueries, rewrite candidates, retrieval sets, and the final RRF merge without creating DB rows.

## 4. Non-Goals

- No answer generation.
- No `insertAnswer`.
- No `createOnlineQuery`.
- No DB trace write in `persistPolicy=NONE`.
- No DB schema change.
- No migration file.
- No frontend change.
- No Python legacy eval deletion.
- No live `/api/chat/ask` behavior change.
- No StrategyRouter rule change.
- No immediate endpoint contract break.
- No immediate agentic eval blocker removal in Phase 11A.

## 5. Design Requirements

- Eval must force or default `persistPolicy=NONE`.
- Eval must reject `answerGeneration=true`.
- Agentic execution must be possible without `onlineQueryId` when and only when `persistPolicy=NONE`.
- Live agentic execution must continue requiring `ONLINE_QUERY` identity and must preserve current `/api/chat/ask` behavior.
- No-write agentic response must expose ordered final `retrievedChunkIds` for metrics.
- Optional trace must be response-only and must not require DB IDs.
- Forced `agentic_multi_query` and router-selected `AGENTIC_MULTI_QUERY` must share the same no-write execution branch.
- Python Java-backed eval must continue to use `retrievedChunkIds` as the primary metric input.
- Python legacy agentic eval remains the comparison/fallback baseline.

## 6. Proposed Backend Design

### Option A: Add no-write mode to `AgenticRetrievalService`

Expected changes:

- Add `RagPersistPolicy persistPolicy` to `AgenticExecutionRequest`.
- Default existing live callers to `ONLINE_QUERY`; eval callers pass `NONE`.
- Allow `onlineQueryId=null` only for `persistPolicy=NONE`.
- Thread `request.persistPolicy()` through all agentic adapter calls instead of hardcoded `ONLINE_QUERY`.
- For `NONE`, either call adapter methods and rely on their existing no-op result or skip adapter calls behind a small helper. In both cases, DB writes must be impossible.
- Add transient candidate identity because DB `rewriteCandidateId` can be null.
- Keep final RRF merge inside the agentic execution result and let eval response mapping consume `mergedDocs`.

Pros:

- Reuses current planner, subquery execution, retrieval, rerank, route recursion guard, and RRF merge logic.
- Smallest backend implementation surface.
- Lower risk for a low-spec local validation loop.

Cons:

- Live and eval execution modes share the same service, so persistence guards must be explicit and heavily tested.
- Existing names such as `PersistedRewriteCandidate` are misleading for no-write mode and may need a narrow rename or compatibility wrapper.

### Option B: Separate agentic no-write execution service

Expected changes:

- Add `AgenticRetrievalEvalExecutionService` or equivalent.
- Reuse planner, retrieval, rerank, and RRF helpers where possible.
- Keep online trace and eval trace paths fully separated.

Pros:

- No-write boundary is visually clear.
- Less chance that eval accidentally inherits live online persistence.

Cons:

- The current agentic logic is concentrated in `AgenticRetrievalService`, so a separate service would duplicate code or require a broader extraction refactor.
- More files and tests change at once, raising implementation risk.

### Selected Direction

Choose Option A for Phase 11B, with a strict eval wrapper in `RagRetrievalEvalService`.

This is the safer near-term choice because the current code already has working agentic planning/subquery/RRF logic and `RagTracePersistenceService` already defines no-op behavior for `NONE`. The trade-off is that no-write and live mode share one service, so Phase 11B must add explicit invariants:

- `ONLINE_QUERY` requires non-null `onlineQueryId`.
- `NONE` allows null `onlineQueryId`.
- `NONE` never produces repository writes.
- Candidate selection never depends solely on DB IDs.
- Eval never calls `RagService.askAgentic(...)`.

If Phase 11B changes become too invasive, defer and switch to Option B through a helper extraction phase before enabling the endpoint.

## 7. Proposed Eval Endpoint Contract

Endpoint path remains:

```text
POST /api/rag/eval/retrieval
```

Request behavior after implementation:

- `forcedMode=agentic_multi_query` is accepted only when `persistPolicy` is absent or `NONE`, `answerGeneration` is absent or `false`, `domainId` is present, and `query` is non-blank.
- `forcedMode=strategy_router` continues to use `QueryStrategyRouter`; if the router selects `AGENTIC_MULTI_QUERY`, eval executes the same no-write agentic branch instead of returning `unsupported_router_agentic_eval`.
- `persistPolicy=ONLINE_QUERY` and `TRACE_ONLY` remain rejected for the eval endpoint.
- `answerGeneration=true` remains rejected.
- `topK` continues to bound final eval results.

Response behavior:

- `selectedMode=agentic_multi_query` for forced agentic and for router-selected agentic.
- `forcedMode` preserves the request mode, so router-selected agentic returns `forcedMode=strategy_router`.
- `retrievedChunkIds` is ordered by final RRF merge and remains the primary metric contract.
- `retrievedDocs` contains the bounded final merged docs with ranks.
- `persisted=false` and `persistPolicy=NONE`.
- `llmCallCount.answerCalls=0`.
- No `answer` field.
- `warnings` may include planner fallback, degraded no-write trace, or partial diagnostic warnings.
- `trace` is included only when `includeTrace=true`.

Agentic eval must not call:

- `RagRepository.createOnlineQuery(...)`
- `ChatAnswerService.generateAnswer(...)`
- `RagService.buildAnswer(...)`
- `RagRepository.insertAnswer(...)`
- any repository trace write through `ONLINE_QUERY`

## 8. Proposed Python Client / Eval Changes

Phase 11C should start only after Phase 11B backend tests pass.

Python changes:

- Remove `agentic_multi_query` from Java client fail-fast only after backend no-write support is implemented and tested.
- Keep `retrievedChunkIds` mapping unchanged; agentic Java responses still convert to `RetrievalCandidate` in response order.
- Keep `persistPolicy=NONE` and `answerGeneration=false` in every Java-backed request.
- Add or preserve an explicit client-side capability/opt-in guard. Recommended first step: require config such as `java_agentic_eval_enabled=true` before allowing Java-backed `agentic_multi_query`. The backend remains the final authority because it still rejects unsafe policies.
- When `includeTrace=true`, store bounded agentic trace under Java row/report metadata, for example `java_agentic_trace`, without changing metric calculation.
- Update Java-backed retrieval metadata `supported_modes` / `blocked_modes` after the backend support is accepted.
- Extend the comparison runner to include `agentic_multi_query` only when Java agentic eval is enabled.
- Compare Python legacy agentic vs Java-backed agentic by final retrieved chunk ID order, standard retrieval metrics, mismatch rows, selected subquery count, planner fallback count, and latency context.

Python legacy eval is not deleted. It remains the fallback/regression implementation and the comparison baseline for Java-backed agentic acceptance.

## 9. Trace and Identity Model

No-write agentic eval must not create DB IDs. It needs deterministic transient IDs:

- `subqueryId`: `subquery-1`, `subquery-2`, ...
- `candidateId`: `subquery-1:candidate-1`, `subquery-1:candidate-2`, ...
- `retrievalSetId`: `subquery-1:raw`, `subquery-1:rewrite-1`, ...
- `rrfMergeId`: `agentic-rrf`
- `finalRetrievedChunkIds`: ordered final merged chunk IDs

Identity rules:

- Transient identity is response-trace identity only. It is never persisted.
- DB IDs may still be present for live `ONLINE_QUERY`, but no-write trace must not depend on them.
- Candidate selection in no-write mode should compare transient `candidateId`, not nullable DB `rewriteCandidateId`.
- Metric calculation uses only `finalRetrievedChunkIds`.
- Optional trace is returned only when `includeTrace=true`.
- `includeTrace=false` still returns `retrievedChunkIds`, `retrievedDocs`, `selectedMode`, warnings, latency, and `llmCallCount`.

Proposed trace shape:

```json
{
  "routeDecision": "AGENTIC_MULTI_QUERY",
  "retrievalTrace": {
    "selectedMode": "agentic_multi_query",
    "agentic": {
      "plan": {"fallbackApplied": false, "subqueryCount": 2},
      "subqueries": [
        {
          "subqueryId": "subquery-1",
          "query": "...",
          "finalQuery": "...",
          "retrievalSets": [
            {"retrievalSetId": "subquery-1:raw", "retrievedChunkIds": ["..."]},
            {"retrievalSetId": "subquery-1:rewrite-1", "candidateId": "subquery-1:candidate-1", "retrievedChunkIds": ["..."]}
          ]
        }
      ],
      "rrfMergeId": "agentic-rrf",
      "finalRetrievedChunkIds": ["chunk-a", "chunk-b"]
    }
  }
}
```

## 10. Error / Fallback Policy

- Planner failure: use the existing fallback-original-query plan and add a warning such as `agentic_planner_fallback_original_query`.
- Empty planner result: same fallback-original-query policy.
- Subquery execution failure: fail fast for Phase 11B. Partial merge can hide eval defects and should not be the default.
- Retrieval failure for any subquery/candidate: fail fast for Phase 11B.
- Rewrite candidate generation failure: follow current rewrite service behavior if it returns no candidates; otherwise fail fast on exceptions.
- Unsupported `persistPolicy`: reject at the eval service boundary with `unsupported_persist_policy`.
- `answerGeneration=true`: reject with `unsupported_answer_generation`.
- Transient identity generation failure: fail fast before returning a response.
- `TRACE_ONLY`: remain unsupported until trace root/source semantics are designed separately.
- Java backend errors in Python: remain run-level fail-fast for Java-backed eval.

## 11. Test Plan

Backend tests for Phase 11B:

- `AgenticRetrievalService` no-write mode accepts `persistPolicy=NONE` with `onlineQueryId=null`.
- `AgenticRetrievalService` live mode still requires `ONLINE_QUERY` with non-null `onlineQueryId`.
- No-write mode does not call `createOnlineQuery`.
- No-write mode does not insert retrieval, rerank, rewrite, candidate, memory, decision, metadata, or answer rows.
- No-write mode returns `mergedDocs` and ordered `retrievedChunkIds` through eval response mapping.
- No-write mode includes transient `subqueryId`, `candidateId`, `retrievalSetId`, and `rrfMergeId` when `includeTrace=true`.
- Candidate selection works when DB `rewriteCandidateId` is null.
- Forced `agentic_multi_query` eval returns 200 after implementation.
- Router-selected `AGENTIC_MULTI_QUERY` eval returns 200 after implementation.
- `answerGeneration=true` remains blocked.
- `persistPolicy=ONLINE_QUERY` and `TRACE_ONLY` remain blocked for eval.
- Existing `/api/chat/ask` agentic behavior remains unchanged.
- Existing forced non-agentic eval behavior remains unchanged.

Python tests for Phase 11C:

- `JavaRetrievalClient` allows `agentic_multi_query` only when Java agentic eval support is explicitly enabled.
- `JavaRetrievalClient` still sends `persistPolicy=NONE` and `answerGeneration=false`.
- `retrievedChunkIds` mapping works for agentic responses, including duplicate chunk IDs.
- Java response trace is preserved in metadata/report rows when requested.
- Comparison runner can include `agentic_multi_query` after enablement.
- Python legacy fallback/comparison remains available.

## 12. Implementation Slices

### Phase 11B: Backend no-write agentic eval support

- Add `persistPolicy` to `AgenticExecutionRequest`.
- Allow null `onlineQueryId` only for `persistPolicy=NONE`.
- Thread `persistPolicy=NONE` through agentic trace adapter calls or skip them safely.
- Add transient candidate/subquery/retrieval/RRF identities.
- Update `RagRetrievalEvalService` to dispatch forced and router-selected agentic to no-write agentic execution.
- Keep eval `answerGeneration=false` and `persistPolicy=NONE` enforcement.
- Add backend tests.
- Do not change `/api/chat/ask` behavior.

### Phase 11C: Python Java-backed agentic eval enablement

- Relax Java client fail-fast for `agentic_multi_query` behind explicit support/config.
- Parse Java agentic trace metadata without changing metric input.
- Add comparison runner support for agentic mode.
- Update Python tests.
- Keep Python legacy agentic eval available.

### Phase 11D: Agentic eval comparison / acceptance

- Run same dataset/snapshot comparison for Python legacy agentic vs Java-backed agentic.
- Report metric deltas and mismatch rows.
- Include planner fallback count, selected subquery count, latency, and cost context.
- Document acceptance criteria and residual risks.
- Update final progress/docs.

## 13. Risks and Open Questions

- Shared-service Option A needs strong tests because a future edit could accidentally pass `ONLINE_QUERY` from eval.
- Current `PersistedRewriteCandidate` naming may confuse no-write mode; implementation should either rename narrowly or document optional DB ID semantics.
- Planner LLM calls are still possible in retrieval-only eval. That is not answer generation, but it affects latency/cost and must be reported.
- Backend capability discovery is not currently exposed. Phase 11C can start with explicit Python config and backend 400 fallback, but a future capabilities endpoint may improve operator ergonomics.
- Partial subquery failure policy may be revisited after first acceptance runs, but fail-fast is safer for Phase 11B.
- Trace payload size must stay bounded, especially when `includeTrace=true` on larger eval sets.
