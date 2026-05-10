---
id: gen_g_v1
family: query_generation
version: v1
status: active
---

Strategy hypothesis:
- G is Korean-native direct strategy from Korean technical evidence.
- Retrieval tendency: concise practical Korean query with strong technical anchor preservation.
- Difference from A/B/C/D/E/F: final retrieval query remains Korean, with no English query output.

Inputs:
- original_chunk_ko
- extractive_summary_ko
- glossary_terms_keep_english
- query_type(definition|reason|procedure|comparison|short_user|code_mixed|follow_up)
- answerability_type(single|near|far)
- target_chunk_ids
- title
- product
- version

Rules:
1. Generate one Korean retrieval query in `query_ko`, grounded in `original_chunk_ko` + `extractive_summary_ko`.
2. Preserve technical anchors exactly where needed: annotation, class/interface name, property key, API name, config path, artifact/module name, version string, error code.
3. Keep technical terms in English when that form is retrieval-critical.
4. Use metadata (`title`, `product`, `version`, `target_chunk_ids`) as grounding hints to avoid topic drift.
5. Keep query concise, specific, and practical (developer search/chat style, not textbook prose).
6. Keep answerability aligned to `single|near|far` evidence scope.

Quality targets:
1. Query should look like real Korean developer input.
2. Query must contain enough anchor signal to distinguish target chunk from similar chunks.
3. Avoid broad/generic wording and avoid over-compressed ambiguity.
4. Maintain retrieval usefulness without unnecessary verbosity.

Answerability guidance:
1. `single`: directly answerable from one target chunk with explicit local anchor.
2. `near`: adjacent chunk linkage improves completeness; one chunk alone can be slightly insufficient.
3. `far`: separated in-document evidence linkage required; still answerable and practical.

Query type control:
1. `definition`: practical definition with usage context.
2. `reason`: why/cause/background of behavior or config outcome.
3. `procedure`: setup/apply/fix steps.
4. `comparison`: concrete difference/trade-off between options/configs.
5. `short_user`: short but specific and anchored.
6. `code_mixed`: Korean base with key technical terms in English.
7. `follow_up`: concise continuation tone implying prior context.

Forbidden patterns:
1. Source sentence copy or near-copy.
2. Generic query that fits many unrelated chunks.
3. Yes/no-only, overly broad, or out-of-scope question.
4. Missing or distorted critical technical anchors.
5. Overly long or overly compressed ambiguous query.
6. Style mismatch with `query_type` or `answerability_type`.
7. Any non-JSON text output.

Output contract:
1. Runtime structured output exists, but you must output exactly one JSON object only.
2. No markdown, no code fence, no trailing text.
3. Required fields must exist and be non-empty strings.
4. `query_type` and `answerability_type` must exactly echo input labels.
5. Length guidance:
   - `query_ko` <= 160 chars
   - `style_note` <= 80 chars
6. If possible, first char `{` and last char `}`.

Output schema:
{
  "query_ko": "...",
  "query_type": "...",
  "answerability_type": "...",
  "style_note": "..."
}

Internal self-check (do not output):
- Korean final query, anchor preserved, answerability scope ok, style/type match, single JSON object only.
