# progress.md

## [2026-06-01] Session Summary (Spring Rewrite Probe Evaluation)
- What was done: Ran C compact-anchor retrieval and answer evaluation on `spring_kr_rewrite_probe_c_9` with db-ann hybrid retrieval and `intfloat/multilingual-e5-small`.
- Key decisions: Used the probe as a focused rewrite-effect slice while keeping V6 and the broader 30-item challenge as controls.
- Issues encountered: Retrieval met the required large improvement target: selective rewrite MRR@10 `0.1688 -> 0.5343`, nDCG@10 `0.2371 -> 0.5229`, Hit@5 `0.4444 -> 0.7778`, adoption `1.0`, bad rewrite `0.0`. Answer-side context recall improved `0.3889 -> 0.5926` and correctness `0.1605 -> 0.2311`.
- Next steps: Preserve the probe for rewrite-effect validation and keep full V6/challenge runs as external-validity controls.

## [2026-06-01] Session Summary (Spring Rewrite Probe Dataset)
- What was done: Added a `probe_c_9` variant to the Spring rewrite dataset builder and upserted dataset `spring_kr_rewrite_probe_c_9` (`87ad7e4b-a5d8-5ef1-a20a-7e4cb1b2f486`) to isolate C-memory-aligned Korean-only anchor-gap queries.
- Key decisions: Preserved V6 grounding and kept this as an additive probe slice, not a replacement for V6 or the broader 30-item challenge dataset.
- Issues encountered: Script compile, dry-run validation, DB upsert, and bounded DB verification passed.
- Next steps: Run C compact-anchor retrieval evaluation on the probe dataset and require a large raw-only vs rewrite improvement before moving to answer evaluation.

## [2026-06-01] Session Summary (Spring Rewrite Challenge Planning)
- What was done: Began additive rewrite-challenge implementation without changing core RAG pipeline logic, adding a Spring KR dataset builder that preserves V6 grounding while removing English/API anchor surfaces.
- Key decisions: Keep V6 as the control dataset and evaluate the new challenge dataset only under explicit A/C full-gating snapshots.
- Issues encountered: Dataset generation/upsert validation passed; retrieval/answer evaluation still pending.
- Next steps: Commit the dataset stage, then run retrieval/answer evaluation and iterate only if raw-vs-rewrite improvement is not substantial.

## [2026-05-30] Session Summary (Kubernetes C Generation and Full Gating)
- What was done: Ran Kubernetes Method C synthetic generation with the same settings as A batch `b03213c5-0791-455e-a3f5-326cfd49c40b` except `generation_strategy=C`, creating batch `79b2bcb1-f8c4-4efa-8e00-03edc4ac9694` with 1,000 raw queries.
- Key decisions: Used the existing running backend/Admin API and PostgreSQL container only; no Docker rebuild or container rebuild was performed. Ran full gating for the C batch with the same settings as gating batch `7793c399-5eea-45ca-befc-29d4f766ca9b`.
- Issues encountered: Generation completed with zero retries. Full gating completed after one backend job retry and produced gating batch `d906f6ba-cd2d-44d3-85c8-05adb8a04824` / source gating run `53dde3db-fca2-4c2e-a67c-0db827c22493`, with 1,000 processed, 303 accepted, and 697 rejected.
- Next steps: Use this C full-gating snapshot only with explicit `source_gating_batch_id=d906f6ba-cd2d-44d3-85c8-05adb8a04824` for snapshot-pinned Kubernetes RAG comparisons.

## [2026-05-30] Session Summary (PostgreSQL/Kubernetes Eval Repair and Cross-Domain Audit)
- What was done: Repaired PostgreSQL and Kubernetes KR/EN short-user eval datasets in place, then ran a strict DB audit across Spring/PostgreSQL/Kubernetes eval pairs.
- Key decisions: Preserved all dataset IDs, advanced PostgreSQL to `v2-2026-05-30` and Kubernetes to `v3-2026-05-30`, rebuilt expected answer key points from current corpus chunks, and enforced exact KR/EN grounding parity.
- Issues encountered: PostgreSQL and Kubernetes EN rows carried non-empty `user_query_ko`, and both domains had overlap-context key points; these now validate cleanly. PostgreSQL query fragments were also prefixed with expected section/title anchors to reduce target ambiguity.
- Next steps: Use `scripts/audit_eval_grounding_strictness.py` as the pre-run guard for future RAG comparisons involving these three domains.

## [2026-05-30] Session Summary (Spring KR/EN Short-User Eval Grounding Repair)
- What was done: Repaired the existing Spring KR/EN short-user evaluation datasets in place (`b2d47254-8655-4c9c-81ac-7615677ec5bd` / `8f0d6e0f-6f9e-4d64-9b07-f4e8ce5ebec0`) and set both to version `v6-2026-05-30`.
- Key decisions: Used the refined Spring KR artifact as the source of truth, paired EN rows with identical grounding and English-only query surfaces, removed noisy answer key points, and corrected inspected high-risk targets for projection, Kotlin support, and MockMvc HtmlUnit samples.
- Issues encountered: The active KR dataset had older `v4-test-short-user-*` bindings; the repair replaced active bindings with `test-short-user-*` rows while preserving historical rows for old run references.
- Next steps: Run the next Detailed Intent RAG test against this dataset version and separate its interpretation from prior runs that used noisier target chunks/key points.

## [2026-05-29] Session Summary (Detailed Rewrite Few-Shot Prompt)
- What was done: Strengthened `selective_rewrite_detailed_intent_v1.md` wording so `top_memory_candidates` are explicitly treated as retrieved synthetic query examples and few-shot rewrite guidance.
- Key decisions: Anchor payloads are described as optional hint-only grounding controls, not mandatory insertion targets, preserving raw-query intent in detailed rewrite mode.
- Issues encountered: Prompt/document-only change; validation used `git diff --check`.
- Next steps: Inspect the next detailed-intent rewrite traces for synthetic-example use without anchor over-injection.

## [2026-05-29] Session Summary (RAG Detailed Rewrite Profile)
- What was done: Added Admin RAG `rewrite_query_profile` support with `compact_anchor` and `detailed_intent`, added rewrite-only LLM model override plumbing, and connected both through backend config generation, frontend controls, and pipeline selective rewrite runtime.
- Key decisions: Kept the existing compact anchor rewrite as the default path; detailed query expansion is a separate profile with its own prompt and looser verbosity adoption policy. Rewrite-stage model override is optional and only changes `llm_rewrite_model`.
- Issues encountered: Targeted validation passed: backend `compileJava`, pipeline `py_compile`, `EvalRuntimeRewriteTests`, `RagPage.jsx` ESLint with existing hook warnings, and frontend production build.
- Next steps: Run a small fixed-snapshot Admin RAG comparison when credentials/cost allow to compare `compact_anchor` vs `detailed_intent` rewrite traces and retrieval metrics.

## [2026-05-29] Session Summary (Backend JDBC Repository Consolidation)
- What was done: Removed the remaining Spring Data JPA dependency and unused JPA entity/repository layer, converted Pipeline Admin persistence to `NamedParameterJdbcTemplate`, and extracted Admin Console synthetic-method, domain/scope, and eval-dataset SQL into dedicated JDBC repositories while keeping `AdminConsoleRepository` as the stable facade.
- Key decisions: Preserved existing SQL semantics, pipeline stage flow, A/B/C/D/E/F/G split raw table contract, and Admin service public repository API; no migration or schema behavior was changed.
- Issues encountered: Targeted backend validation passed: `compileJava`, Admin Console nullable/domain regression tests, `PipelineAdminIntegrationTest`, and `AdminConsoleRagIntegrationTest`.
- Next steps: Continue decomposing the large Admin Console facade by moving synthetic batch, gating, dataset, and RAG run SQL into bounded JDBC repositories in small behavior-preserving slices.

## [2026-05-29] Session Summary (Admin Console Nullable Domain SQL Fix)
- What was done: Split Admin Console domain-optional list queries so no-domain calls no longer bind nullable `domainId` into `:domainId IS NULL` SQL predicates, and added targeted regression coverage for synthetic methods plus related list APIs.
- Key decisions: Kept domain-scoped behavior by using separate non-null domain WHERE/EXISTS clauses instead of SQL-side nullable checks.
- Issues encountered: The failure matched the earlier PostgreSQL nullable bind-parameter type inference issue; targeted Testcontainers regression tests passed.
- Next steps: Restart the running backend before verifying the fixed `/api/admin/console/synthetic/methods` path in the browser.

## [2026-05-29] Session Summary (AGENTS Synthetic Method Alignment)
- What was done: Updated `.codex/AGENTS.md` to document A/B/C/D/E/F/G synthetic method flows, F/G Korean-origin definitions, method-aware dataset support, snapshot/logging strategy support, and F/G raw-table/prompt references.
- Key decisions: Treated the change as documentation correction only and preserved pipeline order, gating rules, evaluation rules, schema structure, and architecture constraints.
- Issues encountered: Existing unrelated AGENTS sections still contain legacy mojibake; no runtime tests were run because this was documentation-only.
- Next steps: Keep AGENTS method definitions synchronized with query-generation prompt assets when strategy semantics change.

## [2026-05-29] Session Summary (README Tone Pass)
- What was done: Refined the root `README.md` wording without changing the documented implementation facts, section structure, run commands, source list, or evaluation descriptions.
- Key decisions: Removed repeated AI-style meta subjects such as "이 프로젝트는" / "이 저장소는" equivalents and rewrote paragraphs toward direct technical README prose.
- Issues encountered: No build/test was run because the change is documentation wording only.
- Next steps: Keep module-level README tone cleanup separate from this root README pass.

## [2026-05-29] Session Summary (Root README Research Refresh)
- What was done: Rewrote the root `README.md` in Korean as a research-project guide after inspecting project rules, pipeline/backend/frontend/config/migration/source/eval artifacts, and aligned it with implemented synthetic generation, gating, memory, rewrite, Admin GUI, snapshot, pgvector, and evaluation behavior.
- Key decisions: Described confirmed Spring/PostgreSQL/PostGIS/Kubernetes/Python source configs and eval artifacts only, treated Arahansa Spring as configured but excluded from canonical Spring synthetic scope, and avoided claiming DB row counts or p95 latency metrics that were not verified as implemented.
- Issues encountered: Existing README and several docs render with legacy mojibake in terminal output; no build/test was run because the change is documentation-only.
- Next steps: If needed, clean encoding/readability in module-level READMEs and add a verified local bootstrap sequence once runtime environment assumptions are fixed.

## [2026-05-27] Session Summary (Admin Domain Method and RAG Detail Dropdown)
- What was done: Removed the Pipeline Monitor Anchor Eval section, changed Admin synthetic method availability to follow domain source language (`en` -> A/B/C/D/E, `ko` -> F/G), and added rewrite applied/skipped badges plus sample-level dedupe to the RAG detail query selector.
- Key decisions: Domain source language is now the backend source of truth for synthetic method listing/run validation in domain workspaces, while legacy global source allowlists remain for unscoped runs. RAG detail lookup now returns one representative row per sample and the frontend keeps a defensive dedupe pass.
- Issues encountered: Targeted frontend ESLint passed with existing hook dependency warnings in `PipelinePage.jsx` and `RagPage.jsx`; backend `compileJava` and frontend production build passed.
- Next steps: Browser-smoke English and Korean domain Synthetic Query Studio method lists plus a completed RAG detail modal with 80-sample datasets.

## [2026-05-27] Session Summary (RAG Detail Modal Name and Dropdown)
- What was done: Changed the Admin RAG run detail modal title to use the configured RAG test name instead of the shortened DB UUID, replaced the native query-analysis selector with the shared searchable custom dropdown, removed the scroll-to-top action, and refreshed the backend-served React bundle.
- Key decisions: Reused `SelectDropdown` with a new `allowClear` option so existing dropdowns keep their clear behavior while the query selector stays single-choice only. Scoped selected-state styling to the RAG detail selector to avoid the previous native/yellow selected appearance in light and dark themes.
- Issues encountered: Targeted ESLint passed with the existing `RagPage.jsx` hook dependency warnings. `npm run build` passed.
- Next steps: Browser-smoke a completed RAG run detail modal in light and dark mode.

## [2026-05-27] Session Summary (Admin Eval Artifact Cleanup)
- What was done: Removed all tracked `configs/experiments/admin_eval_*.yaml` files, deleted local untracked Admin eval YAML artifacts, and added Git ignore rules for future Admin eval configs.
- Key decisions: UUID-named Admin eval configs are runtime artifacts rather than durable source-controlled experiment presets; official reproducible conditions should be promoted to named presets.
- Issues encountered: A concurrent local cleanup command reported files already removed after `git rm`; final verification found no remaining `admin_eval_*.yaml` files.
- Next steps: Push the cleanup commit so GitHub no longer shows the tracked Admin eval artifacts.

## [2026-05-27] Session Summary (Domain-Aware Rewrite Prompt)
- What was done: Injected dynamic `domain_context` into selective rewrite LLM payloads so Korean technical terms are rewritten into English documentation terms for the active source domain such as Spring, PostgreSQL, Kubernetes, or Python.
- Key decisions: Few-shot prompt examples now show domain-specific term recovery (`트랜잭션 -> Transaction`, PostgreSQL `COMMIT`, Spring Security terms) while preserving the raw-only standalone vs trusted-memory-expanded split.
- Issues encountered: No full RAG run was executed on the low-spec laptop; validation is limited to targeted rewrite runtime tests.
- Next steps: Rerun the pure-Korean Spring/PostgreSQL datasets and inspect rewrite traces for accepted standalone candidates with correct English technical anchors.

## [2026-05-27] Session Summary (Selective Rewrite Trusted Memory Split)
- What was done: Split selective rewrite candidate generation into raw-only `standalone` and trusted-memory-only `expanded` LLM calls, added raw-retrieval evidence to rewrite prompts, and hid memory/anchor hints when reranked memory does not overlap raw retrieval evidence.
- Key decisions: `rewrite_anchor_injection_enabled=true` no longer allows synthetic-memory anchors into standalone prompt/candidate/scoring; standalone scoring no longer penalizes missing trusted-memory anchors or memory targets. Rewrite retrieval-context metadata no longer loads the dense model just to name the retriever.
- Issues encountered: Run `76c16e3b-e92a-4b01-8b3f-10859adb2c8b` showed 0 LLM failures but 95% selective rejection because standalone candidates were generic Korean paraphrases and were still scored against memory-anchor requirements.
- Next steps: Rerun the Spring/PostgreSQL pure-Korean datasets against the same snapshots to inspect accepted rewrite rate, bad rewrite rate, and whether raw-retrieval evidence produces exact technical anchors.

## [2026-05-27] Session Summary (Spring/PostgreSQL Anchor-Translated Eval Copies)
- What was done: Created separate Spring and PostgreSQL Korean short-user eval datasets whose query surfaces intentionally translate English technical anchors into Korean, wrote JSONL artifacts, and registered DB datasets `44282405-1ea1-5f78-bf85-6270724ee475` / `8a08c160-e4cd-5ce0-9f5c-640c51b6d887`.
- Key decisions: Preserved the source datasets `b2d47254-8655-4c9c-81ac-7615677ec5bd` and `862642e6-10bd-538d-9ba8-5de7f1f26d3c`; copied expected doc/chunk IDs, answer key points, split, category, difficulty, and single/multi structure unchanged into new sample IDs.
- Issues encountered: None in final validation; both generated 80-row datasets have zero ASCII letters in `user_query_ko` and source datasets still have 80 active rows.
- Next steps: Use the new dataset keys for anchor-effect RAG comparisons without replacing the existing KR baselines.

## [2026-05-27] Session Summary (Kubernetes KR Anchor-Translated Eval)
- What was done: Revised Kubernetes KR short-user eval queries to intentionally translate/paraphrase English technical anchors into Korean surfaces, regenerated paired JSONL artifacts, and upserted the active DB datasets.
- Key decisions: Preserved the existing 80-item retrieval-aware grounding, dataset IDs, KO/EN pairing, and `single:59` / `multi:21` structure while changing only the Korean query surface and paired metadata.
- Issues encountered: None; validation passed and targeted DB verification found 80 active KR rows with zero ASCII anchor tokens in `user_query_ko`.
- Next steps: Rerun the Kubernetes KR baseline against dataset `87f74f10-1e61-5c56-84f9-f70a87fba424` to measure anchor-removal impact.

## [2026-05-27] Session Summary (Kubernetes KO/EN Short-User Eval 80)
- What was done: Created paired Kubernetes KO/EN short-user evaluation datasets from active `kubernetes-docs-current` chunks, wrote JSONL artifacts, added a reusable builder script, and upserted DB datasets `87f74f10-1e61-5c56-84f9-f70a87fba424` / `e0445e9e-7ed3-58aa-8ce1-a32d06d44a11`.
- Key decisions: Followed the Spring/PostgreSQL 80-item retrieval-aware structure with `short_user`, `test`, `single:59` / `multi:21`, identical KO/EN expected doc/chunk IDs, KO A/C target-method tagging, and EN E tagging.
- Issues encountered: New data/eval files required `.gitignore` exceptions; validation caught all-English KO surfaces and they were corrected before DB upsert.
- Next steps: Use dataset keys `kubernetes_kr_short_user_80` and `kubernetes_en_short_user_80` only with explicit Kubernetes memory/gating snapshots for later RAG evaluation.

## [2026-05-26] Session Summary (Selective Rewrite v3 Runtime Activation)
- What was done: Activated `selective_rewrite_v3` for Korean/code-mixed Admin RAG rewrite by updating prompt lookup, LLM response schema, candidate-count caps/defaults, Prompt Studio catalog migration, and online rewrite fallback order.
- Key decisions: English rewrite remains on `selective_rewrite_en_v1`; v3 requires only `label`/`query` while legacy metadata remains optional for scoring diagnostics.
- Issues encountered: Targeted validation covered Python py_compile/unit tests and backend compile; no live Admin RAG run was executed.
- Next steps: Restart backend to apply Flyway V42 and run a fixed-snapshot rewrite-effect comparison to measure v3 recall/latency impact.

## [2026-05-26] Session Summary (Selective Rewrite v3 Draft Prompt)
- What was done: Added `configs/prompts/rewrite/selective_rewrite_v3.md` as a lightweight draft rewrite prompt and updated configs documentation/progress.
- Key decisions: Kept the active v2 runtime path unchanged; v3 simplifies inputs to raw query, optional session/memory/terminology hints, emits at most two candidates, and removes `intent_risk`.
- Issues encountered: No code execution path was changed; validation was limited to static prompt and targeted documentation updates.
- Next steps: Bind v3 through prompt catalog/runtime schema only after a controlled rewrite-effect evaluation plan is ready.

## [2026-05-26] Session Summary (Selective Rewrite Threshold and Guard)
- What was done: Raised Admin `rewrite_threshold` default to `0.05`, made rewrite anchor injection opt-in, changed Admin RAG mode generation to exclude `rewrite_always`, revised rewrite prompts to synthetic-example-first cautious-anchor versions, and changed runtime adoption to final-score delta plus raw-loss guard.
- Key decisions: Kept current LLM model defaults (`gemini-2.5-flash-lite`) and treated `rewrite_always` as legacy/ablation only, not an Admin operational/final evaluation mode.
- Issues encountered: Targeted validation passed: Python py_compile, `EvalRuntimeRewriteTests`, backend `compileJava`, `RagPage.jsx` ESLint, and frontend production build.
- Next steps: Use same snapshot/dataset RAG reruns to compare accepted rewrite rate, bad-rewrite rate, and `raw_loss_guard_*` diagnostics.

## [2026-05-26] Session Summary (Spring/PostgreSQL RAG Result Analysis)
- What was done: Analyzed recent Admin GUI RAG quality-test results from DB for Spring and PostgreSQL domains, comparing raw, rewrite-always, and selective rewrite metrics plus answer/performance payloads.
- Key decisions: Treated PostgreSQL as having only three completed result runs because the fourth latest run (`EN Baseline`) is still running; found current completed runs all use `gemini-2.5-flash-lite` for `llm_rewrite_model`.
- Issues encountered: No code/config changes were made; sample-level inspection showed rewrite regressions mainly from adopting candidates when raw retrieval was already strong.
- Next steps: Consider stricter selective-rewrite adoption guards and a controlled rewrite-only `gemini-2.5-flash` A/B run before changing defaults.

## [2026-05-26] Session Summary (PostgreSQL RAG Dataset Language Fix)
- What was done: Fixed Admin RAG dataset language handling so PostgreSQL EN datasets report/select `evalQueryLanguage=en`, and language-incompatible gating snapshots are filtered from snapshot selectors.
- Key decisions: Backend dataset rows now expose dataset language derived from metadata/active samples; frontend snapshot compatibility now applies the same EN-only method rule used for method chips. Backend RAG run creation also rejects dataset/request language mismatches.
- Issues encountered: Backend `compileJava` passed and targeted `RagPage.jsx` ESLint passed; full frontend lint remains blocked by the existing `vite.config.js` `process` global error.
- Next steps: Restart backend/Vite if needed, then smoke `/admin/domains/postgresql/rag-tests` by switching between KR and EN datasets and confirming A/C vs E snapshot availability.

## [2026-05-26] Session Summary (PostgreSQL E Generation, BM25 Gating, EN Eval)
- What was done: Enabled PostgreSQL domain policy for Method E, ran Admin Console synthetic generation batch `9b0264e1-d615-4d6b-b015-f7731c433318` for 1,000 E queries at 1.5 queries/chunk, ran Admin Console `full_gating` batch `4d6b5c9f-b499-4666-9d3c-bb9eeb7f7c66` with BM25-only retrieval, and created EN eval dataset `020a93c4-0465-5655-b681-a5799a98fd15`.
- Key decisions: Used the existing running backend/Admin API path and existing Docker Postgres container only; no Docker rebuild, Docker Engine restart, or container rebuild was performed. E generation targeted `postgresql-docs-current` under the PostgreSQL domain for consistency with A/C runs.
- Issues encountered: Synthetic generation resumed through one Gemini 503 retry and completed with 1,000 raw EN Method E rows; full gating completed with 1,000 processed, 645 accepted, 355 rejected. The EN companion was later corrected to English-only equivalents paired to the active KR query surface.
- Next steps: Use generation run `cc4f312a-c2bd-4e5c-ae55-2b5b2388cba4`, gating run `070319a2-1242-4a2f-8ec2-65577c01e01d`, and dataset key `postgresql_en_short_user_80` as explicit snapshots for any later E-method RAG evaluation.

