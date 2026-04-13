---
id: gen_b_v1
family: query_generation
version: v3
status: active
---

Strategy B (Korean-native baseline):
English original -> Korean translation -> Korean summary -> Korean synthetic query.

Experiment hypothesis:
- Korean-first composition improves user-likeness for Korean developers.
- Retrieval quality is maintained if key technical anchors remain in English and survive summarization.
- Difference from A: prioritize Korean practical tone first, while explicitly guarding against anchor loss.

Rules:
1. `translated_chunk_ko` must be faithful and concise; preserve key entities/terms in English when needed.
2. `summary_ko` must retain retrieval-critical anchors, not abstract them away.
3. `query_ko` must sound like a real Korean developer input (search/chatbot style), not a textbook sentence.
4. Prefer problem-solving, configuration-cause, procedure, and comparison intents.
5. Include concrete Spring anchors when relevant:
   annotation, configuration property, bean lifecycle, auto-configuration, transaction, security, testing, web, data access.
6. Keep question compact but specific enough to distinguish target chunk(s).

Answerability bias:
1. `single`: one-chunk answerability with explicit local anchor.
2. `near`: designed to require combining nearby evidence (adjacent setup/result).
3. `far`: designed to connect separated document evidence while staying answerable.

Query type style control:
1. `definition`: practical usage context included.
2. `reason`: explicit cause/why question.
3. `procedure`: step/order/fix intent.
4. `comparison`: concrete behavior/config difference.
5. `short_user`: short, specific, non-ambiguous.
6. `code_mixed`: Korean sentence with core terms in English.
7. `follow_up`: concise continuation tone from prior context.

Forbidden patterns:
1. Source sentence copy or minor rewording copy.
2. Anchor-less generic high-level questions.
3. Over-broad, yes/no-only, or out-of-scope questions.
4. Overly long or abstract summary/query that weakens retrieval discrimination.

Output contract (strict JSON):
1. Output exactly one JSON object. No markdown, no code fence, no trailing text.
2. Required fields must be present and non-empty.
3. `query_type` and `answerability_type` must be exact echo of input labels.
4. Length guidance:
   - `translated_chunk_ko` <= 900 chars
   - `summary_ko` <= 320 chars
   - `query_ko` <= 160 chars

Output schema:
{
  "translated_chunk_ko": "...",
  "summary_ko": "...",
  "query_ko": "...",
  "query_type": "...",
  "answerability_type": "..."
}
