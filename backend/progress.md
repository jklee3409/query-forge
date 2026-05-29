# progress.md

## [2026-05-29] Session Summary (JDBC Repository Consolidation)
- What was done: Converted `PipelineAdminRepository` from JPA/native `EntityManager` to `NamedParameterJdbcTemplate`, removed unused JPA entities/repositories/config/dependency, and extracted Admin Console domain/scope, synthetic-method, and eval-dataset queries into dedicated JDBC repositories.
- Key decisions: Kept `AdminConsoleRepository` as the existing facade so service-layer call sites and business flow remain unchanged. Strategy raw-table writes still use the A-G allowlist and reads still use `synthetic_queries_raw_all`.
- Issues encountered: Targeted validation passed: `.\gradlew.bat compileJava`, Admin Console domain regression tests, `PipelineAdminIntegrationTest`, and `AdminConsoleRagIntegrationTest`.
- Next steps: Continue splitting the remaining Admin Console facade into synthetic batch, gating, eval dataset, and RAG run repositories without changing SQL semantics.

## [2026-05-29] Session Summary (Admin Console Nullable Domain SQL Fix)
- What was done: Changed Admin Console repository method and list queries to avoid nullable `domainId` SQL predicates in no-domain calls, including synthetic methods, generation batches, gating batches, eval datasets, and RAG test runs.
- Key decisions: `findGenerationMethods()` now uses a pure all-method query, while `findGenerationMethods(UUID)` delegates to it for null and applies domain-policy `EXISTS` only for non-null domain IDs.
- Issues encountered: Targeted regression tests passed: `AdminConsoleGatingIntegrationTest.adminConsoleListEndpointsAcceptMissingDomainId` and `generationMethodsCanBeFilteredByDomainPolicy`.
- Next steps: Restart the active backend process to load the repository fix.

## [2026-05-27] Session Summary (Domain-Language Synthetic Methods and RAG Detail Dedup)
- What was done: Changed Admin synthetic method listing/run validation to use `tech_doc_domain.source_language` for domain workspaces (`en` allows A/B/C/D/E, `ko` allows F/G), and changed RAG detail lookup to return one representative detail row per sample.
- Key decisions: Domain language now overrides legacy per-domain method policy for GUI run availability, while global unscoped runs keep the existing fixed source allowlists. RAG detail row selection prefers rows with rewrite/memory candidate payloads before falling back to older rows.
- Issues encountered: `.\gradlew.bat compileJava` passed.
- Next steps: Restart the backend before relying on the updated synthetic method API in a running Admin GUI.

## [2026-05-27] Session Summary (Admin React Bundle Refresh - RAG Detail Modal)
- What was done: Refreshed the backend-served React bundle after the RAG run detail modal started showing configured test names, using a custom query-analysis dropdown, and removing the scroll-to-top button.
- Key decisions: No backend Java/API/schema behavior changed; the static Admin bundle is the deployment artifact for the frontend-only modal update.
- Issues encountered: Frontend validation is recorded in the root/frontend progress summaries.
- Next steps: Serve the refreshed bundle and browser-smoke the RAG detail modal from the Spring Boot static route if needed.

## [2026-05-26] Session Summary (Selective Rewrite v3 Activation)
- What was done: Added Flyway `V42` to register `selective_rewrite_v3` v1 and bind `rag_rewrite.ko` to it, changed Admin-generated RAG configs to `rewrite_candidate_count=2`, and updated online rewrite prompt fallback order to prefer v3.
- Key decisions: Kept English `rag_rewrite.en` on the existing English prompt; v2/v1 remain fallbacks for Korean/code-mixed rewrite paths.
- Issues encountered: Targeted backend validation is recorded in the root session summary.
- Next steps: Restart backend so V42 applies before relying on Prompt Studio catalog binding.

## [2026-05-26] Session Summary (Selective Rewrite Final-Score Guard)
- What was done: Raised the Admin fallback/catalog rewrite threshold to `0.05`, changed new RAG run defaults so anchor injection is opt-in, removed `rewrite_always` from Admin-generated operational/final retrieval mode sets, added Flyway `V41` for cautious rewrite prompt versions, and refreshed the backend-served React bundle.
- Key decisions: Kept Gemini model defaults pinned to `gemini-2.5-flash-lite`; `rewrite_always` remains only as a legacy runtime/ablation mode outside Admin-generated final evaluation configs.
- Issues encountered: `.\gradlew.bat compileJava` passed.
- Next steps: Restart backend so V41 prompt bindings and the new Admin defaults are active.

## [2026-05-26] Session Summary (RAG Dataset Language Enforcement)
- What was done: Added `queryLanguage` / `metadataStrategyProfile` to Admin RAG dataset rows and made RAG run creation reject `eval_query_language` values that conflict with the selected dataset language.
- Key decisions: Dataset language now comes from dataset metadata or active sample rows, so PostgreSQL EN datasets are no longer treated as KO when the dataset key does not end with `_en`.
- Issues encountered: `.\gradlew.bat compileJava` passed.
- Next steps: Restart the backend process before relying on the new dataset row fields or backend mismatch guard in the running Admin GUI.

## [2026-05-26] Session Summary (PostgreSQL E Admin Pipeline Run)
- What was done: Used the Admin Console API path to run PostgreSQL Method E synthetic generation batch `9b0264e1-d615-4d6b-b015-f7731c433318` and `full_gating` batch `4d6b5c9f-b499-4666-9d3c-bb9eeb7f7c66` with `retrieverMode=bm25_only`.
- Key decisions: Preserved Admin GUI defaults for gating other than Retriever Mode; verified the persisted stage config kept `retriever_config.retriever_mode=bm25_only`, BM25 fusion weight `1.0`, dense weight `0.0`, dense required/fallback disabled, and rerank disabled.
- Issues encountered: Method E existed globally but was not enabled for the PostgreSQL domain, so the domain method policy was enabled before using the Admin API. The generation job had one Gemini 503 retry and completed; gating completed with zero retries.
- Next steps: Use source generation run `cc4f312a-c2bd-4e5c-ae55-2b5b2388cba4` and source gating run `070319a2-1242-4a2f-8ec2-65577c01e01d` for snapshot-pinned E evaluations.

## [2026-05-26] Session Summary (Domain-Scoped RAG Dataset Method Validation)
- What was done: Updated Admin RAG test validation so domain-owned technical-document datasets with unknown legacy Spring/Python scope can use generation methods enabled by the selected domain policy.
- Key decisions: Preserved strict legacy behavior when no `domain_id` is provided; PostgreSQL-domain datasets now validate against `tech_doc_domain_method_policy` instead of being rejected as unknown scope.
- Issues encountered: `.\gradlew.bat compileJava` passed.
- Next steps: Use the PostgreSQL KR short-user dataset with explicit A/C snapshots for a narrow RAG smoke when credentials and runtime cost are acceptable.

## [2026-05-26] Session Summary (Domain-Scoped A/C Synthetic Runs)
- What was done: Extended Admin synthetic source validation so domain-scoped A/C runs can use active sources attached to a domain when the domain method policy enables the method, while preserving legacy no-domain Spring/Python allowlists. Added A/C output-token defaults for summary, translation, and query stages to handle long English documentation chunks.
- Key decisions: Domain-scoped generation remains source-bound and method-policy-driven; the rejected `arahansa-github-io-docs-spring` guard and legacy allowlists remain for unscoped calls.
- Issues encountered: `.\gradlew.bat compileJava` passed. The live PostgreSQL A/C generation and BM25-only full-gating runs completed after earlier failed same-version attempts were deleted in the PostgreSQL domain scope.
- Next steps: Monitor future non-Spring English-domain A/C runs for long-chunk truncation before broadening the same path to other strategies.

## [2026-05-26] Session Summary (RAG Rewrite Anchor Eval Persistence)
- What was done: Added Flyway V40 for `rag_rewrite_anchor_eval`, created internal anchor evaluation row generation during RAG test finalization, added detail/run anchor lookup APIs, and enriched RAG run metrics with DB-derived anchor summaries.
- Key decisions: Anchor evaluation is normalized in its own table and calculated from rewrite artifacts, expected/retrieved chunk/doc evidence, memory, glossary, and canonical hints; `metric_contribution` is not the source of truth.
- Issues encountered: Initial compile exposed a repository patch placement error; after correction, `.\gradlew.bat compileJava` passed.
- Next steps: Run a narrow migrated-DB Admin RAG smoke to verify rows for `rewrite_applied=true` details and empty-state compatibility for old runs.

## [2026-05-26] Session Summary (Flyway History Verification)
- What was done: Queried `flyway_schema_history` for the latest applied migrations and V38/V39 specifically, confirmed no failed Flyway entries, checked active `rag_rewrite.ko`/`rag_rewrite.en` prompt bindings, and validated startup through non-web Spring Boot bootRun.
- Key decisions: Kept verification read-only and bounded to Flyway/prompt catalog state.
- Issues encountered: None; the local DB is at Flyway version 39 with V38 and V39 successful.
- Next steps: Commit the V38 immutability repair, V39 English prompt seed, and documentation updates.

## [2026-05-26] Session Summary (Flyway V38 Checksum Repair)
- What was done: Restored `V38__seed_selective_rewrite_v2_v4_prompt_asset.sql` to its already-applied checksum (`9452379`), added `V39__seed_selective_rewrite_en_v1_v2_prompt_asset.sql` for the English rewrite prompt v2 catalog seed and `rag_rewrite.en` binding, and verified backend startup.
- Key decisions: Kept Flyway validation enabled and did not add automatic repair. The local DB mismatch was resolved by making V38 immutable again and expressing the later catalog change as the next migration.
- Issues encountered: V38 had been applied before the English prompt seed was added to the same file; the original V38 content was recovered from Codex session history and verified with the Flyway CRC32 algorithm.
- Next steps: Use normal Flyway startup to apply V39 in other environments; no automatic repair path was added.

## [2026-05-25] Session Summary (RAG Runtime Catalog Defaults)
- What was done: Extended runtime options to expose `defaultRetrieverMode` and `retrieverModeDefaults` from `configs/app/model_catalog.yml`, and made RAG run creation use catalog defaults for omitted threshold/top-K/rerank/retriever values.
- Key decisions: Kept constants only as fallback when the catalog is unavailable or incomplete; final RAG experiment records now copy actual run config values instead of hardcoded fallback numbers.
- Issues encountered: No DB migration execution was performed.
- Next steps: Keep new Admin RAG parameters in `model_catalog.yml` first, then expose them through runtime options instead of adding frontend/backend defaults.

