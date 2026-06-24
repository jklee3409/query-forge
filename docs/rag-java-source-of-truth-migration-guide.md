# Query-Forge RAG Java Source-of-Truth Migration Guide

## 1. Problem Definition

Query-Forge currently has overlapping RAG execution logic in the Python pipeline and the Java backend.

Python pipeline responsibilities:

- Technical-document domain synthetic query generation
- A/B/C/D/E/F/G synthetic query generation
- Quality Gating
- Memory build from gated synthetic queries
- Eval dataset construction
- Retrieval metric calculation
- CSV/JSON report generation
- Legacy mirror execution for raw/selective/anchor/agentic/router eval modes

Java backend responsibilities:

- Live user chat
- `/api/chat/ask`
- `RagService.ask()`
- Runtime config lookup by `domainId`
- Readiness check
- Synthetic memory lookup
- `QueryStrategyRouter`
- Selective rewrite
- Anchor-aware rewrite
- Raw/rewrite retrieval
- Agentic multi-query retrieval
- RRF merge
- Answer generation
- Online query metadata persistence

Core problem:

- If Python eval and Java online chat implement the same retrieval/rewrite/router/agentic behavior differently, drift is inevitable.
- Research metrics can diverge from real user chat behavior.
- Prior code analysis showed that `RagService.ask()` mixes retrieval execution, persistence, answer generation, and response formatting.
- `AgenticRetrievalService` also performs DB writes during execution.
- Therefore, adding a simple eval endpoint is not enough. The migration must separate execution results from persistence adapters before Java can become the retrieval execution source of truth.

## 2. Target Architecture

```text
Python
= research backoffice
+ synthetic query generation
+ quality gating
+ memory build
+ eval dataset loading
+ metric calculation
+ CSV/JSON report

Java
= retrieval execution source of truth
+ memory lookup
+ raw retrieval
+ selective rewrite
+ anchor-aware rewrite
+ strategy router
+ agentic multi-query retrieval
+ RRF merge
+ fallback
+ trace object generation

Python eval
-> Java retrieval-only endpoint
-> retrievedChunkIds
-> Python retrieval_metrics()
```

Python remains the research and reporting environment. Java becomes the single implementation for online/eval retrieval execution.

## 3. Research vs Runtime Config Separation

Research Backoffice Config:

- Used for research and experiments.
- Can test multiple strategy and snapshot combinations.
- Must not affect live user chat until explicitly approved.
- Used only by Python eval or Java eval endpoints.

Approved Runtime Config:

- Used for real user chat.
- Must contain only approved settings.
- Is reflected in `chat_runtime_config`.
- Must retain change history and provenance.

Rules:

- Research settings are not applied to user chat before approval.
- Official evaluation results are computed from Java source-of-truth execution.
- Python legacy mirror eval remains only for fast experiments and regression comparison.
- Real user chat always uses approved Java runtime config.

## 4. Invariants

1. Do not break existing `/ask` behavior.
2. Do not arbitrarily change the existing online chat response shape.
3. Do not break synthetic query generation, gating, or memory build pipelines.
4. Do not change DB schema without separate approval.
5. Cross-Domain Retrieval is outside this migration scope.
6. DomainRouter is outside this migration scope.
7. Adding `AGENTIC_MULTI_QUERY` to Java `QueryStrategyRouter` is a later phase.
8. Do not immediately delete the Python legacy eval path.
9. Java source-of-truth eval endpoints must not run answer generation by default.
10. Eval default `persistPolicy` must be `NONE`.

## 5. Responsibility Split

| Area | Keep In | Reason |
|---|---|---|
| synthetic query generation | Python | batch research pipeline |
| Quality Gating | Python | offline quality control |
| memory build | Python | gated query materialization |
| eval dataset loading | Python | batch evaluation |
| metric calculation | Python | Recall/MRR/nDCG/report |
| online chat | Java | production serving |
| memory lookup | Java | online/eval source of truth |
| raw retrieval | Java | online/eval source of truth |
| selective rewrite | Java | online/eval source of truth |
| anchor-aware rewrite | Java | online/eval source of truth |
| strategy router | Java | approved runtime strategy |
| agentic multi-query retrieval | Java | online/eval source of truth |
| RRF merge | Java | ranking source of truth |
| fallback | Java | production parity |
| answer generation | Java | online chat only |

