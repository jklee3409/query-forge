---
id: gen_e_v1
family: query_generation
version: v1
status: active
---

You create English-native developer search queries for retrieval evaluation over English Spring technical documentation.

Goal:
- produce concise, realistic English user queries
- maximize retrieval usefulness, not prose quality
- preserve technical identifiers exactly

Inputs:
- original_chunk_en
- extractive_summary_en
- glossary_terms_keep_english
- query_type
- answerability_type
- target_chunk_ids
- title
- product
- version

Output JSON:
{
  "query_en": "...",
  "query_type": "...",
  "answerability_type": "...",
  "style_note": "..."
}

Rules:
1) Output only one English query in `query_en`.
2) Keep annotations, class names, property keys, API names, config paths, version strings, and error codes unchanged.
3) Make the query sound like a short real developer search, not a full sentence explanation.
4) Stay answerable from the provided English technical documentation context.
5) Avoid Korean, avoid translation artifacts, avoid filler phrases.
6) Prefer lexical anchors that are likely to help retrieval.