## [2026-05-25] Session Summary (Selective Rewrite Prompt Seed)
- What was done: Added Flyway migration `V38__seed_selective_rewrite_v2_v4_prompt_asset.sql` to register Korean/code-mixed `selective_rewrite_v2` metadata version `v4` and bind `rag_rewrite.ko`; the later English `selective_rewrite_en_v1` v2 seed was split into V39 after checksum repair.
- Key decisions: Migration is catalog metadata only; it does not alter rewrite runtime code, retrieval scoring, synthetic memory tables, or stored synthetic query text.
- Issues encountered: No DB migration execution was performed in this low-scope prompt edit.
- Next steps: Apply Flyway in the runtime DB when Prompt Studio/catalog metadata should show v4 as active.

## [2026-05-25] Session Summary (Admin Rewrite Threshold Alignment)
- What was done: Changed Admin RAG rewrite threshold fallback/base defaults to `0.02`, added `rewrite_memory_candidate_pool_n=20` to generated configs, and refreshed the backend-served React bundle from the frontend build.
- Key decisions: Reused one backend constant for request fallback, runtime option fallback, and generated experiment config to prevent GUI/backend/catalog drift.
- Issues encountered: `.\gradlew.bat compileJava` passed. Frontend `npm run build` passed and replaced the hashed React JS asset.
- Next steps: Use a fixed snapshot rerun to verify adoption rate and bad rewrite rate with the new threshold.

## [2026-05-25] Session Summary (Admin React Bundle Refresh - RAG History UI)
- What was done: Refreshed the backend-served React bundle after the RAG history UI change that puts method codes first and shows completed-run KST start time plus elapsed duration.
- Key decisions: No backend Java/API/schema behavior changed; the existing RAG run list already exposes `startedAt` and `finishedAt`.
- Issues encountered: Frontend `npm run build` passed and replaced the hashed React JS/CSS assets under `src/main/resources/static/react`.
- Next steps: Serve the refreshed bundle only if testing through the Spring Boot static route instead of the Vite dev server.

## [2026-05-25] Session Summary (Short-User Rewrite Policy Cap)
- What was done: Added `max_compact_query_chars=56` to the Admin-generated short-user rewrite adoption policy so very short compressed queries can accept compact anchor-expanded candidates without tripping the ratio-only verbosity gate.
- Key decisions: Left `rewrite_threshold`, snapshot validation, prompt-only memory usage, and retrieval mode generation unchanged.
- Issues encountered: Targeted `compileJava` passed.
- Next steps: Re-run the same RAG condition to confirm adoption improves without increasing bad rewrite rate.

## [2026-05-20] Session Summary (Admin RAG Prompt-Only Rewrite Config)
- What was done: Updated Admin-generated RAG configs so default rewrite runs no longer include `memory_only_gated`, no longer expose/store rewrite retrieval merge strategy, and set `rewrite_memory_hint_retrieval_enabled=false`.
- Key decisions: Kept memory_lookup config only as legacy/ablation support for explicit memory_only modes while default rewrite evaluation compares raw retrieval against rewritten-query retrieval directly.
- Issues encountered: No backend compile/build was run due the requested low-scope validation limit; static diff checks passed.
- Next steps: Add an API-level smoke later to confirm generated retrieval_modes are `raw_only` plus rewrite modes only.

## [2026-05-20] Session Summary (Selective Rewrite Prompt v3 Seed)
- What was done: Added Flyway migration `V37__seed_selective_rewrite_v2_v3_prompt_asset.sql` to register `selective_rewrite_v2` metadata version `v3` and bind `rag_rewrite.ko` to it while retaining v2/v1 fallbacks.
- Key decisions: Kept the prompt name/id and candidate labels stable for runtime/frontend compatibility; the migration is catalog metadata only and does not change rewrite execution code.
- Issues encountered: No DB migration execution was performed in this low-scope prompt edit.
- Next steps: Apply Flyway in the runtime DB when prompt catalog bindings need to reflect v3.

## [2026-05-20] Session Summary (Admin Root Forward)
- What was done: Changed backend `/admin` handling from a redirect to `/admin/pipeline` into a direct React app forward so the Domain Atlas entry route is preserved on direct backend-served access.
- Key decisions: Left legacy admin page redirects and concrete React routes unchanged.
- Issues encountered: Targeted `.\gradlew.bat compileJava` passed. Frontend `npm run build` refreshed the backend-served React bundle.
- Next steps: Smoke-test direct `/admin` access after serving the backend bundle.

## [2026-05-20] Session Summary (Pipeline CLI Domain Forwarding)
- What was done: Forwarded selected pipeline `domainId` into `pipeline/cli.py import-corpus --domain-id` from backend-managed import/full-ingest runs.
- Key decisions: Kept backend post-import domain propagation in place as the Admin-side guard while allowing the Python CLI to record the same domain context directly.
- Issues encountered: Targeted `.\gradlew.bat compileJava` passed.
- Next steps: Smoke-test a migrated backend import/full-ingest run when DB work is acceptable.

## [2026-05-20] Session Summary (Domain Source Membership Bundle)
- What was done: Refreshed the backend-served React static bundle after adding Domain Atlas source membership controls.
- Key decisions: No backend Java/API/schema behavior changed in this UI-only slice; the bundle consumes the existing domain source attach/detach endpoints.
- Issues encountered: Frontend build passed and regenerated static asset hashes under `src/main/resources/static/react`.
- Next steps: Browser-smoke the bundle against a migrated backend.

## [2026-05-20] Session Summary (Domain Scoped Pipeline Execution)
- What was done: Added `domainId` to pipeline run requests, persisted `corpus_runs.domain_id`, filtered pipeline dashboard/history by domain, attached newly upserted/auto-registered sources to the selected domain, and propagated imported run domains to corpus documents/sections/chunks/relations/glossary rows.
- Key decisions: Source selection is validated against `tech_doc_domain_source` when a domain is supplied. Empty-domain collect/full-ingest requests now fail fast instead of falling back to all configured sources.
- Issues encountered: Targeted `.\gradlew.bat compileJava` passed. No DB migration execution was performed.
- Next steps: Add GUI source membership editing and consider passing `domain_id` into standalone Python import CLI paths if direct CLI imports need first-class domain assignment.

## [2026-05-20] Session Summary (Domain Scoped Admin APIs)
- What was done: Added optional `domain_id` filters to existing AdminConsole and Corpus Admin list endpoints, persisted `domain_id` into synthetic generation batches, quality-gating batches, and RAG test runs, and wrote the selected domain into generated experiment configs.
- Key decisions: Kept the retrofit additive and backward compatible; unscoped legacy calls still work, while domain workspace calls are filtered by domain-owned rows and method policy.
- Issues encountered: Targeted backend `compileJava` passed. No DB migration execution was performed.
- Next steps: Carry domain context into lower-level pipeline import/materialization jobs before making `domain_id` columns non-null in a later enforcement phase.

## [2026-05-20] Session Summary (Prompt Asset File Fallback)
- What was done: Updated `PromptAdminService` so file-backed prompt assets return existing prompt file content when `content_body` is still null.
- Key decisions: Kept DB-backed revisions as the editable path while preserving read visibility for current `configs/prompts` assets.
- Issues encountered: Backend `compileJava` passed before the frontend shell build; no DB migration execution was performed.
- Next steps: Use Prompt Studio to create DB-backed revisions and switch prompt bindings without editing files directly.

## [2026-05-20] Session Summary (Domain and Prompt Admin APIs)
- What was done: Added `/api/admin/domains` APIs for domain list/detail/create/update/source attach/detach/summary and `/api/admin/prompt-assets` plus `/api/admin/prompt-bindings` APIs for global prompt catalog/binding management.
- Key decisions: Kept the new controllers/services/repositories isolated from existing AdminConsole execution paths so domain runtime filtering can be wired in a later commit.
- Issues encountered: No DB migration execution was performed. Targeted backend `compileJava` passed.
- Next steps: Add frontend Domain Home/Workspace and Prompt Studio surfaces against these APIs.

## [2026-05-20] Session Summary (Domain and Prompt Schema)
- What was done: Added Flyway migration `V35__add_domain_and_prompt_management.sql` for first-class technical document domains, domain-source/method policy seed data, global prompt asset bindings, and nullable `domain_id` columns across corpus/synthetic/gating/memory/eval/RAG/anchor/job tables.
- Key decisions: Kept prompt assets above domains, kept A-G raw strategy tables split, seeded Spring/Python domains without strict `NOT NULL` enforcement, and backfilled only deterministic same-domain mappings.
- Issues encountered: No DB migration was executed yet to avoid broad local DB work on the low-spec environment.
- Next steps: Add backend read APIs for domains and prompt bindings, then wire domain filters into Admin flows incrementally.

## [2026-05-20] Session Summary (Short-User Eval Dataset Restore)
- What was done: Restored the requested eval dataset rows in the local PostgreSQL DB: KR dataset `b2d47254-8655-4c9c-81ac-7615677ec5bd` now points to `v4-test-short-user-*` active samples with version `v4-2026-04-19`, and EN dataset `8f0d6e0f-6f9e-4d64-9b07-f4e8ce5ebec0` now uses committed v1 EN rows with version `v1-2026-04-28`.
- Key decisions: Limited DB reads/writes to the two dataset IDs, the preserved KR v4 sample prefix, and the Git-stored EN v1 JSONL source. No backend Java/API/schema changes were made.
- Issues encountered: Initial Python command quoting failed before any DB transaction; rerun completed and verification showed 80 active items per dataset with 0 KR/EN grounding mismatches.
- Next steps: Run only targeted RAG comparisons if these restored baselines need metric validation.

## [2026-05-20] Session Summary (Admin RAG Hint Defaults)
- What was done: Updated Admin RAG config generation so `rewrite_threshold` defaults to `0.05`, generated configs include intent-preserving memory lookup and rewrite memory-hint retrieval, and `short_user` rewrite adoption is relaxed through explicit config metadata.
- Key decisions: Kept multi-source anchor expansion behind the existing toggle and kept direct synthetic-query replacement out of the default path.
- Issues encountered: Targeted `.\gradlew.bat compileJava` passed.
- Next steps: Run a new Admin RAG test from the GUI and inspect the persisted rewrite config for `memory_hint_query` observability.