## 6. Recommended Final Java Structure

```text
RagController /ask
-> RagService
-> RagRetrievalExecutionService
-> RagTracePersistenceService
-> ChatAnswerService
-> AskResponse

RagEvalRetrievalController /api/rag/eval/retrieval
-> RagRetrievalExecutionService
-> RagEvalRetrievalResponse
```

### RagRetrievalExecutionService

Responsibilities:

- Retrieval execution core
- Memory lookup
- Router decision
- Raw retrieval
- Selective rewrite
- Anchor-aware rewrite
- Agentic multi-query retrieval
- RRF merge
- Rerank
- Final retrieved docs decision
- In-memory trace creation

Restrictions:

- Must not depend on `ChatAnswerService`.
- Must not perform DB writes directly.
- Must return execution results and trace objects that can be persisted or returned by callers.

### RagTracePersistenceService

Responsibilities:

- Persist execution results according to `persistPolicy`.
- Preserve existing online trace behavior for `/ask`.
- Avoid persistence for eval by default.
- Distinguish `NONE`, `TRACE_ONLY`, and `ONLINE_QUERY`.

### RagEvalRetrievalController

Responsibilities:

- Evaluation-only retrieval endpoint.
- Never perform answer generation.
- Default `persistPolicy` is `NONE`.
- Return retrieved chunk ids, retrieved docs, route/rewrite/agentic trace, latency, and LLM call counts.

## 7. persistPolicy

`NONE`

- No DB writes.
- Default for Python eval.
- Response trace only.

`TRACE_ONLY`

- Store `online_queries` root and trace rows with `source=eval`, without answer generation.
- Must be distinguishable from real user traffic.

`ONLINE_QUERY`

- Store online trace similarly to existing `/ask`.
- If `answerGeneration=false`, do not store an `answers` row.
- Default policy for `/ask`.

Notes:

- Current trace tables are centered on `online_query_id`, so `TRACE_ONLY` may still require an `online_queries` root row.
- Eval default must remain `NONE`.
- Eval trace persistence must include metadata such as `source`, `evalRunId`, `sampleId`, and `forcedMode` so it does not pollute production traffic analysis.

## 8. forcedMode Semantics

`raw_only`

- Bypass Router.
- Skip memory, rewrite, and agentic execution.
- Retrieve with the original query.

`selective_rewrite`

- Bypass Router.
- Force synthetic selective rewrite.
- Set anchor injection false.
- Disable agentic execution.

`anchor_aware_rewrite`

- Bypass Router.
- Force `anchorInjectionApplied=true`.
- In eval, this may be forced regardless of technical-anchor detection.

`agentic_multi_query`

- Bypass Router.
- Force agentic execution.
- This is not currently selected by Java `QueryStrategyRouter`.
- Until Router enhancement, use only as forced mode.

`strategy_router`

- Do not bypass Router.
- Use current Java `QueryStrategyRouter` result.
- Current supported strategies are `RAW_ONLY`, `SYNTHETIC_SELECTIVE_REWRITE`, and `ANCHOR_AWARE_REWRITE`.
- `AGENTIC_MULTI_QUERY` selection is a later phase.

## 9. Migration Phases

| Phase | Goal | Key Work | Prohibited | Done When |
|---|---|---|---|---|
| Phase 0 | Guide creation | Write this document | Code changes | Guide created and progress recorded |
| Phase 1 | `/ask` characterization test | Freeze existing online chat behavior in tests | Production logic changes | Raw/selective/anchor/agentic key tests pass |
| Phase 2 | Execution result model | Add request/result/trace/persistPolicy models | Existing ask flow changes | Compile/test pass |
| Phase 3 | Low-level helper extraction | Candidate `DomainScopedRetrievalService` extraction | Orchestration changes | No `/ask` regression |
| Phase 4 | Non-agentic execution service | Move raw/selective/anchor/router to `RagRetrievalExecutionService` | Agentic write changes | `/ask` result and writes preserved |
| Phase 5 | Persistence adapter | Introduce `RagTracePersistenceService` | Answer storage policy changes | `ONLINE_QUERY` preserves existing writes |
| Phase 6 | Agentic side-effect control | Remove/control `AgenticRetrievalService` writes | Agentic behavior changes | Agentic `/ask` regression tests pass |
| Phase 7 | Retrieval-only eval endpoint | Add `/api/rag/eval/retrieval` | Answer generation | forced modes and `persistPolicy.NONE` verified |
| Phase 8 | Python eval Java backend | Add `JavaRetrievalClient` | Delete `python_legacy` | Java backend metrics can be calculated |
| Phase 9 | Official eval path switch | Use Java backend for official eval | Immediate legacy deletion | Report comparison completed |
| Phase 10 | StrategyRouter agentic enhancement | Add `AGENTIC_MULTI_QUERY` strategy | Before source-of-truth stabilization | Router impact verified by eval |

