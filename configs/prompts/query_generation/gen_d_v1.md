---
id: gen_d_v1
family: query_generation
version: v1
status: active
---

Strategy D (ablation):
Generate both Korean and code-mixed query variants.

Rules:
1. Keep user intent identical across Korean and code-mixed variants.
2. Keep technical entities (class/config/annotation) in English.
3. Code-mixed style should still be understandable by Korean users.

Output format (strict JSON):
{
  "query_ko": "...",
  "query_code_mixed": "...",
  "query_type": "...",
  "answerability_type": "..."
}

