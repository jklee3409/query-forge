# index.md

## Directory Overview
Focused Python unit tests for pipeline runtime helpers, evaluation logic, LLM client behavior, corpus import safeguards, and synthetic generation utilities.

## Key Notes
- Tests are designed to stay lightweight and avoid full pipeline, full corpus, or unbounded DB workloads.
- `test_strategy_router_eval.py` covers the opt-in retrieval-eval `strategy_router` decision rules and sample trace fallback behavior.
