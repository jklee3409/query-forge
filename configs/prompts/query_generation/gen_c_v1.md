---
id: gen_c_v1
family: query_generation
version: v3
status: active
---

Strategy C (SAP practical-troubleshooting):
Generate Korean synthetic queries with SAP flow.

Inputs:
- original_chunk_en
- extractive_summary_en
- extractive_summary_ko
- glossary_terms_keep_english
- answerability_type(single|near|far)
- query_type(definition|reason|procedure|comparison|short_user|code_mixed|follow_up)

Experiment hypothesis:
- C should maximize practical troubleshooting realism and retrieval discrimination.
- Compared with A/B, C should show stronger intent variety by `query_type` and context-sensitive tone.

Rules:
1. `query_ko` must be Korean developer-native and practically actionable.
2. Keep technical glossary terms and critical entities in English.
3. Explicitly prefer troubleshooting, misconfiguration diagnosis, and applied usage intent.
4. Include concrete retrieval anchors when relevant:
   annotation, configuration property, bean lifecycle, auto-configuration, transaction, security, testing, web, data access.
5. Keep query concise, specific, and non-generic.
6. `style_note` must record the stylistic intent in one short phrase (not meta explanation).

Answerability bias:
1. `single`: one chunk direct answer with clear local anchor.
2. `near`: naturally requires combining nearby chunk details (e.g., trigger + behavior).
3. `far`: requires linking separated concepts/info in document scope, still answerable.

Query type style control:
1. `definition`: practical meaning-in-context, not glossary style only.
2. `reason`: why/cause/background of behavior.
3. `procedure`: configuration or fix steps.
4. `comparison`: concrete option/behavior difference.
5. `short_user`: very short but unambiguous; must keep at least one anchor.
6. `code_mixed`: Korean base + key technical terms English.
7. `follow_up`: continuation tone that implies prior turn context.

Forbidden patterns:
1. Source sentence copy or near-copy.
2. Template-like textbook phrasing.
3. Generic questions applicable to almost any Spring chunk.
4. Out-of-scope or unanswerable question for target evidence.
5. Long, noisy, multi-intent query.

Output contract (strict JSON):
1. Output exactly one JSON object. No markdown, no code fence, no trailing text.
2. Required fields must be present and non-empty strings.
3. `query_type` and `answerability_type` must be exact echo of input labels.
4. Keep `query_ko` <= 160 chars and `style_note` <= 80 chars.

Output schema:
{
  "query_ko": "...",
  "query_type": "...",
  "answerability_type": "...",
  "style_note": "..."
}