## 10. Phase Work Rules

- Each Phase is a separate Codex task.
- Do not combine multiple Phases in one task.
- Before each Phase, read this guide and `../.codex/AGENTS.md`.
- Before each Phase, read the related module `index.md` and `progress.md`.
- After each Phase, update the necessary `progress.md` files.
- Each Phase must include explicit validation commands.
- Do not proceed to the next Phase if validation fails.
- If existing `/ask` regression appears, stop immediately and report the cause.
- Do not infer uncertain structure. Inspect code and report uncertainty before implementation.

## 11. Test Gates

### Java Gate

```text
- compileJava passes
- RagServiceTest passes
- RagControllerWebTest passes
- QueryStrategyRouterTest passes
- SearchResultMergerTest passes
- AgenticQueryPlannerServiceTest passes
- Newly added phase tests pass
```

### Python Gate

```text
- py_compile passes
- pipeline.tests.test_eval_runtime passes
- Java backend off graceful failure test passes
- Java response -> metric input conversion test passes
- python_legacy regression test passes
```

### Required Regression Checks

```text
- Existing /ask response behavior is preserved
- Answer generation is preserved for /ask
- Existing online persistence is preserved for /ask
- domainId filter is preserved
- Agentic service is not called when agentic is disabled
- ChatAnswerService is not called by eval endpoint
- persistPolicy.NONE performs no DB writes
```

## 12. progress.md Recording Rules

At the end of each Phase, update the root progress and related module progress with a concise entry.

Each entry should include:

- Phase number
- Change summary
- Validation command
- Result
- Remaining risks

Detailed design iteration belongs in this guide or a separate phase note, not in long progress narratives.

## 13. Prohibited Work

- Removing existing Python generation/gating/memory build.
- Immediately deleting Python legacy eval.
- Introducing breaking changes to Java `/ask` responses.
- Running answer generation in eval endpoints.
- Polluting `online_queries` from eval by default.
- Enhancing Router agentic strategy before source-of-truth stabilization.
- Cross-Domain Retrieval.
- DomainRouter.
- Making `domainId` optional.
- DB schema changes without separate approval.
- Combining large refactoring and endpoint addition in a single Phase.

## 14. Phase 7A Retrieval-only Eval Endpoint Boundary Design

Phase 7A is a design-only phase. It does not add `/api/rag/eval/retrieval`, does not modify production retrieval services, and does not connect eval traffic to `RagService.ask()`.

### 14.1 Code Audit Baseline

Current code facts verified before the Phase 7A design:

- `RagService.ask()` validates `domainId`, loads chat runtime config/readiness, calls `repository.createOnlineQuery(...)`, performs retrieval/rewrite routing, calls `buildAnswer(...)`, and then calls `repository.insertAnswer(...)`.
- `buildAnswer(...)` calls `ChatAnswerService.generateAnswer(...)`.
- `RagRetrievalExecutionService` already exposes non-agentic execution methods:
  - `executeRawOnly(...)`
  - `executeSelectiveRewrite(...)`
  - `executeAnchorAwareRewrite(...)`
