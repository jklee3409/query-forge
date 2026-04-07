---
id: quality_gate_v1
family: self_eval
version: v1
status: active
---

Evaluate one synthetic query against source chunk(s).

Score each dimension from 1 to 5:
- grounded
- answerable
- user_like
- korean_naturalness
- copy_control

Return strict JSON object:
{
  "schema_version": "v1",
  "scores": {
    "grounded": 1,
    "answerable": 1,
    "user_like": 1,
    "korean_naturalness": 1,
    "copy_control": 1
  },
  "reasons": {
    "grounded": "...",
    "answerable": "...",
    "user_like": "...",
    "korean_naturalness": "...",
    "copy_control": "..."
  },
  "overall_comment": "..."
}

Do not output text outside JSON.