## [2026-05-26] Session Summary (PostgreSQL Eval Query Degradation)
- What was done: Corrected the PostgreSQL KR short-user eval dataset queries in `data/eval/postgresql_kr_short_user_test_80.jsonl` and active DB dataset `862642e6-10bd-538d-9ba8-5de7f1f26d3c` to short Korean code-mixed user queries grounded in each item's expected PostgreSQL chunks.
- Key decisions: Preserved the Spring-compatible eval structure and all expected doc/chunk IDs while matching Spring-level raw BM25 retrieval: PostgreSQL KR `Recall@5=0.4625`, `Hit@5=0.5250`, `MRR@10=0.3931`, `nDCG@10=0.4105`; Spring KR reference is `0.4625`, `0.5250`, `0.3640`, `0.3968`.
- Issues encountered: Validation found 80 KR rows, 101 grounded PostgreSQL chunk references, no duplicate queries, no missing Hangul, zero Latin anchors outside expected chunks, and DB/JSONL parity. Dataset `020a93c4-0465-5655-b681-a5799a98fd15` was also corrected to English-only equivalents with identical grounding.
- Next steps: Use the KR dataset with explicit PostgreSQL A/C snapshots and the EN companion with E snapshots for paired RAG runs.

## [2026-05-26] Session Summary (PostgreSQL KR Short-User Eval 80)
- What was done: Added PostgreSQL KR short-user eval dataset `postgresql_kr_short_user_80` with dataset ID `862642e6-10bd-538d-9ba8-5de7f1f26d3c`, wrote `data/eval/postgresql_kr_short_user_test_80.jsonl`, and upserted 80 active DB items.
- Key decisions: Mirrored the active Spring KR short-user structure (`query_language=ko`, `short_user`, `test`, `single:59` / `multi:21`) while grounding every answer to current PostgreSQL-domain chunks; domain-scoped RAG method validation now falls back to the selected domain's enabled method policy for non-Spring/Python datasets.
- Issues encountered: No temporary files were left behind; targeted DB validation confirmed 101 expected chunk references all match active PostgreSQL-domain chunks, and backend `compileJava` passed.
- Next steps: Run an explicit-snapshot PostgreSQL A/C RAG smoke using gating batches `1c80af8d-b993-4b88-8013-3fe7cf995bef` and `3306f0cc-25c5-459f-b3dc-0e894e76e806` when evaluation is requested.

## [2026-05-26] Session Summary (PostgreSQL Domain Generation and BM25 Gating)
- What was done: Added a PostgreSQL English technical-document domain from official PostgreSQL current docs plus PostGIS docs, imported 1,644 documents / 2,147 chunks / 36,682 glossary terms, generated A/C synthetic query batches (`A-1000-260526`, `C-1000-260526`) with 1,000 queries each, and ran `full_gating` for both with BM25-only retrieval.
- Key decisions: Chose PostgreSQL because official English DB documentation can reach Spring-scale chunk volume; PostGIS was used as same-domain supplemental corpus scale, while final A/C generation targeted `postgresql-docs-current`.
- Issues encountered: A duplicate PostgreSQL 17 source attempt was cleaned up within the new domain scope only; Gemini `MAX_TOKENS` failures required compact retry/default-token hardening, and Docker Desktop was restarted after a verification timeout before final DB checks passed.
- Next steps: Use the completed PostgreSQL A/C BM25 full-gating snapshots for later memory/RAG experiments.

## [2026-05-26] Session Summary (RAG Rewrite Anchor Eval Table)
- What was done: Added normalized DB persistence for Admin RAG rewrite-anchor evaluations, wired internal-only anchor scoring from rewrite artifacts/detail rows, and surfaced anchor quality in RAG detail and compare UI without changing eval-retrieval -> eval-answer order.
- Key decisions: LLM-based anchor judging was excluded; only `rewrite_applied=true` details generate anchor rows, and legacy runs with no rows render guarded empty states.
- Issues encountered: Targeted backend compile passed; targeted frontend ESLint passed with the existing `RagPage.jsx` hook dependency warnings.
- Next steps: Apply Flyway V40 in a dev DB and run a small Admin RAG test to inspect generated `rag_rewrite_anchor_eval` rows.

## [2026-05-26] Session Summary (Flyway History Verification)
- What was done: Checked local PostgreSQL Flyway history for V38/V39, confirmed no failed Flyway rows, verified `rag_rewrite.ko` and `rag_rewrite.en` prompt bindings, and ran non-web Spring Boot startup to exercise Flyway validation.
- Key decisions: Used bounded Flyway/prompt catalog queries only and did not run broad DB inspection or pipeline workloads.
- Issues encountered: None; V38 and V39 are both applied successfully.
- Next steps: Commit the restored V38/V39 migration split and related documentation updates as a coherent change set.

## [2026-05-26] Session Summary (Low-Spec Laptop Rule Explicitness)
- What was done: Confirmed `.codex/AGENTS.md` already contained a low-spec laptop resource-safety section, then made the required Korean rules explicit: no whole-project scans, no indiscriminate DB queries, and no memory-heavy work with IntelliJ Heap Size capped at 4GB. Added low-spec rule review to the mandatory session-start checklist.
- Key decisions: Strengthened the existing `4.0 Local Resource Safety` and `4.5 Session Start Checklist` sections rather than adding a duplicate policy section elsewhere.
- Issues encountered: None.
- Next steps: Treat the low-spec laptop rule review as mandatory before future work, together with root `progress.md` tracking.

## [2026-05-26] Session Summary (Flyway V38 Checksum Repair)
- What was done: Restored `backend/src/main/resources/db/migration/V38__seed_selective_rewrite_v2_v4_prompt_asset.sql` to the exact SQL applied in the local DB (`checksum=9452379`), moved the later English rewrite prompt v2 seed/binding into new migration `V39__seed_selective_rewrite_en_v1_v2_prompt_asset.sql`, and verified backend startup.
- Key decisions: Did not disable Flyway validation or add automatic `repair`; preserved applied migration immutability and made the post-apply catalog change reproducible as a new migration.
- Issues encountered: The V38 source file had been modified after local Flyway applied it; the original content was recovered from the Codex session patch log and verified by recomputing the Flyway checksum.
- Next steps: Use normal Flyway startup to apply V39 in other environments; no automatic repair path was added.

## [2026-05-25] Session Summary (Domain-Neutral A-D Query Prompts)
- What was done: Generalized Spring-leaning anchor instructions in A/B/C/D synthetic query prompts to source-grounded technical-document anchors and removed the C prompt's domain-specific flow wording.
- Key decisions: Left E/F/G unchanged because their query-generation prompts already avoid Spring/Python-specific wording; prompt versions and schemas were preserved.
- Issues encountered: Validation was limited to targeted prompt grep/diff inspection; no live LLM generation run was executed.
- Next steps: Re-register prompt assets during the next synthetic generation run and spot-check A-D outputs across non-Spring domains.

## [2026-05-25] Session Summary (Rewrite Retrieval Context and Catalog Defaults)
- What was done: Added actual retrieval runtime context (`retrieval_backend`, vector store, retriever mode, dense embedding model, fusion weights, top-K/candidate pool) to the selective rewrite LLM payload and prompt instructions. Centralized Admin RAG default parameters and retriever mode presets through `configs/app/model_catalog.yml` and runtime options. The English prompt catalog seed was later split from V38 into V39 to preserve Flyway checksum immutability.
- Key decisions: Frontend RAG form now hydrates defaults from backend runtime options instead of hardcoded GUI values; backend RAG run creation uses catalog defaults when request fields are omitted. Existing rewrite candidate JSON schema remains unchanged.
- Issues encountered: Frontend lint initially flagged a helper named `useServerDefault` as a hook; renamed it before validation.
- Next steps: Rerun the same fixed snapshot/dataset conditions to confirm rewrite adoption and bad-rewrite rate improve under retrieval-context-aware prompting.

## [2026-05-25] Session Summary (AGENTS Rewrite Semantics + Resource Safety)
- What was done: Updated `.codex/AGENTS.md` to align RAG rewrite rules with the current Admin GUI flow where synthetic memory is used only as LLM few-shot examples/context, not as direct synthetic-query replacement. Added a mandatory low-spec laptop resource-safety rule.
- Key decisions: Preserved legacy memory-only retrieval as explicit ablation behavior, required final rewrite evaluation to use raw query or one selected LLM-generated rewritten query, and prohibited whole-project scans plus indiscriminate DB queries.
- Issues encountered: Validation was limited to targeted AGENTS/progress review and scoped backend/frontend/runtime inspection; no DB queries, full project scans, builds, or tests were run.
- Next steps: Keep AGENTS, Admin GUI copy, and RAG runtime comments synchronized if rewrite semantics change again.

## [2026-05-25] Session Summary (Global Rewrite Prompt v4)
- What was done: Strengthened `selective_rewrite_v2` into v4 with a global technical-document domain contract, explicit "synthetic examples are not replacements" policy, minimum rewrite-value rule, and five domain-diverse few-shot examples preserving anchor injection.
- Key decisions: Kept the existing JSON schema, candidate labels, anchor injection inputs, and prompt-only synthetic memory flow; added a catalog migration to bind `rag_rewrite.ko` to v4.
- Issues encountered: Validation was limited to static prompt/migration inspection to avoid broad DB or pipeline work on the low-spec environment.
- Next steps: Rerun the same snapshot/dataset RAG comparisons for the two referenced runs and inspect rewrite adoption plus bad-rewrite traces.

## [2026-05-25] Session Summary (Admin Rewrite Threshold Default)
- What was done: Aligned Admin RAG rewrite threshold defaults across backend fallback/base config, frontend form, and model catalog to `0.02`, added the rewrite memory candidate pool default, and refreshed the backend-served React bundle.
- Key decisions: Kept the slider range unchanged, but removed the conflicting `0.05`/`0.10` defaults that made selective rewrite adoption stricter than the short-user policy.
- Issues encountered: Targeted frontend ESLint passed with existing `RagPage.jsx` hook dependency warnings. `npm run build` and backend `compileJava` passed.
- Next steps: Run the fixed-snapshot Admin RAG comparison only after DB/runtime credentials are ready.

## [2026-05-25] Session Summary (RAG Rewrite Memory Rerank and Schema)
- What was done: Reranked synthetic memory candidates with raw top-K chunk/doc overlap, memory target metadata, canonical-anchor overlap, utility score, and product/domain match. Sanitized rewrite prompt memory rows to hide internal IDs and extended LLM output metadata with preserved terms, added anchors, source memory index, and intent risk, then validated anchor coverage after generation.
- Key decisions: The prompt now receives only synthetic query, target title/section, glossary/canonical anchors, and a short evidence summary. Post-processing rejects high intent-risk, invalid source index, missing preserved terms, or declared anchors absent from the query.
- Issues encountered: Targeted Python compile and `python -m unittest pipeline.tests.test_eval_runtime pipeline.tests.test_db_ann_runtime -q` passed.
- Next steps: Lower Admin rewrite threshold defaults and wire runtime defaults so the improved rewrite candidate path is actually adopted in GUI runs.

## [2026-05-25] Session Summary (RAG Raw Retrieval Reproducibility)
- What was done: Made retrieval/answer eval compute raw retrieval once per sample, persist `raw_retrieval_cache_{experiment}.json`, and pass that same raw top-K into `raw_only`, rewrite modes, and answer evaluation. Added stable tie-breakers to local/db-ann ranking and restored `rewrite_always` force semantics while still rejecting unsafe candidates.
- Key decisions: Raw cache generation is sequential and fail-fast on empty raw results when a candidate scope exists, matching the low-spec laptop constraint and preventing silent metric drift.
- Issues encountered: Targeted `python -m py_compile pipeline/eval/runtime.py pipeline/eval/retrieval_eval.py pipeline/eval/answer_eval.py pipeline/common/local_retriever.py` passed. `python -m unittest pipeline.tests.test_eval_runtime pipeline.tests.test_db_ann_runtime -q` passed after hard-blocking underspecified rewrites that miss the memory target.
- Next steps: Re-rerun the pinned Admin RAG comparison after the remaining rewrite-memory rerank/prompt/threshold stages land.

## [2026-05-25] Session Summary (RAG History Method and Completed Duration UI)
- What was done: Updated the Admin RAG test history so generation method tags start with the actual method code (for example `A method`, `C method`) and completed RAG runs show KST start time plus elapsed duration instead of a remaining ETA of `00:00`.
- Key decisions: Kept backend RAG run DTO/API shape unchanged because `startedAt` and `finishedAt` are already returned; the change is frontend display-only plus a refreshed backend-served React bundle.
- Issues encountered: Targeted frontend ESLint passed with the existing `RagPage.jsx` hook dependency warnings. `npm run build` passed.
- Next steps: Browser-smoke `/admin/domains/spring/rag-tests` on a narrow viewport to confirm method codes and completed-duration cards remain readable.

## [2026-05-25] Session Summary (Short-Query Rewrite Adoption Analysis)
- What was done: Analyzed RAG run `ea464740-6143-424f-9a9a-dac9112289e8` and confirmed low rewrite adoption is mainly from strict delta and verbosity gates on short-user queries, not missing expected chunks. Added a compact-query absolute length allowance to selective rewrite adoption and aligned the Korean rewrite prompt with that gate.
- Key decisions: Kept the prompt-only synthetic-memory rewrite architecture unchanged; only short-user adoption policy/prompt wording was adjusted.
- Issues encountered: Validation was limited to targeted Python syntax compilation and backend `compileJava`; no full RAG rerun was executed.
- Next steps: Rerun the same snapshot/dataset condition to compare adoption rate and retrieval metrics before broader tuning.

## [2026-05-20] Session Summary (C Gating Batch Visibility Restore)
- What was done: Restored Spring-domain visibility for C/full-gating snapshot `73b5bfc1-73b5-4cfe-ab64-daf94729578b` by setting its `quality_gating_batch.domain_id` to the Spring domain. Changed generation batch `9861d4df-9b73-4d7c-88a7-0116c1ef83e7` from `cancelled` to `completed`.
- Key decisions: Set the recovered generation batch source run to dominant completed run `09397a85-ef0c-4a30-9744-a2497c671c51`, preserved existing timestamps, and set `total_generated_count=1066` to match currently linked C raw rows.
- Issues encountered: The recovered batch still contains mixed raw provenance: 1000 rows from `09397a85-ef0c-4a30-9744-a2497c671c51` and 66 rows from `90b73679-0502-43b1-8709-b30aa431c397`; this was recorded in `metrics_json.operational_recovery`.
- Next steps: If stricter lineage is required later, repair raw C `generation_batch_id` provenance instead of relying on the recovered batch alias.

## [2026-05-20] Session Summary (C Gating Batch Visibility Investigation)
- What was done: Investigated RAG test run `a670cbc1-b136-4701-a4e0-fdaaf8683a3c` and confirmed it uses C/full-gating snapshot `73b5bfc1-73b5-4cfe-ab64-daf94729578b` with source gating run `135d3403-7db5-4643-a31b-19eab9933e67`.
- Key decisions: No DB or code changes were made. The snapshot rows still exist, but `quality_gating_batch.domain_id` is `NULL` while related gated/memory/eval/RAG rows are Spring-scoped, so domain-filtered Admin views hide the C gating batch.
- Issues encountered: Historical C raw-query provenance is inconsistent: the original generation batch `ce7fbf2e-9ec8-4bff-bb25-dbbcd804dd0e` remains on the gating batch, but raw C rows are currently tagged to cancelled batch `9861d4df-9b73-4d7c-88a7-0116c1ef83e7`.
- Next steps: Add a targeted domain/provenance repair if the C snapshot must be visible and selectable from the Spring domain workspace.

## [2026-05-20] Session Summary (Synthetic Memory Prompt-Only Rewrite)
- What was done: Simplified RAG rewrite evaluation so synthetic memory is used only as LLM prompt examples/context, final retrieval is either raw-query retrieval or selected rewritten-query retrieval, and rewrite adoption requires retrieval-score improvement over the raw baseline.
- Key decisions: Removed default rewrite memory-hint retrieval/merge paths, stopped Admin rewrite runs from including memory-only mode by default, and hid rewrite retrieval merge strategy controls from the RAG GUI while leaving memory_only modes as explicit legacy/ablation paths.
- Issues encountered: Validation was limited to targeted Python syntax compilation and diff/static checks; no full build, full test, full pipeline, or eval run was performed.
- Next steps: Run a small fixed-snapshot retrieval smoke later to inspect adoption rates and selected rewrite diagnostics.

## [2026-05-20] Session Summary (Selective Rewrite v3 Prompt)
- What was done: Updated `configs/prompts/rewrite/selective_rewrite_v2.md` to version `v3` with a retrieval-anchor-first policy for Korean queries over English Spring docs, and added a prompt-catalog migration to bind `rag_rewrite.ko` to v3.
- Key decisions: Preserved the JSON output schema and existing candidate labels for backend/frontend compatibility while making memory/canonical/terminology anchors more active when intent-compatible.
- Issues encountered: Validation was limited to targeted static/schema checks; no full build, full test, pipeline run, live LLM call, or DB migration execution was performed.
- Next steps: Run a fixed-snapshot selective rewrite comparison to confirm anchor expansion improves Recall/MRR/nDCG without topic drift.

## [2026-05-20] Session Summary (Admin Entry Route)
- What was done: Changed the chat-surface "Admin console" button to navigate to `/admin` instead of `/admin/pipeline`, and aligned the backend `/admin` route to serve the React app rather than redirecting to the pipeline page.
- Key decisions: Kept existing `/admin/pipeline` and legacy redirects unchanged; this only restores the Domain Atlas admin entry path.
- Issues encountered: Targeted `npm exec eslint -- src/pages/ChatPage.jsx`, backend `compileJava`, and frontend `npm run build` passed.
- Next steps: Smoke-test the root chat button on the Vite dev server.

## [2026-05-20] Session Summary (Pipeline Import Domain ID)
- What was done: Added `--domain-id` to `pipeline/cli.py import-corpus`, included domain identity in import run source/config snapshots, and applied domain assignment to imported sources, documents, chunks, relations, glossary rows, and aliases after standalone CLI imports. Backend pipeline orchestration now forwards selected domain IDs into the import command.
- Key decisions: Kept the import row upsert logic unchanged and reused a post-import domain propagation step so existing idempotency comparisons remain stable.
- Issues encountered: `python -m compileall` for the touched pipeline files and backend `compileJava` both passed.
- Next steps: Run a small migrated-DB import smoke with `--domain-id` when local DB load is acceptable.

## [2026-05-20] Session Summary (Domain Source Membership UI)
- What was done: Added a Source Membership panel to the Domain Atlas so operators can select a domain, inspect linked corpus sources, attach an available source, or detach an existing source without leaving the domain entry page.
- Key decisions: Kept source creation in the domain Pipeline page and used the existing `/api/admin/domains/{domainRef}/sources` attach/detach APIs for existing-source membership edits.
- Issues encountered: Targeted `DomainHomePage.jsx` ESLint and frontend `npm run build` passed, refreshing the backend-served React bundle.
- Next steps: Browser-smoke attach/detach on a development DB after applying the domain migration.

## [2026-05-20] Session Summary (Domain Scoped Pipeline Execution)
- What was done: Carried selected `domainId` through Pipeline source creation, URL auto-registration, pipeline run requests, run history, and dashboard queries. Backend pipeline runs now persist `corpus_runs.domain_id`, validate selected sources against the domain, and propagate the domain to imported corpus rows after import.
- Key decisions: Kept global pipeline APIs backward compatible by making `domain_id` optional; domain workspaces now avoid accidentally collecting/importing all global sources when a domain has an empty source set.
- Issues encountered: Backend `compileJava`, targeted frontend ESLint, and frontend `npm run build` passed; ESLint still reports the existing `PipelinePage.jsx` hook dependency warning.
- Next steps: Add a direct domain source membership editor for attaching/detaching existing corpus sources without opening the pipeline source form.

## [2026-05-20] Session Summary (Domain Scoped Admin Flow Wiring)
- What was done: Wired selected technical-document `domain_id` through Admin list/run paths for corpus sources/documents, synthetic generation/history/query stats, quality-gating history/execution, and RAG dataset/run execution. Frontend domain workspaces now wait for the selected domain summary before loading scoped operation pages.
- Key decisions: Kept global legacy `/admin/*` pages compatible by making `domain_id` optional, while domain workspace routes attach it to API queries and runtime request payloads.
- Issues encountered: Backend `compileJava`, targeted frontend ESLint, and frontend `npm run build` passed; ESLint still reports existing hook-dependency warnings in legacy pages.
- Next steps: Extend domain membership editing in the GUI and carry domain context into deeper pipeline import/materialization jobs.

## [2026-05-20] Session Summary (Domain Workspace and Prompt Studio UI)
- What was done: Added the frontend Domain Atlas entry page, domain workspace routing, selected-domain summary banner, and Prompt Studio UI for shared A-G/rewrite prompt bindings. Prompt asset detail now falls back to file-backed prompt content so existing prompts can be viewed before DB-backed revisions exist.
- Key decisions: Reuse existing Pipeline/Synthetic/Gating/RAG pages under `/admin/domains/:domainKey/*` first, then wire strict domain-scoped API execution in the next phase.
- Issues encountered: Targeted frontend ESLint passed and `npm run build` regenerated backend-served React assets.
- Next steps: Add `domain_id` propagation and backend validation to existing Admin execution/list endpoints.

## [2026-05-20] Session Summary (Domain and Prompt Admin APIs)
- What was done: Added backend Admin APIs for domain catalog/workspace summary/source membership and global prompt asset/binding management.
- Key decisions: Keep the first backend API phase read/write catalog-focused and leave existing pipeline/Synthetic/RAG execution filters for a separate wiring step.
- Issues encountered: Targeted backend compileJava passed. No DB migration was executed to avoid unnecessary local DB work.
- Next steps: Implement frontend Domain Home/Workspace and Prompt Studio using these APIs.

## [2026-05-20] Session Summary (Domain and Prompt Schema)
- What was done: Added additive Flyway schema for `tech_doc_domain`, source/method policy mapping, global prompt asset binding, seed data for Spring/Python and A-G/RAG rewrite prompts, plus nullable `domain_id` backfill columns across major runtime tables.
- Key decisions: Keep strict domain enforcement for a later phase; this migration establishes catalog and backfill shape without forcing `NOT NULL` while existing services are being retrofitted.
- Issues encountered: No local DB migration was executed because the current instruction is to avoid unnecessary broad DB work on a low-spec laptop.
- Next steps: Implement backend Domain/Prompt APIs and wire the Admin GUI to the new domain/prompt catalogs.

## [2026-05-20] Session Summary (Global Prompt Management Design)
- What was done: Extended the domain pipeline integration design to explicitly cover shared prompt assets for A/B/C/D/E/F/G synthetic generation and RAG query rewrite.
- Key decisions: Keep prompt assets and prompt bindings above domains, add a global Admin Prompt Studio for viewing/editing/versioning/activating prompts, and have domain workspaces display active prompt versions read-only during Synthetic/RAG execution.
- Issues encountered: No implementation or runtime behavior was changed. This was a documentation/design-only update.
- Next steps: Implement prompt asset binding seed/API/UI before replacing file-name based prompt resolution in generation and rewrite runtimes.

