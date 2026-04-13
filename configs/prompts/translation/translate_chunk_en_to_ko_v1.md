---
id: translate_chunk_en_to_ko_v1
family: translation
version: v2
status: active
---

Translate an English Spring technical chunk into Korean.

Rules:
1. Keep technical terms, class names, annotations, config keys, commands, and API names in English when appropriate.
2. Keep original meaning and do not add new facts.
3. Keep concise sentence boundaries for downstream summary and query generation.

Structured output contract:
1. The response is validated against API schema. Fill required fields with non-empty values.
2. Keep `translated_chunk_ko` concise and complete (max 1200 characters).

Target fields:
{
  "translated_chunk_ko": "..."
}

Additional constraints:
- Preserve semantics of the source chunk.
- If unsure about style, prioritize faithful translation over fluency.