## [2026-05-20] Session Summary (Anchor Normalization Full-Scope Dry-Run)
- What was done: Changed `AnchorNormalizationService.createDryRun` so an omitted/non-positive target `limit` no longer defaults to 500 and instead scans all matching anchors. List pagination still keeps its bounded page limit. Added integration coverage for unbounded active-anchor dry-run scope.
- Key decisions: Kept explicit positive create limits available for scoped/testing calls, capped explicit limits at 100,000, and left review/approval semantics unchanged.
- Issues encountered: `anchor-normalize-471d787c` was verified as partial (500/6,481). After backend restart, `anchor-normalize-7d079b88` covered all 6,481 active anchors with 0 missing candidates and now shows approved with 48 applied updates plus 5 skipped conflicts.
- Next steps: Investigate the 5 skipped conflicts separately if they need canonical cleanup beyond the approved safe updates.

## [2026-05-19] Session Summary (Multi-source Anchor Tracker Static Bundle)
- What was done: Refreshed the backend-served React static bundle after adding the `/admin/pipeline` multi-source anchor tracker UI.
- Key decisions: No backend Java API, service, repository, migration, or relation-build behavior changed.
- Issues encountered: Frontend build passed and produced new static asset hashes under `src/main/resources/static/react`.
- Next steps: Serve the refreshed admin bundle and smoke-test the tracker against the completed multi-source anchor run.

## Overview
High-level backend progress tracking.

## [2026-05-19] Session Summary (Anchor Normalization History Delete API)
- What was done: Added `DELETE /api/admin/corpus/anchors/normalization-runs/{runId}` and `AnchorNormalizationService.deleteRun`, deleting candidate review rows before deleting the run history row.
- Key decisions: The delete operation is history-only and does not reverse canonical values already applied by approved runs. Current dry-run generation remains synchronous and transaction-scoped; restart-time resume would need a separate checkpointed job model.
- Issues encountered: Targeted `.\gradlew.bat test --tests io.queryforge.backend.admin.corpus.CorpusAdminMutationIntegrationTest.anchorNormalizationRunDeleteRemovesHistoryAndCandidates` passed.
- Next steps: Smoke-test the endpoint against the intended development DB after confirming V32/V34 are applied.

## [2026-05-19] Session Summary (Multi-source Anchor Relation API)
- What was done: Added Flyway `V33` for `canonical_anchor_relation_run` / `canonical_anchor_relation` plus the `rag_test_run.multi_source_anchor_expansion_enabled` flag. Added `MultiSourceAnchorService` and Admin corpus endpoints to build/list/get relation-index runs, and wired Admin RAG config/records to persist multi-source anchor settings.
- Key decisions: The build writes only additive relation tables, marks older active relation rows superseded by version, and never mutates synthetic raw tables or existing query text. Default relation sources are approved canonical mappings, synthetic-query anchor co-occurrence, and chunk evidence co-occurrence.
- Issues encountered: `.\gradlew.bat compileJava` initially caught unsafe Java text-block interpolation; named SQL parameters fixed it and compile passed.
- Next steps: Apply V33 only to the target DB, trigger the Admin build once, and inspect relation counts/source distribution before enabling in larger RAG runs.

## [2026-05-19] Session Summary (Admin RAG Query-Language Guard)
- What was done: Added Admin RAG validation that requires English synthetic methods (`E/F`) to run only with `eval_query_language=en` and Korean/code-mixed methods (`A/B/C/D/G`) only with `eval_query_language=ko`. Generated RAG configs and experiment records now include `rewrite_prompt_profile`.
- Key decisions: Kept source/dataset scope validation and snapshot identity rules unchanged; language compatibility is enforced before job/config creation.
- Issues encountered: Targeted `.\gradlew.bat test --tests io.queryforge.backend.admin.console.AdminConsoleRagIntegrationTest` passed.
- Next steps: Smoke-test one accepted E/en request through the running Admin API after selecting a completed E snapshot.

## [2026-05-19] Session Summary (Anchor Normalization Dry-Run SQL Fix)
- What was done: Fixed `AnchorNormalizationService.findTargets` SQL assembly so optional `activeOnly` and `keyword` predicates remain separated from the following `ORDER BY`, resolving the Admin anchor normalization dry-run 500.
- Key decisions: Kept the endpoint contract and review-table schema unchanged and added a targeted `CorpusAdminMutationIntegrationTest` case for `POST /api/admin/corpus/anchors/normalization-runs`.
- Issues encountered: PostgreSQL logged `syntax error at or near "BY"` from `gt.is_active = TRUEORDER BY ...`; targeted integration test passed and live 8080 smoke succeeded after backend restart.
- Next steps: Browser-smoke the Anchors normalization history/detail/approve/reject UI with real operator filters.

## [2026-05-19] Session Summary (Anchor Normalization Review APIs)
- What was done: Added `V32__add_anchor_normalization_review_tables.sql`, `AnchorNormalizationService`, and Admin corpus endpoints for anchor normalization dry-run history, detail, approve, and reject.
- Key decisions: Approval updates only `corpus_glossary_terms.canonical_form` and `normalized_form` for conflict-free candidates. It does not update evidence, synthetic links, synthetic raw data, memory entries, or mapping rows. Migration was added but not applied.
- Issues encountered: Targeted `.\gradlew.bat compileJava` passed.
- Next steps: Apply V32 only after explicit DB approval, then smoke dry-run/approve/reject against a small scoped anchor set.

## [2026-05-19] Session Summary (Canonical Anchor Version Pins for RAG Records)
- What was done: Added canonical anchor version metadata to Admin RAG generated config, initial/final `rag_eval_experiment_record` configs, and completed RAG `metrics_json`. RAG experiment-record upsert now merges final config JSON with existing config JSON so completion does not drop richer server-created fields.
- Key decisions: Used additive JSON/config metadata only; no migration was added or applied, and RAG runtime options remain server-driven through the existing catalog path.
- Issues encountered: Targeted `.\gradlew.bat compileJava` passed.
- Next steps: Keep future RAG finalization changes merge/additive so reproducibility metadata is not overwritten by smaller completion payloads.

## [2026-05-19] Session Summary (Canonical Anchor Mapping Migration Review)
- What was done: Reviewed Flyway `V31__create_canonical_anchor_mapping.sql` for FK compatibility with `corpus_glossary_terms(term_id)`, approved-active unique indexing, pending candidate allowance, alias-language checks, and canonical self-row rejection.
- Key decisions: Left V31 unchanged and did not apply it. Read-only inspection of the local development DB showed Flyway latest `V30`, no `V31` history row, and no `canonical_anchor_mapping` table.
- Issues encountered: `psql` was unavailable locally, so catalog inspection used a read-only Python PostgreSQL connection.
- Next steps: With explicit approval, apply V31 only to the intended development DB and inspect the created table, indexes, constraints, trigger, and function before any mapping-row insert/backfill work.

## [2026-05-19] Session Summary (Canonical Anchor Mapping Migration Draft)
- What was done: Added Flyway `V31__create_canonical_anchor_mapping.sql` for the additive alias-to-canonical mapping table with FK links to `corpus_glossary_terms`, approved-active deterministic uniqueness, pending multi-candidate support, and a self-row rejection trigger.
- Key decisions: Stored mapping status as `mapping_status`, kept `term_type` out of the mapping row, and left normalized alias calculation to application code.
- Issues encountered: Migration SQL was written only; it was not applied to any database in this session.
- Next steps: Session 6 should review/apply `V31` only after approval and inspect constraints/indexes before any data writes.

## [2026-05-19] Session Summary (Synthetic Batch Completion Recovery)
- What was done: Operationally completed generation batch `c122d7c2-3bc5-4442-94d1-90c9cd1a31fa` and its `GENERATE_SYNTHETIC_QUERY` job/item without using cancel/fail paths, preserving 1465 generated Strategy B raw rows.
- Key decisions: Stopped the active backend worker and child `generate-queries` process before DB finalization to avoid `deleteSyntheticQueriesByGenerationBatch` failure/cancel cleanup, then restarted the backend on port 8080.
- Issues encountered: Repeated retry runs left 13 `experiment_runs` in `running`; these were marked `completed` with per-run raw-row counts and batch completion metadata.
- Next steps: Patch `pipeline/generation/synthetic_query_generator.py` so retry/resume target accounting counts existing/reused rows for the generation batch, not only newly inserted rows in the current process attempt.

## [2026-05-18] Session Summary (MAX_TOKENS Retry Guard)
- What was done: Changed `LlmJobService` retry policy so `failure_category=max_tokens_truncated` retries only once, even when synthetic generation jobs use `max_retries=-1`, and terminal failures include `failure_policy=failed_needs_config` plus category-specific retry metadata.
- Key decisions: Kept `llm_job.job_status` and generation batch status as `failed` for DB compatibility while recording the new policy in JSON observability payloads.
- Issues encountered: The active Strategy B batch was failing in the translation stage with Gemini `finish_reason=MAX_TOKENS`, not in query generation.
- Next steps: Implement a non-compressive full translation design for Strategy B if large all-source batches must be robust under provider token limits.

## [2026-05-18] Session Summary (Strategy B Gemini Batch Opt-In)
- What was done: Added optional Admin synthetic request fields `llmExecutionMode` and `geminiBatchInputMode`, writing `llm_execution_mode=gemini_batch` and `gemini_batch_input_mode` only when explicitly requested.
- Key decisions: Restricted Gemini Batch mode to Strategy B and left Admin-generated configs online by default; B safe defaults and split raw tables were unchanged.
- Issues encountered: None in backend validation.
- Next steps: Use the opt-in fields only for a tiny live B batch smoke after fake/unit coverage.

## [2026-05-18] Session Summary (Gemini Flash-Lite Fallback Pin)
- What was done: Pinned Admin-generated LLM fallback configs to `gemini-2.5-flash-lite` so Strategy B scale-up batches do not silently use the higher-cost `gemini-2.5-flash` fallback path.
- Key decisions: Kept the primary default model unchanged and scoped the change to fallback model selection; Strategy B safe defaults, split raw tables, and fixed pipeline order were unchanged.
- Issues encountered: None during implementation.
- Next steps: Verify the generated Admin experiment YAML contains `llm_fallback_models: gemini-2.5-flash-lite` before larger B runs.