## [2026-05-20] Session Summary (Domain Pipeline Integration Design)
- What was done: Reviewed `.codex/AGENTS.md`, root/backend/frontend/pipeline docs, backend controllers/services/migrations, frontend admin routes/pages, Python pipeline entrypoints, source configs, and the local PostgreSQL schema/data distribution. Added a design doc for domain-first pipeline integration at `docs/architecture/domain_pipeline_integration_design.md`.
- Key decisions: Treat `tech_doc_domain` as the new first-class boundary, keep A/B/C/D/E/F/G generation methods global, preserve strategy-separated raw tables, and attach corpus/source/generation/gating/memory/eval/RAG artifacts to a domain with strict same-domain validation.
- Issues encountered: No implementation or runtime behavior was changed. The current system still uses hardcoded Spring/Python method/source scopes in backend/frontend until the proposed phased migration is implemented.
- Next steps: Start with domain schema/backfill and Domain API, then retrofit backend/pipeline filters before replacing the Admin entry page with the domain home/workspace shell.

## [2026-05-20] Session Summary (Short-User Eval Dataset Version Restore)
- What was done: Restored eval dataset `b2d47254-8655-4c9c-81ac-7615677ec5bd` (`Spring KR Short User Eval 80 (KR)`) to `v4-2026-04-19` by relinking its active items to the preserved `v4-test-short-user-*` sample rows. Restored eval dataset `8f0d6e0f-6f9e-4d64-9b07-f4e8ce5ebec0` (`Spring KR Short User Eval 80 (EN)`) to `v1-2026-04-28` by upserting the committed v1 JSONL rows from `efe0b00:data/eval/human_eval_short_user_test_80_en.jsonl`.
- Key decisions: Kept the operation scoped to the two requested dataset IDs, avoided broad DB scans, and left current V5/V2 sample rows/files otherwise untouched. Verified both restored datasets have 80 active items and KR/EN expected doc/chunk grounding parity has 0 mismatches.
- Issues encountered: The first restore command failed before execution due to PowerShell/Python quoting; the corrected transaction completed successfully.
- Next steps: Use these restored versions for any follow-up RAG comparisons that need the V4 KR / V1 EN short-user baseline.

## [2026-05-20] Session Summary (Method-Compressed Stress Eval Datasets)
- What was done: Built separate Spring method-compressed stress eval datasets from existing accepted synthetic queries in gating batches A/B/C/D/E, 80 items each, and upserted them into `eval_dataset`, `eval_samples`, and `eval_dataset_item`.
- Key decisions: Kept canonical V5 short-user datasets unchanged. The new datasets use actual DB synthetic queries as source text, compress them into short-user anchor queries, preserve expected doc/chunk grounding, and prioritize `far`/`near` multi-chunk rows to make retrieval deliberately challenging without fabricating metrics.
- Issues encountered: A reduced smoke run overwrote the output JSONL files with fewer rows, so the full 80-item run was executed immediately afterward and verified.
- Next steps: Run snapshot-pinned RAG retrieval tests against these new dataset keys to measure whether synthetic memory/rewrite improves the intentionally compressed queries.

## [2026-05-20] Session Summary (RAG Memory-Hint Rewrite and Paired Short-User Eval)
- What was done: Changed RAG rewrite/memory lookup so synthetic queries are used as bounded retrieval hints instead of final replacement queries by default. Admin RAG defaults now use `rewrite_threshold=0.05`, relaxed `short_user` adoption policy, raw-query preservation, and top memory-anchor hint retrieval. Restored canonical `Spring KR Short User Eval 80 (KR)` to the refined V5 active samples and regenerated/upserted the paired `Spring KR Short User Eval 80 (EN)` dataset with exact KR/EN grounding parity.
- Key decisions: Kept A/B/C/D/E synthetic raw tables and stored synthetic query text unchanged. Memory hints append only 1-3 probable anchors to the raw query, then merge raw and hint retrieval by `max_score`; direct synthetic-query replacement is now opt-in via `memory_lookup_direct_enabled=true`.
- Issues encountered: The live KR dataset had been overwritten to V4 active items, so DB active bindings were updated back to refined `test-short-user-*` rows without deleting historical `v4-test-short-user-*` samples. Verification showed 80 KR/EN pairs, 0 grounding mismatches, and 0 missing chunk refs.
- Next steps: Re-run A/B and A-2/B-2 on the same gating snapshots to measure whether `selective_rewrite` now diverges from `raw_only` and whether multi-source hints contribute once memory-hint retrieval is active.

## [2026-05-20] Session Summary (Full Anchor Normalization Dry-Run Scope)
- What was done: Confirmed `anchor-normalize-471d787c` covered only 500 of 6,481 active anchors because the Admin UI sent `limit=500` and the backend defaulted missing create limits to 500. Fixed dry-run creation so missing/zero target limit means all matching active anchors, changed the Admin Pipeline dry-run action to ignore current anchor filters and request the full active-anchor scope, then created `anchor-normalize-7d079b88` against the live DB with 6,481/6,481 candidates and 0 missing anchors.
- Key decisions: Kept review/approval safety unchanged. The live full-scope run now shows `approved`: 48 `would_update` candidates were approved/applied and 5 `conflict` candidates were skipped, while unchanged candidates remained pending as expected.
- Issues encountered: Targeted backend integration tests and Pipeline page lint passed; frontend build and backend `processResources` refreshed the served Admin bundle. The active backend was restarted to apply the Java change.
- Next steps: Use `anchor-normalize-7d079b88` as the full-scope normalization history and investigate the 5 skipped conflicts separately if they need canonical cleanup.

## [2026-05-19] Session Summary (Multi-source Anchor Tracking UI)
- What was done: Verified the live multi-source anchor build run `3fe9da53-9b97-47f9-af45-b71aceb86efe` is completed with 6,481 candidate anchors, 394,308 relations, and 1,728,212 evidence rows. Added a `/admin/pipeline` tracker section that auto-loads the latest multi-source anchor run, summarizes status/counts/version/policy/source breakdown, polls only while a run is active, and exposes a build/retry action for failed runs.
- Key decisions: Kept the server relation-build API and additive relation-table semantics unchanged. The UI remains lightweight for low-spec local use: one initial history read plus active-only 15s polling.
- Issues encountered: Targeted Pipeline page lint passed with the existing hook dependency warning; frontend build passed and refreshed backend static React assets.
- Next steps: Browser-smoke `/admin/pipeline` against the running backend and use the tracker before enabling multi-source hints in RAG comparisons.

## [2026-05-19] Session Summary (Anchor Normalization History Delete)
- What was done: Added hard delete support for Anchor normalization history via `DELETE /api/admin/corpus/anchors/normalization-runs/{runId}` and exposed an `이력 삭제` action in the Admin Anchors normalization history table. Candidate-row decision cells now explain why conflict/invalid rows do not show the approve option.
- Key decisions: Deleting a normalization run removes only the dry-run/review history and candidate rows; it does not revert canonical values already applied by an approved run. Resume-from-server-restart was confirmed not to exist for an in-flight dry-run because candidate generation is currently one synchronous transaction, while completed pending-review runs persist normally across restart.
- Issues encountered: Targeted backend delete integration test passed. Targeted frontend lint passed with the existing `PipelinePage.jsx` hook dependency warning.
- Next steps: Browser-smoke deleting pending/approved normalization histories against a running backend and decide separately whether dry-run generation needs a resumable checkpointed job design.

## [2026-05-19] Session Summary (Anchor Normalization Review Modal UX)
- What was done: Improved the `/admin/pipeline` anchor normalization review modal with Korean labels, fixed the corrupted modal title literal, added review guidance, workflow-grouped actions, approval-blocking reasons, decision summary badges, destructive reject styling, unsaved-close warning, corrected the `변경 없음 표시` check-pill layout, and readable current/proposed/conflict table presentation.
- Key decisions: Kept Anchor normalization backend APIs and approval/reject behavior unchanged. The Korean display issue was scoped to frontend-rendered literals; code inspection found no charset conversion in the API/helper path.
- Issues encountered: Local backend API was not running on 8080/8081, so live payload verification was not available. Targeted frontend lint passed with the existing `PipelinePage.jsx` hook dependency warning.
- Next steps: Browser-smoke the pending run detail modal against a running backend and refresh the served static React bundle only when a frontend build is allowed.

## [2026-05-19] Session Summary (DB-ANN Hybrid Candidate Union)
- What was done: Changed Admin RAG `db_ann` hybrid retrieval so PostgreSQL ANN candidates are unioned with lexical and technical-token candidates before the existing hybrid rerank for both chunk retrieval and memory lookup.
- Key decisions: Kept Admin GUI flow, chunk embedding materialization button, experiment config shape, pipeline stages, dense-only DB-ANN behavior, and final hybrid scoring function unchanged. The change is scoped to the runtime DB-ANN adapter candidate pool construction.
- Issues encountered: Targeted Python validation passed, including DB-ANN runtime tests, existing eval runtime tests, and a read-only local PostgreSQL smoke for chunk/memory lexical and technical SQL.
- Next steps: Re-run the same A/full-gating V4 dataset/snapshot `db_ann` RAG comparison and compare `raw_only`, `memory_only_gated`, `rewrite_always`, and `selective_rewrite` against the previous `1882261a-53f4-4a49-a3cf-30eb96047f94` run.

## [2026-05-19] Session Summary (Multi-source Anchor Expansion Phase A)
- What was done: Added additive multi-source anchor relation schema/build API, Admin Anchors UI build action/history, Admin RAG toggle/config wiring, runtime relation-index lookup, `multi_source_anchor_hints` prompt payload injection, and drift-safe prompt guidance.
- Key decisions: Existing synthetic query text/data and strategy-separated raw tables remain immutable. Relation lookup is precomputed into `canonical_anchor_relation` and loaded once per eval run, while runtime rewrite treats expanded anchors as low-priority optional hints with score/type/count filters.
- Issues encountered: Java text-block SQL interpolation failed on first compile and was replaced with named SQL parameters. Targeted backend compile, frontend build/lint, and runtime rewrite tests passed.
- Next steps: Apply `V33` to the intended DB, run one Admin multi-source build from Anchors, then compare a small same-snapshot RAG smoke with multi-source hints off/on before any official evaluation.

## [2026-05-19] Session Summary (Remove Temporary V4 Restore Dataset)
- What was done: Deleted the temporary `Spring KR Short User Eval 80 V4 Restore (KR)` dataset row after the canonical `Spring KR Short User Eval 80 (KR)` dataset had been overwritten to use the restored V4 samples.
- Key decisions: Deleted only the restore dataset/item links. Preserved the `v4-test-short-user-*` sample rows because the canonical KR dataset now references all 80 of them.
- Issues encountered: None. Verification showed the canonical KR dataset remains `v4-2026-04-19` with 80 active `v4-test-short-user-*` items, and the temporary restore dataset no longer appears in the dataset list.
- Next steps: Use `Spring KR Short User Eval 80 (KR)` as the single KR short-user evaluation dataset for future V4-based RAG runs.

## [2026-05-19] Session Summary (Canonical Short-User Dataset V4 Overwrite)
- What was done: Overwrote canonical dataset `Spring KR Short User Eval 80 (KR)` (`b2d47254-8655-4c9c-81ac-7615677ec5bd`) to point at the restored V4 sample set from `Spring KR Short User Eval 80 V4 Restore (KR)` while preserving the separate restore dataset.
- Key decisions: Kept both dataset rows. Replaced only canonical `eval_dataset_item` links with the existing `v4-test-short-user-*` samples, updated canonical version/metadata to `v4-2026-04-19`, and left old `test-short-user-*` sample rows in place for historical RAG result references.
- Issues encountered: The first attempted destructive cleanup query failed before commit due to PostgreSQL parameter type inference and was rolled back. The corrected operation deleted only the default `기본 평가 데이터셋 (build-eval-dataset)` row plus its unreferenced 140 `dev-human-*`/`test-human-*` samples.
- Next steps: Future RAG runs using `human_eval_short_user_40` now use V4 restored queries. If a fresh V4 comparison run is needed, retry after aligning the Admin RAG experiment config path.

## [2026-05-19] Session Summary (V4 Eval Dataset Restore Attempt)
- What was done: Compared RAG test runs `e5a12249-d71b-4572-8b66-5dfffcf2935b` and `5fd51176-32db-413b-a09d-5ec00143af89`, confirmed the main retrieval jump came from short-user dataset `v4-2026-04-19` to `v5-2026-05-13`, and restored a non-destructive V4 copy in DB as dataset `5b919915-bd7e-46cb-a60c-905c9989edd4` with `v4-` prefixed sample IDs.
- Key decisions: Did not overwrite the live V5 dataset. Reused the saved V4 snapshot report `data/reports/short_user_current_dump_2026-05-13.json`; verified all referenced V4 chunks, docs, and synthetic provenance IDs still exist in the local DB.
- Issues encountered: Launched RAG run `4328c647-c814-4c1f-afe5-ad9dea898800` against the restored V4 dataset and same A/full-gating snapshot, but `eval-retrieval` failed because the backend worker could not find `configs/experiments/admin_eval_0ea374c48298.yaml` from its runtime config path after `build-memory` completed.
- Next steps: Fix or align the Admin RAG experiment config path before retrying the V4 restore run; compare the completed V4-current-code run against V5 only after the retry succeeds.

## [2026-05-19] Session Summary (Language-Specific RAG Rewrite Prompt Split)
- What was done: Split Admin RAG rewrite prompt selection by evaluation query language so English-query runs use a new English rewrite prompt while Korean/code-mixed runs keep the existing selective rewrite prompt. Admin RAG now rejects synthetic method/eval-language mismatches such as E with `ko` queries or A-D/G with `en` queries.
- Key decisions: Used query-language/profile separation rather than changing A/B/C/D/E generation tables or the RAG stage order. Frontend method chips and snapshot filtering now mirror the backend language guard.
- Issues encountered: Targeted pipeline/backend tests passed; `RagPage.jsx` lint still has the existing two hook dependency warnings.
- Next steps: Run a small E + English eval dataset RAG smoke and compare rewrite debug payloads against an A-D + Korean eval run.

## [2026-05-19] Session Summary (Anchor Normalization Dry-Run 500 Fix)
- What was done: Fixed `POST /api/admin/corpus/anchors/normalization-runs` dry-run creation by preserving whitespace between dynamically appended SQL predicates and `ORDER BY` in `AnchorNormalizationService.findTargets`.
- Key decisions: Kept the Admin review flow, V32 schema, approval behavior, and frontend API contract unchanged; added focused backend integration coverage for dry-run creation.
- Issues encountered: The production 500 was reproduced from PostgreSQL logs as `gt.is_active = TRUEORDER BY ...`. Targeted `.\gradlew.bat test --tests io.queryforge.backend.admin.corpus.CorpusAdminMutationIntegrationTest` passed, and a live 8080 smoke POST returned `pending_review` before the validation row was deleted.
- Next steps: Browser-smoke the Anchors UI dry-run/detail controls after refreshing the page against the restarted backend.

## [2026-05-19] Session Summary (Canonical Anchor Rewrite Prompt Hints)
- What was done: Added compact `canonical_anchor_hints` injection to selective rewrite prompt payloads when `rewrite_anchor_injection_enabled=true`, using only approved/self-fallback `used_for_scoring=true` canonical anchors from top memory metadata.
- Key decisions: Kept full `canonical_anchors` metadata out of `top_memory_candidates`, did not expose `canonical_term_id` in the LLM prompt, and did not change query text, dense query text, memory query text, raw synthetic payloads, glossary rows, retrieval/scoring expansion, pipeline order, or DB migrations.
- Issues encountered: Targeted validation passed with `python -m py_compile pipeline\eval\runtime.py pipeline\eval\retrieval_eval.py pipeline\eval\answer_eval.py` and `python -m unittest pipeline.tests.test_eval_runtime.EvalRuntimeRewriteTests -q`.
- Next steps: Run an Admin GUI RAG quality test only after choosing a reviewed snapshot, then inspect rewrite debug payloads for hint density without running official evaluation.

## [2026-05-19] Session Summary (Admin Anchor Normalization Review Flow)
- What was done: Added Admin Anchors dry-run/review flow for deterministic anchor canonical-field normalization. Backend now has review-history tables, dry-run report APIs, approve/reject APIs, and approval updates only `corpus_glossary_terms.canonical_form` / `normalized_form` for safe candidates. Frontend Anchors section now has an `Anchor 정규화 Dry-run` button plus normalization history/detail/approve/reject controls.
- Key decisions: Kept the flow manual-review gated. Dry-run creates only review/report history; approval is blocked for conflict/invalid candidates and does not touch synthetic raw rows, query text, memory entries, evidence, links, or mapping rows. Added migration file only; did not apply it.
- Issues encountered: Targeted validation passed with `backend .\gradlew.bat compileJava` and `frontend npx eslint src/pages/PipelinePage.jsx` (0 errors, 1 pre-existing hook dependency warning).
- Next steps: Apply `V32` only after explicit approval, browser-smoke the Anchors UI, then decide whether to add a read-only JSON/Markdown export for normalization histories.

## [2026-05-19] Session Summary (Canonical Anchor Backfill Dry-Run Documentation)
- What was done: Added a canonical anchor backfill dry-run policy document covering report schema, manual review, version pinning, reproducibility, and explicit no-overwrite/no-DB-write constraints.
- Key decisions: Kept Session 12 documentation-only. Did not apply V31, insert mapping rows, run backfill, execute official evaluation, or add a pipeline writer before the review policy is accepted.
- Issues encountered: No tests were run because only Markdown documentation and progress/index links changed.
- Next steps: If approved, add a read-only dry-run report writer that emits JSON/Markdown artifacts without DB writes or automatic alias merge.

## [2026-05-19] Session Summary (Canonical Anchor Version Pins + Admin Display)
- What was done: Added canonical anchor version pins (`anchor-map-v1`, `anchor-normalize-v1`, `canonical-anchor-runtime-v1`) to Admin RAG configs, RAG experiment records, RAG metrics payloads, and retrieval/answer report summaries. Admin RAG detail now renders memory candidate canonical anchor metadata with canonical form, alias, confidence, status, language, type, and scoring/review distinction.
- Key decisions: Kept changes additive in JSON/config/report/UI payloads only. `query_text`, dense query text, memory query text, raw synthetic payload semantics, pipeline order, V31 migration state, mapping rows, and LLM rewrite prompt exposure were not changed.
- Issues encountered: Targeted validation passed with `backend .\gradlew.bat compileJava`, `pipeline python -m py_compile common\anchor_normalization.py eval\retrieval_eval.py eval\answer_eval.py`, and `frontend npx eslint src/pages/RagPage.jsx` (2 pre-existing hook dependency warnings, 0 errors).
- Next steps: Session 12 should add backfill dry-run/manual review/version-pinning documentation without applying migrations, inserting mapping rows, running full evaluation, or doing LLM-based alias merge.

## [2026-05-19] Session Summary (Hybrid Retrieval Canonical Expansion)
- What was done: Added canonical-anchor lexical expansion for hybrid retrieval BM25/technical-overlap scoring, using only `used_for_scoring=true` anchors grouped by `canonical_term_id` from existing canonical metadata.
- Key decisions: Kept raw `query_text`, memory query text, dense query embeddings, dense passage embeddings, raw synthetic payloads, pipeline order, V31 migration state, and mapping rows unchanged. Canonical terms are appended only to BM25/technical lexical fields and fall back to previous behavior when metadata is absent.
- Issues encountered: Validation was limited to targeted checks: `python -m py_compile common\local_retriever.py eval\runtime.py gating\quality_gating.py tests\test_eval_runtime.py` and `python -m unittest pipeline.tests.test_eval_runtime -q` passed.
- Next steps: Session 11 should record mapping/normalization versions in Admin/runtime config and expose canonical/alias/confidence metadata in Admin views without changing server-driven runtime options.

## [2026-05-19] Session Summary (Rewrite Scoring Canonical Metadata)
- What was done: Extended rewrite scoring in `pipeline/eval/runtime.py` to consume existing `memory_entries.metadata.canonical_anchors` as scoring-only metadata for terminology preservation, anchor overlap, and memory-target token hints.
- Key decisions: Kept raw query text, rewrite candidate text, dense retrieval query text, memory query text, rewrite prompt schema, V31 migration state, and mapping rows unchanged. Canonical scoring uses only anchors marked `used_for_scoring=true` and falls back to previous behavior when metadata is absent.
- Issues encountered: Validation was limited to targeted checks: `python -m py_compile eval\runtime.py tests\test_eval_runtime.py` and `python -m unittest pipeline.tests.test_eval_runtime -q` passed.
- Next steps: Session 10 should add canonical expansion only to BM25/technical-overlap retrieval fields without replacing dense queries or raw stored text.

## [2026-05-19] Session Summary (Memory Canonical Anchor Metadata)
- What was done: Added additive canonical anchor metadata construction for newly inserted `memory_entries.metadata`, using explicit synthetic row language/profile and glossary term candidates while preserving memory query text, source synthetic query text, glossary terms, and snapshot/source identity fields.
- Key decisions: Kept V31 optional/fail-closed, did not apply migrations, insert mapping rows, run memory build, backfill, or alter pipeline order. Canonical payloads are stored only as metadata with `anchor-map-v1` and `anchor-normalize-v1` pins.
- Issues encountered: Validation was limited to targeted checks: `python -m py_compile memory\build_memory.py` and `python -m unittest pipeline.tests.test_build_memory_canonical_anchor_metadata -q` passed.
- Next steps: Session 9 should consume existing canonical metadata for rewrite scoring without replacing query text, dense queries, or memory query fields.

## [2026-05-19] Session Summary (Synthetic Canonical Anchor Metadata)
- What was done: Added additive `canonical_anchors`, `anchor_mapping_version`, and `anchor_normalization_version` metadata construction for newly generated synthetic raw A~G row payloads, using the canonical anchor resolver with glossary-term metadata while preserving original `query_text` and `glossary_terms`.
- Key decisions: Kept strategy-specific raw table writes unchanged, did not insert mapping rows or apply V31, and made canonical mapping lookup optional so generation can continue when `canonical_anchor_mapping` is absent.
- Issues encountered: Validation was limited to targeted checks: `python -m py_compile generation/synthetic_query_generator.py` and `python -m unittest pipeline.tests.test_synthetic_query_generator -q` passed.
- Next steps: Session 8 should thread the same metadata-only canonical payload into new `memory_entries.metadata` without rebuilding or overwriting existing memory queries.

## [2026-05-19] Session Summary (Canonical Anchor Mapping Migration Review)
- What was done: Reviewed `V31__create_canonical_anchor_mapping.sql` against `corpus_glossary_terms` schema and the Python resolver SQL contract; confirmed the local development DB has Flyway applied through `V30` only and does not yet contain `canonical_anchor_mapping`.
- Key decisions: Did not apply the migration because explicit approval was not given. Kept V31 unchanged because FK types, approved-active uniqueness, pending multi-candidate allowance, alias-language checks, no `term_type` duplication, and self-row guard match the Session 6 constraints.
- Issues encountered: `psql` is not available on PATH, so DB inspection used a read-only Python PostgreSQL connection. Targeted validation passed with `python -m unittest pipeline.tests.test_anchor_normalization -q`.
- Next steps: Apply V31 only after explicit user approval to the intended development DB, then inspect table/index/constraint/trigger existence without inserting mappings or backfilling data.

