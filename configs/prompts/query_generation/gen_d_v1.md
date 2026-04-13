---
id: gen_d_v1
family: query_generation
version: v4
status: active
---

Strategy hypothesis:
- D is language-style ablation: Korean vs code-mixed only.
- Retrieval tendency to compare style impact while keeping identical information need.
- Difference from A/B/C: style variance is the only intended variable.

Inputs:
- original_chunk_en
- extractive_summary_en
- extractive_summary_ko
- glossary_terms_keep_english
- query_type(definition|reason|procedure|comparison|short_user|code_mixed|follow_up)
- answerability_type(single|near|far)

Rules:
1. Generate two variants: `query_ko` and `query_code_mixed`.
2. Both variants must keep semantic parity: same intent, same scope, same evidence requirement.
3. Keep technical entities in English in both variants.
4. `query_code_mixed` must be natural for Korean developers; unnatural Konglish is forbidden.
5. Keep both variants concise and retrieval-anchor rich.
6. Use anchors when relevant: annotation, configuration property, bean lifecycle, auto-configuration, transaction, security, testing, web, data access, actuator, configuration binding.

Quality targets:
1. Difference between variants should be style only, not content.
2. Both variants should discriminate target chunk from similar chunks.
3. Both variants must remain answerable in `single/near/far` scope.

Answerability guidance:
1. `single`: one-chunk direct answerability.
2. `near`: adjacent evidence linkage needed.
3. `far`: separated evidence linkage needed, still answerable.

Query type control:
1. `definition`: practical definition in context.
2. `reason`: why/cause-oriented.
3. `procedure`: setup/apply/fix steps.
4. `comparison`: concrete difference/trade-off.
5. `short_user`: short but clear and anchored.
6. `code_mixed`: Korean base + key technical terms English.
7. `follow_up`: continuation tone with implied context.

Forbidden patterns:
1. Source sentence copy or shallow paraphrase.
2. Generic or anchor-less question.
3. Yes/no-only or too broad question.
4. One variant containing extra constraints not in the other.
5. Overly long or overly compressed ambiguous question.
6. Style mismatch with `query_type` or `answerability_type`.
7. Any non-JSON text output.

Output contract:
1. Runtime structured output is available, but output must still be exactly one JSON object.
2. No markdown, no code fence, no trailing text.
3. Required fields must be present and non-empty.
4. `query_type` and `answerability_type` must exactly echo input labels.
5. Keep `query_ko` and `query_code_mixed` each <= 170 chars.
6. If possible, first char `{` and last char `}`.

Output schema:
{
  "query_ko": "...",
  "query_code_mixed": "...",
  "query_type": "...",
  "answerability_type": "..."
}

Internal self-check (do not output):
- two variants parity ok, anchor present, answerability scope ok, single JSON object only.
