---
id: gen_b_v1
family: query_generation
version: v4
status: active
---

Strategy hypothesis:
- B is Korean-native strategy: maximize Korean developer search tone while keeping technical anchors.
- Retrieval tendency: user-like wording with preserved anchor signal after translation/summarization.
- Difference from A: prioritize Korean query naturalness, explicitly controlling anchor loss.

Inputs:
- original_chunk_en
- query_type(definition|reason|procedure|comparison|short_user|code_mixed|follow_up)
- answerability_type(single|near|far)

Rules:
1. `translated_chunk_ko` must be faithful and concise.
2. Preserve critical technical entities in English where needed (class/annotation/property/API names).
3. `summary_ko` must keep retrieval-critical anchors and constraints; do not over-abstract.
4. `query_ko` must sound like real Korean developer search/chat input.
5. Prefer troubleshooting, configuration-cause, procedure, and comparison intent.
6. Use concrete Spring anchors when relevant: annotation, configuration property, bean lifecycle, auto-configuration, transaction, security, testing, web, data access, actuator, configuration binding.
7. Keep query short but not vague.

Quality targets:
1. Query should differentiate the target chunk from nearby similar chunks.
2. Query must remain answerable within `single/near/far` evidence scope.
3. Avoid generic high-level wording and textbook-style phrasing.
4. Avoid too long or too compressed ambiguous wording.

Answerability guidance:
1. `single`: direct one-chunk answerability with explicit local anchor.
2. `near`: adjacent chunk linkage improves correctness; one chunk can be slightly insufficient.
3. `far`: separated evidence linkage required; still answerable in-document.

Query type control:
1. `definition`: definition with usage context.
2. `reason`: cause/background of behavior.
3. `procedure`: setup/apply/fix steps.
4. `comparison`: concrete config/behavior difference.
5. `short_user`: short and unambiguous.
6. `code_mixed`: Korean base with core terms in English.
7. `follow_up`: concise continuation tone.

Forbidden patterns:
1. Source sentence copy or shallow paraphrase copy.
2. Generic anchor-less question.
3. Yes/no-only, too broad, or out-of-scope question.
4. Overly abstract summary that drops retrieval signal.
5. Style mismatch with `query_type` or `answerability_type`.
6. Any non-JSON text output.

Output contract:
1. Runtime structured output is available, but output must still be one JSON object only.
2. No markdown, no code fence, no trailing text.
3. Required fields must be present and non-empty.
4. `query_type` and `answerability_type` must exactly echo input labels.
5. Length guidance:
   - `translated_chunk_ko` <= 900 chars
   - `summary_ko` <= 320 chars
   - `query_ko` <= 160 chars
6. If possible, first char `{` and last char `}`.

Output schema:
{
  "translated_chunk_ko": "...",
  "summary_ko": "...",
  "query_ko": "...",
  "query_type": "...",
  "answerability_type": "..."
}

Internal self-check (do not output):
- anchor retained, answerability scope ok, query style matches type, single JSON object only.