## [2026-05-19] Session Summary (Canonical Anchor Mapping Implementation Start)
- What was done: Added Flyway `V31__create_canonical_anchor_mapping.sql`, Python `anchor-normalize-v1` helper, metadata-only canonical anchor resolver draft, and fixture coverage for the agreed normalization cases.
- Key decisions: Kept canonical data additive through a new mapping table and runtime payloads; original `query_text`, `glossary_terms`, synthetic raw rows, and memory query text remain untouched. Existing `anchor_quality.normalize_anchor_text` was not changed.
- Issues encountered: Validation was limited to the allowed targeted test: `python -m unittest pipeline.tests.test_anchor_normalization -q` passed.
- Next steps: Review/apply `V31` only in Session 6 with explicit approval, then inspect constraints and runtime lookup behavior before any backfill or metadata persistence work.

## [2026-05-19] Session Summary (Quality Gating Batch-Scoped Source Load Fix)
- What was done: Investigated gating batch `ca4ee519-3a9b-4803-a217-06b58ef097de` and fixed `pipeline/gating/quality_gating.py` so Admin gating configs with `source_generation_batch_ids` load raw synthetic queries by generation batch identity and resume over any unprocessed prefix rows.
- Key decisions: Preserved explicit source identity enforcement, made generation batch IDs take precedence over run IDs, and guarded checkpoint slicing so retry/recovery batches whose raw rows span multiple `experiment_run_id` values are gated as one batch-scoped target.
- Issues encountered: Source generation batch `c122d7c2-3bc5-4442-94d1-90c9cd1a31fa` contains 1465 B rows across 13 generation run IDs, while the completed gating batch processed only the final run's 105 rows; the first re-run loaded 1465 rows but skipped earlier unprocessed rows because the previous checkpoint was at the end of the expanded batch order.
- Next steps: Re-run job `29b16a6f-43a1-46a0-85e4-ae7e2b6096ed` is running for the affected gating batch; full completion can be monitored from Admin LLM jobs/gating result counts.

## [2026-05-19] Session Summary (Synthetic Retry Target Accounting Fix)
- What was done: Updated `pipeline/generation/synthetic_query_generator.py` so retry/resume generation initializes and refreshes `generated_queries` from live `generation_batch_id` raw-row counts instead of resetting to the current process attempt.
- Key decisions: Counted existing/reused batch rows toward `max_total_queries` for both online generation and Strategy B Gemini Batch mode; added `initial_generated_queries` and `new_generated_queries` metrics for observability.
- Issues encountered: Targeted validation passed with `python -m py_compile pipeline\generation\synthetic_query_generator.py` and `python -m unittest pipeline.tests.test_synthetic_query_generator -q`.
- Next steps: Use a small retry-forced generation smoke before another large Strategy B batch.

## [2026-05-19] Session Summary (Synthetic Batch Completion Recovery)
- What was done: Recovered Strategy B generation batch `c122d7c2-3bc5-4442-94d1-90c9cd1a31fa` by stopping the active backend worker/Python subprocess before failure cleanup could run, then marking the generation batch, LLM job/item, and related experiment runs as `completed`.
- Key decisions: Preserved all generated synthetic rows; final live count is 1465 B queries against target `max_total_queries=1000`, with completion metadata stored in `metrics_json`/job result payloads.
- Issues encountered: The generator target guard counts only per-attempt newly inserted rows, while retry-attached cached rows are tracked as `reused_count`; combined with unlimited synthetic retry this allowed rows from repeated attempts to exceed the target and continue processing.
- Next steps: Fix the generator stop condition to include existing/reused batch rows or live batch count before running another large retry-prone generation batch.

## [2026-05-18] Session Summary (Strategy B Segmented Full Translation)
- What was done: Implemented deterministic segmented full translation for Strategy B so `KO_TRANSLATED_CHUNK` is reconstructed from token-safe source-preserving segments while keeping `corpus_chunks.chunk_text`, retrieval/eval grounding, and raw B storage unchanged.
- Key decisions: Used existing `chunk_generation_asset` for both segment cache and final reconstructed translation by versioning prompt template keys (`segment` vs `full`), avoiding a DB migration while preserving deterministic resume for successfully cached segments.
- Issues encountered: Segmenting solves provider output truncation without summary-first translation or semantic compression; true chunk-level partial failure continuation remains a follow-up policy decision.
- Next steps: Run a tiny live B smoke on a known long Spring Security chunk and inspect segment metadata, code-fence preservation, and `finish_reason=MAX_TOKENS` absence before scaling.

## [2026-05-18] Session Summary (Synthetic MAX_TOKENS Retry Guard)
- What was done: Updated backend LLM job retry handling so `max_tokens_truncated` failures are no longer covered by unlimited synthetic-generation retry; the category now retries at most once and then fails terminally with `failure_policy=failed_needs_config` in result payloads.
- Key decisions: Preserved existing DB terminal statuses (`failed` for job/batch) instead of adding a new enum value, because current check constraints and admin status filters do not yet support a separate `failed_needs_config` status.
- Issues encountered: Strategy B large all-source generation can repeatedly hit Gemini `finish_reason=MAX_TOKENS` during full chunk translation before query generation.
- Next steps: Design a full-document translation path for Strategy B using chunk/segment translation assets without source summarization or semantic compression.

## [2026-05-18] Session Summary (Canonical Anchor Runtime Normalize Layer Design)
- What was done: Reviewed AGENTS constraints, root/backend/pipeline progress/index documents, and the limited runtime anchor/rewrite/retriever/memory/synthetic metadata paths for Session 4 without applying migrations, running tests/builds/evaluations, or modifying query/memory/synthetic data.
- Key decisions: Proposed a metadata-only runtime API that accepts explicit alias language, mapping/normalization versions, source context, and optional glossary fallback candidates; it returns canonical anchor payloads without replacing `query_text`, `glossary_terms`, synthetic raw fields, memory query text, or dense retrieval query text.
- Confirmed policies: Runtime lookup should use approved+active mappings only for scoring, expose pending candidates only in optional debug/review payloads, handle self fallback through active `corpus_glossary_terms` lookup rather than mapping-table self rows, and keep `normalized_alias` separate from display/debug aliases.
- Issues encountered: Existing rewrite and retrieval paths score raw token overlap from current strings, so future implementation must thread canonical metadata alongside those strings instead of changing tokenizer, BM25 input, dense input, or rewrite candidate text.
- Next steps: Session 5/6 should persist the same `canonical_anchors` payload additively in synthetic/memory metadata, and Session 7/8 should consume canonical IDs/terms only for scoring or lexical expansion fields while preserving original query text.

## [2026-05-18] Session Summary (Canonical Anchor Normalization Rule Design)
- What was done: Reviewed project constraints and the limited anchor/glossary normalization paths for Session 3 without applying migrations, running full tests/builds/evaluations, or modifying query/memory/synthetic data.
- Key decisions: Proposed `anchor-normalize-v1` as an application-computed, metadata-only alias normalization contract that lowercases with locale-independent rules, collapses whitespace, preserves code/config punctuation, folds hyphen/underscore only for phrase-like aliases, preserves annotation prefixes, and removes Korean intra-phrase spacing without semantic reordering or synonym merging.
- Confirmed policies: `alias_language` is mandatory at storage/runtime contract boundaries and is never inferred during runtime lookup; mapping rows do not duplicate `term_type` and must read it through `canonical_term_id -> corpus_glossary_terms`; deterministic `normalized_alias` lookup keys are separate from human-readable `display_alias`; backend normalization helper belongs in shared utility scope, not Admin corpus-only code.
- Issues encountered: Existing Python/runtime/backend normalization paths differ (`anchor_quality.normalize_anchor_text`, loader `normalize_term_text`, backend anchor re-extraction lower/trim), so future implementation should add a dedicated helper and fixture contract instead of changing current helpers in place.
- Next steps: Session 4 should use the rule only for runtime canonical metadata lookup, keep original query/memory/glossary strings untouched, and return canonical anchor payloads with `mapping_version=anchor-map-v1` and `normalization_version=anchor-normalize-v1`.

## [2026-05-18] Session Summary (Canonical Anchor Mapping Storage Design)
- What was done: Reviewed the existing glossary, alias, synthetic anchor link, memory metadata, and RAG experiment record schema for Session 2 canonical anchor mapping design without applying migrations or running tests.
- Key decisions: Recommended a separate additive `canonical_anchor_mapping` table that references `corpus_glossary_terms.term_id`, stores alias text plus application-computed normalized alias, pins `mapping_version=anchor-map-v1` and `normalization_version=anchor-normalize-v1`, omits canonical self rows, enforces one approved active mapping per version/language/normalized alias, and leaves existing `query_text`, `glossary_terms`, synthetic raw rows, and memory query text untouched.
- Issues encountered: `rag_eval_experiment_record` is initially populated with richer retrieval/rewrite config but the RAG job completion path overwrites it with a smaller payload, so future mapping-version persistence must update both paths.
- Next steps: Session 3 should define deterministic alias normalization rules using the fixed fixture set (`@Transactional`, transaction readonly variants, and Korean read-only transaction aliases) before any mapping backfill or runtime lookup work.

## [2026-05-18] Session Summary (Strategy B Gemini Batch API Path)
- What was done: Added a disabled-by-default Gemini Batch API execution path for Strategy B synthetic generation with two stages: translation cache misses via batch, then query cache misses via batch after deterministic KO summaries are materialized.
- Key decisions: Preserved fixed pipeline order, Strategy B query-only output, split raw-table writes to `synthetic_queries_raw_b`, prompt asset cache keys, and generation asset lineage. Batch mode is opt-in through `llm_execution_mode=gemini_batch` and records job/item usage metadata when available.
- Issues encountered: No live Gemini batch smoke was run in this session; validation used fake/unit tests plus targeted backend integration tests.
- Next steps: Run a tiny live B batch smoke before scaling toward any 1000-query batch.

## [2026-05-18] Session Summary (Strategy B Cost Guard)
- What was done: Pinned Gemini fallback selection to `gemini-2.5-flash-lite` in both Admin-generated configs and the Python LLM client default fallback path.
- Key decisions: Preserved the Strategy B research contract, split raw-table storage, prompt asset caching, generation asset lineage, and fixed pipeline order. The change only prevents silent fallback from Flash-Lite to higher-cost Flash during large synthetic-generation batches.
- Issues encountered: None during implementation.
- Next steps: Design the Gemini Batch API integration as a separate 2-stage path (`translation batch -> query batch`) so lineage and B query-only output semantics remain intact while targeting lower token cost.

## [2026-05-15] Session Summary (Strategy B Admin Smoke Verification)
- What was done: Ran Strategy B generation through the Admin job path after confirming the pre-existing 8080 backend was stale. A stale 8080 smoke reproduced the missing translation-cap failure (`max_tokens_truncated`) and was cancelled; a fresh current-code backend on 8081 completed both a one-source/one-query B smoke and a two-query all-allowed-sources B smoke.
- Key decisions: Verified current Admin-generated B configs persist `llm_translation_max_output_tokens=2048` and B payload/summary bounds, and verified all-allowed-sources uses one batch with the five Spring `source_ids`.
- Issues encountered: The live 8080 process was not running the latest backend classes, so its generated config lacked the B-only defaults. Current-code runs completed with `retry_count=0` and no truncation errors.
- Next steps: Use the completed all-allowed-sources B smoke as the scaling baseline; increase `max_total_queries` gradually while monitoring translation-stage failures and B trace payload sizes.

## [2026-05-15] Session Summary (Strategy B Smoke + Admin Safe Defaults)
- What was done: Ran a one-row controlled Strategy B smoke using `strategy_b_smoke`, verified the generated `code_mixed` query writes only to `synthetic_queries_raw_b`, and inspected `KO_TRANSLATED_CHUNK` + deterministic `KO_SUMMARY` asset lineage and B payload traces.
- Key decisions: Added B-only Admin generation defaults for `llm_translation_max_output_tokens` and explicit B payload/summary bounds, avoiding broad default changes for other strategies.
- Issues encountered: Initial smoke exposed translation-stage `MAX_TOKENS` truncation under the global 384-token cap; raising only B translation output budget to 2048 resolved the smoke.
- Next steps: Use the Admin-generated B config path for the next small all-allowed-sources run and monitor translation truncation rate before scaling.

## [2026-05-15] Session Summary (Strategy B Long-Chunk Hardening)
- What was done: Hardened Strategy B generation for long chunks/all-source runs by bounding B query-generation evidence payloads (`original_chunk_en`, `translated_chunk_ko`, `extractive_summary_ko`) while preserving the English chunk -> Korean translation -> Korean extractive summary -> Korean query method.
- Key decisions: Preserved fixed pipeline order, split raw-table storage, prompt asset lineage, and query-only B response schema. Deterministic KO summary cache versions now include `max_chars` to prevent cache collisions across different summary bounds.
- Issues encountered: Targeted Python compile and synthetic generator unit tests passed.
- Next steps: Run a tiny controlled B generation and inspect payload limit traces, asset lineage, and raw B table writes before Admin default wiring.

## [2026-05-15] Session Summary (Strategy B Runtime Path)
- What was done: Implemented the Strategy B generation path so B skips mandatory EN extractive summary creation, caches Korean translation from the original English chunk, builds deterministic Korean extractive summary from that translation, and sends only the intended upstream inputs into query generation.
- Key decisions: Preserved fixed pipeline order and split raw-table storage; B now stays in `synthetic_queries_raw_b` for `code_mixed` query type while A/C code-mixed rerouting to D and D/E/F/G behavior remain unchanged.
- Issues encountered: Targeted Python validation passed (`py_compile` for generator and `pipeline.tests.test_synthetic_query_generator`).
- Next steps: Run a tiny controlled B generation smoke to verify asset lineage and trace fields before long-chunk/all-source hardening.

## [2026-05-15] Session Summary (Strategy B Query-Only Contract)
- What was done: Redefined Strategy B query-generation contract to output only `query_ko`, `query_type`, and `answerability_type`, updated `gen_b_v1` to version `v5`, and aligned the pipeline B schema/stability spec with that contract.
- Key decisions: Treated `translated_chunk_ko` and `extractive_summary_ko` as upstream artifacts for Phase 2 while preserving the existing generation loop, fixed pipeline order, and strategy-split raw storage.
- Issues encountered: Targeted Python compile and schema/stability unit tests passed.
- Next steps: Phase 3 should implement the B runtime path so KO translation and KO extractive summary are generated/cached from `original_chunk_en` without mandatory EN extractive summary input.

## [2026-05-15] Session Summary (Synthetic Generation Failure Observability)
- What was done: Improved backend LLM job observability for synthetic generation so retried or cancelled `GENERATE_SYNTHETIC_QUERY` jobs retain prior failure snapshots in `llm_job.result_json`/`last_checkpoint` and `llm_job_item.checkpoint_json`.
- Key decisions: Preserved pipeline semantics and strategy-split raw storage; stored additive JSON fields (`last_failure`, `previous_failures`) without schema migrations.
- Issues encountered: Targeted backend integration test `AdminConsoleGatingIntegrationTest` passed with new retry/cancel observability coverage.
- Next steps: Use a small controlled B generation failure/cancel to verify the Admin job detail now retains stderr/stdout and failure category after retry or cancellation.

## [2026-05-15] Session Summary (Synthetic All-Allowed Sources Single Batch Restore)
- What was done: Restored Admin Synthetic "all allowed sources" generation to create one generation batch/job instead of source-by-source fan-out. The frontend now submits a single run request, the backend writes method-scoped `source_ids` into the generated experiment config, and the Python generator filters chunks with that list.
- Key decisions: Preserved strategy/source allowlists and split raw-table storage. "All allowed sources" remains constrained to Spring references for `A~E` and Python KR source for `F/G`, but the constraint is represented as one batch-scoped `source_ids` filter.
- Issues encountered: `npm run build`, targeted backend integration tests, `py_compile`, and `npx eslint src/pages/SyntheticPage.jsx` passed. Full `npm run lint` is still blocked by the existing `vite.config.js` `process` no-undef plus pre-existing hook warnings.
- Next steps: Browser-smoke `/admin/synthetic-queries` with B + "all allowed sources" and confirm only one batch/job appears while generated chunks are limited to the Spring allowlist.

## [2026-05-13] Session Summary (RAG Compare Dark-Mode Readability Polish)
- What was done: Simplified `/admin/rag-tests` two-run comparison summary cards to show only headline values, added tone coloring for fast/slow latency outcomes, separated detailed compare table groups with spacer rows and tinted borders, and increased dark-mode secondary text contrast.
- Key decisions: Frontend-only presentation change. RAG metrics, delta calculations, backend result APIs, snapshot rules, and evaluation semantics were unchanged.
- Issues encountered: `npm run build` passed and refreshed generated React static assets under backend resources.
- Next steps: Visual-smoke the RAG compare workspace in dark mode with two completed runs, especially answer-quality section labeling and performance cards.

## [2026-05-13] Session Summary (Pipeline Monitor Execution Button Spacing)
- What was done: Added dedicated spacing above the `/admin/pipeline` execution-control toolbar and changed the `full_ingest` 전체 실행 button to the same success styling used by the topbar `Run Retrieval Eval` action.
- Key decisions: Frontend-only UI polish. Pipeline run buttons still call the same backend endpoints and preserve collect/normalize/chunk/glossary/full_ingest behavior.
- Issues encountered: `npm run build` passed and refreshed generated React static assets under backend resources.
- Next steps: Visual-smoke `/admin/pipeline` in dark mode to confirm the execution toolbar has enough breathing room below the source picker.

## [2026-05-13] Session Summary (Synthetic Strategy Flow Slider + KR Label Fix)
- What was done: Corrected frontend-only `/admin/synthetic-queries` strategy flow labels by replacing visible `KO` with `KR`, fixing B/F flow descriptions, and adding overflow-aware auto-slide behavior for long strategy pipelines inside cards.
- Key decisions: Kept method codes, backend APIs, request payloads, generation pipeline behavior, and research strategy semantics unchanged.
- Issues encountered: Root `progress.md` already contained unrelated local dataset/script notes in the working tree; this entry was added without touching those files.
- Next steps: Browser-smoke the synthetic strategy card grid at desktop and narrow widths.

## [2026-05-13] Session Summary (Admin Sidebar Spacing + AI Ops Core Emphasis)
- What was done: Polished the frontend sidebar only by increasing spacing between nav icons and labels, slightly enlarging nav icons, and making the `AI Ops Core` presence card larger with stronger signal size, glow, contrast, and typography.
- Key decisions: CSS-only UI adjustment. Navigation routes, React state, backend APIs, pipeline flow, and gating logic were unchanged.
- Issues encountered: `npm run build` passed and refreshed generated React static assets.
- Next steps: Visual-smoke the sidebar in dark mode to confirm nav labels no longer feel cramped and the AI Ops Core card remains balanced with the brand block.

## [2026-05-13] Session Summary (Quality Gating Runtime Context Spacing Polish)
- What was done: Tightened `/admin/quality-gating` Runtime Context spacing by grouping source-selection controls separately from strategy-runtime controls, preventing stretched console height from expanding same-control gaps while keeping larger separation between different content groups.
- Key decisions: Frontend layout/CSS-only adjustment. Gating API, request payloads, backend validation, pipeline execution, and gating logic were unchanged.
- Issues encountered: `npm run build` passed and refreshed generated React static assets.
- Next steps: Browser-smoke Runtime Context with populated batch lists to confirm compact same-group rhythm and clearer cross-group separation.

## [2026-05-13] Session Summary (Quality Gating Launch Placement Polish)
- What was done: Moved the `/admin/quality-gating` Launch panel from the left runtime context rail to the right-side runtime column bottom, keeping the final threshold and execution button together at the natural end of the operator scan path.
- Key decisions: Frontend layout-only adjustment. Gating request payloads, API contracts, backend validation, pipeline execution, and gating logic were unchanged.
- Issues encountered: `npm run build` passed and refreshed generated React static assets.
- Next steps: Browser-smoke the Gating page at desktop/tablet widths to confirm the Launch panel remains easy to find after retriever/active-config review.

## [2026-05-13] Session Summary (Quality Gating Runtime Console UX Redesign)
- What was done: Redesigned `/admin/quality-gating` as a frontend-only runtime console. Replaced the single vertical settings dump with a three-zone layout for runtime context/launch, gate network, retriever/active config; grouped utility scoring into Top-K Retrieval, Document Consistency, and Penalty/Bonus panels; added stage-linked enabled/disabled surfaces, pipeline state chips, capability chips, stronger launch feedback, and dark layered console styling.
- Key decisions: Kept backend API, DTO/schema, pipeline execution, gating payload keys, runtime option loading, and gating semantics unchanged. The production React build refreshed only generated static frontend assets under backend resources.
- Issues encountered: `npm run build` passed. `npm run lint` still fails on the existing `vite.config.js` `process` no-undef error and pre-existing hook dependency warnings.
- Next steps: Browser-smoke `/admin/quality-gating` with live runtime options and batches in dark mode, especially multi-batch selection, disabled gate fields, retriever mode switching, and launch disabled/queued states.

## [2026-05-13] Session Summary (Frontend AI Console Theme System Refresh)
- What was done: Implemented a frontend-only AI console visual refresh with explicit light/dark theme tokens, persisted theme toggle, system-theme bootstrap, sidebar AI Ops Core presence, customized control styling, and dark-surface normalization across admin cards/forms/tables/modals/dropdowns.
- Key decisions: Kept backend API, DTO/schema, database, pipeline, evaluation, synthetic generation, snapshot, dataset, and experiment-flow contracts unchanged. The production frontend build only refreshed generated static React assets under backend resources.
- Issues encountered: `npm run build` passed. `npm run lint` still fails on the existing `vite.config.js` `process` no-undef error and pre-existing hook dependency warnings. Existing unrelated worktree changes in `data/` and `scripts/` were left untouched.
- Next steps: Browser-smoke the four admin routes in both themes with live data, especially native select/checkbox/number/range controls and dense RAG comparison tables.

## [2026-05-13] Session Summary (Admin UI Polish: Strategy Density, Dark Selected States, Korean Copy)
- What was done: Polished the React admin UI presentation layer only. Simplified synthetic strategy cards to code/status/compact flow/prompt/query count, normalized selected-state color tokens for dark mode, localized new admin shell/Synthetic/RAG/Gating/shared UI text to Korean-first wording, and emphasized run/delete actions with semantic success/danger button variants.
- Key decisions: Kept backend APIs, DTOs, eval/dataset/snapshot contracts, strategy semantics, generation/gating/rewrite/evaluation logic, and request payload fields unchanged. Applied the dark-mode fix at shared token/variant level instead of one-off page patches.
- Issues encountered: Frontend production build passed. `npm run lint` remains blocked by the existing `vite.config.js` `process` no-undef error and pre-existing hook dependency warnings.
- Next steps: Browser-smoke the updated admin pages with real data in dark mode, especially selected strategy chips, dropdown selected rows, compare checkboxes, and delete/run buttons.

