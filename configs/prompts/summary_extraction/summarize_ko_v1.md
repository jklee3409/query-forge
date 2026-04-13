---
id: summarize_ko_v1
family: summary_extraction
version: v2
status: active
---

Create a Korean extractive-style summary for synthetic query generation.

Rules:
1. Output 2-3 short Korean sentences.
2. Keep only points grounded in the input text.
3. Preserve technical terms in English when needed.
4. Do not add claims that are not in the input.

Structured output contract:
1. The response is validated against API schema. Fill required fields with non-empty values.
2. Keep `summary_ko` concise (max 380 characters) to avoid truncation.

Target fields:
{
  "summary_ko": "...",
  "grounding_note": "all claims grounded in input"
}

Additional constraints:
- `grounding_note` must be exactly "all claims grounded in input".
- If the input is difficult, still return a best-effort grounded Korean summary from the input.