- `QueryStrategyRouter` currently returns only `RAW_ONLY`, `SYNTHETIC_SELECTIVE_REWRITE`, or `ANCHOR_AWARE_REWRITE`.
- `ForcedRetrievalMode` already includes `RAW_ONLY`, `SELECTIVE_REWRITE`, `ANCHOR_AWARE_REWRITE`, `AGENTIC_MULTI_QUERY`, and `STRATEGY_ROUTER`.
- `RagRetrievalExecutionRequest` defaults null `persistPolicy` to `RagPersistPolicy.NONE`, but it is not yet a complete eval orchestration boundary.
- `RagTracePersistenceService` phase-specific methods return no-write results for `persistPolicy=NONE`; `TRACE_ONLY` remains unsupported and generic `ONLINE_QUERY` remains unsupported.
- `AgenticRetrievalService.execute(...)` returns `mergedDocs` without answer generation, but its adapter calls currently pass `RagPersistPolicy.ONLINE_QUERY` directly.

### 14.2 Eval Request DTO Design

Recommended Java DTO name for Phase 7B:

```text
RagRetrievalEvalRequest
```

API string values should be lower snake case and normalized into the existing Java enums internally.

| Field | Required | Default | Allowed values | Meaning |
|---|---:|---|---|---|
| `domainId` | yes | none | UUID | Mandatory domain boundary. Cross-domain retrieval remains prohibited. |
| `query` | yes | none | non-blank string | User/eval query to execute against Java retrieval source of truth. |
| `forcedMode` | no | `strategy_router` | `raw_only`, `selective_rewrite`, `anchor_aware_rewrite`, `agentic_multi_query`, `strategy_router` | Retrieval mode requested by eval. `strategy_router` uses the current Java router only. |
| `topK` | no | chat runtime `retrievalTopK`, capped to positive value | positive integer | Eval result size target. Phase 7B should map this to both retrieval top K and rerank top N for stable `retrievedChunkIds`. |
| `persistPolicy` | no | `NONE` | `NONE`, `ONLINE_QUERY`; `TRACE_ONLY` parsed but rejected | Persistence policy. Eval default is always no-write. |
| `answerGeneration` | no | `false` | `false`; `true` unsupported | Must remain false for Phase 7A/7B. True should fail validation instead of falling through to chat answer generation. |
| `includeTrace` | no | `false` | boolean | Include in-memory route/rewrite/retrieval trace in the response. |
| `includeScores` | no | `true` | boolean | Include per-doc score fields and optional score map. |
| `includeMetadata` | no | `false` | boolean | Include bounded per-doc/runtime metadata. Default avoids large payloads. |

Validation rules:

- `domainId` is required.
- `query` is required and must not be blank.
- `answerGeneration=true` is unsupported in Phase 7A/7B.
- `persistPolicy` defaults to `NONE`.
- `TRACE_ONLY` is unsupported until a separate trace root/source policy is designed.
- `ONLINE_QUERY` is not the eval default and should not be enabled in the first implementation slice.
- `agentic_multi_query` is a forced mode only. It must not be added to `QueryStrategyRouter`.
- `strategy_router` must not select agentic until the router enum and tests are enhanced in a later phase.

### 14.3 Eval Response DTO Design

Recommended Java DTO name for Phase 7B:

```text
RagRetrievalEvalResponse
```

| Field | Included | Meaning |
|---|---|---|
| `query` | always | Original eval query. |
| `finalQuery` | always | Query actually used for final retrieval. Raw mode uses the original query. |
| `selectedMode` | always | Actual selected execution mode after router/forced-mode resolution. |
| `forcedMode` | always | Requested forced mode after normalization. |
| `domainId` | always | Domain used for retrieval. |
| `retrievedChunkIds` | always | Primary Python eval input. Ordered final chunk IDs. |
| `retrievedDocs` | always | Bounded doc list with `chunkId`, `documentId`, `title`, `contentPreview`, `score`, and `rank`; metadata is included only when requested. |
| `scores` | when `includeScores=true` | Optional score map keyed by chunk ID, useful for diagnostics. |
| `trace` | when `includeTrace=true` | In-memory route/rewrite/retrieval/agentic trace. |
| `llmCallCount` | always | Rewrite/planner/answer call counts. `answerCalls` must be `0`. |
| `latencyMs` | always | Total eval orchestration latency. |
| `persisted` | always | `false` when `persistPolicy=NONE`. |
| `persistPolicy` | always | Effective persistence policy. |
| `warnings` | always | Validation or partial-support warnings such as unsupported agentic no-write. |