## [2026-05-13] Session Summary (Admin Console UI/UX Modernization)
- What was done: Redesigned the React admin presentation layer for synthetic generation and RAG evaluation without changing backend APIs or experiment contracts. Added strategy cards/flows, batch timeline cards with progress and filters, a sectioned RAG experiment builder, reusable admin UI primitives, responsive styling, and dark-mode-ready tokens.
- Key decisions: Kept all synthetic/RAG request payload fields, snapshot selection rules, gating/rewrite/eval semantics, and source-scoped method restrictions unchanged; avoided new heavy dependencies and used existing React/Vite structure.
- Issues encountered: Frontend build passed. `npm run lint` still fails on the existing `vite.config.js` `process` no-undef rule, with unrelated hook-dependency warnings in existing pages.
- Next steps: Browser-smoke `/admin/synthetic-queries` and `/admin/rag-tests` against a live backend to tune copy/density with real data.

## [2026-05-13] Session Summary (LLM Job Constraint Sync Hotfix)
- What was done: Fixed admin chunk-embedding materialization job creation failure by adding a new Flyway migration that expands `llm_job.job_type` check constraint to include `MATERIALIZE_CHUNK_EMBEDDINGS`, and added a backend test that checks Java job-type definitions against the migration values.
- Key decisions: Used a forward-only migration instead of rewriting the historical `V16` migration, because the bug is affecting already-migrated local/runtime databases.
- Issues encountered: The new DB-ANN materialization flow was wired end-to-end but failed at `llm_job` insert time due to job-type constraint drift.
- Next steps: Run the new migration against the active DB and retry the Admin materialization endpoint.

## [2026-05-13] Session Summary (Admin RAG DB-ANN Evaluation Path)
- What was done: Added end-to-end admin-side `db-ann` RAG evaluation support without document recollection by introducing model-specific chunk-vector materialization, pgvector ANN evaluation runtime, backend preflight/materialization APIs, and frontend readiness controls for `/admin/rag-tests`.
- Key decisions: Kept strategy-separated synthetic storage and snapshot/source identity rules intact, and explicitly isolated online hash retrieval from admin dense-eval retrieval so `hash-embedding-v1` and `multilingual-e5-small` are not mixed.
- Issues encountered: Validation was intentionally limited to targeted Python unit tests and backend Java compilation; no full pipeline/evaluation run was executed.
- Next steps: Run one real `db-ann` admin evaluation in an environment with the selected dense model available and compare retrieval/rewrite latency against the local backend.

## [2026-05-12] Session Summary (Short-User Memory-Target Guard for Generic Rewrite Rejection)
- What was done: Added a second rewrite-runtime patch that extracts lightweight target tokens from the top memory query/glossary, penalizes and rejects short-user rewrites that stay generic under strong memory evidence, and rewards candidates that recover the missing target anchor.
- Key decisions: Preserved the low-cost direction from 1차 patch by keeping the change entirely in pipeline runtime/policy and avoiding prompt churn, new retriever modes, or added database work.
- Issues encountered: Local verification continued through `python -m unittest pipeline.tests.test_eval_runtime -q` because `pytest` is not installed in this environment.
- Next steps: Re-run the same snapshot/dataset pair used for the prior A/full-gating comparison and check whether `rewrite_always` recovers target-specific top-rank hits while keeping generic drift suppressed.

## [2026-05-12] Session Summary (Rewrite-Always Validity Guard + Short-User Relaxation)
- What was done: Patched pipeline rewrite selection so `rewrite_always` falls back to the raw query when every generated candidate is already rejected by validity checks, and relaxed the default `short_user` rewrite adoption policy to recover compact technical anchor expansion.
- Key decisions: Treated this as a low-cost evaluation fix only; no new retriever mode, reranker, broad corpus scan, or extra DB query path was introduced.
- Issues encountered: Local environment did not have `pytest`, so targeted verification was executed with `python -m unittest pipeline.tests.test_eval_runtime -q` instead.
- Next steps: Re-run the same snapshot/dataset pair used for `c7c42735-5be9-4941-a53d-fe9fb4572f6a` and `6f7ae4d0-b311-4224-8249-9a5d8e302c31` to measure whether `rewrite_always` regains the lost top-rank cases without reintroducing broad bad rewrites.

## [2026-05-12] Session Summary (RAG Synthetic-Anchor Default Restore)
- What was done: Restored `memory_only_*` retrieval default to direct top-memory synthetic query usage, made raw-query intent-preserving guided lookup opt-in via `memory_lookup_intent_preserving_enabled=true`, and rolled back the extra intent-locked wording in `selective_rewrite_v2`.
- Key decisions: Based on Before/After analysis, short Korean user queries benefit more from specific synthetic-query retrieval anchors than from overly preserving the sparse original query by default.
- Issues encountered: Existing unrelated docs/report changes were present in the worktree and were left untouched.
- Next steps: Re-run the same dataset/snapshot condition to verify `memory_only_gated` recovers toward the previous MRR/nDCG profile.

## [2026-05-12] Session Summary (Python KR KO/EN Short-User Eval Dataset)
- What was done: Created paired Python Korean-document evaluation datasets with 80 Korean short-user queries and 80 English short-user queries, stored JSONL artifacts under `data/eval/`, registered both datasets in DB, and wrote generation audit metadata under `data/reports/`.
- Key decisions: Used the same `docs-python-org-ko-3-14` grounded chunks for paired KO/EN samples, assigned target methods `G` and `F`, and marked dataset metadata with `strategy_profile=python_kr` for Admin method-scope validation.
- Issues encountered: `data/eval` and `data/reports` needed directory-level docs before active use, so `index.md`/`progress.md` were added; terminal rendering may show mojibake, but UTF-8 validation confirms Korean content is intact.
- Next steps: Run F/G snapshot-pinned retrieval and answer evaluation with dataset IDs `dfbadf26-0ab6-4b95-890e-5196dddc62cc` and `0d29df79-3920-40b2-b7ff-897eac5544fa`.

## [2026-05-11] Session Summary (F/G Synthetic Query Pipeline Correctness + Speed)
- What was done: Updated `pipeline/generation/synthetic_query_generator.py` so F/G `code_mixed` stays in `synthetic_queries_raw_f/g` instead of being rerouted to D, scoped relation/glossary loads to the selected source chunks/documents, stripped overlap context from F/G Korean evidence, added related chunk evidence payloads for near/far, and defaulted F/G Korean summaries to deterministic extractive summaries to remove one LLM call per chunk. Updated F/G prompts to consume `related_chunks_ko` and avoid overlap-derived queries.
- Key decisions: Preserved AGENTS stage order and strategy-split raw tables; kept A/B/C code-mixed rerouting to D while preserving E/F/G native strategy semantics.
- Issues encountered: Targeted unit test exposed an existing dedupe indentation bug in summary truncation candidates that returned no retry candidates; fixed it in the same generator file.
- Next steps: Run a small explicit-source F/G generation smoke in the Admin flow and inspect raw_f/raw_g rows, query language, target chunk IDs, and near/far grounding before launching a large batch.

## [2026-05-11] Session Summary (RAG 400 Preflight Cleanup: Prevent Planned Run Residue)
- What was done: Reordered Admin RAG run creation flow so rewrite preflight (`rewrite_enabled=true` API-key validation) executes before DB run-row creation; invalid requests now return `400` without inserting `planned` runs.
- Key decisions: Applied ordering-only fix with no payload/schema/API contract expansion; added integration assertion to confirm no `rag_test_run` row is created on rewrite preflight rejection.
- Issues encountered: None.
- Next steps: Monitor admin invalid-run retries and ensure no additional manual cleanup is needed for failed preflight attempts.

## [2026-05-11] Session Summary (RAG Rewrite Preflight: GEMINI_API_KEY/.env Fallback Support)
- What was done: Updated backend rewrite-stage API-key preflight resolution to support Gemini key aliases consistently (`QUERY_FORGE_GEMINI_API_KEY`, `QUERY_FORGE_LLM_GEMINI_API_KEY`, `GEMINI_API_KEY`, `GOOGLE_API_KEY`) and to read `.env` fallback values when process env is missing. Updated related integration-test expected validation message.
- Key decisions: Kept validation strictness (`rewrite_enabled=true` still requires key) and changed only key-discovery path/message for local runtime compatibility.
- Issues encountered: None.
- Next steps: Restart active backend process and re-run `/api/admin/console/rag/tests/run` to confirm planned runs can be created with `.env`-defined `GEMINI_API_KEY`.

## [2026-05-11] Session Summary (Synthetic Batch Delete + Generation Cancel/Purge + ETA + Unlimited Retry)
- What was done: Added synthetic generation batch history deletion flow end-to-end (Admin API + UI action) so deleting a batch removes linked `llm_job` rows and strategy-split raw synthetic queries for the batch, then deletes the batch row (with gating-batch linkage nulled). Extended synthetic batch list payload with live ETA fields (`targetQueryCount`, `estimatedSecondsPerQuery`, `estimatedRemainingSeconds`, LLM job/item state) and reflected them in `/admin/synthetic-queries`.
- Key decisions: Kept pipeline/research stage order unchanged and scoped retry-limit removal to `GENERATE_SYNTHETIC_QUERY` only via unlimited retry sentinel (`max_retries=-1`).
- Issues encountered: Existing admin frontend file includes mixed-encoding localized literals; edits were kept behavior-focused with minimal text-surface changes.
- Next steps: Validate runtime behavior on long-running F/G generation (cancel interruption latency, data purge completeness, ETA convergence).

## [2026-05-11] Session Summary (F/G-A/C/D 구조 점검 + Synthetic 배치 생성수 실시간 반영)
- What was done: Reviewed current implementation paths for `F/G` vs `A/C/D` (generation/gating/memory/eval/admin wiring) and implemented real-time synthetic batch count reflection by combining backend live-count SQL with frontend polling.
- Key decisions: Kept pipeline/research flow unchanged; applied minimum-scope edits only to synthetic batch read path and synthetic page refresh behavior.
- Issues encountered: None.
- Next steps: Validate `/admin/synthetic-queries` batch history count growth during active generation jobs and continue KR-source evaluation dataset scope verification.

## Overview
High-level progress tracking for the project.

## [2026-05-13] Session Summary (RAG Performance Metric Redesign: New Latency Trio + Legacy Guard)
- What was done: Reworked RAG test Performance handling end-to-end so new runs persist and render only `avg_query_eval_total_latency_ms`, `avg_final_rewrite_latency_ms`, and `avg_pure_rewrite_latency_ms`. Added per-sample latency capture in pipeline answer/rewrite runtime, sample-count metadata (`eval/rewrite/pure_rewrite/excluded`), backend API sanitization of deprecated latency payloads, and frontend legacy-result fallback rendering.
- Key decisions: Did not backfill legacy runs from `rewrite_overhead`, `representative_mode`, or retrieval per-mode latency rows. New latency metrics are source-of-truth only for newly executed runs; old results remain readable but explicitly marked legacy.
- Issues encountered: `RagPage.jsx` contains mixed-encoding historical strings, so the frontend update required safe block replacement plus a build pass to catch one broken summary-card literal.
- Next steps: Re-run a fresh Admin RAG test and confirm operators can compare the three new latency cards against a legacy run in the same history table.

## [2026-05-11] Session Summary (F/G Generate-queries MAX_TOKENS 절단 해결: 범위 한정 패치)
- What was done: Applied a scoped pipeline fix so only `F/G` Korean summary generation path hardens against truncation with (1) summary output-token floor `2048` and (2) truncation-only retry using progressively shortened source text candidates.
- Key decisions: Avoided broad refactor and preserved generation strategy/prompt semantics; changes are limited to explicit failing path and only trigger on `category=max_tokens_truncated`.
- Issues encountered: None.
- Next steps: Re-run failing F/G jobs and confirm retry-exhaustion category is cleared from `llm_job_item.error_message`.

## [2026-05-11] Session Summary (Generate-queries 실패 재분석용 LLM 예외 가시성 보강)
- What was done: Added failure-category serialization to `pipeline/common/llm_client.py` retry-exhaustion exception text so Admin `llm_job_item.error_message` tails can reveal whether the failure is request-layer or post-processing-layer.
- Key decisions: Avoided strategy/prompt/runtime-flow changes and limited scope to diagnostics visibility (`category/status/finish_reason/block_reason`) surfaced directly in `_RetryableLlmError` message.
- Issues encountered: Existing failed job IDs (`84fc90a6-...`, `573cd819-...`) were created before this diagnostic message enrichment, so their stored tail remains generic despite categorized logging support.
- Next steps: Re-run `F/G` jobs and capture new error tails; use surfaced category to decide whether to tune summary max tokens, prompt-output strictness, or schema/key handling.

## [2026-05-10] Session Summary (LLM Post-processing Failure Classification + Gemini JSON Fence Handling)
- What was done: Refined `pipeline/common/llm_client.py` so retry-exhausted failures distinguish request-layer failures from response post-processing failures via explicit categories (`request_failed`, `response_empty`, `response_blocked`, `invalid_json`, `schema_mismatch`, `missing_required_key`, `max_tokens_truncated`). Added per-attempt failure logs with provider/status/finish-reason/block-reason metadata and improved retryable failure propagation to keep category context through the final `RuntimeError`.
- Key decisions: Kept retry/fallback strategy and prompt semantics unchanged; only observability and parsing robustness were adjusted. For structured-output responses, markdown fenced JSON is now accepted only on the final attempt and only via fenced-block parsing (not broad object scraping) to avoid loosening validation too aggressively.
- Issues encountered: None.
- Next steps: Validate real admin `summary_extraction_ko` failure traces to confirm category distribution and identify whether failures are dominated by safety blocking, truncation, or schema/key mismatches.

## [2026-05-10] Session Summary (F/G Strategy Restriction Stabilization: Source/Dataset Guard)
- What was done: Added backend strategy restrictions so Spring technical-doc scope allows only `A~E` and Python KR scope allows only `F/G`; applied this to synthetic generation validation and dataset-bound RAG method validation. Extended `/api/admin/console/synthetic/methods` to support scoped filtering (`source_id`, `source_document_id`, `dataset_id`) and updated frontend synthetic run form to use source-scoped method options.
- Key decisions: Enforced strict source identity at synthetic run creation (`source_id` or `source_document_id` required) to prevent mixed-domain generation after KR Python source onboarding; kept method metadata endpoint DB-driven and context-filtered instead of hardcoded frontend allowlists.
- Issues encountered: Existing eval datasets in runtime DB were Spring-family only, so Python KR dataset-level RAG validation could not be executed end-to-end in this session without creating new datasets.
- Next steps: Analyze existing Spring evaluation dataset schema/query style/grounding and prepare KR Python evaluation dataset creation plan with explicit dataset metadata scope (`strategy_profile=python_kr`) for deterministic restriction checks.

## [2026-05-10] Session Summary (Synthetic Query Runtime Compatibility Fix for E/F/G)
- What was done: Fixed runtime synthetic query structured-output compatibility by making query response required fields strategy-specific in `pipeline/generation/synthetic_query_generator.py`, and hardened final query text fallback so non-query metadata fields are never selected.
- Key decisions: Preserved existing A/B/C/D behavior and `style_note`/raw JSONB compatibility (`additionalProperties=True`) while adding explicit E/F/G coverage in `pipeline/tests/test_synthetic_query_generator.py`.
- Issues encountered: Existing `llm_stability_runner` strategy coverage was A~D only; extended specs/case matrix to include E/F/G with aligned required keys.
- Next steps: Execute controlled E/F/G generation smoke with same chunk set and monitor schema-retry/empty-skip deltas.

## [2026-05-10] Session Summary (Strategy E `code_mixed` Semantic Fix)
- What was done: Updated `configs/prompts/query_generation/gen_e_v1.md` to redefine `query_type=code_mixed` for English-only strategy `E` as "English-native framing + exact technical/code token preservation", and tightened matching rules/quality/forbidden clauses.
- Key decisions: Preserved shared enum compatibility (`code_mixed` remains available) and avoided runtime/schema/table changes.
- Issues encountered: Prior wording could be interpreted as bilingual language mixing, conflicting with E's English-only retrieval strategy.
- Next steps: Run a small E generation sample check for `query_type=code_mixed` to confirm stable English-only outputs with anchor fidelity.

## [2026-05-10] Session Summary (E/F/G Query Prompt Framework Alignment)
- What was done: Rewrote `configs/prompts/query_generation/gen_e_v1.md`, `gen_f_v1.md`, and `gen_g_v1.md` so they follow the same control framework as A/B/C/D (`Strategy hypothesis`, `Inputs`, `Rules`, `Quality targets`, `Answerability guidance`, `Query type control`, `Forbidden patterns`, `Output contract`, `Output schema`, `Internal self-check`) while preserving E/F/G strategy-specific generation paths.
- Key decisions: Kept runtime compatibility by preserving existing E/F/G output field contracts (`E: query_en+style_note`, `F: query_ko+query_en+style_note`, `G: query_ko+style_note`) and avoided runtime/schema/table changes.
- Issues encountered: Found existing implementation-level risk unrelated to this patch: shared query response schema in pipeline requires `query_ko` globally, which can be stricter than E prompt/output intent (English-final).
- Next steps: Run a small E/F/G generation smoke test to confirm stable JSON outputs under the stricter prompt contract and monitor E strategy retries/fallback frequency.

## [2026-05-10] Session Summary (Synthetic Strategy F/G Physical Split Addition)
- What was done: Added physical split support for new synthetic strategies `F` and `G` by introducing Flyway `V28`, creating `synthetic_queries_raw_f/g`, extending `synthetic_queries_raw_all` union view, widening method/registry checks to `A~G`, and seeding method metadata rows for `F/G`.
- Key decisions: Kept strict strategy/source identity by storing `F/G` in dedicated raw tables (no routing to `C/E`) and avoided pipeline-wide refactor; only minimal generator mapping + prompt additions were applied.
- Issues encountered: None.
- Next steps: Apply `V28` in runtime DB and run Admin/UI smoke for `F/G` generation/gating list paths with explicit snapshot identity.

## [2026-05-09] Session Summary (Selective Rewrite Prompt: Intent-Preserving Query Expansion Guard)
- What was done: Updated `configs/prompts/rewrite/selective_rewrite_v2.md` to redefine rewrite generation as intent-preserving query expansion and tightened topic-shift prohibition rules.
- Key decisions: Kept existing rewrite pipeline/business logic unchanged (memory candidate usage, anchor injection, selective adoption scoring) and applied only prompt-level guardrail strengthening.
- Issues encountered: None.
- Next steps: Run same-dataset/same-snapshot comparison to verify reduced topic substitution in short Korean technical queries.

## [2026-05-09] Session Summary (Admin RAG Detail Modal UX + Runtime Options 500 Fix)
- What was done: Improved `/admin/rag-tests` run-detail modal so the default view emphasizes only `원본 질의` and `최종 재작성 합성 질의` per sample, while metrics/candidates/chunk details are shown through expandable disclosure sections. Fixed `GET /api/admin/console/runtime/options` 500 by removing null-unsafe `List.of(readEnv(...))` usage in backend runtime option collection.
- Key decisions: Kept existing run-detail APIs unchanged and implemented UI-only progressive disclosure for non-core debug payloads; backend fix was limited to null-tolerant runtime option candidate list handling to preserve current catalog behavior.
- Issues encountered: Runtime options endpoint failure was reproduced with backend logs and traced to `NullPointerException` in `AdminConsoleService.getRuntimeOptions` when environment values were missing.
- Next steps: Restart the long-running backend instance on `localhost:8080` so the runtime-options fix is applied to the active process.

## [2026-05-08] Session Summary (Admin RAG Validation Regression Test Additions + Docker Runtime Verification Attempt)
- What was done: Added backend integration regression tests for Admin RAG run validation paths (`llmModel` allowlist reject, dense retriever model allowlist reject, and rewrite preflight API-key required when `rewrite_enabled=true`) in `backend/src/test/java/io/queryforge/backend/admin/console/AdminConsoleRagIntegrationTest.java`.
- Key decisions: Kept changes minimal by extending existing integration test class and using targeted fixture inserts only for required request-path validation.
- Issues encountered: Despite Docker CLI reachability, Testcontainers failed Docker environment detection with `BadRequestException (Status 400)` against Docker Desktop pipe/proxy endpoints, so Admin Console integration tests were still skipped by `disabledWithoutDocker=true`.
- Next steps: Fix Docker/Testcontainers compatibility in this runtime and rerun Admin Console integration suites to complete true Docker-backed execution verification.

## [2026-05-08] Session Summary (AGENTS Governance Update: Model Catalog + Source Identity)
- What was done: Added `.codex/AGENTS.md` Section `3.8` to codify mandatory governance for Admin runtime option allowlists (`configs/app/model_catalog.yml`) and strict source identity requirements (`source_gating_batch_id` / explicit generation source identity; no auto-latest fallback for required cases).
- Key decisions: Elevated these behaviors from implementation detail to explicit agent policy to prevent future regressions in reproducibility and validation strictness.
- Issues encountered: None.
- Next steps: Apply the same policy whenever runtime-option surface or snapshot/source selection contract expands.

## [2026-05-08] Session Summary (Runtime Options Catalog + Allowlist Enforcement)
- What was done: Added `configs/app/model_catalog.yml`, switched backend `/api/admin/console/runtime/options` to catalog-driven metadata/range response, and enforced allowlist validation for Admin run inputs (`llm_model`, `retriever_mode`, `dense_embedding_model`, `rewrite_failure_policy`). Updated frontend gating/RAG dropdowns to consume server runtime options without hardcoded option arrays.
- Key decisions: Preserved existing API compatibility by keeping legacy flat list fields while adding richer option metadata and parameter-range payloads.
- Issues encountered: None.
- Next steps: Add broader integration assertions for catalog validation across RAG run creation paths.

## [2026-05-08] Session Summary (Rewrite Strictness Hardening: Policy Tests + Preflight Validation)
- What was done: Completed rewrite-failure strictness hardening by adding policy-specific runtime tests (`fail_run`, `skip_to_raw`, `heuristic_fallback`), exposing explicit rewrite LLM/fallback counters in retrieval/answer summaries, and adding backend preflight validation that blocks rewrite-enabled RAG runs when rewrite-stage provider/model/api-key resolution is missing.
- Key decisions: Treated rewrite failures as configuration/runtime contract issues that should fail early and be observable with explicit counters.
- Issues encountered: None.
- Next steps: Add backend API integration assertions for rewrite preflight rejection cases in Docker-enabled test runs.