## [2026-05-15] Session Summary (Strategy B Admin Runtime Smoke)
- What was done: Verified Strategy B Admin generation from the live API. A stale 8080 backend produced an Admin config without the B-only defaults and hit translation `max_tokens_truncated`; after starting a current-code backend on 8081, a one-source smoke completed with one B row and an all-allowed-sources smoke completed with two B rows.
- Key decisions: Confirmed current Admin configs include `llm_translation_max_output_tokens=2048`, `b_summary_max_chars=900`, and B query payload bounds; all-allowed-sources creates one batch/job with `source_ids` for the five Spring reference sources.
- Issues encountered: The existing 8080 process should be restarted before relying on Admin UI results, because it is stale relative to the checked-out code.
- Next steps: Scale B from the completed two-query all-source baseline with low increments and monitor job retry/failure categories.

## [2026-05-15] Session Summary (Strategy B Admin Safe Defaults)
- What was done: Updated Admin synthetic generation config writing so Strategy B batches explicitly persist `llm_translation_max_output_tokens=2048` plus B query/summary bounds, and extended the B all-allowed-sources integration test to assert those keys.
- Key decisions: Scoped defaults to Strategy B generation only; no Admin defaults for A/C/D/E/F/G, raw-table structure, or pipeline stage order were changed.
- Issues encountered: Controlled B smoke showed the previous inherited 384-token translation cap could fail before query generation with `category=max_tokens_truncated`.
- Next steps: Run an Admin-triggered B smoke and confirm the generated config uses these B-only defaults with one batch/job.

## [2026-05-15] Session Summary (Synthetic Generation Failure Observability)
- What was done: Preserved `GENERATE_SYNTHETIC_QUERY` failure observations across retry/cancel transitions by appending snapshots with stderr/stdout, summary, exit code, retry counts, and parsed failure category into `llm_job.result_json`/`last_checkpoint`, and by moving failed item payloads into `llm_job_item.checkpoint_json` before retry reset.
- Key decisions: Kept DB schema, pipeline command semantics, synthetic raw table structure, and retry/cancel state transitions unchanged; used additive JSON payloads (`last_failure`, `previous_failures`) instead of migrations.
- Issues encountered: Targeted `AdminConsoleGatingIntegrationTest` passed, including new retry/cancel observability cases.
- Next steps: Reproduce a small failing/cancelled B generation and confirm Admin job detail exposes `previous_failures` with the surfaced LLM failure category.

## [2026-05-15] Session Summary (Synthetic All-Allowed Source IDs Config)
- What was done: Updated `AdminConsoleService.runSyntheticGeneration` so a source-unselected synthetic run is valid again and writes the method allowlist as `source_ids` into the experiment YAML. Added an integration test that verifies B/all-sources creates exactly one generation batch and one LLM job with the five Spring source IDs.
- Key decisions: Kept explicit source/document validation for narrowed runs and retained disallowed-source enforcement. Source allowlists were converted to ordered lists so generated configs are deterministic.
- Issues encountered: `./gradlew test --tests io.queryforge.backend.admin.console.AdminConsoleGatingIntegrationTest` and the targeted new test passed. Frontend build refreshed backend-served React static assets.
- Next steps: Smoke the active backend UI after deploy and verify batch history no longer shows one row per Spring source for a single all-sources request.

## [2026-05-13] Session Summary (RAG Compare Static UI Bundle Refresh)
- What was done: Refreshed the backend-served React static bundle after the frontend `/admin/rag-tests` comparison summary/table readability changes.
- Key decisions: No backend Java API, DTO, service, repository, migration, or RAG evaluation behavior changed.
- Issues encountered: Frontend `npm run build` passed and produced new static asset hashes under `src/main/resources/static/react`.
- Next steps: Serve the refreshed admin bundle and visually verify the RAG compare workspace in the active backend environment.

## [2026-05-13] Session Summary (Pipeline Monitor Static UI Bundle Refresh)
- What was done: Refreshed the backend-served React static bundle after the frontend `/admin/pipeline` execution toolbar spacing and 전체 실행 button color adjustment.
- Key decisions: No backend Java API, DTO, service, repository, migration, or pipeline execution behavior changed.
- Issues encountered: Frontend `npm run build` passed and produced new static asset hashes under `src/main/resources/static/react`.
- Next steps: Serve the refreshed admin bundle and visually verify `/admin/pipeline` in the active backend environment.

## [2026-05-13] Session Summary (LLM Job Type Constraint Hotfix for Chunk Embedding Materialization)
- What was done: Added Flyway `V30__allow_materialize_chunk_embeddings_llm_job_type.sql` to recreate `llm_job_job_type_check` with `MATERIALIZE_CHUNK_EMBEDDINGS` included, and centralized Java-side job type definitions in `LlmJobType` so `LlmJobService` no longer hardcodes the new materialization type ad hoc.
- Key decisions: Fixed the production failure with an additive migration instead of editing old applied migration `V16`, so existing databases can migrate forward cleanly without document/pipeline rework.
- Issues encountered: The new admin chunk-embedding materialization path could enqueue a valid backend command but failed on insert because DB constraint and Java job-type additions were out of sync.
- Next steps: Apply Flyway migration in the running DB and re-run `/api/admin/console/rag/chunk-embeddings/materialize` to confirm job creation succeeds.

## [2026-05-13] Session Summary (Admin RAG DB-ANN Backend + Chunk Embedding Materialization)
- What was done: Added Flyway `V29__add_chunk_embeddings_for_db_ann_eval.sql`, admin chunk-embedding status/materialization APIs, runtime-option support for `retrieval_backend=local|db_ann`, and RAG-run preflight validation that blocks `db-ann` execution until the selected dense model has fully materialized chunk vectors. Added backend job wiring for `materialize-chunk-embeddings`.
- Key decisions: Kept online `ask` hash retrieval isolated from admin dense evaluation by using `chunk_embeddings` for evaluation chunk ANN and switching online memory lookup to `query_embeddings(hash-embedding-v1)`, leaving `memory_entries.query_embedding` dedicated to dense eval memory ANN.
- Issues encountered: None during compile-only validation.
- Next steps: Smoke the new admin endpoints against a running DB with real dense model availability and verify `status -> materialize -> run` operator flow.

## [2026-05-13] Session Summary (RAG Performance Payload Redesign)
- What was done: Updated `LlmJobService` RAG finalization so `metrics_json.performance` now exposes only the new latency trio (`avg_query_eval_total_latency_ms`, `avg_final_rewrite_latency_ms`, `avg_pure_rewrite_latency_ms`) and sample counts. Deprecated retrieval-side latency payloads are sanitized out of stored retrieval summaries, and legacy summary response payloads no longer expose removed performance fields.
- Key decisions: Deprecated acceptance/rejection/confidence-delta summary columns are no longer populated for new RAG summary writes; no fallback recomputation from representative mode or rewrite-overhead math was added.
- Issues encountered: None.
- Next steps: When a backend-side RAG summary DTO cleanup or migration is scheduled, the deprecated DB columns can be formally dropped instead of left null-populated.

## [2026-05-11] Session Summary (RAG Run Preflight Ordering: No Planned Run on 400)
- What was done: Moved `runRagTest` rewrite-stage preflight validation ahead of `rag_test_run` row creation so `400 Bad Request` cases (e.g., missing rewrite API key) no longer leave `planned` run residue.
- Key decisions: Kept request-validation semantics unchanged and limited the change to operation ordering (`validateRewriteStageLlmConfig` before `createRagTestRun`).
- Issues encountered: None.
- Next steps: Verify Admin GUI repeatedly rejects invalid rewrite requests without accumulating planned run rows.

## [2026-05-11] Session Summary (RAG Rewrite Preflight Gemini Key Resolution + `.env` Fallback)
- What was done: Enhanced `AdminConsoleService` rewrite-stage API-key preflight resolution so Gemini paths accept `QUERY_FORGE_GEMINI_API_KEY`, `QUERY_FORGE_LLM_GEMINI_API_KEY`, `GEMINI_API_KEY`, and `GOOGLE_API_KEY`, and added `.env` fallback lookup when process env is absent. Updated integration-test expected error message accordingly.
- Key decisions: Preserved existing fail-fast contract (`rewrite_enabled=true` still rejects missing keys) and limited changes to key discovery/diagnostic text only.
- Issues encountered: None.
- Next steps: Restart running backend and validate Admin RAG run creation under `.env`-defined `GEMINI_API_KEY`.

## [2026-05-11] Session Summary (Synthetic Batch Delete + Generation Cancel/Purge + ETA + Unlimited Retry)
- What was done: Added `DELETE /api/admin/console/synthetic/batches/{batchId}` and backend deletion flow (`AdminConsoleService` + `AdminConsoleRepository`) to remove generation-batch-linked `llm_job` rows, raw synthetic rows (`synthetic_queries_raw_a..g` by `generation_batch_id`), then delete the batch row while nulling `quality_gating_batch.generation_batch_id` references.
- Key decisions: Removed generation retry cap only for `GENERATE_SYNTHETIC_QUERY` by using `max_retries=-1` sentinel and treating negative retries as unlimited in failure backoff logic; other job types keep existing retry behavior.
- Issues encountered: None.
- Next steps: Validate DB runtime behavior for active-cancel and delete flows against real F/G generation batches.

## [2026-05-11] Session Summary (Synthetic Batch Live Count Query Reflection)
- What was done: Updated `AdminConsoleRepository.findGenerationBatches` and `findGenerationBatch` to return `total_generated_count` as the max of persisted batch count and live raw-row count (`synthetic_queries_raw_all` by `generation_batch_id`).
- Key decisions: Kept generation job lifecycle/status update flow unchanged and avoided schema/API contract changes by reusing the existing `totalGeneratedCount` field.
- Issues encountered: None.
- Next steps: Verify running batch rows in `/api/admin/console/synthetic/batches` increase during `GENERATE_SYNTHETIC_QUERY` execution before batch completion.

