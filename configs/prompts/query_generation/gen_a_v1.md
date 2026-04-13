---
id: gen_a_v1
family: query_generation
version: v3
status: active
---

Strategy A (anchor-fidelity baseline):
English original -> English extractive summary -> English synthetic query -> Korean translation.

Experiment hypothesis:
- English-first generation preserves source semantics and technical anchors better.
- This strategy should produce high retrieval discrimination when anchor terms are explicit.
- Difference from B/C/D: prioritize source-faithful anchor retention, then Korean naturalization.

Rules:
1. Generate `query_en` first from source-grounded intent, then produce `query_ko`.
2. `query_en` and `query_ko` must be semantic parity: same information need, same constraints, same target evidence.
3. Do not literal-translate awkwardly. `query_ko` must read like a Korean developer query while keeping key technical terms in English.
4. Prefer troubleshooting/configuration/procedure/comparison intent over textbook definition-only wording.
5. Include at least one concrete Spring retrieval anchor when relevant:
   annotation, configuration property, bean lifecycle, auto-configuration, transaction, security, testing, web, data access.
6. Keep both queries concise and one sentence.

Answerability bias:
1. `single`: directly answerable from one chunk with specific local anchor.
2. `near`: needs neighboring chunk linkage (e.g., setup + effect) but still answerable.
3. `far`: needs non-adjacent evidence connection in the same document scope; not unanswerable.

Query type style control:
1. `definition`: include practical context (when/why used), not dictionary style only.
2. `reason`: ask cause/background of behavior or config outcome.
3. `procedure`: ask setup/apply/fix sequence.
4. `comparison`: ask concrete difference or trade-off between two options.
5. `short_user`: short but specific, with anchor.
6. `code_mixed`: Korean base with essential technical terms in English.
7. `follow_up`: natural continuation tone with implicit prior context.

Forbidden patterns:
1. Copying source chunk sentences or near-verbatim phrases.
2. Generic questions that can match many unrelated chunks.
3. Overly broad, yes/no-only, or answer-out-of-scope questions.
4. Long multi-clause questions that reduce retrieval precision.

Output contract (strict JSON):
1. Output exactly one JSON object. No markdown, no code fence, no trailing text.
2. All required fields must exist and be non-empty strings.
3. `query_type` and `answerability_type` must be exact echo of input labels (no normalization/paraphrase).
4. Keep `query_en` and `query_ko` each <= 160 chars.

Output schema:
{
  "query_en": "...",
  "query_ko": "...",
  "query_type": "...",
  "answerability_type": "..."
}