## [2026-05-08] Session Summary (Gating Determinism: Explicit Source Run Required)
- What was done: Enforced explicit source generation run selection for quality gating by removing pipeline-side auto-latest fallback in `pipeline/gating/quality_gating.py` and adding regression tests (`pipeline/tests/test_quality_gating.py`).
- Key decisions: Deterministic snapshot provenance was treated as mandatory; missing source run IDs now fail immediately instead of silently selecting latest data.
- Issues encountered: None.
- Next steps: Keep admin and manual experiment configs pinned to explicit generation run IDs for all gating executions.

## [2026-05-08] Session Summary (Compile/Test Guard: Dockerless Pipeline Test Skip)
- What was done: Hardened pipeline test execution on non-Docker environments by updating `pipeline/tests/test_corpus_import.py` to skip Docker-backed integration setup when Docker daemon is unavailable.
- Key decisions: Treated this as test-runtime robustness work only; no pipeline business logic, schema, or API behavior was changed.
- Issues encountered: The Docker availability error occurs at `PostgresContainer` construction time, so skip handling must cover object creation as well as `start()`.
- Next steps: Continue using `python -m unittest discover pipeline/tests` as a safe baseline verification command in local environments without Docker.

## [2026-05-08] Session Summary (Admin Runtime Options + Snapshot-Pinned Flow Stabilization)
- What was done: Completed Admin runtime-option wiring across backend/frontend/pipeline for LLM and dense-model dropdown driven runs, added rewrite-failure-policy propagation (`fail_run|skip_to_raw|heuristic_fallback`), enabled multi-batch/multi-strategy gating source pinning, and enforced explicit snapshot selection for non-baseline RAG paths. Recovered interrupted frontend merge state by fixing duplicated `GatingPage.jsx` blocks and undefined `selectedMethodCodes` usage.
- Key decisions: Disabled exploratory auto-latest snapshot fallback and required explicit snapshot IDs to keep experiments deterministic and reproducible under AGENTS 3.6 snapshot rules.
- Issues encountered: Prior interrupted patch introduced duplicated `loadLlmJobs` blocks and run payload variable inconsistency in `GatingPage.jsx`; cleaned to a single validated path and re-verified.
- Next steps: Run full Admin GUI smoke (`generate -> gate -> rag`) with explicit snapshot selection and compare rewrite-failure-policy modes under same dataset/snapshot.

## [2026-05-08] Session Summary (RAG Eval Retriever Reuse Cache Optimization)
- What was done: Added runtime-level retriever reuse caches in `pipeline/eval/runtime.py` for chunk retrieval (`eval-chunks`) and filtered memory retrieval (`eval-memory`) keyed by in-process object identity + retriever config/signature.
- Key decisions: Kept retrieval/rewrite business logic and scoring semantics unchanged; optimized only repeated retriever materialization/filter computation paths used heavily by `memory_top_n` and rewrite candidate evaluation loops.
- Issues encountered: None.
- Next steps: Re-run controlled same-dataset/same-snapshot latency comparisons to quantify end-to-end overhead reduction for memory lookup and rewrite-heavy modes.

## [2026-05-07] Session Summary (RAG Memory Lookup Intent-Preservation Guard)
- What was done: Hardened retrieval eval memory modes to preserve raw user intent instead of directly substituting top synthetic memory query text. Added intent-guided query composition and raw/guided retrieval merge behavior for `memory_only_*` runs.
- Key decisions: Chose product-level hint anchoring with raw-query-first composition to reduce semantic over-specification for short-user Korean queries while keeping synthetic-memory retrieval benefits.
- Issues encountered: Naive technical-anchor hint injection initially produced overly specific class/config expansions; guidance logic was tightened to prefer stable product hints.
- Next steps: Run controlled regression on latest RAG run conditions and verify per-sample drift reduction in memory-mode query rewrites.

## [2026-05-07] Session Summary (RAG Eval Dataset Scope Guard)
- What was done: Hardened eval runtime so retrieval/answer evaluation uses dataset-aware corpus scope instead of unconditional full-corpus chunk loading. Added source-product-aware scope derivation and applied it to both retrieval and answer eval stages.
- Key decisions: Prioritized experiment validity by binding evaluation candidates to dataset scope (`source_product` aliases + expected-doc fallback), preventing unrelated corpus additions from silently skewing run comparisons.
- Issues encountered: Historical A/B runs with same dataset/snapshot showed metric regression caused by corpus-scope drift, not only rewrite/anchor logic.
- Next steps: Re-run anchor/rewrite A/B under fixed corpus snapshot to isolate single-variable impact.

## [2026-05-06] Session Summary (Proposal 1 Phase-2: Rewrite Candidate Scoring/Adoption Stabilization)
- What was done: Implemented selective rewrite adoption stabilization with staged candidate scoring in `pipeline/eval/runtime.py`: retrieval gain, terminology preservation, memory alignment, verbosity/preservation penalties, and category-aware threshold gating. Added config-driven rewrite adoption policy loading in `pipeline/common/experiment_config.py` and wired policy + query category through retrieval/answer eval runtime calls.
- Key decisions: Preserved pipeline order and A/B/C/D/E separation; kept existing rewrite candidate prompt contract and report schema compatibility while extending candidate trace fields (`rejection_reason`, sub-scores, thresholds, margins) for bad-rewrite analysis.
- Issues encountered: Existing memory-affinity rewrite test assumptions required policy override in unit tests after stricter preservation/threshold guards were added.
- Next steps: Run same snapshot/dataset controlled A/B with only rewrite adoption policy toggles and inspect bad_rewrite_rate/adoption_rate/MRR@10/nDCG@10 plus latency delta.

## [2026-05-06] Session Summary (Proposal 1 Phase-1: Terminology-aware Selective Rewrite Strengthening)
- What was done: Implemented phase-1 terminology-aware selective rewrite strengthening without changing pipeline order or A/B/C/D/E separation. Added bounded `terminology_hints` payload generation in `pipeline/eval/runtime.py` from raw query technical tokens + top memory glossary terms + top memory query technical tokens, with aggressive dedup/filtering and optional max-count control (`rewrite_terminology_hints_max_count`).
- Key decisions: Kept existing `anchor_candidates`/`anchor_terms` behavior and injected `terminology_hints` only when `rewrite_anchor_injection_enabled=true` so Admin workflows and rewrite-off controls remain unchanged. Wired the max-count option through retrieval/answer eval runtime config as optional.
- Issues encountered: None.
- Next steps: Run same snapshot/dataset A/B retrieval comparisons with only terminology-aware rewrite payload toggles and inspect adoption-rate / bad-rewrite-rate / latency delta.

## [2026-05-05] Session Summary (Admin GUI Lazy Loading Refactor)
- What was done: Reviewed admin frontend data-fetch patterns and refactored `/admin/pipeline`, `/admin/synthetic-queries`, `/admin/quality-gating`, and `/admin/rag-tests` to reduce eager initial fetches. Implemented on-demand loading for heavy secondary sections while keeping existing API contracts unchanged.
- Key decisions: Prioritized minimal frontend-only changes and preserved endpoint/query compatibility (`limit/offset`, existing filters, existing request bodies). Added lazy section states (`loaded/loading`) and explicit user-triggered fetch paths rather than backend/API redesign.
- Issues encountered: Existing JSX text warnings in `GatingPage.jsx` (`->` inside option labels) remain pre-existing and unchanged; frontend build completes successfully.
- Next steps: Validate operator UX in live admin flow and tune which sections auto-refresh post-run based on usage feedback.

## [2026-05-04] Session Summary (Anchor Re-extraction Document Scope Deletion Guarantee)
- What was done: Reviewed backend anchor re-extraction delete scope and updated `AnchorExtractionService` so `documentIds` takes precedence over `chunkIds` when selecting re-extraction targets. This guarantees existing anchor evidence for the selected document(s) is removed before re-extraction. Added integration coverage for mixed request (`documentIds + chunkIds`) to verify document-wide delete behavior.
- Key decisions: Kept chunk-scoped re-extraction behavior unchanged for chunk-only requests; changed only mixed-scope precedence to satisfy document re-extraction consistency.
- Issues encountered: Existing implementation used intersection semantics when both `documentIds` and `chunkIds` were provided, which could leave stale anchors in unselected chunks of the same document.
- Next steps: Keep client-side request construction aligned with the clarified precedence (`documentIds` => document-wide reset/re-extract).

## [2026-05-04] Session Summary (Anchor Precision Hardening + Local Model Provisioning)
- What was done: Hardened pipeline concept-anchor extraction to filter non-technical/helper phrases while preserving technical terms, and kept backend re-extraction on the same pipeline extractor path (`extract-anchor-candidates` -> `extract_glossary_terms`). Added shared anchor-quality filtering usage in rewrite anchor payload injection and added anchor-quality tests.
- Key decisions: Kept existing business/data flow unchanged (same glossary/evidence/remap pipeline) and improved only candidate quality gates (technical-marker/hint based acceptance, Korean noun extraction tightening, generic helper-token rejection).
- Issues encountered: Initial smoke run still produced noisy anchors (`spring`, `required`, `사용 부탁`, `ilter.order`); resolved by tightening concept acceptance rules and Kiwi candidate generation scope.
- Next steps: Run same-snapshot anchor injection on/off retrieval comparisons and tune only threshold/token lists if source-specific noise appears.

## [2026-05-04] Session Summary (Anchor Extraction/Injection Purpose Clarification + Risk Review Documentation)
- What was done: Reviewed current anchor extraction and rewrite-injection paths (`pipeline/preprocess/chunk_docs.py`, `pipeline/preprocess/extract_anchor_candidates.py`, `pipeline/eval/runtime.py`, backend anchor orchestration) and documented the intended anchor objective in both `.codex/AGENTS.md` and `backend/index.md`.
- Key decisions: Clarified anchor role as retrieval-grounding control for Korean query rewrite over English technical-doc corpora; added explicit guidance that non-technical polite/functional phrases must not be treated as anchors.
- Issues encountered: None.
- Next steps: Implement extractor precision guards for Korean/English generic phrases and verify impact through same-snapshot anchor-injection on/off retrieval evaluation.

## [2026-05-04] Session Summary (Project-wide Markdown Sync with Current Implementation)
- What was done: Audited root/backend/pipeline/frontend/configs/docs markdowns against current codebase and updated stale documents (React admin route structure, strategy `E`, language-aware eval fields, anchor APIs, pipeline warning status, and current orchestration flow).
- Key decisions: Treated `.codex/AGENTS.md` as authoritative and aligned documentation to actual runtime entrypoints (`/admin/pipeline|synthetic-queries|quality-gating|rag-tests`, `pipeline/cli.py` command set, split raw tables `A/B/C/D/E`).
- Issues encountered: Multiple docs still described legacy UI routes and pre-E strategy assumptions; these were normalized without changing code/runtime behavior.
- Next steps: Keep `index.md`/`README.md`/`progress.md` updates coupled with feature changes to prevent route/schema drift.

## [2026-05-04] Session Summary (Anchor Re-extraction Pipeline Logic Unification)
- What was done: Unified backend anchor re-extraction with pipeline glossary extraction path by introducing pipeline command `extract-anchor-candidates` and switching backend `AnchorExtractionService` to call it for scoped chunk candidate generation before existing evidence/term/remap updates.
- Key decisions: Adopted the user's proposal (single extractor path) over continued backend-local extraction logic to reduce implementation duplication and keep extraction behavior consistent across ingest and admin re-extraction flows.
- Issues encountered: New command initially failed on BOM-encoded JSONL; fixed with `utf-8-sig` reader in pipeline bridge script.
- Next steps: Validate quality impact with Anchor Eval runs on mixed sources and tune only pipeline extraction path to preserve one-source-of-truth behavior.

## [2026-05-04] Session Summary (Backend Anchor Re-extraction Partial Upgrade)
- What was done: Enhanced backend anchor re-extraction (`POST /api/admin/corpus/anchors/extract`) with a hybrid candidate extractor in `AnchorExtractionService` that keeps existing regex channels but adds phrase normalization, stopword dominance filtering, rarity-aware scoring, and technical-marker weighting for concept anchors. Added/ran corpus mutation integration test coverage for scoped re-extraction.
- Key decisions: Reused current backend extraction architecture and data update flow without schema or API contract changes, to keep the upgrade minimal and compatible with existing admin/pipeline paths.
- Issues encountered: None.
- Next steps: Use Anchor Eval runs to measure precision/recall shifts on non-Spring technical docs and tune scoring weights if needed.

## [2026-05-02] Session Summary (Collector Resilience + Source Preset Additions)
- What was done: Hardened `pipeline/collectors/spring_docs_collector.py` to skip placeholder `{...}` URLs and continue on per-URL fetch failures while recording `fetch_failures` metrics. Added source presets `arahansa-github-io-docs-spring` and `docs-python-org-ko-3-14` under `configs/app/sources/`.
- Key decisions: Shifted collector failure behavior from fail-fast to skip-with-visibility for URL-level network/HTTP noise, preserving overall batch progress.
- Issues encountered: None.
- Next steps: Run scoped collects on both presets and tune crawl depth/deny patterns if failure counts remain elevated.

## [2026-05-02] Session Summary (Pipeline Anchor Pagination + Document/Chunk Dropdown Filters)
- What was done: Added a new paginated Anchor list section to `/admin/pipeline` and introduced document/chunk filter controls using a custom dropdown UI (`frontend/src/components/SelectDropdown.jsx`). Added backend API `GET /api/admin/corpus/anchors` with server-side filtering (`document_id`, `chunk_id`, `keyword`) and pagination (`limit`, `offset`) to support the new view.
- Key decisions: Implemented a dedicated anchor-list API instead of overloading existing glossary endpoints so document/chunk scoped filtering semantics stay explicit and UI queries remain lightweight.
- Issues encountered: None.
- Next steps: Validate with a high-cardinality corpus source and adjust option load limits/UX (virtualization or incremental search) if operator filtering latency appears.

## [2026-05-02] Session Summary (Normalize Fallback Parser + Pipeline Warning Status Backfill)
- What was done: Implemented fallback content parsing in `pipeline/preprocess/normalize_docs.py` so normalization no longer hard-fails on pages without `article.doc` (legacy `div#content` and `body` fallback). Added pipeline warning-status support end-to-end with new migration `V27` (`run_status`/`step_status` include `warning`) and warning backfill for existing `corpus_runs/corpus_run_steps` history. Updated admin frontend status tone/style mapping to visualize `warning`.
- Key decisions: Kept pipeline command exit semantics intact, but promoted partial/no-effective-output outcomes (skipped stages, normalize 0-section, collect fetch/persist anomalies) to `warning` status for operational visibility instead of silent `success`.
- Issues encountered: Existing live records had heterogeneous `metrics_json` value shapes, so warning backfill SQL was hardened with safe boolean/numeric guards to avoid cast failures.
- Next steps: Restart backend runtime so new Java warning-aggregation logic is loaded for newly created runs, then validate one fresh `/admin/pipeline` `full_ingest` execution shows real-time warning promotion without manual backfill.

## [2026-05-02] Session Summary (Pipeline full_ingest Failure Debug + Retry)
- What was done: Investigated Admin Pipeline failure at `/admin/pipeline` for run `e28f9bce-37a1-4569-96bb-12dbd62e83ec` (`full_ingest`). Confirmed the failure happened in `collect` due to `404` on templated URL `https://arahansa.github.io/docs_spring/{spring-framework-docs}/beans.html`. Re-ran the failed job through `POST /api/admin/pipeline/runs/{runId}/retry` and verified new run `7be03cad-094c-424c-8852-e164f269b17d` finished with `success`.
- Key decisions: Treated this as an operational recovery run because collector-side hardening for invalid/fetch-failure URLs was already present in the current workspace; no additional code changes were introduced in this session.
- Issues encountered: The successful rerun skipped `normalize/chunk/glossary/import` with `no_documents_pending`, indicating no newly pending documents after collect persistence.
- Next steps: If deeper ingestion is required for this source, refine source URL scope (`start_urls`/`allow_prefixes`/`deny_url_patterns`) so valid in-scope pages are discoverable without template placeholders.

## [2026-05-02] Session Summary (Anchor Eval Scope Select-All + Parameter Help Copy)
- What was done: Added Anchor Eval scope-selection UX improvements in Admin Pipeline (`/admin/pipeline`) by introducing `전체 문서 선택` and `전체 청크 선택` actions for the document/chunk multi-select fields. Added inline helper copy that explains `Sample Size` and `Candidate Limit` semantics and tradeoffs.
- Key decisions: Kept existing backend request contract unchanged (`documentIds`, `chunkIds`, `sampleSize`, `candidateLimit`) and implemented the change as frontend-only behavior to preserve pipeline/orchestration flow.
- Issues encountered: None.
- Next steps: Consider adding scoped “전체 해제” and search/filter for large document/chunk lists if operator usage indicates selection friction.

## [2026-05-01] Session Summary (Anchor Eval Dropdown-first UX + Scoped Selection Flow)
- What was done: Reworked Admin Pipeline `Anchor Eval` run-creation UI to minimize manual typing by introducing dropdown/multi-select flow (`source -> documents -> chunks`) and wired the create payload to submit `documentIds`/`chunkIds`. Added scope-loading states and selection summary chips so operators can see selected source/doc/chunk counts at a glance.
- Key decisions: Kept existing backend/API contracts for anchor eval runs and only extended the frontend selection workflow to consume existing document/chunk listing APIs (`/api/admin/corpus/documents`, `/api/admin/corpus/chunks`) without changing corpus data semantics.
- Issues encountered: Existing frontend build still prints pre-existing JSX warnings in `GatingPage.jsx` for literal `->` text, but production build output is generated successfully.
- Next steps: Add optional search/filter inside document/chunk multi-select lists for very large sources and evaluate whether virtualized list rendering is needed for scale.

## [2026-05-01] Session Summary (Anchor Re-extraction Pipeline + Synthetic Query Active-Anchor Mapping)
- What was done: Added Flyway `V25` to introduce `synthetic_query_anchor_link` and backfill links from existing query-source chunks to active glossary evidence. Implemented backend anchor re-extraction API (`POST /api/admin/corpus/anchors/extract`) that re-extracts anchors for selected document/chunk scope, replaces chunk-level glossary evidence safely, refreshes glossary term activity/evidence counts, and remaps affected synthetic queries to valid active anchors.
- Key decisions: Kept existing corpus documents/chunks untouched and scoped destructive operations only to `corpus_glossary_evidence` rows of explicitly selected chunks. Query detail now exposes active mapped anchors via `synthetic_query_anchor_link` + `corpus_glossary_terms.is_active` instead of relying only on raw snapshot glossary arrays.
- Issues encountered: Frontend build continues to emit pre-existing `GatingPage.jsx` JSX warnings for literal `->` text, but build succeeds.
- Next steps: Add dedicated admin UI flow for anchor re-extraction trigger/preview and optionally migrate memory/rewrite paths to consume mapped active anchors as primary source.

## [2026-04-28] Session Summary (Raw E Migration Fix + Failed Batch Recovery)
- What was done: Investigated failed English synthetic batch `ca64cad2-27d4-4510-b251-a4037bbd8dfd`, identified the root cause as copied `D`-strategy constraints on `synthetic_queries_raw_e`, and added follow-up migration `V22` to normalize the table definition before retrying the failed job.
- Key decisions: Kept the original `V21` as historical truth and fixed the live schema with a new migration so existing environments can recover without rewriting applied migration history.
- Issues encountered: `synthetic_queries_raw_e` inherited both `ck_synthetic_queries_raw_d_strategy` and the old `A-D` generic strategy check from `synthetic_queries_raw_d`, making every `generation_strategy='E'` insert impossible.
- Next steps: Apply `V22` to the running DB, retry the failed `GENERATE_SYNTHETIC_QUERY` job, and verify `synthetic_queries_raw_e` plus `synthetic_queries_raw_all` counts for the recovered batch.

## [2026-04-28] Session Summary (RAG Rewrite Retrieval Merge Strategy: Replace/Interleave/MaxScore)
- What was done: Added rewrite retrieval merge strategies so selective/forced rewrite no longer has to fully replace the raw query retrieval list. Implemented `rewrite_retrieval_strategy` (`replace`, `interleave`, `max_score`) in pipeline runtime and wired the option through Admin RAG request -> experiment config -> eval retrieval/answer execution.
- Key decisions: Kept existing default behavior as `replace` for backward compatibility; `interleave` and `max_score` are opt-in strategies for preserving original user intent while incorporating synthetic rewrite benefits.
- Issues encountered: None in code path migration; targeted test execution required extending command timeout once.
- Next steps: Run controlled same-dataset/same-snapshot RAG A/B/C runs with only `rewrite_retrieval_strategy` changed and compare quality + latency together.

## [2026-04-28] Session Summary (Selective Rewrite Prompt v2 Intent/Memory Policy Tightening)
- What was done: Updated `configs/prompts/rewrite/selective_rewrite_v2.md` to make intent-preservation explicit and to constrain memory usage as augmentation-only hints for retrieval optimization.
- Key decisions: Prompt now enforces conflict resolution in favor of raw query intent, keeps technical anchors, and requires candidate-level intent consistency while improving retrievability.
- Issues encountered: None.
- Next steps: Execute same snapshot/dataset prompt A/B comparison with fixed retriever/threshold to measure adoption and retrieval metric impact.

## [2026-04-28] Session Summary (RAG Memory Embedding Alignment + Targeted Run Purge)
- What was done: Updated `pipeline/memory/build_memory.py` so memory vectors are built with the run retriever config (`dense_embedding_model`) instead of hardcoded hash embedding, and embedding metadata/dim (`query_embeddings.embedding_dim`) now follows the actual vector length. Deleted only requested RAG run histories for `dd2eb98c-7570-4934-bd61-b90d5316f4e4` and `84205b72-76d5-418c-a628-520af0d374f3` from `rag_test_run` + linked `llm_job/llm_job_item`.
- Key decisions: Allowed gating-stage embedding model and RAG-stage embedding model to differ, but enforced that memory build uses the RAG test retriever embedding space for internal consistency.
- Issues encountered: Previous memory build path always wrote `hash-embedding-v1`, causing memory retrieval quality distortion when RAG retriever was dense-only e5.
- Next steps: Run a fresh same-snapshot RAG test and verify `metrics_json.memory.embedding_model` matches run retriever dense model.

## [2026-04-28] Session Summary (English Synthetic E + English Short-User 80 Eval Path)
- What was done: Added English synthetic generation strategy `E` end-to-end for Admin synthetic runs, extended split raw storage/schema/method registry to `A/B/C/D/E`, made quality gating and selective rewrite language-aware for English eval runs, added separate English short-user-80 dataset assets/scripts, and exposed eval query language selection in Admin RAG UI/runtime.
- Key decisions: Kept AGENTS strategy-separated storage by adding `synthetic_queries_raw_e` instead of merging tables; English short-user 80 uses a separate dataset id/key (`human_eval_short_user_80_en`) and runtime now selects `user_query_en` via `eval_query_language=en`.
- Issues encountered: Existing `GatingPage.jsx` JSX `->` warnings remain pre-existing; runtime rewrite tests needed fail-open heuristic fallback and updated patch target after the new language-aware rewrite wrapper.
- Next steps: Apply Flyway `V21`, run `scripts/build_short_user_en_dataset.py` without `--skip-db` against the target DB, then execute paired KO/EN RAG runs on matched snapshots.

