---
id: gen_c_v1
family: query_generation
version: v4
status: active
---

Strategy hypothesis:
- C is aggressive practical-troubleshooting strategy for SAP flow.
- Retrieval tendency: strongest real-world problem-solving signal and style diversity by query type.
- Difference from A/B: prioritize applied troubleshooting behavior over neutral phrasing.

Inputs:
- original_chunk_en
- extractive_summary_en
- extractive_summary_ko
- glossary_terms_keep_english
- answerability_type(single|near|far)
- query_type(definition|reason|procedure|comparison|short_user|code_mixed|follow_up)

Rules:
1. `query_ko` must reflect practical Korean developer intent (search/chat/RAG usage).
2. Prioritize troubleshooting, misconfiguration diagnosis, and operational fix intent.
3. Keep key technical entities in English where needed.
4. Include concrete anchors when relevant: annotation, configuration property, bean lifecycle, auto-configuration, transaction, security, testing, web, data access, actuator, configuration binding.
5. `style_note` must be a short reason for style choice (e.g., `troubleshooting-cause`, `follow-up-context`, `short-search-intent`), not a long explanation.
6. Keep query concise, specific, and non-generic.

Quality targets:
1. Clear retrieval discrimination against similar chunks.
2. Strong distinction across `follow_up`, `code_mixed`, `short_user`.
3. Answerability remains inside `single/near/far` scope.
4. Avoid textbook tone; prefer applied engineering context.

Answerability guidance:
1. `single`: directly answerable from one chunk with explicit anchor.
2. `near`: adjacent chunk linkage needed for complete answer.
3. `far`: separated evidence linkage needed; still answerable and meaningful.

Query type control:
1. `definition`: practical definition in usage context.
2. `reason`: root cause / why behavior occurs.
3. `procedure`: setup/apply/fix sequence.
4. `comparison`: concrete difference between options/configs.
5. `short_user`: short but specific, anchor mandatory.
6. `code_mixed`: Korean base + essential English technical terms.
7. `follow_up`: continuation tone with implicit prior context.

Forbidden patterns:
1. Source chunk copy or near-copy.
2. Generic question that fits many unrelated chunks.
3. Yes/no-only or out-of-scope question.
4. Excessively long or overly compressed ambiguous wording.
5. Style mismatch with `query_type` or `answerability_type`.
6. Any non-JSON text output.

Output contract:
1. Structured output is enforced by runtime, but output must remain exactly one JSON object.
2. No markdown, no code fence, no trailing text.
3. Required fields must be non-empty.
4. `query_type` and `answerability_type` must exactly echo input labels.
5. Keep `query_ko` <= 160 chars and `style_note` <= 80 chars.
6. If possible, first char `{` and last char `}`.

Output schema:
{
  "query_ko": "...",
  "query_type": "...",
  "answerability_type": "...",
  "style_note": "..."
}

Internal self-check (do not output):
- style reason clear, anchor present, answerability scope ok, single JSON object only.
