# Langfuse Event Schema (Query Forge)

## 1) Scope

This schema is for LLM observability events emitted by `pipeline/common/llm_client.py`.

- No pipeline stage order changes
- No generation/gating/eval business logic changes
- Instrumentation is fail-open (Langfuse errors must never break pipeline execution)

## 2) Observation Model

- Observation type: `generation`
- Observation name: `llm.{request_purpose}`
- Trace name: `query-forge.llm`

Supported request purposes (current):

- `summary_extraction_en`
- `translate_chunk_en_to_ko`
- `summary_extraction_ko`
- `generate_query`
- `generate_query_retry`
- `quality_gating_self_eval`
- `selective_rewrite`
- `stability_eval_*`, `stability_mock_fallback_probe`

## 3) Required Tags

The following tags are attached to every emitted observation:

- `query-forge`
- `pipeline`
- `llm`
- `purpose:{request_purpose}`
- `provider:{provider}`
- `provider_type:{provider_type}`
- `stage:{logical_stage}`
- `status:{success|error}`

Conditional tags:

- `fallback:true` (when fallback model path is used)
- `structured_output:true` (when structured output path is used)

## 4) Required Metadata

Metadata keys (schema version `qf.langfuse.llm.v1`):

- `schema_version`
- `component` (`pipeline.common.llm_client`)
- `request_purpose`
- `stage`
- `trace_id` (Query Forge logical trace id, if provided)
- `provider`
- `provider_type`
- `model`
- `status`
- `fallback_used`
- `structured_output_used`
- `retry_count`
- `attempts_used`
- `latency_ms`
- `estimated_tokens`
- `usage_details` (`prompt_tokens`, `completion_tokens`, `total_tokens` when available)
- `response_schema_present`
- `response_schema_hash`
- `prompt_fingerprint`
- `http_status` (error path)
- `error_type` (error path)
- `error_message` (error path, truncated)
- `sample_rate`

## 5) Input / Output Payload Policy

Input payload:

- Always: prompt fingerprint + prompt length + schema hash/presence + estimated tokens
- Optional prompt previews by env flags:
  - `QUERY_FORGE_LANGFUSE_CAPTURE_SYSTEM_PROMPT`
  - `QUERY_FORGE_LANGFUSE_CAPTURE_USER_PROMPT`

Output payload:

- Success: key list + truncated JSON preview + usage details
- Error: error type/message + usage details

Truncation controls:

- `QUERY_FORGE_LANGFUSE_MAX_PROMPT_CHARS`
- `QUERY_FORGE_LANGFUSE_MAX_OUTPUT_CHARS`

## 6) Free-Tier Guardrails

Default controls optimized for Langfuse free-tier usage:

- `QUERY_FORGE_LANGFUSE_ENABLED=false` (explicit opt-in)
- Low default success sampling (`0.03`)
- Purpose-specific success sampling:
  - High-volume generation paths are sampled very low (`generate_query=0.01`)
  - Quality-critical paths sampled higher (`quality_gating_self_eval=0.15`, `selective_rewrite=0.30`)
  - Retry path sampled fully (`generate_query_retry=1.0`)
- Error sampling default `1.0` for incident visibility
- Hard ingestion caps:
  - `QUERY_FORGE_LANGFUSE_MAX_EVENTS_PER_MINUTE=120`
  - `QUERY_FORGE_LANGFUSE_MAX_EVENTS_PER_DAY=30000`

This policy prioritizes:

1. failure and retry visibility,
2. gating/rewrite quality monitoring,
3. low overhead on high-volume generation traffic.

## 7) Scoring Policy

Score mode is controlled by:

- `QUERY_FORGE_LANGFUSE_SCORE_MODE` = `off | errors | sampled_all`

Default: `errors`

When enabled, scores are emitted via Langfuse score API:

- `llm_request_success` (BOOLEAN)
- `llm_latency_ms` (NUMERIC)

## 8) Correlation and Session Semantics

- Logical `trace_id` from Query Forge request context is persisted in metadata.
- `QUERY_FORGE_LANGFUSE_USER_ID` defaults to `query-forge-pipeline` for pipeline-level grouping.

## 9) Non-Interference Contract

The integration must preserve:

- response parsing and retry/fallback behavior
- gating decision logic and thresholds
- experiment run persistence and metrics semantics

Langfuse emission failures are swallowed after local logging and do not affect LLM request outcome.
