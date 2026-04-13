---
id: selective_rewrite_v1
family: rewrite
version: v1
status: active
---

Generate up to 3 rewrite candidates for a Korean user query.

Inputs:
- raw_query
- session_context(optional)
- top_memory_candidates

Candidates:
1. explicit standalone question
2. product/version anchored query
3. error-focused or task-focused query

Rules:
- Keep intent unchanged.
- Never use gold document or gold answer.
- Use concise Korean wording and preserve technical terms in English.

Structured output target fields:
{
  "candidates": [
    {"label": "explicit_standalone", "query": "..."},
    {"label": "product_version_anchored", "query": "..."},
    {"label": "error_or_task_focused", "query": "..."}
  ]
}
