---
id: gen_a_v1
family: query_generation
version: v4
status: active
---

Strategy hypothesis:
- A is anchor-fidelity strategy: preserve source semantics and technical anchors through English-first drafting.
- Retrieval tendency: precise intent + explicit technical anchor, strong single-hit discrimination.
- Difference from B/C/D: prioritize semantic accuracy and anchor preservation before Korean naturalization.

Inputs:
- original_chunk_en
- extractive_summary_en
- glossary_terms_keep_english
- query_type(definition|reason|procedure|comparison|short_user|code_mixed|follow_up)
- answerability_type(single|near|far)

Rules:
1. Generate `query_en` first from grounded user intent, then generate `query_ko`.
2. `query_en` and `query_ko` must keep semantic parity: same intent, same scope, same evidence need.
3. `query_ko` must be natural Korean developer phrasing; avoid literal translation tone.
4. Preserve key technical terms in English where needed.
5. Prefer problem-solving, root-cause, procedure, and behavior-difference intent over textbook wording.
6. Include concrete retrieval anchors when relevant: annotation, configuration property, bean lifecycle, auto-configuration, transaction, security, testing, web, data access, actuator, configuration binding.
7. Keep both queries concise (one sentence).

Quality targets:
1. Should look like real Korean developer search/chat query.
2. Must contain enough signal to distinguish target chunk from similar chunks.
3. Avoid too broad, too generic, or too compressed ambiguous wording.
4. Keep answerability within chunk scope implied by `answerability_type`.

Answerability guidance:
1. `single`: direct question answerable from one chunk, with clear local anchor.
2. `near`: better answered by linking adjacent chunk details (one chunk alone can be slightly insufficient).
3. `far`: requires connecting separated document evidence; still answerable, not impossible.

Query type control:
1. `definition`: practical usage context included.
2. `reason`: why/cause/background of behavior or config outcome.
3. `procedure`: setup/apply/fix steps.
4. `comparison`: concrete difference/trade-off between options.
5. `short_user`: short but specific and anchored.
6. `code_mixed`: Korean base with key technical terms in English.
7. `follow_up`: continuation tone implying prior context.

Forbidden patterns:
1. Source chunk sentence copy or near-copy.
2. Generic question that can fit many unrelated chunks.
3. Yes/no-only, overly broad, or out-of-scope question.
4. Missing retrieval anchor.
5. Overly long or overly compressed ambiguous question.
6. Style mismatch with `query_type` or `answerability_type`.

Output contract:
1. Runtime uses structured output, but you must still output exactly one JSON object only.
2. No markdown, no code fence, no trailing text.
3. Required fields must exist and be non-empty strings.
4. `query_type` and `answerability_type` must exactly echo input labels.
5. Keep `query_en` and `query_ko` <= 160 chars.
6. If possible, first char `{` and last char `}`.

Output schema:
{
  "query_en": "...",
  "query_ko": "...",
  "query_type": "...",
  "answerability_type": "..."
}

Internal self-check (do not output):
- parity ok, anchor present, answerability scope ok, single JSON object only.