## [2026-05-10] Session Summary (Source/Dataset Method Restriction + Synthetic Stats Method-Zero Stability)
- What was done: Added source/dataset strategy-scope guards in `AdminConsoleService` to enforce `Spring -> A~E`, `Python KR -> F/G` for synthetic generation and dataset-bound RAG requests. Extended `AdminConsoleController` synthetic methods API to accept optional context params (`source_id`, `source_document_id`, `dataset_id`) and return filtered methods. Added repository scope resolvers (`findSourceIdByDocumentId`, `findSourceStrategyContext`, `findDatasetStrategyContext`) and fixed synthetic stats `byMethod` aggregation to keep method metadata rows even when count is `0`.
- Key decisions: Prevented ambiguous cross-domain generation by requiring `source_id` or `source_document_id` on synthetic run creation, while keeping context-less method listing available for full inventory view (`A~G`).
- Issues encountered: Runtime DB currently has Spring-family eval datasets only; Python KR dataset-level RAG allow-path validation remains pending until KR eval dataset registration exists.
- Next steps: Register KR Python eval dataset metadata scope (`strategy_profile=python_kr`) and verify RAG dataset-level allow/block matrix (`G allow`, `B block`) through the same backend validation path.

## [2026-05-10] Session Summary (Synthetic F/G Physical Split Migration + Backend Raw Table Registry)
- What was done: Added Flyway `V28__add_kr_source_strategies_f_g.sql` to extend `synthetic_query_generation_method`/`synthetic_query_registry` strategy checks to `A~G`, create `synthetic_queries_raw_f/g`, register F/G sync triggers, seed `method_code` `F/G`, and extend `synthetic_queries_raw_all` union view with `raw_f/raw_g`. Updated `AdminConsoleRepository.STRATEGY_RAW_TABLES` to include `synthetic_queries_raw_f/g`.
- Key decisions: Enforced physical split identity for KR-source strategies (`F/G`) and explicitly avoided any routing/reuse of `C/E` raw tables.
- Issues encountered: None.
- Next steps: Apply Flyway `V28` in the target DB and verify Admin synthetic/gating flows list and cleanup behavior for `F/G`.

## [2026-05-09] Session Summary (Runtime Options 500 NPE Fix)
- What was done: Fixed `GET /api/admin/console/runtime/options` server error by replacing null-unsafe environment candidate construction (`List.of(readEnv(...))`) with a null-tolerant candidate list helper in `AdminConsoleService`.
- Key decisions: Scoped the change to runtime-option candidate collection paths only (`getRuntimeOptions` + fallback catalog builder), preserving catalog parsing, allowlist validation, and response schema.
- Issues encountered: Endpoint failure reproduced as `NullPointerException` at `AdminConsoleService.getRuntimeOptions` when one or more environment variables were unset.
- Next steps: Restart any currently running backend process to load the patched service class.

## [2026-05-08] Session Summary (RAG Run Validation Regression Tests + Docker/Testcontainers Execution Probe)
- What was done: Added Admin Console RAG integration regression cases in `src/test/java/io/queryforge/backend/admin/console/AdminConsoleRagIntegrationTest.java` for (1) catalog allowlist rejection on `llmModel`, (2) catalog allowlist rejection on dense retriever model in RAG request, and (3) `rewrite_enabled=true` rewrite-stage API key preflight rejection.
- Key decisions: Reused existing Testcontainers-based `AdminConsoleRagIntegrationTest` class and added only minimal DB fixture helpers (`eval_dataset`, `quality_gating_batch` + `source_gating_run_id`) to hit validation paths without unrelated refactors.
- Issues encountered: Docker daemon itself was reachable (`docker version` OK), but Testcontainers provider detection failed with `BadRequestException (Status 400)` and empty Docker server fields (label points to `docker_cli` proxy), so Docker-gated integration tests remained skipped (`@Testcontainers(disabledWithoutDocker = true)`).
- Next steps: Resolve local Docker/Testcontainers transport compatibility (or pin supported versions), then rerun `AdminConsoleGatingIntegrationTest` and `AdminConsoleRagIntegrationTest` to confirm newly added assertions execute (not skipped).

## [2026-05-08] Session Summary (Runtime Options Catalog Allowlist + Backend Selection Validation)
- What was done: Switched Admin runtime options source to `configs/app/model_catalog.yml` allowlist and extended `/api/admin/console/runtime/options` response with option metadata (`status`, `availability`, `reason`) and `defaultParameterRanges`. Added service-level validation to reject out-of-catalog `llm_model`, `retriever_mode`, `dense_embedding_model`, and `rewrite_failure_policy` selections before run creation.
- Key decisions: Kept legacy list fields (`llmModels`, `denseEmbeddingModels`, `retrieverModes`, `rewriteFailurePolicies`) for frontend compatibility while adding richer metadata fields.
- Issues encountered: None.
- Next steps: Add RAG-run request integration assertions for catalog validation paths in Docker-enabled backend integration environments.

## [2026-05-08] Session Summary (Rewrite Stage Preflight Validation for RAG Runs)
- What was done: Added preflight validation in `AdminConsoleService.runRagTest` so when `rewrite_enabled=true`, stage `rewrite` requires provider/model/api-key resolution before job enqueue (`llm_rewrite_*`/`llm_*` config + environment fallbacks by provider).
- Key decisions: Enforced fail-fast validation at backend service layer to avoid delayed runtime-stage failures after run creation.
- Issues encountered: None.
- Next steps: Add request-level integration coverage for rewrite-enabled missing API-key rejection path in Docker-enabled backend test environments.

## [2026-05-08] Session Summary (Admin Console Runtime Options + Deterministic Snapshot Enforcement)
- What was done: Added `GET /api/admin/console/runtime/options` and exposed runtime-selectable model/policy options (`llmModels`, `denseEmbeddingModels`, `retrieverModes`, `rewriteFailurePolicies`). Extended gating run request handling for multi-batch/multi-strategy inputs (`generationBatchIds`, `methodCodes`) and LLM override propagation. Updated RAG run request handling with `llmModel` and `rewriteFailurePolicy` persistence into experiment config and experiment record metadata.
- Key decisions: Removed auto-latest snapshot fallback and required explicit `source_gating_batch_id` for non-baseline/non-gating-effect RAG paths to preserve snapshot reproducibility.
- Issues encountered: None after integration; targeted Admin Console integration tests passed.
- Next steps: End-to-end Admin smoke validation on explicit snapshot workflows and mixed-strategy gating inputs.

## [2026-05-04] Session Summary (Anchor Re-extraction Scope Precedence Fix)
- What was done: Updated `AnchorExtractionService.findTargetChunks(...)` so `documentIds` takes precedence over `chunkIds` in `POST /api/admin/corpus/anchors/extract`. Added integration test `anchorReExtractionWithDocumentScopeRemovesDocumentAnchorsFirst` to verify document-wide evidence deletion even when a chunk filter is also present.
- Key decisions: Preserved existing chunk-only scoped re-extraction (`chunkIds` only) and changed only mixed-scope behavior to prevent stale document anchors after re-extraction.
- Issues encountered: Previous mixed-scope behavior used `documentIds AND chunkIds` intersection, which could leave old anchors in non-selected chunks of the same document.
- Next steps: Keep API usage explicit: use `documentIds` for document-level reset/re-extract and `chunkIds` for chunk-only re-extract.

## [2026-05-04] Session Summary (Backend Index: Anchor Injection Purpose Documentation)
- What was done: Updated `backend/index.md` Key Notes to explicitly document that anchor extraction/injection is a rewrite-grounding control for preserving technical intent when Korean rewrite over English technical-doc memory drops anchor terms.
- Key decisions: Kept this as documentation-only clarification tied to existing `rewrite_anchor_injection_enabled` runtime/config path.
- Issues encountered: None.
- Next steps: Apply extraction-quality hardening in pipeline glossary path so injected anchors stay technical (exclude polite/functional phrases).

## [2026-05-04] Session Summary (Backend Documentation Realignment)
- What was done: Updated `backend/README.md` and `backend/index.md` to reflect current backend scope: Admin Console APIs, corpus/pipeline orchestration APIs, online RAG APIs, React admin static serving, warning status model, and anchor extraction delegation to `pipeline/cli.py extract-anchor-candidates`.
- Key decisions: Removed legacy wording that implied Thymeleaf-admin 중심/미구현 RAG 상태, and aligned docs with current controllers/services/migrations already in repository.
- Issues encountered: None.
- Next steps: Keep backend docs synchronized with future API surface changes (`admin/console`, `admin/corpus`, `admin/pipeline`, `rag`).

## [2026-05-04] Session Summary (Anchor Re-extraction -> Pipeline Glossary Delegation)
- What was done: Refactored `AnchorExtractionService` so `POST /api/admin/corpus/anchors/extract` no longer runs backend-local anchor heuristics; it now writes scoped chunk JSONL, calls `python pipeline/cli.py extract-anchor-candidates`, reads returned candidate JSONL, then continues existing glossary evidence replace/term refresh/synthetic remap flow.
- Key decisions: Chose pipeline-logic delegation as the primary path to remove duplicate anchor extraction implementations and keep glossary/anchor candidate semantics aligned between ingest pipeline and backend re-extraction API.
- Issues encountered: The new pipeline command initially failed on UTF-8 BOM JSONL inputs; fixed by reading input with `utf-8-sig` in `pipeline/preprocess/extract_anchor_candidates.py`.
- Next steps: Evaluate extraction precision/coverage deltas on non-Spring sources through existing Anchor Eval runs and tune only in pipeline extractor path when needed.

## [2026-05-04] Session Summary (Anchor Re-extraction Hybrid Candidate Scoring)
- What was done: Upgraded `AnchorExtractionService` keyphrase extraction for `POST /api/admin/corpus/anchors/extract` from simple n-gram accumulation to a hybrid scorer that combines regex-derived technical candidates, phrase normalization, stopword/all-stopword rejection, token rarity bonus (`1/sqrt(freq)`), and technical-marker bonuses (camelCase, symbol separators, alpha+digit patterns). Added integration coverage in `CorpusAdminMutationIntegrationTest` for scoped chunk re-extraction.
- Key decisions: Kept existing extraction pipeline contract and DB flow intact (target chunk resolution -> evidence replacement -> glossary refresh -> synthetic anchor remap) and only strengthened candidate ranking/filtering inside the existing service to minimize churn.
- Issues encountered: None.
- Next steps: Compare anchor precision/coverage on non-Spring technical sources via existing Anchor Eval run flow and tune score weights only if false positives remain high.

## [2026-05-02] Session Summary (Corpus Anchor List API for Pipeline UI)
- What was done: Added `GET /api/admin/corpus/anchors` in `CorpusAdminController/Service/Repository`, with new DTO `AnchorSummary`. The API supports `document_id`, `chunk_id`, `keyword`, `active_only`, `limit`, `offset` and returns paginated anchor rows ordered by scoped evidence density.
- Key decisions: Introduced a dedicated anchor listing path instead of extending existing glossary list responses, so document/chunk evidence scope can be handled as a first-class filter contract.
- Issues encountered: None.
- Next steps: If anchor volume grows significantly, consider adding index tuning around `corpus_glossary_evidence(term_id, document_id, chunk_id)` for scoped query acceleration.

