package io.queryforge.backend.admin.console.evaluation.run.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.queryforge.backend.admin.console.configuration.service.ConsoleExperimentConfig;
import io.queryforge.backend.admin.console.embedding.service.ChunkEmbeddingAdminService;
import io.queryforge.backend.admin.console.evaluation.anchor.support.CanonicalAnchorVersionMetadata;
import io.queryforge.backend.admin.console.evaluation.dataset.repository.AdminEvalDatasetRepository;
import io.queryforge.backend.admin.console.evaluation.dataset.service.EvalDatasetService;
import io.queryforge.backend.admin.console.evaluation.run.dto.RagTestDtos;
import io.queryforge.backend.admin.console.evaluation.run.repository.RagTestRunRepository;
import io.queryforge.backend.admin.console.gating.dto.GatingDtos;
import io.queryforge.backend.admin.console.gating.repository.GatingRepository;
import io.queryforge.backend.admin.console.jobs.service.LlmJobService;
import io.queryforge.backend.admin.console.runtime.dto.AdminRuntimeOptionsDtos.RuntimeCatalog;
import io.queryforge.backend.admin.console.runtime.service.AdminRetrieverConfiguration;
import io.queryforge.backend.admin.console.runtime.service.AdminRuntimeOptionsService;
import io.queryforge.backend.admin.console.scope.service.ConsoleScopeService;
import io.queryforge.backend.rag.retrieval.agentic.dto.AgenticRetrievalLimits;
import io.queryforge.backend.rag.runtime.configuration.model.RagExecutionSettings;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static io.queryforge.backend.admin.console.configuration.support.ConsoleParameters.asTrimmedString;
import static io.queryforge.backend.admin.console.configuration.support.ConsoleParameters.blankToNull;
import static io.queryforge.backend.admin.console.configuration.support.ConsoleParameters.defaultCreatedBy;
import static io.queryforge.backend.admin.console.configuration.support.ConsoleParameters.firstNonBlank;
import static io.queryforge.backend.admin.console.configuration.support.ConsoleParameters.normalizeGatingPreset;
import static io.queryforge.backend.admin.console.configuration.support.ConsoleParameters.normalizeMethodCodes;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RagTestService {

    private final ConsoleExperimentConfig experimentConfig;
    private final AdminRuntimeOptionsService runtimeOptionsService;
    private final AdminRetrieverConfiguration retrieverConfiguration;
    private final RewriteLlmPreflight rewriteLlmPreflight;
    private final EvalDatasetService evalDatasetService;
    private final ChunkEmbeddingAdminService chunkEmbeddingService;
    private final ConsoleScopeService scopeService;
    private static final String RUN_DISCIPLINE_OFFICIAL = "official";
    private static final String RUN_DISCIPLINE_EXPLORATORY = "exploratory";
    private static final String COMPARISON_GATING_EFFECT = "gating_effect";
    private static final String COMPARISON_REWRITE_EFFECT = "rewrite_effect";
    private static final String REWRITE_FAILURE_POLICY_FAIL_RUN = "fail_run";
    private static final String REWRITE_FAILURE_POLICY_SKIP_TO_RAW = "skip_to_raw";
    private static final String REWRITE_FAILURE_POLICY_HEURISTIC_FALLBACK = "heuristic_fallback";
    private static final String REWRITE_QUERY_PROFILE_COMPACT_ANCHOR = "compact_anchor";
    private static final String REWRITE_QUERY_PROFILE_DETAILED_INTENT = "detailed_intent";
    private static final String SYNTHETIC_FREE_BASELINE_METHOD = "BASELINE";
    private static final String STAGE_CUTOFF_RULE_ONLY = "rule_only";
    private static final String STAGE_CUTOFF_RULE_PLUS_LLM = "rule_plus_llm";
    private static final String STAGE_CUTOFF_UTILITY = "utility";
    private static final String STAGE_CUTOFF_DIVERSITY = "diversity";
    private static final String STAGE_CUTOFF_FULL_GATING = "full_gating";
    private static final String QUERY_LANGUAGE_KO = "ko";
    private static final String QUERY_LANGUAGE_EN = "en";
    private static final String RETRIEVAL_BACKEND_DB_ANN = "db_ann";
    private static final String RETRIEVER_MODE_BM25_ONLY = "bm25_only";
    private static final String RETRIEVER_MODE_HYBRID = "hybrid";
    private static final String VECTOR_STORE_POSTGRESQL_PGVECTOR = "postgresql-pgvector";
    private static final int MAX_RAG_RUN_LABEL_LENGTH = 120;
    private static final ZoneId KOREA_ZONE_ID = ZoneId.of("Asia/Seoul");

    private static final DateTimeFormatter RUN_LABEL_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
            .withZone(KOREA_ZONE_ID);
    private final AdminEvalDatasetRepository evalDatasetRepository;
    private final RagTestRunRepository ragTestRunRepository;
    private final GatingRepository gatingRepository;
    private final LlmJobService llmJobService;
    private final ObjectMapper objectMapper;

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public RagTestDtos.RagTestRunRow runRagTest(RagTestDtos.RagTestRunRequest request) {
        evalDatasetService.ensureDefaultEvalDataset();
        if (request.datasetId() == null) {
            throw new IllegalArgumentException("dataset_id is required");
        }
        UUID domainId = request.domainId();
        scopeService.validateEvalDatasetDomain(domainId, request.datasetId());
        String evalQueryLanguage = normalizeEvalQueryLanguage(
                request.evalQueryLanguage(),
                evalDatasetRepository.findEvalDatasetQueryLanguage(request.datasetId()).orElse(null)
        );
        boolean syntheticFreeBaseline = Boolean.TRUE.equals(request.syntheticFreeBaseline());
        List<String> methodCodes = syntheticFreeBaseline ? List.of() : normalizeMethodCodes(request.methodCodes());
        if (!syntheticFreeBaseline && methodCodes.isEmpty()) {
            throw new IllegalArgumentException("at least one generation method is required");
        }
        if (!syntheticFreeBaseline) {
            scopeService.validateDatasetMethodRestriction(domainId, request.datasetId(), methodCodes);
            scopeService.validateRagMethodLanguageCompatibility(evalQueryLanguage, methodCodes);
        }
        List<UUID> batchIds = request.generationBatchIds() == null ? List.of() : request.generationBatchIds();
        String runDiscipline = normalizeRunDiscipline(request.officialRun());
        String officialComparisonType = normalizeOfficialComparisonType(request.officialComparisonType(), runDiscipline);
        Map<String, UUID> comparisonBatchIds = normalizeComparisonBatchIds(request.comparisonGatingBatchIds());
        UUID sourceGatingBatchId = request.sourceGatingBatchId();
        boolean stageCutoffEnabled = Boolean.TRUE.equals(request.stageCutoffEnabled());
        scopeService.validateGenerationBatchDomains(domainId, batchIds);
        scopeService.validateGatingBatchDomains(domainId, sourceGatingBatchId);
        scopeService.validateGatingBatchDomains(domainId, comparisonBatchIds.values());

        if (syntheticFreeBaseline) {
            if (RUN_DISCIPLINE_OFFICIAL.equals(runDiscipline)) {
                throw new IllegalArgumentException("synthetic-free baseline supports exploratory run only");
            }
            if (!batchIds.isEmpty()) {
                throw new IllegalArgumentException("synthetic-free baseline must not provide generation_batch_ids");
            }
            if (sourceGatingBatchId != null) {
                throw new IllegalArgumentException("synthetic-free baseline must not provide source_gating_batch_id");
            }
            if (!comparisonBatchIds.isEmpty()) {
                throw new IllegalArgumentException("synthetic-free baseline must not provide comparison_gating_batch_ids");
            }
        }

        if (RUN_DISCIPLINE_OFFICIAL.equals(runDiscipline)) {
            if (methodCodes.size() != 1) {
                throw new IllegalArgumentException("official comparison runs require exactly one generation method");
            }
            if (!batchIds.isEmpty()) {
                throw new IllegalArgumentException(
                        "official comparison runs must not provide generation_batch_ids (use explicit snapshot identities only)"
                );
            }
        }

        boolean gatingApplied = request.gatingApplied() == null || request.gatingApplied();
        String gatingPreset = gatingApplied ? normalizeGatingPreset(request.gatingPreset()) : "ungated";
        boolean rewriteEnabled = request.rewriteEnabled() == null || request.rewriteEnabled();
        boolean selectiveRewrite = request.selectiveRewrite() == null || request.selectiveRewrite();
        boolean useSessionContext = request.useSessionContext() != null && request.useSessionContext();
        boolean explicitRetrievalExecutionMode = request.routerEnabled() != null
                || (request.forcedRetrievalMode() != null && !request.forcedRetrievalMode().isBlank());
        boolean routerEnabled = Boolean.TRUE.equals(request.routerEnabled());
        String forcedRetrievalMode = normalizeRetrievalExecutionMode(
                request.forcedRetrievalMode(),
                rewriteEnabled,
                selectiveRewrite,
                useSessionContext
        );
        boolean requestedAgenticMultiQuery = request.agenticMultiQueryEnabled() == null
                ? routerEnabled
                : Boolean.TRUE.equals(request.agenticMultiQueryEnabled());
        if (explicitRetrievalExecutionMode && routerEnabled) {
            forcedRetrievalMode = "strategy_router";
            rewriteEnabled = true;
            selectiveRewrite = true;
            useSessionContext = false;
        } else if (explicitRetrievalExecutionMode) {
            rewriteEnabled = !"raw_only".equals(forcedRetrievalMode);
            selectiveRewrite = List.of(
                    "selective_rewrite",
                    "anchor_aware_rewrite",
                    "agentic_multi_query"
            ).contains(forcedRetrievalMode);
            useSessionContext = false;
        }
        boolean agenticMultiQueryEnabled = "agentic_multi_query".equals(forcedRetrievalMode)
                || (routerEnabled && requestedAgenticMultiQuery);
        boolean rewriteAnchorInjectionEnabled = Boolean.TRUE.equals(request.rewriteAnchorInjectionEnabled());
        boolean multiSourceAnchorExpansionEnabled = Boolean.TRUE.equals(request.multiSourceAnchorExpansionEnabled());
        RuntimeCatalog runtimeCatalog = runtimeOptionsService.loadRuntimeCatalog();
        RagExecutionSettings defaults = runtimeOptionsService.defaultRagExecutionSettings(runtimeCatalog);
        RagExecutionSettings settings = runtimeOptionsService.resolveRagExecutionSettings(
                runtimeCatalog.defaultParameterRanges(),
                defaults,
                new RagExecutionSettings.Overrides(
                        request.retrievalTopK(),
                        request.rerankTopN(),
                        request.memoryTopN(),
                        request.rewriteCandidateCount(),
                        request.threshold()
                )
        );
        AgenticRetrievalLimits agenticLimits = AgenticRetrievalLimits.forRegistration(
                request.agenticMaxSubqueries() == null
                        ? runtimeOptionsService.runtimeDefaultInt(runtimeCatalog, "agentic_max_subqueries",
                                AgenticRetrievalLimits.DEFAULT_EVAL_MAX_SUBQUERIES)
                        : request.agenticMaxSubqueries(),
                request.agenticRrfK() == null
                        ? runtimeOptionsService.runtimeDefaultInt(runtimeCatalog, "agentic_rrf_k",
                                AgenticRetrievalLimits.DEFAULT_RRF_K)
                        : request.agenticRrfK()
        );
        Map<String, Object> retrieverConfig = retrieverConfiguration.resolveRetrieverConfig(request.retrieverConfig(), false, runtimeCatalog);
        runtimeOptionsService.validateLlmModelSelection(blankToNull(request.llmModel()), runtimeCatalog);
        runtimeOptionsService.validateLlmModelSelection(blankToNull(request.rewriteLlmModel()), runtimeCatalog);
        String retrievalBackend = retrieverConfiguration.normalizeRetrievalBackend(request.retrievalBackend(), runtimeCatalog);
        retrieverConfiguration.validateRetrievalBackendSelection(retrievalBackend, runtimeCatalog);
        retrieverConfiguration.validateRetrieverSelection(retrieverConfig, runtimeCatalog);
        validateDbAnnConfigurationIfNeeded(retrievalBackend, retrieverConfig);
        String retrievalEmbeddingModel = blankToNull(asTrimmedString(retrieverConfig.get("dense_embedding_model")));
        if (RETRIEVAL_BACKEND_DB_ANN.equals(retrievalBackend)) {
            chunkEmbeddingService.requireChunkEmbeddingsReady(retrievalEmbeddingModel);
        }
        String runLabel = resolveRagRunLabel(
                request.runName(),
                retrievalBackend + ":" + String.valueOf(retrieverConfig.get("retriever_mode"))
        );
        String rewriteFailurePolicy = RUN_DISCIPLINE_OFFICIAL.equals(runDiscipline)
                ? REWRITE_FAILURE_POLICY_SKIP_TO_RAW
                : normalizeRewriteFailurePolicy(request.rewriteFailurePolicy());
        runtimeOptionsService.validateRewriteFailurePolicySelection(rewriteFailurePolicy, runtimeCatalog);
        String requestedRewriteQueryProfile = normalizeRewriteQueryProfile(request.rewriteQueryProfile(), runtimeCatalog);
        runtimeOptionsService.validateRewriteQueryProfileSelection(requestedRewriteQueryProfile, runtimeCatalog);
        String stageCutoffLevel = normalizeStageCutoffLevel(request.stageCutoffLevel(), gatingPreset);

        if (stageCutoffEnabled) {
            if (syntheticFreeBaseline) {
                throw new IllegalArgumentException("stage-cutoff is not supported in synthetic-free baseline");
            }
            if (!gatingApplied) {
                throw new IllegalArgumentException("stage-cutoff requires gating_applied=true");
            }
            if (RUN_DISCIPLINE_OFFICIAL.equals(runDiscipline)) {
                throw new IllegalArgumentException("stage-cutoff currently supports exploratory runs only");
            }
            if (sourceGatingBatchId == null) {
                throw new IllegalArgumentException("stage-cutoff requires source_gating_batch_id");
            }
        }

        if (RUN_DISCIPLINE_OFFICIAL.equals(runDiscipline) && COMPARISON_GATING_EFFECT.equals(officialComparisonType)) {
            if (sourceGatingBatchId != null) {
                throw new IllegalArgumentException(
                        "official gating-effect runs must use comparison_gating_batch_ids only (no source_gating_batch_id)"
                );
            }
            if (!comparisonBatchIds.keySet().containsAll(List.of("ungated", "rule_only", "full_gating"))
                    || comparisonBatchIds.size() != 3) {
                throw new IllegalArgumentException(
                        "official gating-effect runs require comparison_gating_batch_ids for ungated, rule_only, full_gating"
                );
            }
            if (Boolean.FALSE.equals(request.gatingApplied())) {
                throw new IllegalArgumentException("official gating-effect runs require gating_applied=true");
            }
            if (request.gatingPreset() != null && !request.gatingPreset().isBlank()
                    && !"full_gating".equalsIgnoreCase(request.gatingPreset().trim())) {
                throw new IllegalArgumentException("official gating-effect runs require gating_preset=full_gating");
            }
            if (Boolean.TRUE.equals(request.useSessionContext())) {
                throw new IllegalArgumentException("official gating-effect runs require use_session_context=false");
            }
            gatingApplied = true;
            gatingPreset = "full_gating";
            rewriteEnabled = true;
            selectiveRewrite = true;
            useSessionContext = false;
            routerEnabled = false;
            forcedRetrievalMode = "selective_rewrite";
            agenticMultiQueryEnabled = false;
            rewriteAnchorInjectionEnabled = false;
            multiSourceAnchorExpansionEnabled = false;
        }

        if (RUN_DISCIPLINE_OFFICIAL.equals(runDiscipline) && COMPARISON_REWRITE_EFFECT.equals(officialComparisonType)) {
            if (!comparisonBatchIds.isEmpty()) {
                throw new IllegalArgumentException(
                        "official rewrite-effect runs must use source_gating_batch_id only (no comparison_gating_batch_ids)"
                );
            }
            if (sourceGatingBatchId == null) {
                throw new IllegalArgumentException("official rewrite-effect runs require explicit source_gating_batch_id");
            }
            if (Boolean.FALSE.equals(request.gatingApplied())) {
                throw new IllegalArgumentException("official rewrite-effect runs require gating_applied=true");
            }
            if (Boolean.FALSE.equals(request.rewriteEnabled())) {
                throw new IllegalArgumentException("official rewrite-effect runs require rewrite_enabled=true");
            }
            if (Boolean.FALSE.equals(request.selectiveRewrite())) {
                throw new IllegalArgumentException("official rewrite-effect runs require selective_rewrite=true");
            }
            if (Boolean.TRUE.equals(request.useSessionContext())) {
                throw new IllegalArgumentException("official rewrite-effect runs require use_session_context=false");
            }
            GatingDtos.GatingBatchRow sourceBatch = gatingRepository.findGatingBatch(sourceGatingBatchId)
                    .orElseThrow(() -> new IllegalArgumentException("gating batch not found: " + sourceGatingBatchId));
            if (request.gatingPreset() != null && !request.gatingPreset().isBlank()
                    && !sourceBatch.gatingPreset().equalsIgnoreCase(request.gatingPreset().trim())) {
                throw new IllegalArgumentException(
                        "official rewrite-effect runs require gating_preset to match selected source snapshot"
                );
            }
            gatingApplied = true;
            gatingPreset = sourceBatch.gatingPreset();
            rewriteEnabled = true;
            selectiveRewrite = true;
            useSessionContext = false;
            routerEnabled = false;
            forcedRetrievalMode = "selective_rewrite";
            agenticMultiQueryEnabled = false;
        }

        if (!syntheticFreeBaseline
                && !(RUN_DISCIPLINE_OFFICIAL.equals(runDiscipline)
                && COMPARISON_GATING_EFFECT.equals(officialComparisonType))
                && sourceGatingBatchId == null) {
            throw new IllegalArgumentException(
                    "source_gating_batch_id is required (auto-latest snapshot selection is disabled)"
            );
        }

        if (syntheticFreeBaseline) {
            gatingApplied = false;
            gatingPreset = "ungated";
            rewriteEnabled = false;
            selectiveRewrite = false;
            useSessionContext = false;
            routerEnabled = false;
            forcedRetrievalMode = "raw_only";
            agenticMultiQueryEnabled = false;
            rewriteAnchorInjectionEnabled = false;
            multiSourceAnchorExpansionEnabled = false;
            stageCutoffEnabled = false;
            stageCutoffLevel = null;
        }
        if (!rewriteEnabled) {
            rewriteAnchorInjectionEnabled = false;
        }
        if (!rewriteEnabled || !rewriteAnchorInjectionEnabled || RUN_DISCIPLINE_OFFICIAL.equals(runDiscipline)) {
            multiSourceAnchorExpansionEnabled = false;
        }
        String rewriteQueryProfile = rewriteEnabled
                ? requestedRewriteQueryProfile
                : REWRITE_QUERY_PROFILE_COMPACT_ANCHOR;

        String experimentName = "admin_eval_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        Map<String, Object> config = experimentConfig.baseExperimentConfig(
                experimentName,
                syntheticFreeBaseline ? SYNTHETIC_FREE_BASELINE_METHOD : methodCodes.getFirst(),
                runtimeCatalog,
                settings
        );
        CanonicalAnchorVersionMetadata.putDefaults(config);
        experimentConfig.applyLlmModelOverrides(config, request.llmModel());
        experimentConfig.applyRewriteLlmModelOverride(config, request.rewriteLlmModel());
        config.put("run_name", runLabel);
        config.put("dataset_id", request.datasetId().toString());
        if (domainId != null) {
            config.put("domain_id", domainId.toString());
        }
        config.put("eval_query_language", evalQueryLanguage);
        config.put("memory_generation_strategies", methodCodes);
        config.put("source_generation_strategies", methodCodes);
        config.put("synthetic_free_baseline", syntheticFreeBaseline);
        config.put("gating_applied", gatingApplied);
        config.put("gating_preset", gatingPreset);
        config.put("rewrite_enabled", rewriteEnabled);
        config.put("selective_rewrite", selectiveRewrite);
        config.put("use_session_context", useSessionContext);
        config.put("retrieval_eval_backend", "java");
        config.put("java_backend_domain_id", domainId == null ? null : domainId.toString());
        config.put("mode", routerEnabled ? "strategy_router" : forcedRetrievalMode);
        config.put("router_enabled", routerEnabled);
        config.put("routerEnabled", routerEnabled);
        config.put("forced_retrieval_mode", routerEnabled ? "strategy_router" : forcedRetrievalMode);
        config.put("agentic_multi_query_enabled", agenticMultiQueryEnabled);
        config.put("agenticMultiQueryEnabled", agenticMultiQueryEnabled);
        config.put("agentic_max_subqueries", agenticLimits.maxSubqueries());
        config.put("agenticMaxSubqueries", agenticLimits.maxSubqueries());
        config.put("maxSubqueries", agenticLimits.maxSubqueries());
        config.put("agentic_rrf_k", agenticLimits.rrfK());
        config.put("agenticRrfK", agenticLimits.rrfK());
        config.put("rrfK", agenticLimits.rrfK());
        config.put("rewrite_query_profile", rewriteQueryProfile);
        config.put("rewrite_anchor_injection_enabled", rewriteAnchorInjectionEnabled);
        // memory_lookup_* is retained only for explicit memory_only_* ablation modes.
        // Default rewrite evaluation uses synthetic memory as LLM prompt context only.
        config.put("memory_lookup_intent_preserving_enabled", true);
        config.put("memory_lookup_hint_token_max", 3);
        config.put("memory_lookup_retrieval_strategy", "max_score");
        config.put("rewrite_memory_hint_retrieval_enabled", false);
        config.put("multi_source_anchor_expansion_enabled", multiSourceAnchorExpansionEnabled);
        config.put("multi_source_anchor_relation_version", "multi-source-anchor-v1");
        config.put(
                "multi_source_anchor_relation_types",
                List.of("canonical_alias", "synthetic_query_cooccurrence", "chunk_cooccurrence")
        );
        config.put("multi_source_anchor_min_score", 0.72d);
        config.put("multi_source_anchor_max_total", 8);
        config.put("multi_source_anchor_max_per_seed", 2);
        config.put("rewrite_failure_policy", rewriteFailurePolicy);
        config.put("rewrite_adoption_policy", experimentConfig.rewriteAdoptionPolicyForProfile(
                rewriteQueryProfile, settings.rewriteThreshold(), defaults.rewriteThreshold()
        ));
        config.put("rewrite_prompt_profile", resolveRewritePromptProfile(evalQueryLanguage));
        retrieverConfiguration.attachRetrieverConfig(config, retrieverConfig);
        config.put("retrieval_backend", retrievalBackend);
        config.put("chunk_embedding_model", retrievalEmbeddingModel);
        config.put("vector_store", RETRIEVAL_BACKEND_DB_ANN.equals(retrievalBackend) ? VECTOR_STORE_POSTGRESQL_PGVECTOR : null);
        config.put("fallback_used", false);
        config.put("run_discipline", runDiscipline);
        config.put("stage_cutoff_enabled", stageCutoffEnabled);
        if (stageCutoffEnabled) {
            config.put("stage_cutoff_level", stageCutoffLevel);
        }
        if (RUN_DISCIPLINE_OFFICIAL.equals(runDiscipline)) {
            config.put("official_comparison_type", officialComparisonType);
            config.put("official_variable_axis", officialComparisonType);
            config.put("official_isolation_validated", true);
            config.put("eval_policy_version", 2);
            config.put("answer_eval_mode", COMPARISON_GATING_EFFECT.equals(officialComparisonType)
                    ? "selective_rewrite_full_gating" : "selective_rewrite");
        }

        if (syntheticFreeBaseline) {
            config.put("retrieval_modes", List.of("raw_only"));
        } else if (RUN_DISCIPLINE_OFFICIAL.equals(runDiscipline) && COMPARISON_GATING_EFFECT.equals(officialComparisonType)) {
            Map<String, Object> comparisonSnapshots = new LinkedHashMap<>();
            UUID ungatedRunId = resolveSourceGatingRunId(methodCodes, "ungated", comparisonBatchIds.get("ungated"))
                    .orElseThrow(() -> new IllegalStateException("ungated snapshot source run not found"));
            UUID ruleOnlyRunId = resolveSourceGatingRunId(methodCodes, "rule_only", comparisonBatchIds.get("rule_only"))
                    .orElseThrow(() -> new IllegalStateException("rule_only snapshot source run not found"));
            UUID fullGatingRunId = resolveSourceGatingRunId(methodCodes, "full_gating", comparisonBatchIds.get("full_gating"))
                    .orElseThrow(() -> new IllegalStateException("full_gating snapshot source run not found"));

            comparisonSnapshots.put(
                    "ungated",
                    Map.of(
                            "gating_batch_id", comparisonBatchIds.get("ungated").toString(),
                            "source_gating_run_id", ungatedRunId.toString()
                    )
            );
            comparisonSnapshots.put(
                    "rule_only",
                    Map.of(
                            "gating_batch_id", comparisonBatchIds.get("rule_only").toString(),
                            "source_gating_run_id", ruleOnlyRunId.toString()
                    )
            );
            comparisonSnapshots.put(
                    "full_gating",
                    Map.of(
                            "gating_batch_id", comparisonBatchIds.get("full_gating").toString(),
                            "source_gating_run_id", fullGatingRunId.toString()
                    )
            );
            config.put("comparison_gating_batch_ids", comparisonBatchIds);
            config.put("comparison_snapshots", comparisonSnapshots);
            config.put(
                    "retrieval_modes",
                    List.of("raw_only", "selective_rewrite_ungated", "selective_rewrite_rule_only", "selective_rewrite_full_gating")
            );
            config.put("snapshot_id", comparisonBatchIds.get("full_gating").toString());
        } else if (RUN_DISCIPLINE_OFFICIAL.equals(runDiscipline)
                && COMPARISON_REWRITE_EFFECT.equals(officialComparisonType)) {
            Optional<UUID> sourceGatingRunId = resolveSourceGatingRunId(
                    methodCodes,
                    gatingPreset,
                    sourceGatingBatchId
            );
            config.put("retrieval_modes", List.of("raw_only", "selective_rewrite"));
            config.put("source_gating_batch_id", sourceGatingBatchId.toString());
            config.put("snapshot_id", sourceGatingBatchId.toString());
            sourceGatingRunId.ifPresent(uuid -> config.put("source_gating_run_id", uuid.toString()));
        } else {
            config.put(
                    "retrieval_modes",
                    explicitRetrievalExecutionMode
                            ? List.of(routerEnabled ? "strategy_router" : forcedRetrievalMode)
                            : resolveRetrievalModes(rewriteEnabled, selectiveRewrite, useSessionContext)
            );
            Optional<UUID> sourceGatingRunId = stageCutoffEnabled
                    ? resolveStageCutoffSourceGatingRunId(methodCodes, sourceGatingBatchId)
                    : resolveSourceGatingRunId(
                    methodCodes,
                    gatingPreset,
                    sourceGatingBatchId
            );
            if (sourceGatingBatchId != null) {
                config.put("source_gating_batch_id", sourceGatingBatchId.toString());
                config.put("snapshot_id", sourceGatingBatchId.toString());
                if (stageCutoffEnabled) {
                    config.put("stage_cutoff_source_gating_batch_id", sourceGatingBatchId.toString());
                }
            }
            sourceGatingRunId.ifPresent(uuid -> config.put("source_gating_run_id", uuid.toString()));
        }

        rewriteLlmPreflight.validateRewriteStageLlmConfig(config, rewriteEnabled);
        JsonNode methodCodesNode = objectMapper.valueToTree(methodCodes);
        JsonNode batchIdsNode = objectMapper.valueToTree(batchIds);
        UUID runId = ragTestRunRepository.createRagTestRun(
                runLabel,
                request.datasetId(),
                domainId,
                methodCodesNode,
                batchIdsNode,
                gatingApplied,
                gatingPreset,
                rewriteEnabled,
                selectiveRewrite,
                useSessionContext,
                rewriteAnchorInjectionEnabled,
                multiSourceAnchorExpansionEnabled,
                request.topK(),
                settings.rewriteThreshold(),
                settings.retrievalTopK(),
                settings.rerankTopN(),
                experimentName,
                defaultCreatedBy(request.createdBy())
        );
        experimentConfig.writeExperimentConfig(experimentName, config);
        ragTestRunRepository.upsertRagTestRunConfig(runId, objectMapper.valueToTree(config));
        String initialSnapshotId = firstNonBlank(
                config.get("snapshot_id") == null ? null : String.valueOf(config.get("snapshot_id")),
                sourceGatingBatchId == null ? null : sourceGatingBatchId.toString(),
                "UNSPECIFIED"
        );
        Map<String, Object> initialGatingConfig = new LinkedHashMap<>();
        CanonicalAnchorVersionMetadata.putDefaults(initialGatingConfig);
        initialGatingConfig.put("gating_preset", gatingPreset);
        initialGatingConfig.put("gating_applied", gatingApplied);
        initialGatingConfig.put("comparison_snapshots", config.get("comparison_snapshots"));
        initialGatingConfig.put("stage_cutoff_enabled", stageCutoffEnabled);
        initialGatingConfig.put("stage_cutoff_level", stageCutoffEnabled ? stageCutoffLevel : null);
        initialGatingConfig.put("stage_cutoff_source_gating_batch_id", config.get("stage_cutoff_source_gating_batch_id"));
        Map<String, Object> initialRetrievalConfig = new LinkedHashMap<>();
        initialRetrievalConfig.put("retrieval_backend", retrievalBackend);
        initialRetrievalConfig.put("embedding_model", retrievalEmbeddingModel);
        initialRetrievalConfig.put(
                "vector_store",
                RETRIEVAL_BACKEND_DB_ANN.equals(retrievalBackend) ? VECTOR_STORE_POSTGRESQL_PGVECTOR : null
        );
        initialRetrievalConfig.put("fallback_used", false);
        initialRetrievalConfig.put("retrieval_top_k", settings.retrievalTopK());
        initialRetrievalConfig.put("rerank_top_n", settings.rerankTopN());
        initialRetrievalConfig.put("memory_top_n", settings.memoryTopN());
        initialRetrievalConfig.put("retrieval_modes", config.get("retrieval_modes"));
        initialRetrievalConfig.put("retrieval_eval_backend", "java");
        initialRetrievalConfig.put("router_enabled", routerEnabled);
        initialRetrievalConfig.put("forced_retrieval_mode", routerEnabled ? "strategy_router" : forcedRetrievalMode);
        initialRetrievalConfig.put("agentic_multi_query_enabled", agenticMultiQueryEnabled);
        initialRetrievalConfig.put("agentic_max_subqueries", agenticLimits.maxSubqueries());
        initialRetrievalConfig.put("agentic_rrf_k", agenticLimits.rrfK());
        initialRetrievalConfig.put("synthetic_free_baseline", syntheticFreeBaseline);
        initialRetrievalConfig.put("retriever_config", retrieverConfig);
        CanonicalAnchorVersionMetadata.putDefaults(initialRetrievalConfig);
        Map<String, Object> initialRewriteConfig = new LinkedHashMap<>();
        initialRewriteConfig.put("rewrite_enabled", rewriteEnabled);
        initialRewriteConfig.put("selective_rewrite", selectiveRewrite);
        initialRewriteConfig.put("use_session_context", useSessionContext);
        initialRewriteConfig.put("rewrite_threshold", settings.rewriteThreshold());
        initialRewriteConfig.put("rewrite_candidate_count", settings.rewriteCandidateCount());
        initialRewriteConfig.put("rewrite_query_profile", rewriteQueryProfile);
        initialRewriteConfig.put("llm_rewrite_model", config.get("llm_rewrite_model"));
        initialRewriteConfig.put("rewrite_anchor_injection_enabled", rewriteAnchorInjectionEnabled);
        initialRewriteConfig.put("memory_lookup_intent_preserving_enabled", config.get("memory_lookup_intent_preserving_enabled"));
        initialRewriteConfig.put("memory_lookup_hint_token_max", config.get("memory_lookup_hint_token_max"));
        initialRewriteConfig.put("memory_lookup_retrieval_strategy", config.get("memory_lookup_retrieval_strategy"));
        initialRewriteConfig.put("rewrite_memory_hint_retrieval_enabled", config.get("rewrite_memory_hint_retrieval_enabled"));
        initialRewriteConfig.put("multi_source_anchor_expansion_enabled", multiSourceAnchorExpansionEnabled);
        initialRewriteConfig.put("multi_source_anchor_relation_version", config.get("multi_source_anchor_relation_version"));
        initialRewriteConfig.put("multi_source_anchor_relation_types", config.get("multi_source_anchor_relation_types"));
        initialRewriteConfig.put("multi_source_anchor_min_score", config.get("multi_source_anchor_min_score"));
        initialRewriteConfig.put("multi_source_anchor_max_total", config.get("multi_source_anchor_max_total"));
        initialRewriteConfig.put("multi_source_anchor_max_per_seed", config.get("multi_source_anchor_max_per_seed"));
        initialRewriteConfig.put("rewrite_failure_policy", rewriteFailurePolicy);
        initialRewriteConfig.put("rewrite_prompt_profile", resolveRewritePromptProfile(evalQueryLanguage));
        CanonicalAnchorVersionMetadata.putDefaults(initialRewriteConfig);
        ragTestRunRepository.upsertRagExperimentRecord(
                runId,
                initialSnapshotId,
                objectMapper.valueToTree(methodCodes),
                objectMapper.valueToTree(initialGatingConfig),
                0,
                objectMapper.valueToTree(initialRetrievalConfig),
                objectMapper.valueToTree(initialRewriteConfig),
                ragTestRunRepository.findRagDatasetVersion(runId),
                Instant.now(),
                objectMapper.createObjectNode()
        );
        llmJobService.createRagTestJob(runId, experimentName, defaultCreatedBy(request.createdBy()));
        return ragTestRunRepository.findRagTestRun(runId)
                .orElseThrow(() -> new IllegalStateException("rag test run not found after enqueue: " + runId));
    }

    private Optional<UUID> resolveSourceGatingRunId(
            List<String> methodCodes,
            String gatingPreset,
            UUID sourceGatingBatchId
    ) {
        if (sourceGatingBatchId == null) {
            throw new IllegalArgumentException(
                    "source_gating_batch_id is required (auto-latest snapshot selection is disabled)"
            );
        }
        GatingDtos.GatingBatchRow batch = gatingRepository.findGatingBatch(sourceGatingBatchId)
                .orElseThrow(() -> new IllegalArgumentException("gating batch not found: " + sourceGatingBatchId));
        if (!"completed".equalsIgnoreCase(batch.status())) {
            throw new IllegalArgumentException("gating batch is not completed: " + sourceGatingBatchId);
        }
        if (!gatingPreset.equalsIgnoreCase(batch.gatingPreset())) {
            throw new IllegalArgumentException(
                    "gating batch preset mismatch: expected=" + gatingPreset + ", actual=" + batch.gatingPreset()
            );
        }
        if (batch.methodCode() != null && !methodCodes.contains(batch.methodCode().toUpperCase())) {
            throw new IllegalArgumentException(
                    "gating batch method mismatch: expected one of=" + methodCodes + ", actual=" + batch.methodCode()
            );
        }
        if (batch.sourceGatingRunId() == null) {
            throw new IllegalArgumentException("gating batch has no source_gating_run_id: " + sourceGatingBatchId);
        }
        return Optional.of(batch.sourceGatingRunId());
    }

    private Optional<UUID> resolveStageCutoffSourceGatingRunId(List<String> methodCodes, UUID sourceGatingBatchId) {
        if (sourceGatingBatchId == null) {
            throw new IllegalArgumentException("stage-cutoff requires source_gating_batch_id");
        }
        GatingDtos.GatingBatchRow batch = gatingRepository.findGatingBatch(sourceGatingBatchId)
                .orElseThrow(() -> new IllegalArgumentException("gating batch not found: " + sourceGatingBatchId));
        if (!"completed".equalsIgnoreCase(batch.status())) {
            throw new IllegalArgumentException("gating batch is not completed: " + sourceGatingBatchId);
        }
        if (!STAGE_CUTOFF_FULL_GATING.equalsIgnoreCase(batch.gatingPreset())) {
            throw new IllegalArgumentException(
                    "stage-cutoff source snapshot must be full_gating: actual=" + batch.gatingPreset()
            );
        }
        if (batch.methodCode() != null && !methodCodes.contains(batch.methodCode().toUpperCase())) {
            throw new IllegalArgumentException(
                    "gating batch method mismatch: expected one of=" + methodCodes + ", actual=" + batch.methodCode()
            );
        }
        if (batch.sourceGatingRunId() == null) {
            throw new IllegalArgumentException("gating batch has no source_gating_run_id: " + sourceGatingBatchId);
        }
        return Optional.of(batch.sourceGatingRunId());
    }

    private String normalizeRunDiscipline(Boolean officialRun) {
        return Boolean.TRUE.equals(officialRun) ? RUN_DISCIPLINE_OFFICIAL : RUN_DISCIPLINE_EXPLORATORY;
    }

    private String normalizeOfficialComparisonType(String value, String runDiscipline) {
        if (!RUN_DISCIPLINE_OFFICIAL.equals(runDiscipline)) {
            return null;
        }
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    "official comparison runs require official_comparison_type (gating_effect or rewrite_effect)"
            );
        }
        String normalized = value.trim().toLowerCase();
        if (!List.of(COMPARISON_GATING_EFFECT, COMPARISON_REWRITE_EFFECT).contains(normalized)) {
            throw new IllegalArgumentException("unsupported official_comparison_type: " + value);
        }
        return normalized;
    }

    private String normalizeStageCutoffLevel(String value, String gatingPreset) {
        if (value == null || value.isBlank()) {
            if ("rule_only".equalsIgnoreCase(gatingPreset)) {
                return STAGE_CUTOFF_RULE_ONLY;
            }
            if ("rule_plus_llm".equalsIgnoreCase(gatingPreset)) {
                return STAGE_CUTOFF_RULE_PLUS_LLM;
            }
            return STAGE_CUTOFF_FULL_GATING;
        }
        String normalized = value.trim().toLowerCase();
        if (!List.of(
                STAGE_CUTOFF_RULE_ONLY,
                STAGE_CUTOFF_RULE_PLUS_LLM,
                STAGE_CUTOFF_UTILITY,
                STAGE_CUTOFF_DIVERSITY,
                STAGE_CUTOFF_FULL_GATING
        ).contains(normalized)) {
            throw new IllegalArgumentException("unsupported stage_cutoff_level: " + value);
        }
        return normalized;
    }

    private String normalizeRewriteFailurePolicy(String value) {
        if (value == null || value.isBlank()) {
            return REWRITE_FAILURE_POLICY_FAIL_RUN;
        }
        String normalized = value.trim().toLowerCase().replace("-", "_");
        if (!List.of(
                REWRITE_FAILURE_POLICY_FAIL_RUN,
                REWRITE_FAILURE_POLICY_SKIP_TO_RAW,
                REWRITE_FAILURE_POLICY_HEURISTIC_FALLBACK
        ).contains(normalized)) {
            throw new IllegalArgumentException("unsupported rewrite_failure_policy: " + value);
        }
        return normalized;
    }

    private String normalizeRewriteQueryProfile(String value, RuntimeCatalog catalog) {
        if (value == null || value.isBlank()) {
            return firstNonBlank(
                    catalog.defaultRewriteQueryProfile(),
                    REWRITE_QUERY_PROFILE_COMPACT_ANCHOR
            );
        }
        String normalized = value.trim().toLowerCase().replace("-", "_");
        if (!List.of(
                REWRITE_QUERY_PROFILE_COMPACT_ANCHOR,
                REWRITE_QUERY_PROFILE_DETAILED_INTENT
        ).contains(normalized)) {
            throw new IllegalArgumentException("unsupported rewrite_query_profile: " + value);
        }
        return normalized;
    }

    private Map<String, UUID> normalizeComparisonBatchIds(Map<String, UUID> value) {
        if (value == null || value.isEmpty()) {
            return Map.of();
        }
        LinkedHashMap<String, UUID> normalized = new LinkedHashMap<>();
        value.forEach((key, batchId) -> {
            if (key == null || key.isBlank() || batchId == null) {
                return;
            }
            normalized.put(key.trim().toLowerCase(), batchId);
        });
        return normalized;
    }

    private void validateDbAnnConfigurationIfNeeded(String retrievalBackend, Map<String, Object> retrieverConfig) {
        if (!RETRIEVAL_BACKEND_DB_ANN.equals(retrievalBackend)) {
            return;
        }
        String retrieverMode = runtimeOptionsService.normalizeRetrieverMode(asTrimmedString(retrieverConfig.get("retriever_mode")));
        if (RETRIEVER_MODE_BM25_ONLY.equals(retrieverMode)) {
            throw new IllegalArgumentException("db-ann retrieval_backend requires retriever_mode=dense_only or hybrid");
        }
        boolean denseFallbackEnabled = Boolean.TRUE.equals(retrieverConfig.get("dense_fallback_enabled"));
        if (denseFallbackEnabled) {
            throw new IllegalArgumentException("db-ann retrieval_backend must not enable dense_fallback_enabled");
        }
        String denseModel = blankToNull(asTrimmedString(retrieverConfig.get("dense_embedding_model")));
        if (denseModel == null) {
            throw new IllegalArgumentException("db-ann retrieval_backend requires dense_embedding_model");
        }
    }

    private String resolveRagRunLabel(String requestedRunName, String retrieverMode) {
        String normalized = blankToNull(requestedRunName);
        if (normalized != null) {
            if (normalized.length() > MAX_RAG_RUN_LABEL_LENGTH) {
                throw new IllegalArgumentException("run_name must be at most " + MAX_RAG_RUN_LABEL_LENGTH + " characters");
            }
            return normalized;
        }
        String mode = blankToNull(retrieverMode);
        if (mode == null) {
            mode = RETRIEVER_MODE_HYBRID;
        }
        return "RAG " + mode + " " + RUN_LABEL_TIME_FORMATTER.format(Instant.now());
    }

    private List<String> resolveRetrievalModes(boolean rewriteEnabled, boolean selectiveRewrite, boolean useSessionContext) {
        if (!rewriteEnabled) {
            return List.of("raw_only");
        }
        if (!selectiveRewrite) {
            return List.of("raw_only");
        }
        if (useSessionContext) {
            return List.of("raw_only", "selective_rewrite_with_session");
        }
        return List.of("raw_only", "selective_rewrite");
    }

    private String normalizeRetrievalExecutionMode(
            String requestedMode,
            boolean rewriteEnabled,
            boolean selectiveRewrite,
            boolean useSessionContext
    ) {
        String normalized = requestedMode == null || requestedMode.isBlank()
                ? null
                : requestedMode.trim().toLowerCase(Locale.ROOT).replace("-", "_");
        if (normalized == null) {
            return resolveRetrievalModes(rewriteEnabled, selectiveRewrite, useSessionContext).getLast();
        }
        if ("multi_agentic".equals(normalized) || "multi_agentic_query".equals(normalized)) {
            normalized = "agentic_multi_query";
        }
        if (!List.of(
                "raw_only",
                "selective_rewrite",
                "anchor_aware_rewrite",
                "agentic_multi_query",
                "strategy_router"
        ).contains(normalized)) {
            throw new IllegalArgumentException("forced_retrieval_mode is not supported: " + normalized);
        }
        return normalized;
    }

    private String normalizeEvalQueryLanguage(String requested, String datasetDefault) {
        String normalizedRequested = blankToNull(requested);
        String normalizedDatasetDefault = blankToNull(datasetDefault);
        if (normalizedRequested != null) {
            normalizedRequested = normalizedRequested.trim().toLowerCase();
        }
        if (normalizedDatasetDefault != null) {
            normalizedDatasetDefault = normalizedDatasetDefault.trim().toLowerCase();
        }
        if (normalizedRequested != null
                && normalizedDatasetDefault != null
                && !normalizedRequested.equals(normalizedDatasetDefault)) {
            throw new IllegalArgumentException(
                    "eval_query_language must match dataset query language: requested="
                            + normalizedRequested + ", dataset=" + normalizedDatasetDefault
            );
        }
        String candidate = firstNonBlank(normalizedDatasetDefault, normalizedRequested, QUERY_LANGUAGE_KO);
        candidate = candidate == null ? QUERY_LANGUAGE_KO : candidate.trim().toLowerCase();
        if (!List.of(QUERY_LANGUAGE_KO, QUERY_LANGUAGE_EN).contains(candidate)) {
            throw new IllegalArgumentException("unsupported eval_query_language: " + requested);
        }
        return candidate;
    }

    private String resolveRewritePromptProfile(String evalQueryLanguage) {
        return QUERY_LANGUAGE_EN.equalsIgnoreCase(evalQueryLanguage) ? "en" : "ko";
    }
}
