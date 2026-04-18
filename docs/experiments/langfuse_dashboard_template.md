# Langfuse Dashboard Template for RAG Quality + Performance

## 1) Purpose

This template defines a practical Langfuse dashboard set for Query Forge RAG evaluation runs.
It combines quality signals and performance signals in one operational view.

## 2) Recommended Dashboard Panels

### A. LLM Reliability

- Metric: request count (success/error)
- Group by: `metadata.request_purpose`, `metadata.provider`, `metadata.model`
- Filters:
  - `tags contains query-forge`
  - `tags contains pipeline`
- Goal: quickly detect error spikes by purpose and model.

### B. LLM Latency (p50/p95)

- Metric: observation latency (avg, p95)
- Group by: `metadata.request_purpose`
- Filters:
  - `metadata.status = success`
- Goal: monitor LLM latency drift for generation/gating/rewrite paths.

### C. Token Usage

- Metric: usage (`prompt_tokens`, `completion_tokens`, `total_tokens`)
- Group by: `metadata.request_purpose`, `metadata.model`
- Goal: detect high-cost purposes and prompt growth.

### D. Fallback / Retry Signal

- Metric: count
- Filters:
  - `tags contains fallback:true`
  - optional: `metadata.retry_count > 0`
- Goal: catch provider instability and model mismatch.

### E. Rewrite Overhead Watch (derived)

- Source: RAG summary payload (`metrics_json.performance.rewrite_overhead_avg_latency_ms`)
- Display:
  - latest value by run
  - trend by run timestamp
- Goal: verify rewrite improves quality without unacceptable latency overhead.

## 3) Suggested Filters for Daily Ops

- `tags contains stage:eval-retrieval/eval-answer`
- `metadata.request_purpose in (selective_rewrite, quality_gating_self_eval, generate_query)`
- `metadata.status in (success, error)`
- `metadata.sample_rate >= 0.1` (when investigating sparse traces)

## 4) Suggested Alert Conditions

- Error rate by purpose > 5% (5m window)
- p95 latency by purpose > baseline x 1.5
- fallback ratio > 10%
- rewrite overhead average (`rewrite_overhead_avg_latency_ms`) exceeds agreed SLO

## 5) Query Forge Field Mapping

Main fields used by this template:

- tags:
  - `purpose:*`
  - `provider:*`
  - `provider_type:*`
  - `stage:*`
  - `status:*`
- metadata:
  - `request_purpose`
  - `latency_ms`
  - `usage_details`
  - `retry_count`
  - `fallback_used`
  - `trace_id`
  - `sample_rate`

## 6) Rollout Notes

- For initial verification, temporarily increase success sampling in staging.
- After baseline stabilization, restore low sampling to protect free-tier quotas.
- Keep score mode at `errors` unless deeper experiments require `sampled_all`.
