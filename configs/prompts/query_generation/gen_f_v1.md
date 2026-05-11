---
id: gen_f_v1
family: query_generation
version: v1
status: active
---

Strategy hypothesis:
- F is Korean-to-English bridge strategy: start from Korean evidence and end with English retrieval query.
- Retrieval tendency: preserve Korean intent anchors through bilingual parity, then optimize final retrieval query in English.
- Difference from A/B/C/D/E/G: mandatory path is `Korean source -> Korean summary -> Korean query -> English query(final)`.

Inputs:
- original_chunk_ko
- extractive_summary_ko
- glossary_terms_keep_english
- query_type(definition|reason|procedure|comparison|short_user|code_mixed|follow_up)
- answerability_type(single|near|far)
- target_chunk_ids
- related_chunks_ko
- title
- product
- version

Rules:
1. Build `query_ko` first as a natural Korean developer query grounded in `original_chunk_ko` + `extractive_summary_ko`.
2. Build `query_en` second from `query_ko` with strict semantic parity: same intent, same scope, same evidence requirement.
3. Preserve technical anchors exactly where needed across both languages: annotation, class/interface name, property key, API name, config path, artifact/module name, version string, error code.
4. Use metadata (`title`, `product`, `version`, `target_chunk_ids`) as grounding hints to prevent topic drift.
5. Keep both queries concise, retrieval-oriented, and practical (search/chat style), not explanatory prose.
6. Keep answerability aligned to `single|near|far` evidence scope.
7. Treat `related_chunks_ko` as optional grounding evidence only for `near` or `far`; do not invent a relation that is absent from `target_chunk_ids`.
8. Do not generate from overlap/previous-chunk context. Use the primary Korean evidence and the listed related chunks only.

Quality targets:
1. `query_ko` should sound like real Korean developer input.
2. `query_en` should sound like real English developer retrieval query.
3. Both queries must be anchor-rich enough to discriminate target chunk from similar chunks.
4. Korean-English pair must differ by language/style only, not by information need.
5. Avoid too broad, too generic, or too compressed ambiguous wording.

Answerability guidance:
1. `single`: directly answerable from one target chunk with explicit local anchor.
2. `near`: adjacent chunk linkage improves correctness; one chunk alone can be slightly insufficient.
3. `far`: requires linking separated in-document evidence from `related_chunks_ko`; still answerable and practical.

Query type control:
1. `definition`: practical definition with usage context.
2. `reason`: why/cause/background of behavior or configuration outcome.
3. `procedure`: setup/apply/fix steps.
4. `comparison`: concrete difference/trade-off between options/configs.
5. `short_user`: short but specific and anchored.
6. `code_mixed`: Korean base with key technical terms in English while keeping `query_en` fully natural English.
7. `follow_up`: concise continuation tone implying prior context.

Forbidden patterns:
1. Source sentence copy or near-copy (Korean or English).
2. Generic query that can fit many unrelated chunks.
3. Yes/no-only, overly broad, or out-of-scope question.
4. Semantic drift between `query_ko` and `query_en`.
5. Critical anchor omission or mutation.
6. Overly long or overly compressed ambiguous query.
7. Style mismatch with `query_type` or `answerability_type`.
8. Any non-JSON text output.

Output contract:
1. Runtime structured output exists, but you must output exactly one JSON object only.
2. No markdown, no code fence, no trailing text.
3. Required fields must exist and be non-empty strings.
4. `query_type` and `answerability_type` must exactly echo input labels.
5. Length guidance:
   - `query_ko` <= 170 chars
   - `query_en` <= 170 chars
   - `style_note` <= 80 chars
6. If possible, first char `{` and last char `}`.

Output schema:
{
  "query_ko": "...",
  "query_en": "...",
  "query_type": "...",
  "answerability_type": "...",
  "style_note": "..."
}

Internal self-check (do not output):
- path order kept (KO->EN), bilingual parity ok, anchor preserved, answerability scope ok, single JSON object only.
