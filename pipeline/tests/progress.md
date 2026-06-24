# progress.md

## [2026-06-25] Java Runtime Retrieval Eval Tests
- Updated Java retrieval client tests for official Java default behavior, GUI runtime config propagation, and Java-backed `agentic_multi_query` execution instead of client-side blocking.
- Kept comparison runner tests DB-free and Java-server-free; comparison-mode agentic remains a separate comparison policy while direct Java retrieval eval can execute agentic through the endpoint.
- Validation: `PYTHONPATH=E:\dev_factory\univ\query-forge python -m unittest discover -s pipeline/tests -p test_java_retrieval_client.py` passed; `PYTHONPATH=E:\dev_factory\univ\query-forge python -m unittest discover -s pipeline/tests -p test_retrieval_eval_compare.py` passed.

## [2026-06-24] Official Java-backed Eval Policy Regression Tests
- Added Phase 9B regression coverage for `retrieval_eval_backend=java`, `official_eval_backend=java`, and `eval_retrieval_backend=java` selecting the Java client branch.
- Added audit coverage that `retrieval_eval_backend=legacy` avoids Java client construction, explicit legacy overrides old `use_java_backend=true`, and the implicit default remains legacy.
- Locked metadata fields for `official_backend`, `retrieval_eval_backend`, `legacy_available`, `legacy_fallback_used`, `official_java_endpoint`, `supported_modes`, and `blocked_modes`.
- Verified comparison runner variants keep explicit legacy/java backend policy and preserve `schema_version`, `metric_delta_rows`, and `mismatch_rows`.
- Kept tests Java-server-free and DB-free with fake Java clients/settings; Admin GUI and StrategyRouter files were not touched.
- Validation: `python -m py_compile pipeline/eval/java_retrieval_client.py pipeline/eval/retrieval_eval.py pipeline/eval/retrieval_eval_compare.py pipeline/tests/test_java_retrieval_client.py pipeline/tests/test_retrieval_eval_compare.py` passed; `python -m unittest pipeline.tests.test_eval_runtime pipeline.tests.test_strategy_router_eval pipeline.tests.test_java_retrieval_client pipeline.tests.test_retrieval_eval_compare -q` passed; focused Java endpoint and `/api/chat/ask` regression commands passed; `git diff --check` passed.

## [2026-06-24] Official Java-backed Eval Policy Tests
- Added Phase 9A tests for `retrieval_eval_backend=java|legacy`, explicit legacy fallback overriding old Java opt-in flags, official/actual backend metadata, supported non-agentic Java mode calls, agentic Java blocking, and comparison report policy metadata.
- Kept tests Java-server-free and DB-free with fake Java clients/settings.
- Validation: `python -m py_compile pipeline/eval/java_retrieval_client.py pipeline/eval/retrieval_eval.py pipeline/eval/retrieval_eval_compare.py pipeline/tests/test_java_retrieval_client.py pipeline/tests/test_retrieval_eval_compare.py` passed; `python -m unittest pipeline.tests.test_eval_runtime pipeline.tests.test_strategy_router_eval pipeline.tests.test_java_retrieval_client pipeline.tests.test_retrieval_eval_compare -q` passed.

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
