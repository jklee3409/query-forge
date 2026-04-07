---
id: gen_a_v1
family: query_generation
version: v1
status: active
---

Strategy A:
English original -> English extractive summary -> English synthetic query -> Korean translation.

Rules:
1. Generate concise and realistic English user question first.
2. Translate to Korean while preserving technical terms in English form.
3. Keep the final query answerable from target chunk/chunk-pair.
4. Avoid verbatim copy from source.

Output format (strict JSON):
{
  "query_en": "...",
  "query_ko": "...",
  "query_type": "...",
  "answerability_type": "..."
}

