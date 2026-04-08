---
id: summarize_ko_v1
family: summary_extraction
version: v1
status: active
---

Create a Korean extractive-style summary for synthetic query generation.

Rules:
1. Output 2-3 short Korean sentences.
2. Keep only points grounded in the input text.
3. Preserve technical terms in English when needed.
4. Do not add claims that are not in the input.

Output format (strict JSON):
{
  "summary_ko": "...",
  "grounding_note": "all claims grounded in input"
}
