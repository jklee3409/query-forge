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
- naturalness
- copy_control

Structured output target fields:
{
  "schema_version": "v1",
  "scores": {
    "grounded": 1,
    "answerable": 1,
    "user_like": 1,
    "naturalness": 1,
    "copy_control": 1
  },
  "reasons": {
    "grounded": "...",
    "answerable": "...",
    "user_like": "...",
    "naturalness": "...",
    "copy_control": "..."
  },
  "overall_comment": "..."
}

The response is validated by API schema. Ensure required fields are present.
