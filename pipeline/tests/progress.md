# progress.md

## [2026-06-24] Comparison Report Contract Tests
- Added Phase 8D coverage for comparison report top-level schema, metric delta row fields/metric coverage, mismatch row fields/content exclusion, supported/blocked mode constants, and Phase 9 readiness criteria documentation.
- Kept tests DB-free and Java-server-free; no official eval default switch or agentic support was added.

## [2026-06-24] Legacy vs Java Retrieval Compare Tests
- Added `test_retrieval_eval_compare.py` for Phase 8C comparison mode validation, agentic fail-fast, sample/mode joins, metric delta calculation, exact/different mismatch detection, full-content exclusion, Java error fail-fast, and fake Java client injection.
- Kept tests DB-free and Java-server-free; existing legacy eval runtime tests were run in the combined command.

## [2026-06-24] Java-backed Eval Runtime Opt-in Tests
- Extended `test_java_retrieval_client.py` for Phase 8B runtime opt-in behavior: Java disabled does not build/call a client, Java enabled calls fake client for supported modes, ordered `retrievedChunkIds` drive metrics, additive Java metadata is emitted, agentic/forced-agentic are blocked before client calls, and Java client errors fail fast.
- Kept tests DB-free and Java-server-free with fake sessions/clients.

## [2026-06-24] Java Retrieval Eval Client Adapter Tests
- Added `test_java_retrieval_client.py` for Java eval request payload defaults, agentic rejection, response parsing, ProblemDetail mapping, backend unavailable wrapping, and Java-disabled legacy path behavior.
- Kept tests DB-free and Java-server-free by using fake sessions/clients.

## [2026-06-23] Strategy Router Eval Tests
- Added `test_strategy_router_eval.py` for Python strategy-router decision coverage and retrieval-eval raw fallback trace verification.
- Kept tests DB-free and scoped to targeted runtime behavior.

## Notes
- Keep this file concise.
- Record only meaningful test coverage changes.