Response rules:

- `retrievedChunkIds` is the stable contract consumed by Python retrieval metrics.
- Full chunk content should not be returned by default. Use `contentPreview` only; align the initial preview cap with existing chat trace preview behavior unless a later API contract chooses another cap.
- Do not include answer text.
- Do not run answer generation.
- Do not expose `onlineQueryId` for `persistPolicy=NONE`.

### 14.4 Non-agentic Execution Boundary

Phase 7B should introduce an eval-specific orchestration service rather than using `RagService.ask()`.

Recommended Java service name:

```text
RagRetrievalEvalService
```

Allowed dependencies:

- `ChatRuntimeConfigService` for domain config and readiness lookup.
- `QueryStrategyRouter` for `strategy_router` only.
- `DomainScopedRetrievalService` for embedding literals, memory candidates, and retrieval runtime.
- `RagRetrievalExecutionService` for non-agentic execution.
- `RagTracePersistenceService` only when explicitly passing `persistPolicy=NONE`, or skip it entirely for the first no-write slice.

Forbidden dependencies in this eval boundary:

- `RagService.ask()`
- `ChatAnswerService`
- direct `RagRepository.createOnlineQuery(...)`
- direct `RagRepository.insertAnswer(...)`

Mode mapping:

| Eval mode | Boundary behavior |
|---|---|
| `raw_only` | Build raw query embedding, then call `RagRetrievalExecutionService.executeRawOnly(...)`. |
| `selective_rewrite` | Load memory candidates in the same domain, then call `executeSelectiveRewrite(...)` with anchor injection disabled. |
| `anchor_aware_rewrite` | Load memory candidates in the same domain, then call `executeAnchorAwareRewrite(...)` with anchor injection applied. |
| `strategy_router` | Call the current `QueryStrategyRouter`; if it returns `RAW_ONLY`, `SYNTHETIC_SELECTIVE_REWRITE`, or `ANCHOR_AWARE_REWRITE`, dispatch to the matching execution method. |

Non-agentic no-write policy:

- Do not call `createOnlineQuery`.
- Do not call direct repository trace writes.
- Do not call answer generation.
- Do not call `insertAnswer`.
- Effective `persistPolicy=NONE` must produce `persisted=false`.
- `onlineQueryId` must be absent/null and must not be required for execution.
- Legacy direct-write fallback branches in `RagService` must be kept out of the eval path by avoiding `RagService.ask()`.

### 14.5 Agentic Execution Boundary and Blocker

Agentic eval is conceptually possible because `AgenticRetrievalService.execute(...)` returns `mergedDocs` and does not call `ChatAnswerService`. However, it is not no-write safe today.

Current blocker:

```text
AgenticRetrievalService passes RagPersistPolicy.ONLINE_QUERY directly to trace persistence adapter calls.
```

Additional Phase 7B risk to resolve before enabling no-write agentic eval:

- `AgenticExecutionRequest` has `onlineQueryId` but no `persistPolicy`.
- With `persistPolicy=NONE`, adapter candidate creation would return `rewriteCandidateId=null`.
- Current in-memory agentic selection/adoption trace compares selected candidates by `rewriteCandidateId`, so Phase 7B should avoid enabling agentic until transient in-memory candidate identity is designed.

Recommended later minimal agentic change:

1. Add `RagPersistPolicy persistPolicy` to `AgenticExecutionRequest`, defaulting to `ONLINE_QUERY` for existing `/ask` callers if omitted through constructor compatibility.
2. Allow `onlineQueryId=null` only when `persistPolicy=NONE`.
3. Thread `request.persistPolicy()` through all agentic adapter calls instead of hardcoded `ONLINE_QUERY`.
4. Keep `TRACE_ONLY` unsupported.
5. Add a transient candidate identity for no-write mode, or change in-memory selection to use candidate index when no DB candidate ID exists.
6. Cover no-write behavior with repository no-interaction tests before exposing `agentic_multi_query` in eval.

Phase 7B recommendation:

```text
agentic_multi_query should return an explicit unsupported/blocked response or validation error in the first eval implementation slice.
```

### 14.6 persistPolicy and onlineQueryId Policy