## [2026-05-02] Session Summary (Pipeline Warning Status Model + History Backfill)
- What was done: Added Flyway `V27__add_pipeline_warning_status_and_backfill.sql` to extend `corpus_runs.run_status` and `corpus_run_steps.step_status` with `warning`, then backfilled existing pipeline history to warning where steps were skipped or produced zero-effective outputs. Updated pipeline dashboard issue query to include warning steps in recent problematic-step list.
- Key decisions: Warning was implemented as first-class status (not metadata-only flag) so admin run history and API consumers can distinguish partial-success from full-success consistently.
- Issues encountered: Initial backfill cast failed on heterogeneous `metrics_json` shapes; migration predicates were rewritten with safe text checks/regex-gated numeric parsing.
- Next steps: Restart backend process to activate updated `PipelineAdminService` runtime warning aggregation logic for newly generated runs.

## [2026-05-02] Session Summary (Pipeline full_ingest Failure Debug + Retry)
- What was done: Investigated failed Admin Pipeline run `e28f9bce-37a1-4569-96bb-12dbd62e83ec` (`full_ingest`) and confirmed collect-stage crash on `requests.exceptions.HTTPError` (`404`) for templated URL `https://arahansa.github.io/docs_spring/{spring-framework-docs}/beans.html`. Triggered backend retry API and verified rerun `7be03cad-094c-424c-8852-e164f269b17d` completed with `success`.
- Key decisions: Applied operational recovery only, because collector-side invalid URL/fetch-failure handling was already present in the current workspace at debug time.
- Issues encountered: Rerun succeeded but marked `normalize/chunk/glossary/import` as skipped (`no_documents_pending`) after collect persistence.
- Next steps: If this source needs broader ingestion coverage, tighten source crawling scope configuration to avoid placeholder/template links and improve valid-page discovery.

## [2026-05-01] Session Summary (Anchor Re-extraction API + Active Anchor Mapping)
- What was done: Added Flyway `V25__add_anchor_reextract_and_query_anchor_links.sql` to create `synthetic_query_anchor_link` and backfill link rows from synthetic query source chunks to active glossary evidence. Added corpus API `POST /api/admin/corpus/anchors/extract` and implemented `AnchorExtractionService` to: resolve selected document/chunk scope, replace chunk-level glossary evidence, refresh glossary term active/evidence state, and remap affected synthetic queries to valid active anchors.
- Key decisions: Scoped replacement deletes to selected chunk evidence only, preserving all existing corpus document/chunk rows. Kept legacy raw `glossary_terms` snapshot unchanged for backward compatibility while adding `mappedAnchors` in synthetic query detail as the active-anchor source.
- Issues encountered: None in backend test path; full backend tests passed.
- Next steps: Extend admin GUI trigger for anchor extraction and evaluate adopting mapped active anchors in downstream memory/rewrite runtime paths.

## [2026-04-28] Session Summary (Gating Dense/Hybrid Failure Guard + Retriever Mode Exposure)
- What was done: Updated gating retriever config default so `dense_fallback_enabled` is enabled by default (unless fixed-mode preset flow), preventing whole-batch failure when sentence-transformers/torch dense backend is unavailable. Extended `GatingBatchRow` mapping to expose `retrieverMode` from `stage_config_json.retriever_config.retriever_mode`.
- Key decisions: Kept explicit user override behavior; if operators set fallback false intentionally, strict failure semantics stay available. Added retriever mode exposure in DTO/repository only (no schema change).
- Issues encountered: Existing E-method specific fallback path (`forceBm25RetrieverConfig`) remains in service and is orthogonal to this generic Dense/Hybrid failure guard.
- Next steps: Run one gating batch each with `dense_only` and `hybrid` under no-sentence-transformers environment and verify batch completes with fallback backend.

## [2026-04-28] Session Summary (RAG Rewrite Retrieval Strategy Request/Config Wiring)
- What was done: Extended `RagTestRunRequest` with `rewriteRetrievalStrategy`, added backend normalization/validation (`replace`, `interleave`, `max_score`), and persisted the strategy into generated experiment config plus `rag_eval_experiment_record.rewrite_config`.
- Key decisions: Default strategy remains `replace` when omitted so existing clients and historical run semantics are unchanged.
- Issues encountered: None.
- Next steps: Add API-level integration coverage for invalid strategy rejection and default/explicit strategy config persistence.

## [2026-04-28] Session Summary (E Gating Dense Dependency Fallback)
- What was done: Investigated failed gating batch `15660322-b3a9-4391-a88a-464fc6e5e11a` and confirmed the failure came from retrieval utility dense backend bootstrap (`sentence-transformers` unavailable), then updated `AdminConsoleService.runGating` to force BM25-only retriever config by default for method `E` when retriever config is omitted.
- Key decisions: Kept user-provided retriever config untouched; applied fallback only to default E-path to unblock English strategy gating without changing the overall gating pipeline flow.
- Issues encountered: Existing batch was already persisted with hybrid+dense-required config and cannot be auto-healed; rerun is required.
- Next steps: Re-run quality gating for method `E` (same generation batch) and verify `stage_config_json.retriever_config.retriever_mode = bm25_only` plus successful completion.

## [2026-04-28] Session Summary (Raw E Constraint Hotfix)
- What was done: Added Flyway `V22` to normalize `synthetic_queries_raw_e` after live `E` generation failure, removing copied `D`-only strategy checks, widening the generic strategy check to include `E`, forcing `query_language` default to `en`, and restoring missing FK constraints for `generation_method_id` / `generation_batch_id`.
- Key decisions: Fixed the live DB bug with a follow-up migration instead of rewriting applied `V21`; the fix is idempotent enough to be applied manually first and later picked up by Flyway startup without changing generation/runtime code paths.
- Issues encountered: `V21` created `synthetic_queries_raw_e` via `LIKE synthetic_queries_raw_d INCLUDING CONSTRAINTS`, so `D`-only checks and `A-D` generic checks were copied into the new table and blocked the first real `E` insert.
- Next steps: Apply `V22` in the target DB, retry failed generation job `2b0ed910-8e2f-4186-8424-436b3c9b8148`, and confirm `synthetic_queries_raw_e` rows are written for batch `ca64cad2-27d4-4510-b251-a4037bbd8dfd`.

## [2026-04-28] Session Summary (English Strategy E + Eval Query Language Wiring)
- What was done: Added Flyway `V21` for `synthetic_queries_raw_e`, expanded method/registry constraints to include `E`, added `eval_samples.user_query_en/query_language`, and wired Admin Console DTO/service/repository paths to persist `eval_query_language` and expose English dataset preview fields.
- Key decisions: Admin synthetic remains DB-driven for strategy listing, so backend changes focused on schema/default config normalization and language-aware defaults (`E` => `query_language=en`, Korean-ratio defaults `0.0`).
- Issues encountered: Existing runtime detail loaders assumed `user_query_ko`; `LlmJobService` now resolves display query text by sample language.
- Next steps: Apply the new migration in the target DB and run one Admin synthetic `E` batch plus one snapshot-bound English RAG test.

## [2026-04-21] Session Summary (RAG Test Run Names + Fixed Presets)
- What was done: Extended `RagTestRunRequest` with `runName`, persisted it as `rag_test_run.run_label` and experiment config `run_name`, added migration `V20` to rename legacy auto-labeled RAG runs, and made Admin RAG tests resolve retriever settings through fixed mode presets.
- Key decisions: RAG tests default `retrieval_top_k` to `10`; BM25/Dense/Hybrid now force server-side weights and flags (`candidate_pool_k=50`, Hybrid `0.60/0.32/0.08`, dense model fixed to `intfloat/multilingual-e5-small`, hash fallback off, Cohere rerank off) while leaving quality-gating's existing configurable resolver path intact.
- Issues encountered: None.
- Next steps: Add request-level integration coverage for `runName` persistence and fixed retriever preset normalization.

## [2026-04-21] Session Summary (Retriever Config API Wiring)
- What was done: Added Admin Console `RetrieverConfigRequest` and wired `runGating` / `runRagTest` to write explicit retriever mode, dense model, fallback, rerank, candidate-pool, and fusion-weight settings into stage config, experiment YAML, and RAG experiment records.
- Key decisions: Preserved existing RAG retrieval strategy mode bundling while treating BM25/Dense/Hybrid as a separate ranking-engine config. Default ranking mode is Hybrid with `intfloat/multilingual-e5-small`, dense required, hash fallback disabled, and Cohere rerank enabled.
- Issues encountered: Frontend build regenerated the backend static React JS asset hash.
- Next steps: Add request-level integration assertions for retriever config persistence and run mode-by-mode RAG evaluations through Admin.

## [2026-04-20] Session Summary (RAG Research Mode Bundle)
- What was done: Updated exploratory Admin RAG retrieval mode resolution so synthetic-backed runs include `memory_only_gated` and `rewrite_always` with `raw_only` and the selected selective rewrite mode.
- Key decisions: Kept synthetic-free baseline as `raw_only` only; synthetic-backed runs now satisfy the AGENTS query rewrite evaluation shape needed to distinguish memory quality, forced rewrite quality, and selective gate behavior.
- Issues encountered: None.
- Next steps: Add request-level integration assertions for the bundled exploratory mode list.

## [2026-04-20] Session Summary (RAG Raw Mode Pairing + Threshold Default)
- What was done: Updated `AdminConsoleService.runRagTest` so synthetic-backed RAG runs include `raw_only` with rewrite/memory modes, including official gating-effect runs; synthetic-free baseline still resolves to `raw_only` only.
- Key decisions: Changed backend fallback/default `rewrite_threshold` from `0.05` to `0.10` in request handling and generated experiment configs.
- Issues encountered: None.
- Next steps: Add request-level integration coverage for exploratory selective rewrite, rewrite-always, official gating-effect, and synthetic-free baseline mode resolution.

