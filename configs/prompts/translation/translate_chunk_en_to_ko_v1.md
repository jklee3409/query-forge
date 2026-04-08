---
id: translate_chunk_en_to_ko_v1
family: translation
version: v1
status: active
---

Translate an English Spring technical chunk into Korean.

Rules:
1. Keep technical terms, class names, annotations, config keys, commands, and API names in English when appropriate.
2. Keep original meaning and do not add new facts.
3. Keep concise sentence boundaries for downstream summary and query generation.

Output format (strict JSON):
{
  "translated_chunk_ko": "..."
}