| Policy | Eval default | DB writes | `onlineQueryId` | Status |
|---|---:|---:|---|---|
| `NONE` | yes | no | not required | Required for Phase 7B. Response `persisted=false`. |
| `ONLINE_QUERY` | no | yes | required | Existing `/ask` online trace path only. Do not enable by default for eval. |
| `TRACE_ONLY` | no | unsupported | unresolved | Keep closed until trace root/source semantics are designed. |

Rules:

- Eval endpoint default must always be `NONE`.
- `NONE` means no `online_queries`, no retrieval/rerank trace rows, no rewrite log rows, no candidate rows, no decision/metadata rows, and no answers.
- `ONLINE_QUERY` belongs to existing live `/ask` behavior. Eval should not use it in Phase 7B.
- `TRACE_ONLY` should remain unsupported in Phase 7B because trace tables are still centered on `online_query_id` and eval source metadata is not implemented.

### 14.7 answerGeneration=false Policy

`answerGeneration=false` is mandatory for retrieval-only eval.

Rules:

- Default is `false`.
- `ChatAnswerService.generateAnswer(...)` must not be called.
- `RagService.buildAnswer(...)` must not be called.
- `RagRepository.insertAnswer(...)` must not be called.
- Response must not include an answer field or answer text.
- `llmCallCount.answerCalls` must be `0`.
- `answerGeneration=true` must be rejected as unsupported in Phase 7A/7B.

### 14.8 Phase 7B Recommended Implementation Slice

Recommended safest Phase 7B scope:

1. Add Java DTOs for `RagRetrievalEvalRequest` and `RagRetrievalEvalResponse`.
2. Add `RagRetrievalEvalService` orchestration skeleton.
3. Implement non-agentic `raw_only`, `selective_rewrite`, `anchor_aware_rewrite`, and current-range `strategy_router`.
4. Support only `persistPolicy=NONE`.
5. Reject `answerGeneration=true`.
6. Reject or explicitly block `agentic_multi_query`.
7. Add service-level tests proving no `createOnlineQuery`, no `insertAnswer`, and no `ChatAnswerService`.
8. Do not add the HTTP controller until the service boundary has no-write tests.

This slice avoids endpoint exposure before the no-write boundary is proven and avoids changing `AgenticRetrievalService` production logic prematurely.

### 14.9 Phase 7B Test Plan

Required tests after implementation starts:

- Request defaulting sets `persistPolicy=NONE`.
- Request defaulting sets `answerGeneration=false`.
- `answerGeneration=true` fails validation as unsupported.
- `domainId` is required.
- `query` is required.
- `raw_only` service execution does not call `createOnlineQuery`.
- `raw_only` service execution does not call `insertAnswer`.
- `raw_only` service execution does not call `ChatAnswerService.generateAnswer(...)`.
- `raw_only` response contains ordered `retrievedChunkIds`.
- `selective_rewrite` response contains `finalQuery` and ordered `retrievedChunkIds`.
- `anchor_aware_rewrite` response contains `selectedMode` and trace when `includeTrace=true`.
- `agentic_multi_query` is explicitly unsupported or blocked in Phase 7B.
- `strategy_router` dispatches only within current Java router strategies.
- `strategy_router` never adds or selects `AGENTIC_MULTI_QUERY`.
- `persistPolicy=NONE` produces `persisted=false`.
- Existing `/api/chat/ask` characterization tests remain unchanged and continue to pass.

## Required Prompt Header for Future Migration Tasks

```text
이번 작업은 Query-Forge RAG Java Source-of-Truth Migration의 일부다.

작업 전에 반드시 다음 문서를 읽어라.

1. ../.codex/AGENTS.md
2. docs/rag-java-source-of-truth-migration-guide.md
3. 관련 module의 index.md
4. 관련 module의 progress.md
5. root progress.md

이번 작업은 guide의 Phase <번호>에 해당한다.

반드시 지킬 것:
- 현재 Phase 범위를 넘지 말 것
- 기존 /ask 동작을 깨지 말 것
- 금지 사항을 위반하지 말 것
- 불확실한 부분은 추론하지 말고 보고할 것
- 작업 후 progress.md를 갱신할 것
- 검증 명령과 결과를 보고할 것
```