## [2026-04-19] Session Summary (Prompt-based Rewrite Candidate Generation in Ask Path)
- What was done: Added `RewriteCandidateService` and routed `RagService.ask`/`previewRewrite` candidate construction through prompt-driven LLM generation instead of hardcoded-only templates.
- Key decisions: Prompt loading resolves `selective_rewrite_v2` first (`v1` fallback), supports env-driven Gemini/OpenAI providers, and falls back to deterministic heuristic candidates on any LLM/prompt failure.
- Issues encountered: None.
- Next steps: Add integration coverage for prompt/LLM fallback matrix and monitor rewrite adoption deltas after v2 rollout.

## [2026-04-19] Session Summary (RAG Timezone Baseline + Run Label Time Shortening)
- What was done: Updated backend time baseline settings in `application.yml` to use `Asia/Seoul` for DB session initialization (`SET TIME ZONE 'Asia/Seoul'`) and app-level serialization/JDBC timezone configuration, and shortened newly created RAG run labels in `AdminConsoleService` to `yyyy-MM-dd HH:mm` KST format.
- Key decisions: Kept TIMESTAMPTZ schema and instant-based persistence model unchanged to avoid timestamp semantic breakage; applied timezone baseline at connection/session and presentation-label levels.
- Issues encountered: Existing historical rows remain valid instants; no destructive timestamp shift migration was introduced.
- Next steps: After backend restart, verify newly created run labels and timestamp display alignment in `/admin/rag-tests`.

## [2026-04-19] Session Summary (RAG Delete Full-Cascade Scope)
- What was done: Expanded `AdminConsoleRepository.deleteRagTestRun(...)` to delete run-linked `llm_job` rows before `rag_test_run` removal and then remove linked experiment artifacts by collected `experiment_run_id` set (`eval_judgments`, `retrieval_results`, `rerank_results`, `online_queries`, `experiment_runs`).
- Key decisions: Added run-existence pre-check to keep missing-run semantics stable, and collected experiment IDs from both direct FK (`source_experiment_run_id`) and persisted JSON payloads (`metrics_json`/`metrics`/`result_json`) to prevent orphaned eval history.
- Issues encountered: Retrieval/rerank eval rows may reference run lineage only through metadata JSON, so cleanup uses explicit `metadata ->> 'experiment_run_id'` matching.
- Next steps: Evaluate whether memory-build lineage cleanup (`memory_entries.metadata.memory_build_run_id`) should be included in the same delete boundary.

## [2026-04-19] Session Summary (Gating Pass-Stage Exact-Semantics Update)
- What was done: Updated quality-gating result `pass_stage` filtering semantics in backend so each stage option returns rows that passed up to that stage only and then failed at the immediate next stage (`passed_rule/llm/utility/diversity`), while `passed_all` remains final accepted rows.
- Key decisions: Introduced `failed_rule` as the primary reject filter token and kept backward compatibility by mapping legacy `rejected` to the same behavior in service-layer normalization.
- Issues encountered: None.
- Next steps: Keep API docs/examples aligned with `failed_rule` as canonical and preserve `rejected` only as compatibility alias.

## [2026-04-19] Session Summary (Gating Result Pass-Stage Full Filter Coverage)
- What was done: Extended gating result query filtering to support all quality-gating pass stages via `pass_stage` (`rejected`, `passed_rule`, `passed_llm`, `passed_utility`, `passed_diversity`, `passed_all`) across controller/service/repository layers.
- Key decisions: Preserved existing endpoint shape (`GET /api/admin/console/gating/batches/{gatingBatchId}/results`) and added strict service-layer value normalization with stage-specific SQL predicates in repository to avoid business-logic refactor.
- Issues encountered: None.
- Next steps: Keep API docs/examples aligned with the expanded `pass_stage` set and monitor operator usage for potential alias needs.

## [2026-04-18] Session Summary (RAG Performance Metrics Aggregation for Test Runs)
- What was done: Extended `LlmJobService` RAG finalization path to capture per-stage command duration (`build-memory`, `eval-retrieval`, `eval-answer`) and total run duration, then persisted these as `metrics_json.performance`.
- Key decisions: Added only additive observability fields (`total_duration_ms`, `orchestration_overhead_ms`, `stage_duration_ms`, representative latency, rewrite overhead) without changing retrieval/answer score computation or gating/rewrite decisions.
- Issues encountered: None.
- Next steps: Add backend integration coverage to assert `metrics_json.performance` presence on completed RAG runs and validate rewrite-overhead math against latency rows.

## [2026-04-18] Session Summary (Gating Top10 Score + Request DTO Nesting)
- What was done: Extended admin gating request flow to support `target_top10` utility score and refactored `GatingBatchRunRequest` to nested DTO structure (`GatingRunConfig`) instead of large flat payload fields.
- Key decisions: Kept service defaults/validation semantics intact and mapped nested config into both `stage_config_json` and generated experiment YAML so pipeline execution receives the same operator inputs.
- Issues encountered: None.
- Next steps: Maintain API docs/examples with nested gating request body and add compatibility adapter only if external flat-payload clients are confirmed.

## [2026-04-18] Session Summary (Failed Generation Cleanup Guard)
- What was done: Added failed-generation cleanup path in `LlmJobService.handleJobFailure` to purge synthetic raw rows by `generation_batch_id` when `GENERATE_SYNTHETIC_QUERY` job reaches final failed state (retry exhausted).
- Key decisions: Cleanup targets strategy-split raw tables (`synthetic_queries_raw_a/b/c/d`) via new repository method `deleteSyntheticQueriesByGenerationBatch`, relying on registry FK cascade to remove dependent synthetic-linked rows.
- Issues encountered: None.
- Next steps: Add integration coverage for failure-exhausted generation job ensuring raw rows are removed and batch remains `failed` with cleanup metadata.

## [2026-04-17] Session Summary (Synthetic Run Random Sampling Request Wiring)
- What was done: Extended admin synthetic run DTO (`SyntheticBatchRunRequest`) with `randomChunkSampling` and wrote it to experiment config as `random_chunk_sampling` in `AdminConsoleService.runSyntheticGeneration`.
- Key decisions: Preserved existing validation/ranges (`max_total_queries` up to `2000`) and kept `limit_chunks` optional so full-corpus generation remains available when omitted.
- Issues encountered: None.
- Next steps: Add integration coverage for `random_chunk_sampling=true` request payload to config persistence path.

## [2026-04-17] Session Summary (Stage-Cutoff Validation/Config Wiring)
- What was done: Extended `RagTestRunRequest` with `stageCutoffEnabled/stageCutoffLevel` and updated `AdminConsoleService.runRagTest` to support stage-cutoff memory-source mode from full-gating batches.
- Key decisions: Enforced strict guards (`exploratory` only, `gatingApplied=true`, explicit `sourceGatingBatchId`, source snapshot must be completed `full_gating`, method compatibility, non-null `source_gating_run_id`) and persisted `stage_cutoff_*` keys into run config/experiment record.
- Issues encountered: Existing service contained multiple ongoing feature edits, so stage-cutoff changes were merged without reverting unrelated in-flight changes.
- Next steps: Add/extend API integration test coverage for invalid stage-cutoff combinations and successful full-gating cutoff run creation.

## [2026-04-17] Session Summary (Backend Concurrency/Transaction Scope Hardening)
- What was done: Reduced long transaction windows in high-latency paths by changing `RagService.ask/reindex` to run without a surrounding service transaction, switched `AdminConsoleService.runRagTest` to non-transactional wrapper mode for file-write flow, and added DB-level advisory lock orchestration for pipeline run start in `PipelineAdminService` + `PipelineAdminRepository`.
- Key decisions: Preserved existing business flow/API DTO behavior while tightening only transaction boundaries and start-run concurrency control (`pg_advisory_xact_lock` + re-check active run inside locked transaction).
- Issues encountered: Existing source files include mixed-encoding localized strings, so lock/transaction changes were applied in narrow scoped edits to avoid unrelated churn.
- Next steps: Monitor production metrics for reduced lock-wait/transaction-time in `ask`, `reindex`, and pipeline start bursts.

## [2026-04-17] Session Summary (Synthetic-free Baseline Validation/Config)
- What was done: Extended `RagTestRunRequest` with `syntheticFreeBaseline` and updated `AdminConsoleService.runRagTest` to support exploratory synthetic-free baseline runs with method list empty (`[]`), forced `ungated + rewrite_off`, and baseline-specific retrieval mode config.
- Key decisions: Kept official run discipline unchanged (baseline blocked for official mode), rejected conflicting snapshot/batch inputs in baseline mode, and persisted `synthetic_free_baseline` into experiment config and retrieval metadata.
- Issues encountered: Existing service file already had unrelated pending edits (rule defaults/delete API wiring), so baseline changes were added without reverting external deltas.
- Next steps: Validate API-level rejection cases for baseline + official/snapshot payloads from Admin UI and external clients.

## [2026-04-15] Session Summary (RAG Detail Metric Contribution Enrichment)
- What was done: Updated `LlmJobService.loadRewriteCasesForRun` to persist extended rewrite contribution fields into `rag_test_result_detail.metric_contribution` (`raw_confidence`, `best_candidate_confidence`, `confidence_delta`, `rewrite_reason`).
- Key decisions: Kept existing detail-row schema intact and enriched JSON payload only, so UI/debug consumers can inspect rewrite decisions without DB schema changes.
- Issues encountered: Existing ingestion path only saved retrieval metric deltas (`raw_mrr/mode_mrr/raw_ndcg/mode_ndcg`), which obscured rewrite decision diagnostics.
- Next steps: Expose new contribution fields in run-detail UI if deeper per-sample rewrite analysis is needed.

## [2026-04-14] Session Summary (RAG Job Retry/Timeout Stabilization)
- What was done: Hardened LLM job execution to resume from completed job items on retry, reset non-completed items safely before retry, and clear stale timestamps/messages when rerunning items.
- Key decisions: Prevented repeated execution of already-completed RAG stages during retry and added experiment subprocess timeout control (`query-forge.admin.pipeline.experiment-command-timeout-seconds`).
- Issues encountered: Prior retry behavior could leave inconsistent item states (`running` with old `finished_at`) and allow indefinite command waits.
- Next steps: Monitor running jobs for cleaner state transitions and tune timeout via `QUERY_FORGE_EXPERIMENT_TIMEOUT_SECONDS` if needed.

