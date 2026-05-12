# progress.md

## Overview
Evaluation dataset artifact changes for `data/eval/`.

## [2026-05-12] Session Summary (Python KR Short-User 80 KO/EN)
- What was done: Added paired Python Korean-document evaluation datasets, `python_kr_short_user_test_80_ko.jsonl` and `python_kr_short_user_test_80_en.jsonl`, with 80 short-user queries each.
- Key decisions: Used the same `docs-python-org-ko-3-14` corpus chunks for paired KO/EN samples, set target methods `G` and `F`, and kept retrieval-aware fields aligned with existing short-user datasets.
- Issues encountered: Terminal output may render Korean as mojibake in PowerShell, but the JSONL files are UTF-8 and Python validation confirms correct Unicode content.
- Next steps: Use dataset IDs `dfbadf26-0ab6-4b95-890e-5196dddc62cc` and `0d29df79-3920-40b2-b7ff-897eac5544fa` for F/G snapshot-controlled RAG evaluation.

---

## Notes
- Keep this file concise.
