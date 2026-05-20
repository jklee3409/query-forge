# progress.md

## Overview
Evaluation dataset artifact changes for `data/eval/`.

## [2026-05-20] Session Summary (Spring Method-Compressed Stress 80)
- What was done: Added five method-specific compressed Spring stress eval files: `spring_method_a_compressed_eval_80_ko.jsonl`, `spring_method_b_compressed_eval_80_ko.jsonl`, `spring_method_c_compressed_eval_80_ko.jsonl`, `spring_method_d_compressed_eval_80_ko.jsonl`, and `spring_method_e_compressed_eval_80_en.jsonl`.
- Key decisions: Each file has 80 retrieval-aware items derived from accepted synthetic queries in the corresponding current DB gating batch. Queries are short compressed anchors, while expected doc/chunk IDs and answer key points remain grounded to corpus chunks.
- Issues encountered: None in final validation; all files contain 80 rows.
- Next steps: Use the dataset keys with explicit source gating snapshots for controlled RAG tests.

## [2026-05-12] Session Summary (Python KR Short-User 80 KO/EN)
- What was done: Added paired Python Korean-document evaluation datasets, `python_kr_short_user_test_80_ko.jsonl` and `python_kr_short_user_test_80_en.jsonl`, with 80 short-user queries each.
- Key decisions: Used the same `docs-python-org-ko-3-14` corpus chunks for paired KO/EN samples, set target methods `G` and `F`, and kept retrieval-aware fields aligned with existing short-user datasets.
- Issues encountered: Terminal output may render Korean as mojibake in PowerShell, but the JSONL files are UTF-8 and Python validation confirms correct Unicode content.
- Next steps: Use dataset IDs `dfbadf26-0ab6-4b95-890e-5196dddc62cc` and `0d29df79-3920-40b2-b7ff-897eac5544fa` for F/G snapshot-controlled RAG evaluation.

---

## Notes
- Keep this file concise.
