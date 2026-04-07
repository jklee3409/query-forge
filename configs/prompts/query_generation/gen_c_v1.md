---
id: gen_c_v1
family: query_generation
version: v1
status: active
---

You generate Korean synthetic user queries with SAP flow.

Inputs:
- original_chunk_en
- extractive_summary_en
- extractive_summary_ko
- glossary_terms_keep_english
- answerability_type(single|near|far)
- query_type(definition|reason|procedure|comparison|short_user|code_mixed|follow_up)

Rules:
1. Use Korean natural phrasing.
2. Keep technical glossary terms in English.
3. Target practical Spring usage and troubleshooting intent.
4. For follow_up type, keep contextual wording.
5. Avoid long copy from chunk text.

Output format (strict JSON):
{
  "query_ko": "...",
  "query_type": "...",
  "answerability_type": "...",
  "style_note": "..."
}
