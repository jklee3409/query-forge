---
id: gen_d_v1
family: query_generation
version: v3
status: active
---

Strategy D (ablation):
Generate both Korean and code-mixed query variants.

Experiment hypothesis:
- Retrieval differences should come from language style (Korean vs code-mixed), not intent drift.
- D is an ablation axis for language style only.

Rules:
1. `query_ko` and `query_code_mixed` must ask the same thing: same target, same constraints, same required evidence.
2. Keep technical entities (annotation/class/config/property/API) in English in both variants.
3. `query_code_mixed` should be naturally readable Korean developer style, not awkward Konglish.
4. Both variants must keep retrieval anchors explicit and comparable.
5. Keep both queries concise and one sentence.

Answerability bias:
1. `single`: direct one-chunk answerability.
2. `near`: requires nearby chunk linkage.
3. `far`: requires connecting separated evidence while remaining answerable.

Query type style control:
1. `definition`: practical contextual definition.
2. `reason`: causal/why-oriented wording.
3. `procedure`: setup/fix/action sequence intent.
4. `comparison`: concrete difference or trade-off.
5. `short_user`: short but specific, anchored.
6. `code_mixed`: Korean base with key terms English, no forced slang.
7. `follow_up`: concise continuation tone.

Semantic parity checklist:
1. Same action/object/problem in both variants.
2. Same technical anchors and scope.
3. No extra requirement added in only one variant.
4. Style can differ, information need cannot differ.

Forbidden patterns:
1. Source chunk copy or shallow paraphrase copy.
2. Generic/no-anchor question.
3. Yes/no-only broad question.
4. One variant being more specific than the other.
5. Any JSON-external output.

Output contract (strict JSON):
1. Output exactly one JSON object. No markdown, no code fence, no trailing text.
2. Required fields must be present and non-empty.
3. `query_type` and `answerability_type` must be exact echo of input labels.
4. Keep `query_ko` and `query_code_mixed` each <= 170 chars.

Output schema:
{
  "query_ko": "...",
  "query_code_mixed": "...",
  "query_type": "...",
  "answerability_type": "..."
}
