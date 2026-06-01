# progress.md

## Overview
Report artifact changes for `data/reports/`.

## [2026-06-01] Session Summary (Spring Rewrite Probe C Audit)
- What was done: Added `spring_kr_rewrite_probe_c_9_audit_2026-06-01.json` for structural validation of the C-memory-aligned rewrite probe dataset.
- Key decisions: Stored this as a dataset-generation audit, not a RAG result report; retrieval metrics remain run-specific under `retrieval_*`.
- Issues encountered: Generated audit status is `pass`; DB verification found 9 active rows, zero ASCII-anchor query surfaces, and no missing grounding.
- Next steps: Compare raw-only and selective rewrite retrieval summaries for the probe dataset.

## [2026-06-01] Session Summary (Spring Rewrite Challenge Audit)
- What was done: Reserved `spring_kr_rewrite_challenge_30_audit_2026-06-01.json` for structural validation of the additive Spring KR rewrite challenge dataset.
- Key decisions: The audit records source V6 sample mapping, no-ASCII query validation, and grounding integrity before any RAG interpretation.
- Issues encountered: Generated audit status is `pass`; DB verification found 30 active rows, all Korean query-language, zero ASCII-anchor query surfaces, and no missing grounding.
- Next steps: Store final retrieval/answer result summaries after snapshot-pinned evaluation.

## [2026-05-20] Session Summary (Method-Compressed Eval Audit)
- What was done: Added `spring_method_compressed_eval_80_audit_2026-05-20.json` with per-method source batch IDs, selected counts, answerability distribution, sample previews, and structural validation status.
- Key decisions: Stored this as a dataset-generation audit, not a RAG result report, because retrieval/answer evaluation has not been run for these datasets yet.
- Issues encountered: None in final validation; report status is `pass`.
- Next steps: Add retrieval/answer reports here after snapshot-pinned RAG runs are executed.

## [2026-05-12] Session Summary (Python KR Eval Dataset Audit)
- What was done: Added `python_kr_eval_dataset_80_audit_2026-05-12.json` for the paired Python KR short-user KO/EN 80 datasets.
- Key decisions: Stored only dataset-generation audit metadata, not a full RAG evaluation report, because this change creates the benchmark inputs rather than running F/G experiments.
- Issues encountered: None.
- Next steps: Create retrieval/answer reports under this directory when F/G snapshot-based evaluations are run.

---

## Notes
- Keep this file concise.