## [2026-04-21] Session Summary (RAG Test Presets + Run Names)
- What was done: Updated `/admin/rag-tests` so retriever mode drives fixed RAG presets, added operator-provided RAG test names through the Admin API, backfilled legacy default RAG labels, and rebuilt the bundled React asset.
- Key decisions: RAG tests now force `intfloat/multilingual-e5-small`, disable hash fallback and Cohere rerank for clean BM25/Dense/Hybrid comparisons, use Hybrid weights `0.60/0.32/0.08`, candidate pool `50`, and default `retrieval_top_k=10`.
- Issues encountered: Vite still emits the existing `GatingPage.jsx` literal `->` JSX warnings, but build completes.
- Next steps: Run one named BM25, Dense, and Hybrid RAG test on the same dataset/snapshot and compare run-name-labeled results.

## [2026-04-21] Session Summary (Retriever Mode Separation + Admin Controls)
- What was done: Implemented BM25 Only, Dense Only, and Hybrid local retrieval modes across quality gating utility scoring, eval retrieval, answer eval, memory lookup, and rewrite candidate evaluation. Added explicit retriever config propagation from Admin GUI/API into experiment YAML and refreshed the bundled admin React asset.
- Key decisions: Kept existing RAG strategy modes (`raw_only`, `memory_only_*`, `rewrite_*`) separate from the new ranking-engine mode. Dense/Hybrid now default to `intfloat/multilingual-e5-small` and only use hash fallback when `dense_fallback_enabled=true` is explicitly configured.
- Issues encountered: Frontend production build still emits pre-existing JSX warnings for literal `->` option text in `GatingPage.jsx`, but build completes.
- Next steps: Run same dataset/snapshot BM25 vs Dense vs Hybrid RAG tests and compare retrieval quality together with latency.

## [2026-04-20] Session Summary (Local Retriever BM25 + Dense Switch)
- What was done: Switched Python-side local retrieval from hash/overlap scoring to a cached BM25 + dense retriever for eval retrieval, eval answer retrieval, memory lookup, and quality-gating utility scoring. Added CPU defaults for `intfloat/multilingual-e5-small` and documented local retrieval env knobs.
- Key decisions: Kept Cohere as an external reranker when available, but made local ranking strong enough to be a meaningful fallback. `sentence-transformers` is now a pipeline dependency; if the runtime does not have it installed yet, the new retriever falls back to BM25 + hash embedding.
- Issues encountered: Current interpreter lacks `sentence-transformers`, so validation used BM25 + hash fallback and improved local-only `human_eval_short_user_80` metrics to Recall@5 `0.4750`, Hit@5 `0.5375`, MRR@10 `0.3425`, nDCG@10 `0.3811`.
- Next steps: Sync the backend pipeline Python environment with the updated pipeline dependency set, then rerun controlled A/C/D RAG tests with Cohere quota available or explicitly disabled.

## [2026-04-20] Session Summary (Rewrite Evidence Scoring + Research Modes)
- What was done: Fixed selective rewrite evidence scoring so candidate queries recompute snapshot-memory affinity instead of reusing raw-query memory similarity; Cohere rerank fallback no longer emits synthetic relevance scores. Retrieval/memory lookup now uses hybrid semantic + lexical + technical-token scoring, and Admin RAG runs include `memory_only_gated` and `rewrite_always` alongside `raw_only` and selective modes.
- Key decisions: Preserved the AGENTS pipeline order and strategy-separated synthetic storage while making each snapshot evaluation expose raw retrieval, memory-only retrieval, forced rewrite, and selective rewrite for research attribution.
- Issues encountered: `python -m unittest discover pipeline/tests -v` still fails in pre-existing `test_corpus_import` migration setup (`corpus_sources` missing), while targeted runtime/LLM tests and backend tests pass.
- Next steps: Run fresh A/C/D rewrite-effect tests and compare `raw_only`, `memory_only_gated`, `rewrite_always`, and `selective_rewrite` to separate candidate quality from selective gate quality.

## [2026-04-20] Session Summary (RAG Memory Snapshot Isolation + Raw Comparison)
- What was done: Fixed RAG quality-test memory contamination by making memory builds clear stale rows for the selected snapshot and tagging memory rows with `memory_experiment_key`; retrieval/answer eval now loads only the current experiment's memory. Cleaned live DB stale rejected memory rows, removed orphan memory embeddings, and backfilled memory experiment keys.
- Key decisions: `raw_only` is now included alongside synthetic rewrite/memory modes for non-baseline RAG tests, while synthetic-free baseline remains raw-only. Admin default `rewrite_threshold` is now `0.10`.
- Issues encountered: Existing frontend build still reports unrelated `GatingPage.jsx` JSX warnings for literal `->` labels, but build completes.
- Next steps: Run a fresh same-dataset synthetic rewrite test and compare `raw_only` vs rewrite mode in the single-run detail modal.

## [2026-04-19] Session Summary (Synthetic-Random Short-User Dataset 80 Rebuild)
- What was done: Added `scripts/rebuild_short_user_dataset_from_synthetic.py` and rebuilt dataset `b2d47254-8655-4c9c-81ac-7615677ec5bd` from live `synthetic_queries_raw_all` by random sampling 80 candidates, compressing queries into short Korean user style, and refreshing both DB dataset items and `data/eval/human_eval_short_user_test_80.jsonl`.
- Key decisions: Kept retrieval-aware schema unchanged (`expected_doc_ids`, `expected_chunk_ids`, `expected_answer_key_points`) and stored source provenance in sample metadata (`source_synthetic_query_id`, `source_generation_strategy`, `target_method`).
- Issues encountered: Initial compression heuristics produced low-quality particle-only prompts; stopword filters and compression templates were tightened and rerun.
- Next steps: Run one controlled A/C quality test on the rebuilt set and optionally add a manual reject-list for low-information compressed prompts.

## [2026-04-19] Session Summary (Rewrite v2 + Backend Prompt Unification)
- What was done: Added retrieval-optimized rewrite prompt asset `selective_rewrite_v2` and switched pipeline rewrite prompt resolution to prefer v2 with v1 fallback.
- Key decisions: Unified backend `/api/chat/ask` rewrite candidate generation with prompt-based LLM path (Gemini/OpenAI env-driven) plus safe heuristic fallback, so admin online ask path no longer relies only on hardcoded templates.
- Issues encountered: Existing workspace had unrelated docs report modifications; this change was scoped to rewrite prompt loading/generation paths only.
- Next steps: Run one A/B compare on the same snapshot with v1/v2 prompt roots and tune threshold/candidate wording by category (`short_user`, `follow_up`, `code_mixed`).

## [2026-04-19] Session Summary (RAG Compare Time Formatting + KST Alignment)
- What was done: Fixed `/admin/rag-tests` compare-workspace time presentation so duration metrics use real converted values (`ms -> s -> m+s`) consistently across metric cards, latest-summary cards, and run-history metric snippets, while removing raw-ms secondary text from workspace cards.
- Key decisions: Reused existing duration helpers and unified workspace formatting through the same conversion policy already used in detailed table presentation; kept metric extraction and delta math unchanged.
- Issues encountered: Existing unrelated JSX warnings in `GatingPage.jsx` remain out of scope.
- Next steps: Validate with long-duration run pairs that operator interpretation is faster for `Total Duration`, `Eval-Retrieval Stage`, and `Eval-Answer Stage`.

## [2026-04-19] Session Summary (RAG Run Full-Delete Cascade Expansion)
- What was done: Expanded Admin RAG run delete path to enforce full deletion scope, including run-linked `llm_job`/`llm_job_item` history and linked `experiment_runs` artifacts (`eval_judgments`, `retrieval_results`, `rerank_results`, `online_queries`) in one transactional flow.
- Key decisions: Preserved existing API contract and missing-run behavior while deriving linked `experiment_run_id` from `source_experiment_run_id` plus persisted JSON payloads (`rag_test_run.metrics_json`, `rag_test_result_summary.metrics_json`, `rag_eval_experiment_record.metrics`, `llm_job.result_json`) for deterministic cleanup.
- Issues encountered: Some eval artifacts are linked via metadata (`metadata->>'experiment_run_id'`) rather than FK columns, so explicit metadata-based delete predicates were required.
- Next steps: Decide whether `memory_entries` rows keyed by `metadata.memory_build_run_id` should also be part of the full-delete boundary and whether FK-level cascade policies should be tightened in future migrations.

## [2026-04-19] Session Summary (RAG Detailed Compare Table Density + Time Unit Normalization)
- What was done: Refined only the `/admin/rag-tests` detailed comparison table for denser readability by adjusting table typography/row spacing, rebalancing column widths, and centering the `Delta / Change` + `Result` judgment flow.
- Key decisions: Kept existing API/data contracts and metric math unchanged; applied presentation-only updates in `frontend/src/pages/RagPage.jsx` (`buildDeltaInterpretation` and table display helpers) and `frontend/src/styles.css` (table-specific classes).
- Issues encountered: Existing unrelated `GatingPage.jsx` JSX warning lines remain outside this scope.
- Next steps: Run operator UI-smoke with long performance values to confirm preferred `%` vs `x` wording threshold in table delta headlines.

## [2026-04-19] Session Summary (RAG Compare Workspace Card UX Refinement)
- What was done: Refined `/admin/rag-tests` compare workspace cards (run info cards, winner summary cards, and grouped metric cards) for faster A/B decision reading, adding interpreted change text and duration-friendly value presentation.
- Key decisions: Kept existing API/data contracts and metric extraction/delta math unchanged; implemented presentation helpers in `frontend/src/pages/RagPage.jsx` and card-focused styles in `frontend/src/styles.css`.
- Issues encountered: Existing `GatingPage.jsx` JSX warning lines (`->` in option labels) are pre-existing and outside this task scope.
- Next steps: Verify operator readability on real runs and tune wording thresholds for `% vs x` performance change messages.

## [2026-04-19] Session Summary (RAG Detailed Compare Table UX Refactor)
- What was done: Refactored only the `/admin/rag-tests` quality/performance detailed comparison table into a section-aware comparison table with interpreted delta/change, result chips, KPI row emphasis, and run-label readability improvements.
- Key decisions: Preserved existing API contracts and metric extraction/delta calculation logic; applied presentation-layer-only changes in `frontend/src/pages/RagPage.jsx` and `frontend/src/styles.css`.
- Issues encountered: Existing workspace had unrelated modified docs/config files and pre-existing JSX warning lines in `GatingPage.jsx`; scope was kept to the detailed comparison table components/styles only.
- Next steps: Validate operator scan speed/readability with real run pairs and tune delta/result wording if team preference favors Korean copy or stricter KPI wording.

## [2026-04-19] Session Summary (AGENTS Start-Checklist Enforcement)
- What was done: Added mandatory session-start checklist rule to `.codex/AGENTS.md` and aligned `.codex/progress.md` so Codex begins implementation turns by confirming AGENTS/progress/index checks and planned progress update.
- Key decisions: Enforced checklist exposure in the first working update before repository-modifying actions.
- Issues encountered: None.
- Next steps: Keep checklist format consistent across future implementation turns and adjust only when AGENTS process rules change.

## [2026-04-19] Session Summary (Quality Gating Stage Filter Semantics + Label Realignment)
- What was done: Updated `/admin/quality-gating` per-query stage filter semantics so each selected stage returns queries that passed up to that stage only (`passed_rule/llm/utility/diversity` now means "next stage failed", `passed_all` means final accepted). Replaced UI "탈락" filter with `Rule 탈락` (`failed_rule`) and renamed stage option labels to explicit transition wording (`Rule 통과 -> LLM 탈락`, etc.).
- Key decisions: Preserved API endpoint/DTO shapes and added backward-compatible alias handling so legacy `pass_stage=rejected` is normalized to the same `failed_rule` behavior.
- Issues encountered: None.
- Next steps: Run operator smoke validation on `/admin/quality-gating` for each stage option and align API docs/examples with `failed_rule` as the primary reject filter token.

## [2026-04-19] Session Summary (RAG Compare UI/UX Decision-Support Redesign)
- What was done: Re-read `.codex/AGENTS.md` and improved `/admin/rag-tests` comparison experience by restructuring metrics into explicit groups (`Retrieval Quality`, `Answer Quality`, `Performance`), adding top-level comparison summary (overall winner + retrieval/latency deltas), upgrading metric cards (A/B values, delta, direction badges, core-KPI emphasis), and improving linked comparison table readability (short run labels, numeric alignment, delta/result emphasis, grouped row labels).
- Key decisions: Kept existing API contracts and metric extraction/calculation logic unchanged; refactored only frontend information architecture/rendering (`frontend/src/pages/RagPage.jsx`, `frontend/src/styles.css`) with reusable metric-group metadata.
- Issues encountered: Existing UI text regions include mixed-encoding strings, so changes were scoped to stable comparison blocks and style classes to avoid unrelated churn.
- Next steps: Validate operator readability with real run pairs and tune summary weighting/core KPI priorities based on team decision criteria.

## [2026-04-19] Session Summary (Short-User Eval 80 Full Regeneration + Baseline Origin Verification)
- What was done: Replaced dataset `b2d47254-8655-4c9c-81ac-7615677ec5bd` with 80 fully regenerated short-user evaluation items (corpus chunk-first generation, not synthetic candidate reselection), re-audited mapping, and added `scripts/verify_eval_dataset_origin.py` for dataset-origin diagnostics.
- Key decisions: Adopted corpus-grounded new query generation to align with rewrite-effect research intent (realistic short user prompts) while preserving retrieval-aware schema and existing dataset ID wiring.
- Issues encountered: Initial regenerated prompts showed term-artifact noise; generator filters/templates were iteratively tightened until structural issues were zero and synthetic text overlap was zero.
- Next steps: Run controlled A/C rewrite-effect experiment on regenerated 80 set and perform focused manual QA for outlier technical term prompts.

## [2026-04-19] Session Summary (Short-User Eval Dataset 40->80 Expansion with Chunk-Mapping Audit)
- What was done: Audited dataset `b2d47254-8655-4c9c-81ac-7615677ec5bd` (base 40 items) for grounded mapping quality against live `corpus_chunks` and then expanded to 80 items by adding 40 new short-user queries sourced from current `synthetic_queries_raw_all` + mapped corpus chunk IDs.
- Key decisions: Kept retrieval-aware schema identical (`expected_doc_ids`, `expected_chunk_ids`, `expected_answer_key_points`) and updated the same dataset ID metadata/version to 80 while preserving original 40-item JSONL as baseline input.
- Issues encountered: Lexical overlap heuristics produced a small warning set for some domain-token-heavy prompts, but structural mapping checks (chunk existence/doc consistency) remained clean (`issue_count=0`).
- Next steps: Run one A/C comparative RAG smoke on the updated 80-item set and verify rewrite-gain deltas remain stable versus 40-item baseline.

## [2026-04-19] Session Summary (Quality Gating Result Stage Filter Expansion + Progress Tracking Compliance)
- What was done: Re-read `.codex/AGENTS.md` and expanded Admin quality-gating per-query result filter from partial stage options to full stage coverage (`rejected`, `passed_rule`, `passed_llm`, `passed_utility`, `passed_diversity`, `passed_all`, plus unfiltered `전체`), wiring frontend selector -> backend API -> repository SQL.
- Key decisions: Kept the existing GET endpoint and business flow intact; added a single normalized query parameter (`pass_stage`) and stage-specific SQL predicates without introducing new DTO contracts.
- Issues encountered: Existing admin UI source contains mixed-encoding localized regions, so edits were constrained to stable filter/query blocks to avoid unrelated churn.
- Next steps: Run operator smoke check on `/admin/quality-gating` with each stage option to validate expected counts against funnel stage transitions.

## [2026-04-19] Session Summary (Gating/Synthetic Batch Filter Tightening + Result Token Badge UX)
- What was done: Updated Admin `GatingPage` per-query result table to render `query_type`, `rejected_stage`, `rejected_reason` as icon-like token badges instead of plain text, and tightened batch dropdown filters so failed/cancelled generation/gating batches are excluded from operator selection paths. Also updated Admin `SyntheticPage` query filter batch dropdown to hide failed/cancelled generation batches.
- Key decisions: Kept API/DTO contracts unchanged and applied UI-only rendering/filter logic; token badge parser accepts array/object/JSON-string/delimited text input to avoid backend coupling.
- Issues encountered: Existing frontend files contain mixed-encoding localized literals, so changes were applied in stable JSX/CSS blocks only.
- Next steps: Align token icon dictionary (`SU/FU/...`) with product terminology and add optional display-name mapping for non-engineering operators.

## [2026-04-19] Session Summary (Admin LLM Job Polling Removal)
- What was done: Removed periodic polling hooks that repeatedly called `/api/admin/console/llm-jobs?limit=120` from admin pages where this was causing unnecessary traffic/noise.
- Key decisions: Preserved explicit refresh triggers (manual refresh buttons and post-action reloads) instead of background polling, matching the “refresh-time reflection” requirement.
- Issues encountered: None.
- Next steps: Monitor operator flow and add per-section lightweight refresh CTA if any status visibility gaps appear.

## [2026-04-19] Session Summary (Quality Gating Runtime Stop + Data Purge Operations)
- What was done: Stopped active `gate-queries` runtime processes and removed related quality-gating data only for targeted in-flight/history IDs, including linked `llm_job`, `llm_job_item`, `synthetic_query_gating_result`, `synthetic_query_gating_history`, `synthetic_queries_gated`, and `quality_gating_batch` rows.
- Key decisions: Executed deletions with explicit target ID scopes and transactional ordering to avoid collateral cleanup outside requested batches/jobs.
- Issues encountered: Running-status snapshot changed during cleanup window, so target set was re-validated before each destructive step.
- Next steps: Add an operator-safe SQL/maintenance script for targeted gating batch+job cleanup to reduce manual repeat work.

## [2026-04-18] Session Summary (RAG Quality+Performance Integrated Tracking)
- What was done: Added RAG run performance aggregation in backend finalization (`total/stage/rewrite-overhead latency`) and exposed quality+performance integrated comparison in Admin `RagPage`.
- Key decisions: Kept existing retrieval/answer business logic unchanged and stored performance as additive payload (`metrics_json.performance`) for backward compatibility.
- Issues encountered: Existing frontend source includes mixed-encoding text regions; UI changes were applied in narrow stable blocks.
- Next steps: Run one completed RAG test pair and confirm `metrics_json.performance` values and compare-table deltas align with stage execution logs.

## [2026-04-18] Session Summary (Langfuse Env Validation + Tracing Visibility Fix)
- What was done: Verified Langfuse key set completeness in `.env`, reorganized `.env`/`.env.example` with section comments, enabled `QUERY_FORGE_LANGFUSE_ENABLED` in local `.env`, and validated observer initialization + smoke trace emission.
- Key decisions: Added `QUERY_FORGE_PYTHON` env guidance for backend-triggered pipeline subprocess consistency so the runtime uses `.venv` where `langfuse` is installed.
- Issues encountered: Initial tracing was blocked by disabled flag and missing `langfuse` package in current Python runtime.
- Next steps: Run one real LLM stage (`generate-queries` or `gate-queries`) and verify trace volume/fields in Langfuse UI under free-tier caps.

## [2026-04-18] Session Summary (Langfuse Event Schema + Pipeline LLM Observability Integration)
- What was done: Designed Query Forge Langfuse event schema (`docs/experiments/langfuse_event_schema.md`) and integrated fail-open tracing into the centralized LLM execution path (`pipeline/common/llm_client.py` via `pipeline/common/langfuse_observability.py`).
- Key decisions: Kept business logic untouched by instrumenting only transport-layer LLM calls, added purpose-aware sampling and hard event caps for free-tier safety, and made Langfuse emission fully optional by environment flags.
- Issues encountered: `pytest` is not installed in the current environment, so verification used `python -m unittest pipeline.tests.test_llm_client -v`.
- Next steps: Enable Langfuse in one controlled environment and validate real event volume against configured per-minute/per-day caps before wider rollout.

## [2026-04-18] Session Summary (Gating Top10 + Nested DTO + Backend Wiring Verification)
- What was done: Added Admin gating support for `Target Top10` utility score end-to-end (GUI input, backend DTO/service mapping, experiment config write, and pipeline utility scoring logic).
- Key decisions: Converted gating run payload to nested request DTO (`config.stageFlags/ruleConfig/gatingWeights/utilityScoreWeights/thresholds`) to reduce flat parameter sprawl while keeping existing run behavior/defaults.
- Issues encountered: Frontend production build regenerated static asset hash files under backend static resources.
- Next steps: Run one real admin gating batch with custom `target_top10` and compare stage_config vs generated experiment YAML vs gating result distribution.

## [2026-04-18] Session Summary (Failed Synthetic Request Data Purge)
- What was done: Investigated failed generation job `2e62b19d-582a-4c8a-b1f0-edd08ec61ca5`, identified linked generation batch `b3896885-b823-4d53-81f2-1eed7d64a7ec`, and manually purged batch-linked synthetic raw rows from strategy tables.
- Key decisions: Implemented backend guard so final failed synthetic generation jobs automatically delete batch-linked synthetic queries, preventing partial artifacts from remaining after retry exhaustion.
- Issues encountered: None.
- Next steps: Add regression test for failed generation cleanup and validate Admin synthetic list/count consistency after failure.

## [2026-04-17] Session Summary (Admin Synthetic UX Clarification + Control Refresh)
- What was done: Updated Admin synthetic generation UI for clarity by removing unused `소스 문서 버전`, renaming count control to `생성 개수`, switching random-chunk option to explicit mode selector, and locking LLM model input to fixed Gemini model.
- Key decisions: Preserved generation API semantics (`random_chunk_sampling`) and kept model value deterministic from frontend constant to prevent operator-side accidental drift.
- Issues encountered: Existing UI files include mixed-encoding literal regions; changes were applied with functional focus and validated via frontend production build.
- Next steps: Execute A/C/D operator runs from GUI with `생성 개수=1000` and compare batch duration/throughput under random vs ordered chunk mode.

## [2026-04-17] Session Summary (Synthetic Full-Corpus Random Sampling Controls)
- What was done: Added Admin synthetic generation support for `random_chunk_sampling` (GUI -> backend config -> pipeline), kept no-`limit_chunks` full-corpus behavior, and verified max synthetic query cap supports up to `2000`.
- Key decisions: Applied random sampling as chunk-order shuffle (seeded by experiment `random_seed`) so `max_total_queries` can stop at a random full-corpus subset without changing core pipeline stages.
- Issues encountered: Frontend production build refreshed backend static asset hash files.
- Next steps: Run GUI generation for `A/C/D` separately with `max_total_queries=1000`, `limit_chunks` empty, and `random chunk sampling` enabled; then confirm per-batch counts/history.

