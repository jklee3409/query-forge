---
id: gen_b_v1
family: query_generation
version: v5
status: active
---

Strategy hypothesis:
- B is Korean-native strategy: English technical chunk -> Korean translation -> Korean extractive summary -> Korean synthetic query.
- Retrieval tendency: user-like Korean developer wording with preserved English technical anchors.
- Difference from A: generate the final query from Korean evidence, not from an English-first query draft.

Inputs:
- original_chunk_en
- translated_chunk_ko
- extractive_summary_ko
- glossary_terms_keep_english
- query_type(definition|reason|procedure|comparison|short_user|code_mixed|follow_up)
- answerability_type(single|near|far)

Rules:
1. Treat `translated_chunk_ko` and `extractive_summary_ko` as fixed upstream inputs, not output fields.
2. Do not translate, summarize, or rewrite the source evidence in the response.
3. Generate only `query_ko` from the Korean translation, Korean extractive summary, and glossary anchors.
4. `query_ko` must sound like a natural Korean developer search/chat query, not a translation artifact.
5. Preserve critical technical entities in English where needed: class names, annotations, properties, API names, commands, module/artifact names.
6. Prefer troubleshooting, configuration-cause, procedure, and comparison intent when compatible with `query_type`.
7. Use concrete source-grounded anchors when relevant: language/framework marker, configuration key/property, lifecycle or callback hook, API or command name, module/package/artifact name, runtime behavior, error message/code, version, file path, protocol, option, or feature area stated in the evidence.
8. Keep query short but not vague.

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
4. Outputting `translated_chunk_ko`, `summary_ko`, `extractive_summary_ko`, `style_note`, or any explanation field.
5. Style mismatch with `query_type` or `answerability_type`.
6. Any non-JSON text output.

Output contract:
1. Runtime structured output is available, but output must still be exactly one JSON object only.
2. No markdown, no code fence, no trailing text.
3. Required fields must be present and non-empty strings.
4. `query_type` and `answerability_type` must exactly echo input labels.
5. Keep `query_ko` <= 160 chars.
6. If possible, first char `{` and last char `}`.

Output schema:
{
  "query_ko": "...",
  "query_type": "...",
  "answerability_type": "..."
}

Internal self-check (do not output):
- Korean-native tone, anchor retained, answerability scope ok, no upstream artifact fields, single JSON object only.