## [2026-04-14] Session Summary (Eval Result FK Alignment Migration)
- What was done: Added `V18__align_eval_result_fks_to_corpus_storage.sql` to move eval-result FK targets from legacy `documents/chunks` to `corpus_chunks` and to restore missing corpus document FKs.
- Key decisions: Included orphan reference cleanup SQL before constraint re-attachment to avoid immediate `ForeignKeyViolation` during `eval-answer`.
- Issues encountered: Mixed-schema live environments can keep legacy FK definitions if tables predate corpus migrations.
- Next steps: Apply migration in runtime DB and verify RAG jobs complete with aligned corpus FK constraints.

## [2026-04-14] Session Summary (Official RAG Experiment Discipline + Normalized Logging)
- What was done: Extended `runRagTest` validation/config flow to enforce official comparison discipline (`officialRun`, `officialComparisonType`, `comparisonGatingBatchIds`), removed official auto-latest snapshot fallback, enforced bundled official modes, and added standardized experiment record persistence (`rag_eval_experiment_record`, `V19`).
- Key decisions: Official runs now reject conflicting variable combinations instead of silently overriding, and write explicit isolation metadata (`official_variable_axis`, `official_isolation_validated`).
- Issues encountered: Existing summary aggregation collapsed multi-mode retrieval; finalization now persists mode-wise payloads and selects representative mode by priority.
- Next steps: Run DB migration apply and verify official `gating_effect`/`rewrite_effect` requests fail fast on missing or incompatible snapshot identities.

---

## [2026-04-13] Session Summary (Synthetic Raw Split)
- What was done: Added `V17__split_strategy_raw_tables_and_drop_legacy_raw.sql` and switched backend synthetic raw reads from single-table `synthetic_queries_raw` to split-table union view `synthetic_queries_raw_all`.
- Key decisions: `AdminConsoleRepository` batch provenance sync now updates `synthetic_queries_raw_a/b/c/d` separately.
- Issues encountered: Existing workspace had unrelated changes; only split-table refactor paths were touched.
- Next steps: Apply migration in runtime DB and verify Admin Console synthetic/gating/RAG flows with split-table storage.

## [2026-04-13] Session Summary (Gating Re-run Cleanup)
- What was done: Added `AdminConsoleRepository.clearCompletedGatingResults(...)` and wired it into `AdminConsoleService.runGating(...)` to remove prior completed/failed/cancelled gating batches for the same method before starting a new run.
- Key decisions: Used method-scoped cleanup with status guard (`completed/failed/cancelled`) and set `synthetic_queries_gated.gating_batch_id` to `NULL` before deleting batch rows to keep FK consistency.
- Issues encountered: Required explicit coverage for deletion scope (target method only, running rows preserved), so integration test data setup was expanded.
- Next steps: Execute admin QA scenario (same method re-run) and monitor batch/result/history tables for non-accumulating behavior.

## [2026-04-13] Session Summary (Gating Results Filter + Pagination)
- What was done: Extended gating results API to accept `method_code` filter and updated admin gating UI to support method-specific result filtering with page-based navigation (`limit/offset`).
- Key decisions: Reused existing endpoint shape (`List<GatingResultRow>`) and implemented frontend pagination via `pageSize + 1` probing (`hasNext`) to avoid API contract break.
- Issues encountered: Existing UI file had mixed encoding text blocks; functional rewrite of `GatingPage.jsx` was applied while preserving API payload shape.
- Next steps: Run Admin GUI smoke test for A/B/C/D filter combinations and verify page transitions against large batches.

## [2026-04-13] Session Summary (Rule Korean Ratio + Funnel Method Filter)
- What was done: Extended `GatingBatchRunRequest`/`AdminConsoleService` to accept `ruleMinKoreanRatio`, inject it into experiment config, and added `method_code` support to gating funnel API.
- Key decisions: Added dual config keys (`rule_min_korean_ratio`, `rule_min_korean_ratio_code_mixed`) to preserve default behavior while enabling dynamic override from GUI.
- Issues encountered: Existing `quality_gating_stage_result` rows are batch-level aggregates only, so per-method funnel stats are computed from `synthetic_query_gating_result`.
- Next steps: Validate API responses for `GET /gating/batches/{id}/funnel?method_code=A|B|C|D` against real batch data.

## [2026-04-14] Session Summary (RAG Snapshot Batch Binding)
- What was done: Added optional `sourceGatingBatchId` to `RagTestRunRequest` and updated `AdminConsoleService.runRagTest` to bind RAG experiments to a validated gating snapshot (`source_gating_run_id`) when provided.
- Key decisions: Enforced snapshot safety checks (batch exists, completed status, preset/method compatibility, non-null source run) and kept fallback to latest matching gating run when snapshot is omitted.
- Issues encountered: RAG config originally only wrote `memory_generation_strategies`; updated to also emit `source_generation_strategies` for downstream memory builder compatibility.
- Next steps: Add/extend integration coverage for invalid snapshot selection and successful snapshot-bound run config generation.

## [2026-05-11] Session Summary (RAG/Gating ETA Exposure + LLM Job ETA Metrics)
- What was done: Extended Admin DTO/repository projections so ETA-related fields are returned for RAG test runs, gating batches, and LLM jobs (`estimated_seconds_per_*`, `estimated_remaining_seconds`, progress totals). Added SQL-side runtime/historical rate estimation using current run throughput (when available) with completed-history fallback.
- Key decisions: Kept schema unchanged and implemented ETA as read-time derived values; for gating, target query count now resolves from multi-source batch IDs (`stage_config_json.source_generation_batch_ids`) instead of single-batch assumptions.
- Issues encountered: Legacy UI/API payloads had no ETA primitives for RAG/gating, so mapping/query layers required additive field expansion while preserving existing contracts.
- Next steps: Monitor ETA stability against real workloads and tune historical sample windows if operator-observed variance is high.

## [2026-05-13] Session Summary (Admin React Static Bundle Refresh)
- What was done: Rebuilt the Admin React static bundle after RAG dataset-scoped strategy filtering changes, updating `src/main/resources/static/react/index.html` and hashed assets.
- Key decisions: Left backend Java validation unchanged because `AdminConsoleService` already enforces dataset method scope for RAG runs.
- Issues encountered: The frontend bundle hash changed as expected after the RAG form update.
- Next steps: Serve the backend app and smoke-test `/admin/rag-tests` dataset switching against the bundled static UI.

## [2026-05-13] Session Summary (Admin React Bundle Refresh - RAG Detail UI)
- What was done: Rebuilt the Admin React static bundle after RAG compare dock and run-detail readability updates, replacing the hashed JS/CSS assets referenced by `src/main/resources/static/react/index.html`.
- Key decisions: No backend API/DTO changes were needed because the existing RAG detail payload already contains the candidate, metric, and chunk JSON nodes.
- Issues encountered: Static asset hashes changed as expected after the frontend rebuild.
- Next steps: Serve the backend app and smoke-test `/admin/rag-tests` detail modal in dark mode.

## [2026-05-13] Session Summary (Synthetic Source Allowlist Guard)
- What was done: Added synthetic-generation source allowlist validation in `AdminConsoleService` so A/B/C/D/E accept only the five Spring reference sources, F/G accepts only `docs-python-org-ko-3-14`, and `arahansa-github-io-docs-spring` is rejected for method listing and run creation.
- Key decisions: Preserved the single-source request DTO and enforced the new policy at service validation rather than adding multi-source API shape.
- Issues encountered: None in backend compile; the frontend build refreshed the bundled React assets under `src/main/resources/static/react`.
- Next steps: Confirm real Admin GUI launch configs contain only the allowed `source_id` values for each strategy family.

---

## [2026-05-19] Session Summary (Anchor Normalization Candidate Review API)
- What was done: Added migration `V34` for anchor normalization candidate review decisions, exposed single/bulk candidate review endpoints, and changed run approval to apply only approved `would_update` candidates after all non-unchanged candidates are reviewed.
- Key decisions: Conflict/invalid candidates can be explicitly skipped instead of blocking the whole run forever; approving conflict/invalid candidates remains rejected server-side.
- Issues encountered: None; targeted corpus mutation integration tests pass.
- Next steps: Apply Flyway migration in the runtime DB and smoke-test skip-then-approve on `anchor-normalize-255d113f`.

---

## Notes
- Keep this file concise.
- Record only major backend changes.

---

## [2026-05-20] Session Summary (Domain Backfill Verification)
- What was done: Added `V36` domain repair migration and made source config sync attach canonical Spring/Python source IDs to their domains after `corpus_sources` upsert.
- Key decisions: Domain repair uses explicit canonical aliases plus expected document/chunk domain evidence for eval samples, then propagates to datasets, RAG runs, LLM jobs, and anchor artifacts.
- Issues encountered: `V35` handled product aliases but not all reference-style `source_product` values used by existing eval data.
- Next steps: Run Flyway on the runtime DB and spot-check that Spring/Python domain workspaces show the expected source and eval/RAG histories.

---

## [2026-05-20] Session Summary (V35/V36 Migration Repair)
- What was done: Repaired V35/V36 UUID aggregate SQL by replacing `MIN(domain_id)` with `MIN(domain_id::text)::uuid`.
- Key decisions: Kept the one-domain-only grouping checks unchanged to avoid changing backfill semantics or touching ambiguous rows.
- Issues encountered: PostgreSQL 16 rejects `MIN(uuid)`, causing V35 rollback at backend startup.
- Next steps: Restart backend normally; Flyway validation now reports schema version 36.

---

## [2026-05-20] Session Summary (Prompt Admin API 500 Fix)
- What was done: Added explicit SQL parameter casts to Prompt Admin list queries so null `family` filters no longer fail `/prompt-bindings` or `/prompt-assets`.
- Key decisions: Limited the fix to the two failing SELECT predicates and did not alter DTOs, controllers, or response shape.
- Issues encountered: PostgreSQL rejected `? IS NULL` for an untyped nullable bind parameter in prepared statements.
- Next steps: Reload the running backend and verify Prompt Studio loads both asset and binding lists.

---

## [2026-05-27] Session Summary (RAG Dataset Delete + Detail Ordering)
- What was done: Added `DELETE /api/admin/console/rag/datasets/{datasetId}`, wired service/repository cleanup, and changed RAG detail lookup ordering to dataset sample-number order.
- Key decisions: Auto-managed `human_eval_default` is not deletable; custom dataset deletion cascades through existing RAG run cleanup for linked terminal histories and blocks active RAG runs.
- Issues encountered: None; `AdminConsoleRagIntegrationTest` passed.
- Next steps: Verify deletion from the live Admin GUI against a non-default dataset after backend reload.