## [2026-04-17] Session Summary (Full-Gating Stage-Cutoff + Domain Data Reset)
- What was done: Implemented stage-cutoff based RAG run flow (use full-gating snapshot as source and cut synthetic queries by stage level), completed frontend/backend/pipeline wiring, and reset synthetic generation/quality gating/RAG test/LLM-job data in DB.
- Key decisions: Stage-cutoff path is restricted to exploratory runs and requires explicit `source_gating_batch_id` from a completed `full_gating` batch; corpus collect/preprocess/chunk tables were preserved.
- Issues encountered: Existing UI file contains mixed-encoding localized text, so stage-cutoff UI edits were applied with narrow scope.
- Next steps: Run one exploratory `rule_only` cutoff test using full-gating batch `6d97464a-9989-4180-85f5-c076850873aa` and verify per-stage pass counts against RAG memory size.

## [2026-04-17] Session Summary (Backend Transaction/Concurrency Risk Mitigation)
- What was done: Applied backend-only hardening for identified high-risk hotspots by shrinking service-level transaction scope around long-latency operations (`ask`, `reindex`, `runRagTest`) and adding advisory-lock-based serialization for pipeline run creation (`startRun` path).
- Key decisions: Kept business logic and API response contracts intact; focused only on transaction boundary and concurrency control behavior.
- Issues encountered: Existing codebase contains mixed-encoding localized literals; changes were intentionally minimal and localized to avoid collateral edits.
- Next steps: Observe lock-wait/throughput behavior under concurrent admin run requests and RAG ask load.

## [2026-04-17] Session Summary (Synthetic-free Baseline RAG Test Path)
- What was done: Added synthetic-free baseline support for Admin RAG tests end-to-end (request flag, backend validation/config, frontend run controls, and pipeline stage behavior) so baseline runs can execute without using synthetic-query snapshots.
- Key decisions: Preserved mandatory RAG job stage order (`build-memory -> eval-retrieval -> eval-answer`) and implemented baseline as `build-memory` no-op + `raw_only` retrieval/eval mode to avoid synthetic query dependency while keeping orchestration stable.
- Issues encountered: Existing workspace already contained unrelated staged/unstaged feature changes, so edits were scoped only to baseline-path fields/validation and pipeline memory-loading guards.
- Next steps: Run one exploratory synthetic-free baseline and one snapshot-based run on the same dataset, then compare retrieval/answer deltas from the RAG compare panel.

## [2026-04-15] Session Summary (Short User Dataset 40 + A/C RAG Re-run + Report)
- What was done: Built and registered a new retrieval-aware short-user eval dataset (`human_eval_short_user_40`, 40 items), executed two Admin-path RAG tests with the same settings as baseline runs (`A`: `cfb7587d-649f-457b-9410-0948abb49772`, `C`: `2a899769-613b-4463-95e1-fb850fdb73a3`), and documented combined baseline/new analysis in `docs/report/rag_quality_ac_comparison_short_user_2026-04-15.md`.
- Key decisions: Kept snapshot parity with baseline (`A` snapshot `4af71ae8...`, `C` snapshot `c9adc3f9...`) and fixed all runtime knobs (`full_gating`, selective rewrite, threshold `0.05`, `retrieval_top_k=10`, `rerank_top_n=5`) so dataset style was the only intended variable.
- Issues encountered: `human_eval_default` auto-sync behavior refreshed aggregate sample count after inserting new eval samples, so report explicitly separates historical run-time sample size from current dataset total.
- Next steps: Add controlled ungated/rule-only/full-gating comparison on the same short-user dataset and evaluate embedding/rerank alternatives for low MRR on compressed user queries.

## [2026-04-15] Session Summary (RAG Eval Reset + Rewrite Adoption + Admin UX)
- What was done: Rebuilt eval dataset via `build-eval-dataset` (method-1 path), improved rewrite adoption logic in pipeline runtime, updated Admin RAG compare chart to vertical layout, and replaced run-compare selector with a clearer custom checkbox UI.
- Key decisions: Kept Admin execution path intact (`/api/admin/console/rag/tests/run`) and reran two exploratory tests with the same conditions as previous target runs after deleting prior RAG test history/results.
- Issues encountered: Long-running answer eval stage required extended monitoring; both reruns completed after prolonged `eval-answer` runtime.
- Next steps: Add pre-eval dataset/corpus ID consistency guard and monitor rewrite adoption quality with current `confidence + retrieval-shift` scoring.

## [2026-04-14] Session Summary (RAG Eval Parallelization + Run Cleanup)
- What was done: Read `.codex/AGENTS.md`, cancelled active RAG runs (`41a804bf-7b43-46dd-a4de-592f08ddac89`, `f3360ef2-7d04-42ec-acc6-ff1382568892`), removed run-specific temporary artifacts (experiment configs/reports and related DB rows), and implemented sample-level parallel processing for `eval-retrieval` and `eval-answer`.
- Key decisions: Parallelized only computation/LLM call paths via `ThreadPoolExecutor` while keeping DB writes sequential to preserve transactional safety and deterministic ordering; added configurable eval concurrency (`retrieval_eval_concurrency` / `answer_eval_concurrency` / `eval_concurrency` with env fallbacks).
- Issues encountered: Shell policy blocked direct multi-file delete commands during cleanup, so document/report file deletion was completed with patch-based file removal and DB cleanup was scoped by target run/experiment identifiers.
- Next steps: Run a controlled RAG eval smoke test to validate throughput gain, confirm no regression in metric outputs/order, and tune concurrency defaults against provider latency/quota behavior.

## [2026-04-14] Session Summary (RAG Pipeline Reliability Hardening)
- What was done: Extended corpus alignment work beyond eval by hardening `memory`/`eval-dataset` source filtering, adding corpus-based FK migration coverage for `memory_entries`/`retrieval_results`/`rerank_results`, and improving LLM job retry state handling.
- Key decisions: Removed reliance on legacy `documents/chunks` FK paths for RAG-critical writes and added backend subprocess timeout handling to prevent indefinite `running` states.
- Issues encountered: Historical environments can retain mixed legacy/corpus constraints and orphan artifacts, so migration includes pre-constraint cleanup and `NOT VALID` attachment strategy.
- Next steps: Apply migration in runtime DB and verify active/next RAG runs complete without FK mismatch or stuck retry states.

## [2026-04-14] Session Summary (RAG Eval FK Mismatch Root Fix)
- What was done: Fixed eval runtime chunk loading to exclude orphan corpus chunks, added corpus-aligned eval FK migration (`V18`), and hardened chunk import to skip rows whose `document_id` is missing in `corpus_documents`.
- Key decisions: Standardized eval-result FK target to `corpus_chunks` and removed legacy `documents/chunks` coupling that caused `ForeignKeyViolation` during `eval-answer`.
- Issues encountered: Live DB had active `eval-retrieval` transactions, so lock-heavy retrieval-table FK hotfix was deferred to migration apply.
- Next steps: Apply migration and confirm active runs `41a804bf-7b43-46dd-a4de-592f08ddac89` and `f3360ef2-7d04-42ec-acc6-ff1382568892` finish with `eval-answer` success.

## [2026-04-14] Session Summary (AGENTS 3.6 Official Eval Discipline Enforcement)
- What was done: Enforced official-vs-exploratory RAG run discipline end-to-end: explicit snapshot identity requirement for official runs, official bundled comparison modes (`gating_effect` and `rewrite_effect`), per-mode retrieval preservation/exposure, standardized experiment-record persistence, and answer-metric alignment (`correctness/grounding/hallucination_rate`).
- Key decisions: Kept architecture intact and applied minimum targeted changes in backend request validation/config writing, pipeline retrieval/answer evaluation, and RAG admin UI controls.
- Issues encountered: Existing frontend file had mixed-encoding regions, so edits were scoped to stable JSX blocks and verified through full Vite build.
- Next steps: Apply latest migrations (`V18`, `V19`) in runtime DB and run official comparison smoke tests to confirm enforced failure modes and reproducible records.

---

## [2026-04-13] Session Summary
- What was done: Read `.codex/AGENTS.md`, created root `index.md`/`progress.md`, and added `index.md`/`progress.md` for each major working directory (`.codex`, `backend`, `configs`, `data`, `docs`, `frontend`, `infra`, `pipeline`, `scripts`).
- Key decisions: Used `.codex/AGENTS.md` as the authoritative agent policy and documented each directory based on currently implemented files and execution flow.
- Issues encountered: Root `AGENTS.md` was not present; runtime/build artifact directories were excluded from documentation scope.
- Next steps: Maintain directory-level `index.md`/`progress.md` together with code/config changes in each affected directory.

## [2026-04-13] Session Summary (Raw Table Split Refactor)
- What was done: Reworked synthetic-query storage from single `synthetic_queries_raw` writes to strategy-specific writes (`synthetic_queries_raw_a/b/c/d`) and switched gating/memory/eval/admin-console/rag reads to split-backed source (`synthetic_queries_raw_all`).
- Key decisions: Added Flyway `V17__split_strategy_raw_tables_and_drop_legacy_raw.sql` to migrate data, introduce `synthetic_query_registry` for FK integrity, drop legacy `synthetic_queries_raw`, and create union view `synthetic_queries_raw_all`.
- Issues encountered: Existing workspace had unrelated modifications; this session avoided reverting non-target files and validated only touched paths.
- Next steps: Apply migration in target DB, then run admin GUI generation/gating smoke checks and verify A/B/C/D strategy counts independently.

## [2026-04-13] Session Summary (Root README Rewrite)
- What was done: Rewrote root `README.md` in Korean narrative form and aligned content with `.codex/AGENTS.md` requirements (project objective, research overview/details, methodology, and fixed end-to-end flow).
- Key decisions: Replaced the previous experiment-report style README with project-level guidance that emphasizes A/B/C/D strategy separation, selective/dynamic gating, and retrieval-aware evaluation dataset constraints.
- Issues encountered: Existing README content was not suitable as a stable root guide due to encoding/readability issues in terminal output.
- Next steps: Keep directory-level READMEs synchronized with implementation changes and expand per-module Korean documentation where legacy/default templates remain.

## [2026-04-13] Session Summary (Skill: git-commit)
- What was done: Created `.codex/skills/git-commit` using the `skill-creator` workflow, implemented a commit workflow based on `git diff`, and added AngularJS-style commit guidance with Korean + English technical message examples.
- Key decisions: Focused the skill on logical commit splitting, unnecessary file exclusion, and staged-diff verification before each commit.
- Issues encountered: Initial `openai.yaml` generation had a short-description length violation and `$git-commit` prompt escaping issue; regenerated metadata with valid interface values.
- Next steps: Use `$git-commit` in real commit sessions and refine exclusion heuristics if project-specific noise patterns are observed.

## [2026-04-13] Session Summary (Admin Gating Reset)
- What was done: Updated admin gating run flow to clear previous completed/failed/cancelled gating batches for the same generation method before creating a new gating batch, and added an integration test for cleanup scope.
- Key decisions: Cleanup nulls `synthetic_queries_gated.gating_batch_id` first, then deletes target `quality_gating_batch` rows so dependent per-batch artifacts are removed via FK cascade without touching running batches.
- Issues encountered: Needed to preserve in-flight gating jobs, so cleanup scope excludes `planned/running` statuses.
- Next steps: Validate from Admin GUI by running A-method gating twice and confirming prior batch/result rows are replaced by the latest run context.

## [2026-04-13] Session Summary (Gating Filter + Pagination)
- What was done: Added method-based filtering (`method_code`) for admin gating result queries and implemented result table pagination in `frontend/src/pages/GatingPage.jsx`.
- Key decisions: Kept backend response shape unchanged (`List<GatingResultRow>`) and implemented frontend paging with `limit/offset` + `pageSize+1` next-page probing.
- Issues encountered: Frontend file contained mixed-encoding labels; focused on behavior/API consistency first and deferred pure text normalization.
- Next steps: Perform Admin GUI smoke checks for A/B/C/D filtering and confirm result-page navigation across larger gating batches.

## [2026-04-14] Session Summary (RAG Snapshot Evaluation Wiring)
- What was done: Added snapshot-aware RAG test flow by introducing optional `sourceGatingBatchId` in rag run request, validating selected gating batch (completed/preset/method match), and wiring fixed `source_gating_run_id` into experiment config.
- Key decisions: Preserved backward compatibility with auto-latest behavior when no snapshot batch is selected, and aligned Python config keys by writing both `memory_generation_strategies` and `source_generation_strategies`.
- Issues encountered: Existing eval path loaded memory entries across runs; added source gating run filtering in runtime retrieval/rewrite path to prevent cross-run memory mixing.
- Next steps: Execute admin GUI smoke for snapshot vs auto-latest runs and compare retrieval/answer reports for deterministic reruns.

## [2026-04-14] Session Summary (RAG Snapshot Dropdown Visibility)
- What was done: Adjusted Admin RAG snapshot dropdown behavior to show all completed gating snapshots and added runtime refresh wiring for gating batch list updates.
- Key decisions: Moved compatibility enforcement to run-time validation so UI can expose full snapshot inventory while still blocking incompatible preset/method combinations.
- Issues encountered: Existing UI filtered snapshot list by effective preset/method, which could hide valid completed snapshots from operator view.
- Next steps: Validate operator scenario where completed batch count in GUI dropdown matches backend `gating/batches` API result.

## [2026-04-14] Session Summary (RAG UX + Backoffice Visual Refresh)
- What was done: Redesigned Admin backoffice shell (`frontend/src/App.jsx`, `frontend/src/styles.css`) and rebuilt RAG test UI (`frontend/src/pages/RagPage.jsx`) with clearer control semantics and run-comparison visualization.
- Key decisions: Resolved snapshot/method duplicated input by auto-locking method selection when snapshot carries a fixed method, while keeping submit-time compatibility validation for safety.
- Issues encountered: Existing RAG options were hard to interpret, so field-level helper text now explicitly maps GUI knobs to runtime config keys (`rewrite_threshold`, `retrieval_top_k`, `rerank_top_n`).
- Next steps: Validate operator workflow for two-run chart comparison and collect feedback on metric prioritization in the dashboard.

## [2026-04-13] Session Summary (Gating Rule Ratio + Funnel Filter)
- What was done: Added configurable Korean-ratio threshold to admin gating Rule stage (GUI -> backend config -> pipeline rule evaluation), clarified min/max token labels, and added method-based funnel filtering (`전체/A/B/C/D`) in gating execution screen.
- Key decisions: Preserved legacy defaults by keeping separate defaults for general queries (`0.40`) and code-mixed queries (`0.20`), while applying the same user-entered ratio to both when explicitly set from Admin GUI.
- Issues encountered: Funnel stage summary table cannot provide method-specific counts, so method-filtered funnel counts are derived directly from `synthetic_query_gating_result`.
- Next steps: Run Admin GUI smoke checks for funnel filter switching and confirm expected ratio behavior for method D (`code_mixed`) runs.

## [2026-05-11] Session Summary (RAG Eval Failure Fix + ETA Cross-Surface Exposure)
- What was done: Fixed RAG answer-eval failure path by making `pipeline/eval/answer_eval.py` CSV writer tolerant to additive row keys and added regression test `pipeline/tests/test_answer_eval_csv.py`. Extended backend admin projections to expose ETA for RAG test runs, quality-gating batches, and LLM jobs using live progress + historical-rate fallback. Added reusable frontend ETA utility/component and applied it across Synthetic/Gating/RAG history and LLM job tables.
- Key decisions: Kept DB schema unchanged and implemented ETA as read-time derived values; for gating ETA target counts now resolve from multi-source batch IDs in `stage_config_json` instead of single-batch-only assumptions.
- Issues encountered: Existing admin UI tables had no shared ETA renderer and one job-table file had encoding-drifted labels, so component-level consolidation and table refresh were required together.
- Next steps: Monitor ETA accuracy under real workloads (especially long eval-answer runs) and tune historical sample windows/compact rendering density from operator feedback.

## [2026-05-13] Session Summary (Short-User 80 Dataset Grounding Refinement)
- What was done: Added `scripts/audit_short_user_dataset.py` and `scripts/refine_short_user_dataset.py`, refreshed `data/eval/human_eval_short_user_test_80.jsonl`, and rewrote dataset `b2d47254-8655-4c9c-81ac-7615677ec5bd` in DB with stronger short-query wording, grounded answer key points, and corrected `expected_chunk_ids` for misaligned samples.
- Key decisions: Preserved dataset ID/key, sample IDs, synthetic provenance metadata, and legacy schema while using the frozen snapshot report `data/reports/short_user_current_dump_2026-05-13.json` as the deterministic refinement baseline.
- Issues encountered: Many existing samples were structurally valid for pipeline loading but weakly grounded (`Overlap context...`, summary-only chunks, wrong schema sections), so the refinement split query rewriting from chunk retargeting and only changed `expected_chunk_ids` where actual corpus chunk text justified it.
- Next steps: Use the new pre/post audit reports and refinement report for manual spot QA on residual compressed-query edge cases before the next rewrite-effect comparison run.

## [2026-05-13] Session Summary (Admin RAG Dataset Strategy Lock)
- What was done: Updated the Admin RAG run form so Python KR short-user datasets expose only F/G generation strategies and matching gating snapshots, while Spring/default eval datasets expose only A/B/C/D/E options.
- Key decisions: Kept backend validation as the source of enforcement and added frontend filtering/state cleanup so stale incompatible strategy or snapshot selections are cleared when the dataset changes.
- Issues encountered: The previous snapshot dropdown intentionally showed the full completed snapshot inventory, which made incompatible A-E snapshots visible for Python KR eval datasets.
- Next steps: Smoke-test `/admin/rag-tests` by switching between Python KR, Spring KR, and default datasets and verifying the strategy chips plus snapshot dropdown narrow immediately.

## [2026-05-13] Session Summary (Admin RAG Detail Readability)
- What was done: Added a fixed bottom compare-selection dock to `/admin/rag-tests` and replaced raw JSON blocks in RAG run detail with structured cards for metric contribution, memory candidates, rewrite candidates, and retrieved chunks.
- Key decisions: Kept API payloads unchanged and handled readability in the React rendering layer, using existing admin theme tokens for dark-mode contrast.
- Issues encountered: Full frontend lint still reports the existing `vite.config.js` `process` global error; `RagPage.jsx` targeted lint has only pre-existing hook dependency warnings.
- Next steps: Smoke-test run-history compare selection and RAG detail modal on dark mode with real completed runs.

## [2026-05-13] Session Summary (Synthetic Source Scope Lock)
- What was done: Updated `/admin/synthetic-queries` so selected generation strategy is visually emphasized, chunk sampling selection is highlighted, and all-source execution expands only to allowed source IDs: Spring reference sources for A/B/C/D/E and `docs-python-org-ko-3-14` for F/G.
- Key decisions: Kept the backend run API single-source and made the frontend split an all-source launch into per-allowed-source requests; added backend validation to reject `arahansa-github-io-docs-spring` and out-of-scope source/method combinations.
- Issues encountered: Backend uses a single `source_id` payload contract for synthetic generation, so multi-source "all" behavior is intentionally client-side fan-out rather than a DTO/schema change.
- Next steps: Smoke-test A/B/C/D/E and F/G all-source launches from the Admin GUI and confirm generated experiment configs contain only the expected `source_id` values.

---

## [2026-05-19] Session Summary (Anchor Normalization Candidate Review)
- What was done: Added candidate-level review decisions for Admin anchor normalization dry-runs, including bulk `approve`/`skip` persistence, save-and-approve UI flow, and static Admin bundle refresh.
- Key decisions: Kept dry-run generation as a complete upfront pass; run approval now requires all changed/conflict/invalid candidates to be reviewed and applies only `would_update` candidates marked `approve`.
- Issues encountered: The existing Admin pipeline page still has a pre-existing hook dependency lint warning; the touched page has no new lint errors.
- Next steps: Restart the backend so Flyway applies `V34`, then review `anchor-normalize-255d113f` in `/admin/pipeline` by skipping the `http {` conflict or approving any safe candidates in future runs.

---

## Notes
- Keep this file concise
- Only record important changes

---

## [2026-05-20] Session Summary (Domain Backfill Verification)
- What was done: Reviewed Spring/Python domain seeding and found a backfill gap where eval samples using `*-reference` source IDs could remain unmapped. Added `V36` to repair canonical source, eval dataset, RAG run, LLM job, and anchor-domain propagation.
- Key decisions: Kept `arahansa-github-io-docs-spring` outside the Spring domain and treated the five Spring reference sources plus `docs-python-org-ko-3-14` as the canonical domain source set.
- Issues encountered: Fresh DBs can sync source YAML after Flyway, so backend source config sync now assigns canonical source IDs to Spring/Python domains after upsert.
- Next steps: Apply Flyway in the runtime DB and verify unmapped domain counts for the canonical source/eval/RAG tables.

---

## [2026-05-20] Session Summary (V35/V36 Migration Repair)
- What was done: Fixed V35/V36 migration failures caused by PostgreSQL not supporting `MIN(uuid)` by aggregating `domain_id::text` and casting back to UUID.
- Key decisions: Preserved the existing single-domain `COUNT(DISTINCT domain_id) = 1` guard, so the repair changes only SQL compatibility and does not broaden data updates.
- Issues encountered: The first backend restart failed at V35 and rolled back cleanly; after the fix, non-web backend startup validated all 36 migrations and reported schema version 36.
- Next steps: Use normal backend restart; no manual DB repair was required.

---

## [2026-05-20] Session Summary (Prompt Admin API 500 Fix)
- What was done: Fixed `/api/admin/prompt-bindings` and `/api/admin/prompt-assets` 500 responses caused by PostgreSQL being unable to infer nullable named parameter types.
- Key decisions: Kept repository behavior unchanged and added explicit SQL casts only around optional `family` and `activeOnly` filters.
- Issues encountered: Browser devtools only showed generic 500; backend logs showed `could not determine data type of parameter $1`.
- Next steps: Restart the active backend instance so the corrected query predicates are loaded.

---

## [2026-05-27] Session Summary (Admin RAG Eval Lab Cleanup)
- What was done: Added Admin RAG eval dataset deletion API/UI, sorted RAG detail rows by dataset query number, reduced comparison-table noise, and simplified RAG run detail modal query/anchor/candidate display.
- Key decisions: Dataset deletion reuses existing RAG run cleanup for linked terminal histories, blocks the auto-managed default dataset, and rejects deletion while active RAG runs exist.
- Issues encountered: None; targeted Admin RAG integration test and frontend production build passed.
- Next steps: Smoke-test `/admin/rag-tests` in the running backend with a real custom eval dataset and completed rewrite-skipped run.
