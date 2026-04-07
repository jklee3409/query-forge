---
id: gen_b_v1
family: query_generation
version: v1
status: active
---

Strategy B:
English original -> Korean translation -> Korean summary -> Korean synthetic query.

Rules:
1. Use Korean phrasing commonly used by developers.
2. Preserve key technical terms in English where needed.
3. Prefer actionable and user-like questions.
4. Keep question length compact and specific.

Output format (strict JSON):
{
  "translated_chunk_ko": "...",
  "summary_ko": "...",
  "query_ko": "...",
  "query_type": "...",
  "answerability_type": "..."
}

