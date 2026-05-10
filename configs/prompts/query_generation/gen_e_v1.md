---
id: gen_e_v1
family: query_generation
version: v1
status: active
---

Strategy hypothesis:
- E is English-native strategy: generate retrieval queries directly from English technical evidence.
- Retrieval tendency: concise English developer query with high anchor fidelity and strong lexical grounding.
- Difference from A/B/C/D/F/G: final synthetic query is English-only, with no Korean query as retrieval output.

Inputs:
- original_chunk_en
- extractive_summary_en
- glossary_terms_keep_english
- query_type(definition|reason|procedure|comparison|short_user|code_mixed|follow_up)
- answerability_type(single|near|far)
- target_chunk_ids
- title
- product
- version

Rules:
1. Generate one English retrieval query in `query_en`, grounded in `original_chunk_en` + `extractive_summary_en`.
2. Preserve technical anchors exactly when relevant: annotation, class/interface name, property key, API name, config path, artifact/module name, version string, error code.
3. Keep the query concise and retrieval-oriented (search/chat style), not explanatory prose.
4. Use metadata (`title`, `product`, `version`, `target_chunk_ids`) as grounding hints to avoid scope drift.
5. Keep answerability aligned to `single|near|far` evidence scope.
6. Avoid Korean text, translation artifacts, and filler phrasing.
7. For `query_type=code_mixed`, keep the sentence frame fully English; "mixed" means preserving exact code/identifier tokens, not Korean-English language mixing.

Quality targets:
1. Query should look like a real English developer search question.
2. Query must contain enough lexical signal to distinguish the target chunk from nearby similar chunks.
3. Avoid wording that is too broad, too generic, or too compressed/ambiguous.
4. Maintain retrieval-useful anchor density without turning the query into a long sentence.
5. `code_mixed` queries should still read as natural English while retaining exact technical tokens verbatim.

Answerability guidance:
1. `single`: directly answerable from one target chunk with explicit local anchor.
2. `near`: best answered by linking adjacent chunk evidence; one chunk alone can be slightly insufficient.
3. `far`: requires connecting separated in-document evidence; still answerable and practical.

Query type control:
1. `definition`: practical definition with usage context.
2. `reason`: asks why/cause/background of behavior or config outcome.
3. `procedure`: setup/apply/fix steps.
4. `comparison`: concrete difference/trade-off between options/configs.
5. `short_user`: short but specific and anchored.
6. `code_mixed`: English-native query with exact technical/code tokens preserved; do not force bilingual phrasing.
7. `follow_up`: concise continuation tone implying prior context.

Forbidden patterns:
1. Source sentence copy or near-copy.
2. Generic query that can match many unrelated chunks.
3. Yes/no-only, overly broad, or out-of-scope question.
4. Anchor loss for critical technical identifiers.
5. Overly long or overly compressed ambiguous query.
6. Style mismatch with `query_type` or `answerability_type`.
7. Forced Korean-English mixing or unnatural token stuffing for `code_mixed`.
8. Any non-JSON text output.

Output contract:
1. Runtime structured output exists, but you must output exactly one JSON object only.
2. No markdown, no code fence, no trailing text.
3. Required fields must exist and be non-empty strings.
4. `query_type` and `answerability_type` must exactly echo input labels.
5. Length guidance:
   - `query_en` <= 160 chars
   - `style_note` <= 80 chars
6. If possible, first char `{` and last char `}`.

Output schema:
{
  "query_en": "...",
  "query_type": "...",
  "answerability_type": "...",
  "style_note": "..."
}

Internal self-check (do not output):
- English-only, anchor preserved, answerability scope ok, style/type match, single JSON object only.
