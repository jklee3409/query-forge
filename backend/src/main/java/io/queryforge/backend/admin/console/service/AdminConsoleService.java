package io.queryforge.backend.admin.console.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.queryforge.backend.admin.console.model.AdminConsoleDtos;
import io.queryforge.backend.admin.console.repository.AdminConsoleRepository;
import io.queryforge.backend.admin.pipeline.config.AdminPipelineProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AdminConsoleService {

    private static final String DEFAULT_DATASET_KEY = "human_eval_default";
    private static final String RUN_DISCIPLINE_OFFICIAL = "official";
    private static final String RUN_DISCIPLINE_EXPLORATORY = "exploratory";
    private static final String COMPARISON_GATING_EFFECT = "gating_effect";
    private static final String COMPARISON_REWRITE_EFFECT = "rewrite_effect";
    private static final String REWRITE_RETRIEVAL_STRATEGY_REPLACE = "replace";
    private static final String REWRITE_RETRIEVAL_STRATEGY_INTERLEAVE = "interleave";
    private static final String REWRITE_RETRIEVAL_STRATEGY_MAX_SCORE = "max_score";
    private static final String REWRITE_FAILURE_POLICY_FAIL_RUN = "fail_run";
    private static final String REWRITE_FAILURE_POLICY_SKIP_TO_RAW = "skip_to_raw";
    private static final String REWRITE_FAILURE_POLICY_HEURISTIC_FALLBACK = "heuristic_fallback";
    private static final String SYNTHETIC_FREE_BASELINE_METHOD = "BASELINE";
    private static final String STAGE_CUTOFF_RULE_ONLY = "rule_only";
    private static final String STAGE_CUTOFF_RULE_PLUS_LLM = "rule_plus_llm";
    private static final String STAGE_CUTOFF_UTILITY = "utility";
    private static final String STAGE_CUTOFF_DIVERSITY = "diversity";
    private static final String STAGE_CUTOFF_FULL_GATING = "full_gating";
    private static final String GATING_PASS_STAGE_FAILED_RULE = "failed_rule";
    private static final String GATING_PASS_STAGE_REJECTED = "rejected";
    private static final String GATING_PASS_STAGE_PASSED_RULE = "passed_rule";
    private static final String GATING_PASS_STAGE_PASSED_LLM = "passed_llm";
    private static final String GATING_PASS_STAGE_PASSED_UTILITY = "passed_utility";
    private static final String GATING_PASS_STAGE_PASSED_DIVERSITY = "passed_diversity";
    private static final String GATING_PASS_STAGE_PASSED_ALL = "passed_all";
    private static final String QUERY_LANGUAGE_KO = "ko";
    private static final String QUERY_LANGUAGE_EN = "en";
    private static final String RETRIEVAL_BACKEND_LOCAL = "local";
    private static final String RETRIEVAL_BACKEND_DB_ANN = "db_ann";
    private static final String RETRIEVER_MODE_BM25_ONLY = "bm25_only";
    private static final String RETRIEVER_MODE_DENSE_ONLY = "dense_only";
    private static final String RETRIEVER_MODE_HYBRID = "hybrid";
    private static final String DEFAULT_LLM_MODEL = "gemini-2.5-flash-lite";
    private static final String DEFAULT_LLM_FALLBACK_MODEL = "gemini-2.5-flash";
    private static final String DEFAULT_DENSE_EMBEDDING_MODEL = "intfloat/multilingual-e5-small";
    private static final String MODEL_CATALOG_RELATIVE_PATH = "configs/app/model_catalog.yml";
    private static final double DEFAULT_HYBRID_DENSE_WEIGHT = 0.58d;
    private static final double DEFAULT_HYBRID_BM25_WEIGHT = 0.34d;
    private static final double DEFAULT_HYBRID_TECHNICAL_WEIGHT = 0.08d;
    private static final int DEFAULT_RAG_RETRIEVAL_TOP_K = 10;
    private static final int DEFAULT_RETRIEVER_CANDIDATE_POOL_K = 50;
    private static final double DEFAULT_RAG_HYBRID_DENSE_WEIGHT = 0.60d;
    private static final double DEFAULT_RAG_HYBRID_BM25_WEIGHT = 0.32d;
    private static final double DEFAULT_RAG_HYBRID_TECHNICAL_WEIGHT = 0.08d;
    private static final String VECTOR_STORE_POSTGRESQL_PGVECTOR = "postgresql-pgvector";
    private static final int MAX_RAG_RUN_LABEL_LENGTH = 120;
    private static final Set<String> ALL_METHOD_CODES = Set.of("A", "B", "C", "D", "E", "F", "G");
    private static final Set<String> SPRING_TECHDOC_METHOD_CODES = Set.of("A", "B", "C", "D", "E");
    private static final Set<String> PYTHON_KR_METHOD_CODES = Set.of("F", "G");
    private static final Set<String> DISALLOWED_SYNTHETIC_SOURCE_IDS = Set.of("arahansa-github-io-docs-spring");
    private static final Set<String> SPRING_TECHDOC_SOURCE_IDS = Set.of(
            "spring-boot-reference",
            "spring-data-commons-reference",
            "spring-data-jpa-reference",
            "spring-framework-reference",
            "spring-security-reference"
    );
    private static final Set<String> PYTHON_KR_SOURCE_IDS = Set.of("docs-python-org-ko-3-14");
    private static final String SCOPE_LABEL_SPRING_TECHDOC = "spring_techdoc";
    private static final String SCOPE_LABEL_PYTHON_KR = "python_kr";
    private static final Pattern NON_ALNUM_PATTERN = Pattern.compile("[^A-Z0-9]");
    private static final ZoneId KOREA_ZONE_ID = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter RUN_LABEL_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
            .withZone(KOREA_ZONE_ID);

    private final AdminConsoleRepository repository;
    private final LlmJobService llmJobService;
    private final AdminPipelineProperties pipelineProperties;
    private final ObjectMapper objectMapper;
    private final Yaml yaml = new Yaml();

    public List<AdminConsoleDtos.SyntheticGenerationMethod> listGenerationMethods() {
        return listGenerationMethods(null, null, null);
    }

    public List<AdminConsoleDtos.SyntheticGenerationMethod> listGenerationMethods(
            String sourceId,
            String sourceDocumentId,
            UUID datasetId
    ) {
        Set<String> allowedMethodCodes = resolveAllowedMethodCodesForMethodList(sourceId, sourceDocumentId, datasetId);
        return repository.findGenerationMethods().stream()
                .filter(method -> allowedMethodCodes.contains(normalizeMethodCode(method.methodCode())))
                .toList();
    }

    public List<AdminConsoleDtos.SyntheticGenerationBatchRow> listGenerationBatches(Integer limit) {
        return repository.findGenerationBatches(limit);
    }

    @Transactional
    public void deleteSyntheticGenerationBatch(UUID batchId) {
        AdminConsoleDtos.SyntheticGenerationBatchRow batch = repository.findGenerationBatch(batchId)
                .orElseThrow(() -> new IllegalArgumentException("generation batch not found: " + batchId));
        String status = batch.status() == null ? "" : batch.status().trim().toLowerCase(Locale.ROOT);
        if (Set.of("planned", "queued", "running").contains(status)) {
            throw new IllegalArgumentException("running generation batch cannot be deleted. cancel the job first.");
        }
        repository.deleteLlmJobsByGenerationBatch(batchId);
        repository.deleteSyntheticQueriesByGenerationBatch(batchId);
        int removed = repository.deleteGenerationBatch(batchId);
        if (removed <= 0) {
            throw new IllegalArgumentException("generation batch not found: " + batchId);
        }
    }

    public List<AdminConsoleDtos.SyntheticQueryRow> listSyntheticQueries(
            String methodCode,
            UUID batchId,
            String queryType,
            Boolean gated,
            Integer limit,
            Integer offset
    ) {
        return repository.findSyntheticQueries(methodCode, batchId, queryType, gated, limit, offset);
    }

    public AdminConsoleDtos.SyntheticQueryDetailResponse getSyntheticQueryDetail(String queryId) {
        return repository.findSyntheticQueryDetail(queryId)
                .orElseThrow(() -> new IllegalArgumentException("synthetic query not found: " + queryId));
    }

    public AdminConsoleDtos.SyntheticStatsResponse getSyntheticStats(String methodCode, UUID batchId) {
        return repository.findSyntheticStats(methodCode, batchId);
    }

    public AdminConsoleDtos.AdminDashboardStats getDashboardStats() {
        return repository.findAdminDashboardStats();
    }

    public AdminConsoleDtos.RuntimeOptionsResponse getRuntimeOptions() {
        RuntimeCatalog catalog = loadRuntimeCatalog();
        List<String> llmModels = fallbackIfEmpty(
                catalog.availableLlmModels(),
                collectRuntimeOptions(
                        nullableStringCandidates(
                                readEnv("QUERY_FORGE_ADMIN_LLM_MODELS"),
                                readEnv("QUERY_FORGE_LLM_MODEL"),
                                readEnv("QUERY_FORGE_LLM_SUMMARY_MODEL"),
                                readEnv("QUERY_FORGE_LLM_QUERY_MODEL"),
                                readEnv("QUERY_FORGE_LLM_SELF_EVAL_MODEL"),
                                readEnv("QUERY_FORGE_LLM_REWRITE_MODEL"),
                                readEnv("QUERY_FORGE_LLM_FALLBACK_MODELS")
                        ),
                        List.of(DEFAULT_LLM_MODEL, DEFAULT_LLM_FALLBACK_MODEL)
                )
        );
        String envLlmModel = readEnv("QUERY_FORGE_LLM_MODEL");
        String defaultLlmModel = firstNonBlank(
                llmModels.contains(envLlmModel) ? envLlmModel : null,
                llmModels.contains(catalog.defaultLlmModel()) ? catalog.defaultLlmModel() : null,
                llmModels.isEmpty() ? null : llmModels.getFirst()
        );

        List<String> denseEmbeddingModels = fallbackIfEmpty(
                catalog.availableDenseEmbeddingModels(),
                collectRuntimeOptions(
                        nullableStringCandidates(
                                readEnv("QUERY_FORGE_ADMIN_DENSE_EMBEDDING_MODELS"),
                                readEnv("QUERY_FORGE_LOCAL_EMBEDDING_MODEL"),
                                readEnv("QUERY_FORGE_ANCHOR_EMBEDDING_MODEL")
                        ),
                        List.of(DEFAULT_DENSE_EMBEDDING_MODEL)
                )
        );
        String envDenseModel = readEnv("QUERY_FORGE_LOCAL_EMBEDDING_MODEL");
        String defaultDenseEmbeddingModel = firstNonBlank(
                denseEmbeddingModels.contains(envDenseModel) ? envDenseModel : null,
                denseEmbeddingModels.contains(catalog.defaultDenseEmbeddingModel()) ? catalog.defaultDenseEmbeddingModel() : null,
                denseEmbeddingModels.isEmpty() ? null : denseEmbeddingModels.getFirst()
        );

        List<String> retrievalBackends = fallbackIfEmpty(
                catalog.availableRetrievalBackends(),
                List.of(RETRIEVAL_BACKEND_LOCAL, RETRIEVAL_BACKEND_DB_ANN)
        );
        String defaultRetrievalBackend = firstNonBlank(
                retrievalBackends.contains(catalog.defaultRetrievalBackend()) ? catalog.defaultRetrievalBackend() : null,
                retrievalBackends.contains(RETRIEVAL_BACKEND_LOCAL) ? RETRIEVAL_BACKEND_LOCAL : null,
                retrievalBackends.isEmpty() ? null : retrievalBackends.getFirst()
        );

        List<String> retrieverModes = fallbackIfEmpty(
                catalog.availableRetrieverModes(),
                List.of(RETRIEVER_MODE_BM25_ONLY, RETRIEVER_MODE_DENSE_ONLY, RETRIEVER_MODE_HYBRID)
        );
        List<String> rewriteFailurePolicies = fallbackIfEmpty(
                catalog.availableRewriteFailurePolicies(),
                List.of(
                        REWRITE_FAILURE_POLICY_FAIL_RUN,
                        REWRITE_FAILURE_POLICY_SKIP_TO_RAW,
                        REWRITE_FAILURE_POLICY_HEURISTIC_FALLBACK
                )
        );
        return new AdminConsoleDtos.RuntimeOptionsResponse(
                llmModels,
                defaultLlmModel,
                denseEmbeddingModels,
                defaultDenseEmbeddingModel,
                retrievalBackends,
                defaultRetrievalBackend,
                retrieverModes,
                rewriteFailurePolicies,
                catalog.llmProviderOptions().stream().map(this::toRuntimeOptionDto).toList(),
                catalog.llmModelOptions().stream().map(this::toRuntimeOptionDto).toList(),
                catalog.denseEmbeddingModelOptions().stream().map(this::toRuntimeOptionDto).toList(),
                catalog.retrievalBackendOptions().stream().map(this::toRuntimeOptionDto).toList(),
                catalog.retrieverModeOptions().stream().map(this::toRuntimeOptionDto).toList(),
                catalog.rewriteFailurePolicyOptions().stream().map(this::toRuntimeOptionDto).toList(),
                catalog.defaultParameterRanges()
        );
    }

    public AdminConsoleDtos.ChunkEmbeddingMaterializationStatusResponse getChunkEmbeddingMaterializationStatus(String embeddingModel) {
        RuntimeCatalog catalog = loadRuntimeCatalog();
        String normalizedEmbeddingModel = normalizeChunkEmbeddingModel(embeddingModel, catalog);
        return buildChunkEmbeddingStatus(normalizedEmbeddingModel);
    }

    @Transactional
    public AdminConsoleDtos.LlmJobRow runChunkEmbeddingMaterialization(
            AdminConsoleDtos.ChunkEmbeddingMaterializationRequest request
    ) {
        RuntimeCatalog catalog = loadRuntimeCatalog();
        String normalizedEmbeddingModel = normalizeChunkEmbeddingModel(
                request == null ? null : request.embeddingModel(),
                catalog
        );
        AdminConsoleDtos.ChunkEmbeddingMaterializationStatusResponse status = buildChunkEmbeddingStatus(normalizedEmbeddingModel);
        if (status.ready()) {
            throw new IllegalArgumentException(
                    "chunk embeddings are already materialized for embedding_model=" + normalizedEmbeddingModel
            );
        }
        String experimentName = "admin_materialize_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        Map<String, Object> config = baseExperimentConfig(experimentName, SYNTHETIC_FREE_BASELINE_METHOD);
        Map<String, Object> retrieverConfig = resolveRetrieverConfig(
                new AdminConsoleDtos.RetrieverConfigRequest(
                        RETRIEVER_MODE_DENSE_ONLY,
                        normalizedEmbeddingModel,
                        true,
                        false,
                        false,
                        DEFAULT_RETRIEVER_CANDIDATE_POOL_K,
                        1.0d,
                        0.0d,
                        0.0d
                ),
                false
        );
        attachRetrieverConfig(config, retrieverConfig);
        config.put("retrieval_backend", RETRIEVAL_BACKEND_DB_ANN);
        config.put("chunk_embedding_model", normalizedEmbeddingModel);
        config.put("vector_store", VECTOR_STORE_POSTGRESQL_PGVECTOR);
        config.put("chunk_embedding_force_refresh", false);
        writeExperimentConfig(experimentName, config);
        UUID jobId = llmJobService.createChunkEmbeddingMaterializationJob(
                experimentName,
                defaultCreatedBy(request == null ? null : request.createdBy())
        );
        return llmJobService.getJob(jobId);
    }

    @Transactional
    public AdminConsoleDtos.SyntheticGenerationBatchRow runSyntheticGeneration(AdminConsoleDtos.SyntheticBatchRunRequest request) {
        String methodCode = normalizeMethodCode(request.methodCode());
        validateSyntheticSourceMethodRestriction(methodCode, request.sourceId(), request.sourceDocumentId());
        AdminConsoleDtos.SyntheticGenerationMethod method = repository.findGenerationMethodByCode(methodCode)
                .orElseThrow(() -> new IllegalArgumentException("generation method not found: " + methodCode));
        RuntimeCatalog runtimeCatalog = loadRuntimeCatalog();
        validateLlmModelSelection(blankToNull(request.llmModel()), runtimeCatalog);

        String experimentName = "admin_gen_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        Map<String, Object> config = baseExperimentConfig(experimentName, methodCode);
        if (request.limitChunks() != null) {
            config.put("limit_chunks", clampRange(request.limitChunks(), 1, 200, "limit_chunks"));
        }
        if (request.sourceDocumentVersion() != null && !request.sourceDocumentVersion().isBlank()) {
            config.put("source_document_version", request.sourceDocumentVersion().trim());
        }
        if (request.sourceId() != null && !request.sourceId().isBlank()) {
            config.put("source_id", request.sourceId().trim());
        }
        if (request.sourceDocumentId() != null && !request.sourceDocumentId().isBlank()) {
            config.put("source_document_id", request.sourceDocumentId().trim());
        }
        if (request.avgQueriesPerChunk() != null) {
            config.put("avg_queries_per_chunk", clampRange(request.avgQueriesPerChunk(), 0.2d, 20.0d, "avg_queries_per_chunk"));
        }
        if (request.maxTotalQueries() != null) {
            config.put("max_total_queries", clampRange(request.maxTotalQueries(), 1, 2000, "max_total_queries"));
        }
        if (request.randomChunkSampling() != null) {
            config.put("random_chunk_sampling", request.randomChunkSampling());
        }
        applyLlmModelOverrides(config, request.llmModel());
        if (request.llmRpm() != null) {
            config.put("llm_rpm", clampRange(request.llmRpm(), 1, 1000, "llm_rpm"));
        }

        UUID batchId = repository.createGenerationBatch(
                method.generationMethodId(),
                normalizeVersionName(request.versionName()),
                blankToNull(request.sourceDocumentVersion()),
                defaultCreatedBy(request.createdBy()),
                objectMapper.valueToTree(config)
        );
        config.put("generation_batch_id", batchId.toString());
        writeExperimentConfig(experimentName, config);
        llmJobService.createGenerationJob(batchId, experimentName, defaultCreatedBy(request.createdBy()));
        return repository.findGenerationBatch(batchId)
                .orElseThrow(() -> new IllegalStateException("generation batch not found after completion: " + batchId));
    }

    public List<AdminConsoleDtos.GatingBatchRow> listGatingBatches(Integer limit) {
        return repository.findGatingBatches(limit);
    }

    public AdminConsoleDtos.GatingFunnelResponse getGatingFunnel(UUID gatingBatchId, String methodCode) {
        return repository.findGatingFunnel(gatingBatchId, normalizeOptionalMethodCode(methodCode));
    }

    public List<AdminConsoleDtos.GatingResultRow> listGatingResults(
            UUID gatingBatchId,
            String methodCode,
            String passStage,
            String queryType,
            Integer limit,
            Integer offset
    ) {
        return repository.findGatingResults(
                gatingBatchId,
                normalizeOptionalMethodCode(methodCode),
                normalizeGatingPassStage(passStage),
                queryType,
                limit,
                offset
        );
    }

    @Transactional
    public AdminConsoleDtos.GatingBatchRow runGating(AdminConsoleDtos.GatingBatchRunRequest request) {
        RuntimeCatalog runtimeCatalog = loadRuntimeCatalog();
        List<UUID> selectedBatchIds = normalizeGenerationBatchIds(request.generationBatchIds(), request.generationBatchId());
        if (selectedBatchIds.isEmpty()) {
            throw new IllegalArgumentException("generation_batch_id is required");
        }
        List<AdminConsoleDtos.SyntheticGenerationBatchRow> selectedBatches = new ArrayList<>();
        LinkedHashSet<String> derivedMethodCodes = new LinkedHashSet<>();
        LinkedHashSet<UUID> sourceGenerationRunIds = new LinkedHashSet<>();
        Map<String, AdminConsoleDtos.SyntheticGenerationMethod> methodByCode = new LinkedHashMap<>();
        for (UUID generationBatchId : selectedBatchIds) {
            AdminConsoleDtos.SyntheticGenerationBatchRow batch = repository.findGenerationBatch(generationBatchId)
                    .orElseThrow(() -> new IllegalArgumentException("generation batch not found: " + generationBatchId));
            if (!"completed".equalsIgnoreCase(batch.status())) {
                throw new IllegalArgumentException("generation batch is not completed: " + generationBatchId);
            }
            if (batch.sourceGenerationRunId() == null) {
                throw new IllegalArgumentException("generation batch has no source_generation_run_id: " + generationBatchId);
            }
            String batchMethodCode = normalizeMethodCode(batch.methodCode());
            AdminConsoleDtos.SyntheticGenerationMethod method = methodByCode.computeIfAbsent(
                    batchMethodCode,
                    key -> repository.findGenerationMethodByCode(key)
                            .orElseThrow(() -> new IllegalArgumentException("generation method not found: " + key))
            );
            selectedBatches.add(batch);
            derivedMethodCodes.add(method.methodCode().toUpperCase());
            sourceGenerationRunIds.add(batch.sourceGenerationRunId());
        }
        List<String> selectedMethodCodes = List.copyOf(derivedMethodCodes);
        if (selectedMethodCodes.isEmpty()) {
            throw new IllegalArgumentException("at least one generation method is required");
        }

        List<String> requestedMethodCodes = normalizeMethodCodes(request.methodCodes());
        String requestedMethodCode = normalizeOptionalMethodCode(request.methodCode());
        if (!requestedMethodCodes.isEmpty()) {
            for (String requestedMethod : requestedMethodCodes) {
                if (!derivedMethodCodes.contains(requestedMethod)) {
                    throw new IllegalArgumentException(
                            "generation batch method mismatch: expected one of=" + requestedMethodCodes + ", actual=" + selectedMethodCodes
                    );
                }
            }
            for (String actualMethod : selectedMethodCodes) {
                if (!requestedMethodCodes.contains(actualMethod)) {
                    throw new IllegalArgumentException(
                            "generation batch method mismatch: expected one of=" + requestedMethodCodes + ", actual=" + selectedMethodCodes
                    );
                }
            }
        }
        if (requestedMethodCode != null && !derivedMethodCodes.contains(requestedMethodCode)) {
            throw new IllegalArgumentException(
                    "generation batch method mismatch: expected=" + requestedMethodCode + ", actual=" + selectedMethodCodes
            );
        }

        String primaryMethodCode = requestedMethodCode;
        if (primaryMethodCode == null && !requestedMethodCodes.isEmpty()) {
            primaryMethodCode = requestedMethodCodes.getFirst();
        }
        if (primaryMethodCode == null || !derivedMethodCodes.contains(primaryMethodCode)) {
            primaryMethodCode = selectedMethodCodes.getFirst();
        }
        AdminConsoleDtos.SyntheticGenerationMethod primaryMethod = methodByCode.get(primaryMethodCode);
        if (primaryMethod == null) {
            String resolvedPrimaryMethodCode = primaryMethodCode;
            primaryMethod = repository.findGenerationMethodByCode(resolvedPrimaryMethodCode)
                    .orElseThrow(() -> new IllegalArgumentException("generation method not found: " + resolvedPrimaryMethodCode));
            methodByCode.put(primaryMethodCode, primaryMethod);
        }

        UUID primaryGenerationBatchId = selectedBatches.getFirst().batchId();
        UUID primarySourceGenerationRunId = selectedBatches.getFirst().sourceGenerationRunId();
        List<String> sourceGenerationRunIdStrings = sourceGenerationRunIds.stream().map(UUID::toString).toList();
        List<String> selectedBatchIdStrings = selectedBatchIds.stream().map(UUID::toString).toList();
        boolean singleEnglishMethod = selectedMethodCodes.size() == 1
                && "E".equalsIgnoreCase(selectedMethodCodes.getFirst());

        String gatingPreset = normalizeGatingPreset(request.gatingPreset());
        AdminConsoleDtos.GatingRunConfig requestConfig = request.config();
        AdminConsoleDtos.GatingStageFlags stageFlags = requestConfig == null ? null : requestConfig.stageFlags();
        AdminConsoleDtos.GatingThresholdConfig thresholdConfig = requestConfig == null ? null : requestConfig.thresholds();
        Map<String, Object> retrieverConfig = resolveRetrieverConfig(
                requestConfig == null ? null : requestConfig.retrieverConfig()
        );
        if (singleEnglishMethod && requestConfig != null && requestConfig.retrieverConfig() == null) {
            retrieverConfig = forceBm25RetrieverConfig(retrieverConfig);
        }
        validateLlmModelSelection(blankToNull(request.llmModel()), runtimeCatalog);
        validateRetrieverSelection(retrieverConfig, runtimeCatalog);
        Map<String, Object> stageConfig = new LinkedHashMap<>();
        stageConfig.put(
                "enable_rule_filter",
                flagValue(stageFlags == null ? null : stageFlags.enableRuleFilter(), gatingPreset, "rule")
        );
        stageConfig.put(
                "enable_llm_self_eval",
                flagValue(stageFlags == null ? null : stageFlags.enableLlmSelfEval(), gatingPreset, "llm")
        );
        stageConfig.put(
                "enable_retrieval_utility",
                flagValue(stageFlags == null ? null : stageFlags.enableRetrievalUtility(), gatingPreset, "utility")
        );
        stageConfig.put(
                "enable_diversity",
                flagValue(stageFlags == null ? null : stageFlags.enableDiversity(), gatingPreset, "diversity")
        );
        Map<String, Object> ruleConfig = resolveRuleConfig(
                singleEnglishMethod ? "E" : primaryMethodCode,
                requestConfig == null ? null : requestConfig.ruleConfig()
        );
        Map<String, Double> utilityScoreWeights = resolveUtilityScoreWeights(
                requestConfig == null ? null : requestConfig.utilityScoreWeights()
        );
        Map<String, Double> gatingWeights = resolveGatingWeights(requestConfig == null ? null : requestConfig.gatingWeights());
        double utilityThreshold = thresholdConfig == null || thresholdConfig.utilityThreshold() == null
                ? 0.70d
                : clampRange(thresholdConfig.utilityThreshold(), 0.0d, 1.0d, "utility_threshold");
        double diversityThresholdSameChunk = thresholdConfig == null || thresholdConfig.diversityThresholdSameChunk() == null
                ? 0.93d
                : clampRange(thresholdConfig.diversityThresholdSameChunk(), 0.0d, 1.0d, "diversity_threshold_same_chunk");
        double diversityThresholdSameDoc = thresholdConfig == null || thresholdConfig.diversityThresholdSameDoc() == null
                ? 0.96d
                : clampRange(thresholdConfig.diversityThresholdSameDoc(), 0.0d, 1.0d, "diversity_threshold_same_doc");
        double finalScoreThreshold = thresholdConfig == null || thresholdConfig.finalScoreThreshold() == null
                ? 0.75d
                : clampRange(thresholdConfig.finalScoreThreshold(), 0.0d, 1.0d, "final_score_threshold");
        stageConfig.put("rule_config", ruleConfig);
        stageConfig.put("utility_score_weights", utilityScoreWeights);
        stageConfig.put("gating_weights", gatingWeights);
        stageConfig.put("utility_threshold", utilityThreshold);
        stageConfig.put("diversity_threshold_same_chunk", diversityThresholdSameChunk);
        stageConfig.put("diversity_threshold_same_doc", diversityThresholdSameDoc);
        stageConfig.put("final_score_threshold", finalScoreThreshold);
        stageConfig.put("llm_batch_size", 1);
        stageConfig.put("retriever_config", retrieverConfig);
        stageConfig.put("source_generation_strategies", selectedMethodCodes);
        stageConfig.put("source_generation_batch_ids", selectedBatchIdStrings);
        stageConfig.put("source_generation_run_ids", sourceGenerationRunIdStrings);
        if (sourceGenerationRunIdStrings.size() == 1) {
            stageConfig.put("source_generation_run_id", sourceGenerationRunIdStrings.getFirst());
        }

        for (AdminConsoleDtos.SyntheticGenerationBatchRow batch : selectedBatches) {
            String batchMethodCode = normalizeMethodCode(batch.methodCode());
            AdminConsoleDtos.SyntheticGenerationMethod batchMethod = methodByCode.get(batchMethodCode);
            if (batchMethod == null) {
                throw new IllegalStateException("generation method not loaded: " + batchMethodCode);
            }
            repository.clearCompletedGatingResults(batchMethod.generationMethodId(), batch.batchId());
        }

        UUID gatingBatchId = repository.createGatingBatch(
                gatingPreset,
                primaryMethod.generationMethodId(),
                primaryGenerationBatchId,
                primarySourceGenerationRunId,
                defaultCreatedBy(request.createdBy()),
                objectMapper.valueToTree(stageConfig)
        );

        String experimentName = "admin_gate_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        Map<String, Object> config = baseExperimentConfig(experimentName, primaryMethodCode);
        config.put("source_generation_strategies", selectedMethodCodes);
        config.put("source_generation_batch_ids", selectedBatchIdStrings);
        config.put("source_generation_run_ids", sourceGenerationRunIdStrings);
        if (sourceGenerationRunIdStrings.size() == 1) {
            config.put("source_generation_run_id", sourceGenerationRunIdStrings.getFirst());
        }
        config.put("enable_code_mixed", selectedMethodCodes.contains("D"));
        config.put("gating_preset", gatingPreset);
        config.put("enable_rule_filter", stageConfig.get("enable_rule_filter"));
        config.put("enable_llm_self_eval", stageConfig.get("enable_llm_self_eval"));
        config.put("enable_retrieval_utility", stageConfig.get("enable_retrieval_utility"));
        config.put("enable_diversity", stageConfig.get("enable_diversity"));
        config.put("rule_min_len_short", ruleConfig.get("rule_min_len_short"));
        config.put("rule_max_len_short", ruleConfig.get("rule_max_len_short"));
        config.put("rule_min_len_long", ruleConfig.get("rule_min_len_long"));
        config.put("rule_max_len_long", ruleConfig.get("rule_max_len_long"));
        config.put("rule_min_tokens", ruleConfig.get("rule_min_tokens"));
        config.put("rule_max_tokens", ruleConfig.get("rule_max_tokens"));
        config.put("rule_min_korean_ratio", ruleConfig.get("rule_min_korean_ratio"));
        config.put("rule_min_korean_ratio_code_mixed", ruleConfig.get("rule_min_korean_ratio_code_mixed"));
        config.put("retrieval_utility_weights", utilityScoreWeights);
        config.put("gating_weights", gatingWeights);
        config.put("utility_threshold", utilityThreshold);
        config.put("diversity_threshold_same_chunk", diversityThresholdSameChunk);
        config.put("diversity_threshold_same_doc", diversityThresholdSameDoc);
        config.put("final_score_threshold", finalScoreThreshold);
        config.put("llm_batch_size", 1);
        config.put("utility_candidate_pool_k", retrieverConfig.get("retriever_candidate_pool_k"));
        attachRetrieverConfig(config, retrieverConfig);
        applyLlmModelOverrides(config, request.llmModel());
        config.put("gating_batch_id", gatingBatchId.toString());
        writeExperimentConfig(experimentName, config);
        llmJobService.createGatingJob(gatingBatchId, experimentName, defaultCreatedBy(request.createdBy()));
        return repository.findGatingBatch(gatingBatchId)
                .orElseThrow(() -> new IllegalStateException("gating batch not found after completion: " + gatingBatchId));
    }

    @Transactional
    public List<AdminConsoleDtos.EvalDatasetRow> listEvalDatasets() {
        ensureDefaultEvalDataset();
        return repository.findEvalDatasets();
    }

    @Transactional
    public List<AdminConsoleDtos.EvalDatasetItemRow> listEvalDatasetItems(UUID datasetId, Integer limit, Integer offset) {
        ensureDefaultEvalDataset();
        return repository.findEvalDatasetItems(datasetId, limit, offset);
    }

    public List<AdminConsoleDtos.RagTestRunRow> listRagTestRuns(Integer limit) {
        return repository.findRagTestRuns(limit);
    }

    public AdminConsoleDtos.RagTestRunDetail getRagTestRunDetail(UUID runId, Integer detailLimit) {
        AdminConsoleDtos.RagTestRunRow run = repository.findRagTestRun(runId)
                .orElseThrow(() -> new IllegalArgumentException("rag test run not found: " + runId));
        JsonNode summary = repository.findRagSummaryMetrics(runId).orElseGet(objectMapper::createObjectNode);
        List<AdminConsoleDtos.RagTestResultDetailRow> details = repository.findRagTestDetails(runId, detailLimit);
        return new AdminConsoleDtos.RagTestRunDetail(run, summary, details);
    }

    @Transactional
    public void deleteRagTestRun(UUID runId) {
        int removed = repository.deleteRagTestRun(runId);
        if (removed <= 0) {
            throw new IllegalArgumentException("rag test run not found: " + runId);
        }
    }

    public AdminConsoleDtos.RagCompareResponse compareRagRuns(UUID datasetId) {
        return new AdminConsoleDtos.RagCompareResponse(datasetId, repository.findRagTestRunsByDataset(datasetId));
    }

    public List<AdminConsoleDtos.RewriteDebugRow> listRewriteDebugRows(Integer limit, Integer offset) {
        return repository.findRewriteDebugRows(limit, offset);
    }

    public AdminConsoleDtos.RewriteDebugDetail getRewriteDebugDetail(UUID rewriteLogId) {
        return repository.findRewriteDebugDetail(rewriteLogId)
                .orElseThrow(() -> new IllegalArgumentException("rewrite log not found: " + rewriteLogId));
    }

    public List<AdminConsoleDtos.LlmJobRow> listLlmJobs(Integer limit) {
        return llmJobService.listJobs(limit);
    }

    public AdminConsoleDtos.LlmJobRow getLlmJob(UUID jobId) {
        return llmJobService.getJob(jobId);
    }

    public List<AdminConsoleDtos.LlmJobItemRow> listLlmJobItems(UUID jobId) {
        return llmJobService.listJobItems(jobId);
    }

    @Transactional
    public void pauseLlmJob(UUID jobId) {
        llmJobService.pauseJob(jobId);
    }

    @Transactional
    public void resumeLlmJob(UUID jobId) {
        llmJobService.resumeJob(jobId);
    }

    @Transactional
    public void cancelLlmJob(UUID jobId) {
        llmJobService.cancelJob(jobId);
    }

    @Transactional
    public void retryLlmJob(UUID jobId) {
        llmJobService.retryJob(jobId);
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public AdminConsoleDtos.RagTestRunRow runRagTest(AdminConsoleDtos.RagTestRunRequest request) {
        ensureDefaultEvalDataset();
        if (request.datasetId() == null) {
            throw new IllegalArgumentException("dataset_id is required");
        }
        String evalQueryLanguage = normalizeEvalQueryLanguage(
                request.evalQueryLanguage(),
                repository.findEvalDatasetQueryLanguage(request.datasetId()).orElse(null)
        );
        boolean syntheticFreeBaseline = Boolean.TRUE.equals(request.syntheticFreeBaseline());
        List<String> methodCodes = syntheticFreeBaseline ? List.of() : normalizeMethodCodes(request.methodCodes());
        if (!syntheticFreeBaseline && methodCodes.isEmpty()) {
            throw new IllegalArgumentException("at least one generation method is required");
        }
        if (!syntheticFreeBaseline) {
            validateDatasetMethodRestriction(request.datasetId(), methodCodes);
        }
        List<UUID> batchIds = request.generationBatchIds() == null ? List.of() : request.generationBatchIds();
        String runDiscipline = normalizeRunDiscipline(request.officialRun());
        String officialComparisonType = normalizeOfficialComparisonType(request.officialComparisonType(), runDiscipline);
        Map<String, UUID> comparisonBatchIds = normalizeComparisonBatchIds(request.comparisonGatingBatchIds());
        UUID sourceGatingBatchId = request.sourceGatingBatchId();
        boolean stageCutoffEnabled = Boolean.TRUE.equals(request.stageCutoffEnabled());

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
        boolean rewriteAnchorInjectionEnabled =
                request.rewriteAnchorInjectionEnabled() == null || request.rewriteAnchorInjectionEnabled();
        int retrievalTopK = request.retrievalTopK() != null && request.retrievalTopK() > 0
                ? request.retrievalTopK()
                : DEFAULT_RAG_RETRIEVAL_TOP_K;
        int rerankTopN = request.rerankTopN() != null && request.rerankTopN() > 0 ? request.rerankTopN() : 5;
        Map<String, Object> retrieverConfig = resolveRetrieverConfig(request.retrieverConfig(), false);
        RuntimeCatalog runtimeCatalog = loadRuntimeCatalog();
        validateLlmModelSelection(blankToNull(request.llmModel()), runtimeCatalog);
        String retrievalBackend = normalizeRetrievalBackend(request.retrievalBackend());
        validateRetrievalBackendSelection(retrievalBackend, runtimeCatalog);
        validateRetrieverSelection(retrieverConfig, runtimeCatalog);
        validateDbAnnConfigurationIfNeeded(retrievalBackend, retrieverConfig);
        String retrievalEmbeddingModel = blankToNull(asTrimmedString(retrieverConfig.get("dense_embedding_model")));
        if (RETRIEVAL_BACKEND_DB_ANN.equals(retrievalBackend)) {
            requireChunkEmbeddingsReady(retrievalEmbeddingModel);
        }
        String runLabel = resolveRagRunLabel(
                request.runName(),
                retrievalBackend + ":" + String.valueOf(retrieverConfig.get("retriever_mode"))
        );
        double threshold = request.threshold() != null ? request.threshold() : 0.10d;
        String rewriteRetrievalStrategy = normalizeRewriteRetrievalStrategy(request.rewriteRetrievalStrategy());
        String rewriteFailurePolicy = normalizeRewriteFailurePolicy(request.rewriteFailurePolicy());
        validateRewriteFailurePolicySelection(rewriteFailurePolicy, runtimeCatalog);
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
            if (request.rewriteEnabled() != null && request.rewriteEnabled()) {
                throw new IllegalArgumentException("official gating-effect runs must not enable rewrite");
            }
            if (Boolean.FALSE.equals(request.gatingApplied())) {
                throw new IllegalArgumentException("official gating-effect runs require gating_applied=true");
            }
            if (request.gatingPreset() != null && !request.gatingPreset().isBlank()
                    && !"full_gating".equalsIgnoreCase(request.gatingPreset().trim())) {
                throw new IllegalArgumentException("official gating-effect runs require gating_preset=full_gating");
            }
            if (Boolean.TRUE.equals(request.selectiveRewrite()) || Boolean.TRUE.equals(request.useSessionContext())) {
                throw new IllegalArgumentException("official gating-effect runs must not include rewrite variants");
            }
            gatingApplied = true;
            gatingPreset = "full_gating";
            rewriteEnabled = false;
            selectiveRewrite = false;
            useSessionContext = false;
            rewriteAnchorInjectionEnabled = false;
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
            AdminConsoleDtos.GatingBatchRow sourceBatch = repository.findGatingBatch(sourceGatingBatchId)
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
            rewriteAnchorInjectionEnabled = false;
            stageCutoffEnabled = false;
            stageCutoffLevel = null;
        }
        if (!rewriteEnabled) {
            rewriteAnchorInjectionEnabled = false;
        }

        String experimentName = "admin_eval_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        Map<String, Object> config = baseExperimentConfig(
                experimentName,
                syntheticFreeBaseline ? SYNTHETIC_FREE_BASELINE_METHOD : methodCodes.getFirst()
        );
        applyLlmModelOverrides(config, request.llmModel());
        config.put("run_name", runLabel);
        config.put("dataset_id", request.datasetId().toString());
        config.put("eval_query_language", evalQueryLanguage);
        config.put("memory_generation_strategies", methodCodes);
        config.put("source_generation_strategies", methodCodes);
        config.put("synthetic_free_baseline", syntheticFreeBaseline);
        config.put("gating_applied", gatingApplied);
        config.put("gating_preset", gatingPreset);
        config.put("rewrite_enabled", rewriteEnabled);
        config.put("selective_rewrite", selectiveRewrite);
        config.put("use_session_context", useSessionContext);
        config.put("rewrite_threshold", threshold);
        config.put("rewrite_retrieval_strategy", rewriteRetrievalStrategy);
        config.put("rewrite_anchor_injection_enabled", rewriteAnchorInjectionEnabled);
        config.put("rewrite_failure_policy", rewriteFailurePolicy);
        config.put("retrieval_top_k", retrievalTopK);
        config.put("rerank_top_n", rerankTopN);
        attachRetrieverConfig(config, retrieverConfig);
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
                    List.of("raw_only", "memory_only_ungated", "memory_only_rule_only", "memory_only_full_gating")
            );
            config.put("snapshot_id", comparisonBatchIds.get("full_gating").toString());
        } else if (RUN_DISCIPLINE_OFFICIAL.equals(runDiscipline)
                && COMPARISON_REWRITE_EFFECT.equals(officialComparisonType)) {
            Optional<UUID> sourceGatingRunId = resolveSourceGatingRunId(
                    methodCodes,
                    gatingPreset,
                    sourceGatingBatchId
            );
            config.put("retrieval_modes", List.of("raw_only", "memory_only_gated", "rewrite_always", "selective_rewrite"));
            config.put("source_gating_batch_id", sourceGatingBatchId.toString());
            config.put("snapshot_id", sourceGatingBatchId.toString());
            sourceGatingRunId.ifPresent(uuid -> config.put("source_gating_run_id", uuid.toString()));
        } else {
            config.put("retrieval_modes", resolveRetrievalModes(rewriteEnabled, selectiveRewrite, useSessionContext));
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

        validateRewriteStageLlmConfig(config, rewriteEnabled);
        JsonNode methodCodesNode = objectMapper.valueToTree(methodCodes);
        JsonNode batchIdsNode = objectMapper.valueToTree(batchIds);
        UUID runId = repository.createRagTestRun(
                runLabel,
                request.datasetId(),
                methodCodesNode,
                batchIdsNode,
                gatingApplied,
                gatingPreset,
                rewriteEnabled,
                selectiveRewrite,
                useSessionContext,
                rewriteAnchorInjectionEnabled,
                request.topK(),
                threshold,
                retrievalTopK,
                rerankTopN,
                experimentName,
                defaultCreatedBy(request.createdBy())
        );
        writeExperimentConfig(experimentName, config);
        repository.upsertRagTestRunConfig(runId, objectMapper.valueToTree(config));
        String initialSnapshotId = firstNonBlank(
                config.get("snapshot_id") == null ? null : String.valueOf(config.get("snapshot_id")),
                sourceGatingBatchId == null ? null : sourceGatingBatchId.toString(),
                "UNSPECIFIED"
        );
        Map<String, Object> initialGatingConfig = new LinkedHashMap<>();
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
        initialRetrievalConfig.put("retrieval_top_k", retrievalTopK);
        initialRetrievalConfig.put("rerank_top_n", rerankTopN);
        initialRetrievalConfig.put("retrieval_modes", config.get("retrieval_modes"));
        initialRetrievalConfig.put("synthetic_free_baseline", syntheticFreeBaseline);
        initialRetrievalConfig.put("retriever_config", retrieverConfig);
        Map<String, Object> initialRewriteConfig = new LinkedHashMap<>();
        initialRewriteConfig.put("rewrite_enabled", rewriteEnabled);
        initialRewriteConfig.put("selective_rewrite", selectiveRewrite);
        initialRewriteConfig.put("use_session_context", useSessionContext);
        initialRewriteConfig.put("rewrite_threshold", threshold);
        initialRewriteConfig.put("rewrite_retrieval_strategy", rewriteRetrievalStrategy);
        initialRewriteConfig.put("rewrite_anchor_injection_enabled", rewriteAnchorInjectionEnabled);
        initialRewriteConfig.put("rewrite_failure_policy", rewriteFailurePolicy);
        repository.upsertRagExperimentRecord(
                runId,
                initialSnapshotId,
                objectMapper.valueToTree(methodCodes),
                objectMapper.valueToTree(initialGatingConfig),
                0,
                objectMapper.valueToTree(initialRetrievalConfig),
                objectMapper.valueToTree(initialRewriteConfig),
                repository.findRagDatasetVersion(runId),
                Instant.now(),
                objectMapper.createObjectNode()
        );
        llmJobService.createRagTestJob(runId, experimentName, defaultCreatedBy(request.createdBy()));
        return repository.findRagTestRun(runId)
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
        AdminConsoleDtos.GatingBatchRow batch = repository.findGatingBatch(sourceGatingBatchId)
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
        AdminConsoleDtos.GatingBatchRow batch = repository.findGatingBatch(sourceGatingBatchId)
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

    private String normalizeRewriteRetrievalStrategy(String value) {
        if (value == null || value.isBlank()) {
            return REWRITE_RETRIEVAL_STRATEGY_REPLACE;
        }
        String normalized = value.trim().toLowerCase();
        if (!List.of(
                REWRITE_RETRIEVAL_STRATEGY_REPLACE,
                REWRITE_RETRIEVAL_STRATEGY_INTERLEAVE,
                REWRITE_RETRIEVAL_STRATEGY_MAX_SCORE
        ).contains(normalized)) {
            throw new IllegalArgumentException("unsupported rewrite_retrieval_strategy: " + value);
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

    private String firstNonBlank(String... values) {
        if (values == null || values.length == 0) {
            return "";
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }

    private String asTrimmedString(Object value) {
        if (value == null) {
            return null;
        }
        String normalized = String.valueOf(value).trim();
        return normalized.isBlank() ? null : normalized;
    }

    private String readEnv(String key) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) {
            value = readFromDotEnvFile(key);
        }
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private String readFromDotEnvFile(String key) {
        Path envPath = resolveRepoRoot().resolve(".env").normalize();
        if (!Files.exists(envPath)) {
            return null;
        }
        try {
            for (String entry : Files.readAllLines(envPath, StandardCharsets.UTF_8)) {
                String line = entry.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                int delimiter = line.indexOf('=');
                if (delimiter <= 0) {
                    continue;
                }
                String name = line.substring(0, delimiter).trim();
                if (!name.equals(key)) {
                    continue;
                }
                String rawValue = line.substring(delimiter + 1).trim();
                if ((rawValue.startsWith("\"") && rawValue.endsWith("\""))
                        || (rawValue.startsWith("'") && rawValue.endsWith("'"))) {
                    rawValue = rawValue.substring(1, rawValue.length() - 1);
                }
                return rawValue.isBlank() ? null : rawValue.trim();
            }
        } catch (IOException ignored) {
            return null;
        }
        return null;
    }

    private String normalizeLlmProviderName(String provider) {
        if (provider == null || provider.isBlank()) {
            return "";
        }
        return provider.trim().toLowerCase().replace("_", "-");
    }

    private String providerEnvSuffix(String provider) {
        String normalized = normalizeLlmProviderName(provider).toUpperCase();
        return NON_ALNUM_PATTERN.matcher(normalized).replaceAll("_");
    }

    private String resolveApiKeyForProvider(String provider, String rewriteStageApiKey, String genericLlmApiKey) {
        String envSuffix = providerEnvSuffix(provider);
        String rewriteEnvApiKey = readEnv("QUERY_FORGE_LLM_REWRITE_API_KEY");
        String sharedEnvApiKey = readEnv("QUERY_FORGE_LLM_API_KEY");
        String providerScopedEnvApiKey = readEnv("QUERY_FORGE_LLM_" + envSuffix + "_API_KEY");
        return switch (normalizeLlmProviderName(provider)) {
            case "gemini", "gemini-native" -> firstNonBlank(
                    rewriteStageApiKey,
                    genericLlmApiKey,
                    rewriteEnvApiKey,
                    sharedEnvApiKey,
                    providerScopedEnvApiKey,
                    readEnv("QUERY_FORGE_GEMINI_API_KEY"),
                    readEnv("QUERY_FORGE_LLM_GEMINI_API_KEY"),
                    readEnv("GEMINI_API_KEY"),
                    readEnv("GOOGLE_API_KEY")
            );
            case "openai" -> firstNonBlank(
                    rewriteStageApiKey,
                    genericLlmApiKey,
                    rewriteEnvApiKey,
                    sharedEnvApiKey,
                    providerScopedEnvApiKey,
                    readEnv("OPENAI_API_KEY")
            );
            case "groq" -> firstNonBlank(
                    rewriteStageApiKey,
                    genericLlmApiKey,
                    rewriteEnvApiKey,
                    sharedEnvApiKey,
                    providerScopedEnvApiKey,
                    readEnv("GROQ_API_KEY")
            );
            default -> firstNonBlank(
                    rewriteStageApiKey,
                    genericLlmApiKey,
                    rewriteEnvApiKey,
                    sharedEnvApiKey,
                    providerScopedEnvApiKey
            );
        };
    }

    private void validateRewriteStageLlmConfig(Map<String, Object> config, boolean rewriteEnabled) {
        if (!rewriteEnabled) {
            return;
        }
        String provider = normalizeLlmProviderName(
                firstNonBlank(
                        asTrimmedString(config.get("llm_rewrite_provider")),
                        asTrimmedString(config.get("llm_provider")),
                        readEnv("QUERY_FORGE_LLM_REWRITE_PROVIDER"),
                        readEnv("QUERY_FORGE_LLM_PROVIDER"),
                        "gemini-native"
                )
        );
        if (provider.isBlank()) {
            throw new IllegalArgumentException(
                    "rewrite_enabled=true requires llm_rewrite_provider (or llm_provider) for stage=rewrite"
            );
        }
        String model = firstNonBlank(
                asTrimmedString(config.get("llm_rewrite_model")),
                asTrimmedString(config.get("llm_model")),
                readEnv("QUERY_FORGE_LLM_REWRITE_MODEL"),
                readEnv("QUERY_FORGE_LLM_MODEL")
        );
        if (model.isBlank()) {
            throw new IllegalArgumentException(
                    "rewrite_enabled=true requires llm_rewrite_model (or llm_model) for stage=rewrite"
            );
        }
        String apiKey = resolveApiKeyForProvider(
                provider,
                asTrimmedString(config.get("llm_rewrite_api_key")),
                asTrimmedString(config.get("llm_api_key"))
        );
        if (apiKey.isBlank()) {
            throw new IllegalArgumentException(
                    "rewrite_enabled=true requires rewrite-stage LLM API key "
                            + "(llm_rewrite_api_key / QUERY_FORGE_LLM_REWRITE_API_KEY / QUERY_FORGE_LLM_API_KEY "
                            + "/ GEMINI_API_KEY / provider API key env)"
            );
        }
    }

    @SuppressWarnings("unchecked")
    private RuntimeCatalog loadRuntimeCatalog() {
        Path catalogPath = resolveRepoRoot().resolve(MODEL_CATALOG_RELATIVE_PATH).normalize();
        if (!Files.exists(catalogPath)) {
            return buildFallbackRuntimeCatalog();
        }
        try {
            Object loaded = yaml.load(Files.readString(catalogPath, StandardCharsets.UTF_8));
            if (!(loaded instanceof Map<?, ?> root)) {
                return buildFallbackRuntimeCatalog();
            }
            Map<String, Object> rootMap = (Map<String, Object>) root;
            List<RuntimeOptionMetadata> llmProviders = parseRuntimeOptions(rootMap.get("llm_providers"), false);
            List<RuntimeOptionMetadata> llmModels = parseRuntimeOptions(rootMap.get("llm_models"), false);
            List<RuntimeOptionMetadata> denseModels = parseRuntimeOptions(rootMap.get("dense_embedding_models"), false);
            List<RuntimeOptionMetadata> retrievalBackends = parseRuntimeOptions(rootMap.get("retrieval_backends"), true);
            List<RuntimeOptionMetadata> retrieverModes = parseRuntimeOptions(rootMap.get("retriever_modes"), true);
            List<RuntimeOptionMetadata> rewritePolicies = parseRuntimeOptions(rootMap.get("rewrite_failure_policies"), true);
            Map<String, AdminConsoleDtos.RuntimeParameterRange> parameterRanges = parseDefaultParameterRanges(
                    rootMap.get("default_parameter_ranges")
            );
            return new RuntimeCatalog(
                    llmProviders,
                    llmModels,
                    denseModels,
                    retrievalBackends,
                    retrieverModes,
                    rewritePolicies,
                    parameterRanges
            );
        } catch (IOException | RuntimeException exception) {
            throw new IllegalStateException("failed to load model catalog: " + catalogPath, exception);
        }
    }

    private RuntimeCatalog buildFallbackRuntimeCatalog() {
        List<RuntimeOptionMetadata> llmModels = collectRuntimeOptions(
                nullableStringCandidates(
                        readEnv("QUERY_FORGE_ADMIN_LLM_MODELS"),
                        readEnv("QUERY_FORGE_LLM_MODEL"),
                        readEnv("QUERY_FORGE_LLM_SUMMARY_MODEL"),
                        readEnv("QUERY_FORGE_LLM_QUERY_MODEL"),
                        readEnv("QUERY_FORGE_LLM_SELF_EVAL_MODEL"),
                        readEnv("QUERY_FORGE_LLM_REWRITE_MODEL"),
                        readEnv("QUERY_FORGE_LLM_FALLBACK_MODELS")
                ),
                List.of(DEFAULT_LLM_MODEL, DEFAULT_LLM_FALLBACK_MODEL)
        ).stream().map(model -> new RuntimeOptionMetadata(
                model,
                model,
                null,
                "active",
                "available",
                null,
                DEFAULT_LLM_MODEL.equals(model)
        )).toList();
        List<RuntimeOptionMetadata> denseModels = collectRuntimeOptions(
                nullableStringCandidates(
                        readEnv("QUERY_FORGE_ADMIN_DENSE_EMBEDDING_MODELS"),
                        readEnv("QUERY_FORGE_LOCAL_EMBEDDING_MODEL"),
                        readEnv("QUERY_FORGE_ANCHOR_EMBEDDING_MODEL")
                ),
                List.of(DEFAULT_DENSE_EMBEDDING_MODEL)
        ).stream().map(model -> new RuntimeOptionMetadata(
                model,
                model,
                null,
                "active",
                "available",
                null,
                DEFAULT_DENSE_EMBEDDING_MODEL.equals(model)
        )).toList();
        List<RuntimeOptionMetadata> retrieverModes = List.of(
                new RuntimeOptionMetadata(RETRIEVER_MODE_BM25_ONLY, "BM25 Only", null, "active", "available", null, false),
                new RuntimeOptionMetadata(RETRIEVER_MODE_DENSE_ONLY, "Dense Only", null, "active", "available", null, false),
                new RuntimeOptionMetadata(RETRIEVER_MODE_HYBRID, "Hybrid", null, "active", "available", null, true)
        );
        List<RuntimeOptionMetadata> retrievalBackends = List.of(
                new RuntimeOptionMetadata(RETRIEVAL_BACKEND_LOCAL, "Local", null, "active", "available", null, true),
                new RuntimeOptionMetadata(RETRIEVAL_BACKEND_DB_ANN, "DB ANN", null, "active", "available", null, false)
        );
        List<RuntimeOptionMetadata> rewritePolicies = List.of(
                new RuntimeOptionMetadata(REWRITE_FAILURE_POLICY_FAIL_RUN, REWRITE_FAILURE_POLICY_FAIL_RUN, null, "active", "available", null, true),
                new RuntimeOptionMetadata(REWRITE_FAILURE_POLICY_SKIP_TO_RAW, REWRITE_FAILURE_POLICY_SKIP_TO_RAW, null, "active", "available", null, false),
                new RuntimeOptionMetadata(REWRITE_FAILURE_POLICY_HEURISTIC_FALLBACK, REWRITE_FAILURE_POLICY_HEURISTIC_FALLBACK, null, "active", "available", null, false)
        );
        Map<String, AdminConsoleDtos.RuntimeParameterRange> ranges = defaultRuntimeParameterRanges();
        return new RuntimeCatalog(
                List.of(),
                llmModels,
                denseModels,
                retrievalBackends,
                retrieverModes,
                rewritePolicies,
                ranges
        );
    }

    @SuppressWarnings("unchecked")
    private List<RuntimeOptionMetadata> parseRuntimeOptions(Object raw, boolean normalizeLowerCaseCode) {
        if (!(raw instanceof List<?> rows)) {
            return List.of();
        }
        ArrayList<RuntimeOptionMetadata> parsed = new ArrayList<>();
        for (Object row : rows) {
            if (!(row instanceof Map<?, ?> map)) {
                continue;
            }
            Map<String, Object> item = (Map<String, Object>) map;
            String code = firstNonBlank(
                    asTrimmedString(item.get("code")),
                    asTrimmedString(item.get("id")),
                    asTrimmedString(item.get("model")),
                    asTrimmedString(item.get("mode")),
                    asTrimmedString(item.get("policy"))
            );
            if (code.isBlank()) {
                continue;
            }
            if (normalizeLowerCaseCode) {
                code = code.toLowerCase().replace("-", "_");
            }
            String label = firstNonBlank(asTrimmedString(item.get("label")), code);
            String provider = asTrimmedString(item.get("provider"));
            String status = firstNonBlank(asTrimmedString(item.get("status")), "active");
            String availability = firstNonBlank(asTrimmedString(item.get("availability")), "available");
            String reason = asTrimmedString(item.get("reason"));
            boolean defaultSelected = parseBoolean(item.get("default"))
                    || parseBoolean(item.get("default_selected"))
                    || parseBoolean(item.get("is_default"));
            parsed.add(new RuntimeOptionMetadata(
                    code,
                    label,
                    provider,
                    status,
                    availability,
                    reason,
                    defaultSelected
            ));
        }
        return List.copyOf(parsed);
    }

    @SuppressWarnings("unchecked")
    private Map<String, AdminConsoleDtos.RuntimeParameterRange> parseDefaultParameterRanges(Object raw) {
        if (!(raw instanceof Map<?, ?> map)) {
            return defaultRuntimeParameterRanges();
        }
        LinkedHashMap<String, AdminConsoleDtos.RuntimeParameterRange> ranges = new LinkedHashMap<>();
        Map<String, Object> source = (Map<String, Object>) map;
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            String key = entry.getKey();
            if (key == null || key.isBlank() || !(entry.getValue() instanceof Map<?, ?> rangeMap)) {
                continue;
            }
            Double min = parseNumber(((Map<?, ?>) rangeMap).get("min"));
            Double max = parseNumber(((Map<?, ?>) rangeMap).get("max"));
            Double defaultValue = parseNumber(((Map<?, ?>) rangeMap).get("default"));
            if (defaultValue == null) {
                defaultValue = parseNumber(((Map<?, ?>) rangeMap).get("default_value"));
            }
            ranges.put(key, new AdminConsoleDtos.RuntimeParameterRange(min, max, defaultValue));
        }
        if (ranges.isEmpty()) {
            return defaultRuntimeParameterRanges();
        }
        return Map.copyOf(ranges);
    }

    private Map<String, AdminConsoleDtos.RuntimeParameterRange> defaultRuntimeParameterRanges() {
        LinkedHashMap<String, AdminConsoleDtos.RuntimeParameterRange> ranges = new LinkedHashMap<>();
        ranges.put("retrieval_top_k", new AdminConsoleDtos.RuntimeParameterRange(1.0d, 100.0d, (double) DEFAULT_RAG_RETRIEVAL_TOP_K));
        ranges.put("rerank_top_n", new AdminConsoleDtos.RuntimeParameterRange(1.0d, 100.0d, 5.0d));
        ranges.put("rewrite_threshold", new AdminConsoleDtos.RuntimeParameterRange(0.0d, 1.0d, 0.10d));
        ranges.put("retriever_candidate_pool_k", new AdminConsoleDtos.RuntimeParameterRange(1.0d, 500.0d, (double) DEFAULT_RETRIEVER_CANDIDATE_POOL_K));
        return Map.copyOf(ranges);
    }

    private boolean parseBoolean(Object value) {
        if (value == null) {
            return false;
        }
        if (value instanceof Boolean bool) {
            return bool;
        }
        return "true".equalsIgnoreCase(String.valueOf(value).trim());
    }

    private Double parseNumber(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        try {
            return Double.parseDouble(String.valueOf(value).trim());
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private AdminConsoleDtos.RuntimeOption toRuntimeOptionDto(RuntimeOptionMetadata option) {
        return new AdminConsoleDtos.RuntimeOption(
                option.code(),
                option.label(),
                option.provider(),
                option.status(),
                option.availability(),
                option.reason(),
                option.defaultSelected()
        );
    }

    private void validateLlmModelSelection(String llmModel, RuntimeCatalog catalog) {
        if (llmModel == null) {
            return;
        }
        RuntimeOptionMetadata option = catalog.findLlmModel(llmModel);
        if (option == null) {
            throw new IllegalArgumentException("llm_model is not allowed by catalog: " + llmModel);
        }
        if (!isSelectable(option)) {
            throw new IllegalArgumentException(
                    "llm_model is not selectable by catalog: " + llmModel + appendReason(option.reason())
            );
        }
        String provider = blankToNull(option.provider());
        if (provider != null) {
            RuntimeOptionMetadata providerOption = catalog.findLlmProvider(provider);
            if (providerOption != null && !isSelectable(providerOption)) {
                throw new IllegalArgumentException(
                        "llm provider is not selectable by catalog: " + provider + appendReason(providerOption.reason())
                );
            }
        }
    }

    private void validateRetrieverSelection(Map<String, Object> retrieverConfig, RuntimeCatalog catalog) {
        String retrieverMode = normalizeRetrieverMode(asTrimmedString(retrieverConfig.get("retriever_mode")));
        RuntimeOptionMetadata retrieverModeOption = catalog.findRetrieverMode(retrieverMode);
        if (retrieverModeOption == null) {
            throw new IllegalArgumentException("retriever_mode is not allowed by catalog: " + retrieverMode);
        }
        if (!isSelectable(retrieverModeOption)) {
            throw new IllegalArgumentException(
                    "retriever_mode is not selectable by catalog: " + retrieverMode + appendReason(retrieverModeOption.reason())
            );
        }
        if (RETRIEVER_MODE_BM25_ONLY.equals(retrieverMode)) {
            return;
        }
        String denseModel = blankToNull(asTrimmedString(retrieverConfig.get("dense_embedding_model")));
        if (denseModel == null) {
            throw new IllegalArgumentException("dense_embedding_model is required for retriever_mode=" + retrieverMode);
        }
        RuntimeOptionMetadata denseOption = catalog.findDenseEmbeddingModel(denseModel);
        if (denseOption == null) {
            throw new IllegalArgumentException("dense_embedding_model is not allowed by catalog: " + denseModel);
        }
        if (!isSelectable(denseOption)) {
            throw new IllegalArgumentException(
                    "dense_embedding_model is not selectable by catalog: " + denseModel + appendReason(denseOption.reason())
            );
        }
    }

    private void validateRetrievalBackendSelection(String retrievalBackend, RuntimeCatalog catalog) {
        RuntimeOptionMetadata backendOption = catalog.findRetrievalBackend(retrievalBackend);
        if (backendOption == null) {
            throw new IllegalArgumentException("retrieval_backend is not allowed by catalog: " + retrievalBackend);
        }
        if (!isSelectable(backendOption)) {
            throw new IllegalArgumentException(
                    "retrieval_backend is not selectable by catalog: " + retrievalBackend + appendReason(backendOption.reason())
            );
        }
    }

    private void validateRewriteFailurePolicySelection(String policy, RuntimeCatalog catalog) {
        RuntimeOptionMetadata option = catalog.findRewriteFailurePolicy(policy);
        if (option == null) {
            throw new IllegalArgumentException("rewrite_failure_policy is not allowed by catalog: " + policy);
        }
        if (!isSelectable(option)) {
            throw new IllegalArgumentException(
                    "rewrite_failure_policy is not selectable by catalog: " + policy + appendReason(option.reason())
            );
        }
    }

    private void validateDbAnnConfigurationIfNeeded(String retrievalBackend, Map<String, Object> retrieverConfig) {
        if (!RETRIEVAL_BACKEND_DB_ANN.equals(retrievalBackend)) {
            return;
        }
        String retrieverMode = normalizeRetrieverMode(asTrimmedString(retrieverConfig.get("retriever_mode")));
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

    private String normalizeChunkEmbeddingModel(String embeddingModel, RuntimeCatalog catalog) {
        String normalized = firstNonBlank(
                blankToNull(embeddingModel),
                blankToNull(catalog.defaultDenseEmbeddingModel()),
                DEFAULT_DENSE_EMBEDDING_MODEL
        );
        RuntimeOptionMetadata option = catalog.findDenseEmbeddingModel(normalized);
        if (option == null) {
            throw new IllegalArgumentException("dense_embedding_model is not allowed by catalog: " + normalized);
        }
        if (!isSelectable(option)) {
            throw new IllegalArgumentException(
                    "dense_embedding_model is not selectable by catalog: " + normalized + appendReason(option.reason())
            );
        }
        return normalized;
    }

    private AdminConsoleDtos.ChunkEmbeddingMaterializationStatusResponse buildChunkEmbeddingStatus(String embeddingModel) {
        long totalChunkCount = repository.countCorpusChunks();
        long materializedChunkCount = repository.countMaterializedChunkEmbeddings(embeddingModel);
        long missingChunkCount = Math.max(0L, totalChunkCount - materializedChunkCount);
        return new AdminConsoleDtos.ChunkEmbeddingMaterializationStatusResponse(
                embeddingModel,
                VECTOR_STORE_POSTGRESQL_PGVECTOR,
                totalChunkCount,
                materializedChunkCount,
                missingChunkCount,
                totalChunkCount > 0L && missingChunkCount == 0L,
                repository.findLatestChunkEmbeddingUpdatedAt(embeddingModel)
        );
    }

    private void requireChunkEmbeddingsReady(String embeddingModel) {
        if (embeddingModel == null || embeddingModel.isBlank()) {
            throw new IllegalArgumentException("db-ann retrieval_backend requires dense_embedding_model");
        }
        AdminConsoleDtos.ChunkEmbeddingMaterializationStatusResponse status = buildChunkEmbeddingStatus(embeddingModel);
        if (status.ready()) {
            return;
        }
        throw new IllegalStateException(
                "chunk embedding materialization required: embedding_model=" + embeddingModel
                        + ", total_chunks=" + status.totalChunkCount()
                        + ", materialized_chunks=" + status.materializedChunkCount()
                        + ", missing_chunks=" + status.missingChunkCount()
        );
    }

    private boolean isSelectable(RuntimeOptionMetadata option) {
        String availability = firstNonBlank(option.availability(), "available").toLowerCase();
        String status = firstNonBlank(option.status(), "active").toLowerCase();
        if (List.of("unavailable", "disabled", "blocked").contains(availability)) {
            return false;
        }
        return !List.of("disabled", "blocked").contains(status);
    }

    private String appendReason(String reason) {
        String normalized = blankToNull(reason);
        return normalized == null ? "" : " (" + normalized + ")";
    }

    private List<String> fallbackIfEmpty(List<String> preferred, List<String> fallback) {
        if (preferred != null && !preferred.isEmpty()) {
            return preferred;
        }
        if (fallback == null) {
            return List.of();
        }
        return List.copyOf(fallback);
    }

    private List<String> collectRuntimeOptions(List<String> envCandidates, List<String> defaults) {
        LinkedHashSet<String> collected = new LinkedHashSet<>();
        if (envCandidates != null) {
            for (String candidate : envCandidates) {
                if (candidate == null || candidate.isBlank()) {
                    continue;
                }
                String[] tokens = candidate.split("[,;\\n\\r]+");
                for (String token : tokens) {
                    if (token == null) {
                        continue;
                    }
                    String normalized = token.trim();
                    if (!normalized.isBlank()) {
                        collected.add(normalized);
                    }
                }
            }
        }
        if (defaults != null) {
            for (String fallback : defaults) {
                if (fallback != null && !fallback.isBlank()) {
                    collected.add(fallback.trim());
                }
            }
        }
        if (collected.isEmpty()) {
            return List.of();
        }
        return List.copyOf(collected);
    }

    private List<String> nullableStringCandidates(String... values) {
        if (values == null || values.length == 0) {
            return List.of();
        }
        return Arrays.asList(values);
    }

    private record RuntimeCatalog(
            List<RuntimeOptionMetadata> llmProviderOptions,
            List<RuntimeOptionMetadata> llmModelOptions,
            List<RuntimeOptionMetadata> denseEmbeddingModelOptions,
            List<RuntimeOptionMetadata> retrievalBackendOptions,
            List<RuntimeOptionMetadata> retrieverModeOptions,
            List<RuntimeOptionMetadata> rewriteFailurePolicyOptions,
            Map<String, AdminConsoleDtos.RuntimeParameterRange> defaultParameterRanges
    ) {
        List<String> availableLlmModels() {
            return llmModelOptions.stream()
                    .filter(this::isSelectable)
                    .map(RuntimeOptionMetadata::code)
                    .toList();
        }

        List<String> availableDenseEmbeddingModels() {
            return denseEmbeddingModelOptions.stream()
                    .filter(this::isSelectable)
                    .map(RuntimeOptionMetadata::code)
                    .toList();
        }

        List<String> availableRetrievalBackends() {
            return retrievalBackendOptions.stream()
                    .filter(this::isSelectable)
                    .map(RuntimeOptionMetadata::code)
                    .toList();
        }

        List<String> availableRetrieverModes() {
            return retrieverModeOptions.stream()
                    .filter(this::isSelectable)
                    .map(RuntimeOptionMetadata::code)
                    .toList();
        }

        List<String> availableRewriteFailurePolicies() {
            return rewriteFailurePolicyOptions.stream()
                    .filter(this::isSelectable)
                    .map(RuntimeOptionMetadata::code)
                    .toList();
        }

        String defaultLlmModel() {
            return defaultCode(llmModelOptions);
        }

        String defaultDenseEmbeddingModel() {
            return defaultCode(denseEmbeddingModelOptions);
        }

        String defaultRetrievalBackend() {
            return defaultCode(retrievalBackendOptions);
        }

        RuntimeOptionMetadata findLlmProvider(String provider) {
            return findExact(llmProviderOptions, provider);
        }

        RuntimeOptionMetadata findLlmModel(String model) {
            return findExact(llmModelOptions, model);
        }

        RuntimeOptionMetadata findDenseEmbeddingModel(String model) {
            return findExact(denseEmbeddingModelOptions, model);
        }

        RuntimeOptionMetadata findRetrievalBackend(String backend) {
            return findNormalized(retrievalBackendOptions, backend);
        }

        RuntimeOptionMetadata findRetrieverMode(String mode) {
            return findNormalized(retrieverModeOptions, mode);
        }

        RuntimeOptionMetadata findRewriteFailurePolicy(String policy) {
            return findNormalized(rewriteFailurePolicyOptions, policy);
        }

        private String defaultCode(List<RuntimeOptionMetadata> options) {
            RuntimeOptionMetadata explicit = options.stream()
                    .filter(option -> option.defaultSelected() && isSelectable(option))
                    .findFirst()
                    .orElse(null);
            if (explicit != null) {
                return explicit.code();
            }
            RuntimeOptionMetadata fallback = options.stream().filter(this::isSelectable).findFirst().orElse(null);
            return fallback == null ? "" : fallback.code();
        }

        private RuntimeOptionMetadata findExact(List<RuntimeOptionMetadata> options, String code) {
            if (code == null || code.isBlank()) {
                return null;
            }
            return options.stream()
                    .filter(option -> code.equals(option.code()))
                    .findFirst()
                    .orElse(null);
        }

        private RuntimeOptionMetadata findNormalized(List<RuntimeOptionMetadata> options, String code) {
            if (code == null || code.isBlank()) {
                return null;
            }
            String normalized = code.trim().toLowerCase().replace("-", "_");
            return options.stream()
                    .filter(option -> normalized.equals(option.code().toLowerCase().replace("-", "_")))
                    .findFirst()
                    .orElse(null);
        }

        private boolean isSelectable(RuntimeOptionMetadata option) {
            String availability = option.availability() == null ? "available" : option.availability().toLowerCase();
            String status = option.status() == null ? "active" : option.status().toLowerCase();
            if (List.of("unavailable", "disabled", "blocked").contains(availability)) {
                return false;
            }
            return !List.of("disabled", "blocked").contains(status);
        }
    }

    private record RuntimeOptionMetadata(
            String code,
            String label,
            String provider,
            String status,
            String availability,
            String reason,
            boolean defaultSelected
    ) {
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
            return List.of("raw_only", "memory_only_gated", "rewrite_always");
        }
        if (useSessionContext) {
            return List.of("raw_only", "memory_only_gated", "rewrite_always", "selective_rewrite_with_session");
        }
        return List.of("raw_only", "memory_only_gated", "rewrite_always", "selective_rewrite");
    }

    private Map<String, Object> resolveRetrieverConfig(AdminConsoleDtos.RetrieverConfigRequest request) {
        return resolveRetrieverConfig(request, false);
    }

    private Map<String, Object> resolveRetrieverConfig(
            AdminConsoleDtos.RetrieverConfigRequest request,
            boolean fixedModePreset
    ) {
        String mode = normalizeRetrieverMode(request == null ? null : request.retrieverMode());
        String denseModel;
        if (fixedModePreset || request == null || request.denseEmbeddingModel() == null || request.denseEmbeddingModel().isBlank()) {
            denseModel = DEFAULT_DENSE_EMBEDDING_MODEL;
        } else {
            denseModel = request.denseEmbeddingModel().trim();
        }
        boolean denseRequired;
        if (fixedModePreset || request == null || request.denseEmbeddingRequired() == null) {
            denseRequired = !RETRIEVER_MODE_BM25_ONLY.equals(mode);
        } else {
            denseRequired = request.denseEmbeddingRequired();
        }
        if (RETRIEVER_MODE_BM25_ONLY.equals(mode)) {
            denseRequired = false;
        }
        boolean denseFallbackEnabled = fixedModePreset
                ? false
                : request == null || request.denseFallbackEnabled() == null || request.denseFallbackEnabled();
        boolean rerankEnabled = fixedModePreset
                ? false
                : request == null || request.rerankEnabled() == null || request.rerankEnabled();
        int candidatePoolK = fixedModePreset || request == null || request.candidatePoolK() == null
                ? DEFAULT_RETRIEVER_CANDIDATE_POOL_K
                : clampRange(request.candidatePoolK(), 1, 500, "retriever_candidate_pool_k");

        double defaultDenseWeight = fixedModePreset ? DEFAULT_RAG_HYBRID_DENSE_WEIGHT : DEFAULT_HYBRID_DENSE_WEIGHT;
        double defaultBm25Weight = fixedModePreset ? DEFAULT_RAG_HYBRID_BM25_WEIGHT : DEFAULT_HYBRID_BM25_WEIGHT;
        double defaultTechnicalWeight = fixedModePreset ? DEFAULT_RAG_HYBRID_TECHNICAL_WEIGHT : DEFAULT_HYBRID_TECHNICAL_WEIGHT;
        double denseWeight = fixedModePreset || request == null || request.denseWeight() == null
                ? defaultDenseWeight
                : clampRange(request.denseWeight(), 0.0d, 1.0d, "retriever_dense_weight");
        double bm25Weight = fixedModePreset || request == null || request.bm25Weight() == null
                ? defaultBm25Weight
                : clampRange(request.bm25Weight(), 0.0d, 1.0d, "retriever_bm25_weight");
        double technicalWeight = fixedModePreset || request == null || request.technicalWeight() == null
                ? defaultTechnicalWeight
                : clampRange(request.technicalWeight(), 0.0d, 1.0d, "retriever_technical_weight");
        if (RETRIEVER_MODE_BM25_ONLY.equals(mode)) {
            denseWeight = 0.0d;
            bm25Weight = 1.0d;
            technicalWeight = 0.0d;
        } else if (RETRIEVER_MODE_DENSE_ONLY.equals(mode)) {
            denseWeight = 1.0d;
            bm25Weight = 0.0d;
            technicalWeight = 0.0d;
        } else {
            double sum = denseWeight + bm25Weight + technicalWeight;
            if (sum <= 0.0d) {
                denseWeight = defaultDenseWeight;
                bm25Weight = defaultBm25Weight;
                technicalWeight = defaultTechnicalWeight;
            } else {
                denseWeight = denseWeight / sum;
                bm25Weight = bm25Weight / sum;
                technicalWeight = technicalWeight / sum;
            }
        }

        Map<String, Double> fusionWeights = new LinkedHashMap<>();
        fusionWeights.put("dense", denseWeight);
        fusionWeights.put("bm25", bm25Weight);
        fusionWeights.put("technical", technicalWeight);

        Map<String, Object> retrieverConfig = new LinkedHashMap<>();
        retrieverConfig.put("retriever_mode", mode);
        retrieverConfig.put("dense_embedding_model", denseModel);
        retrieverConfig.put("dense_embedding_required", denseRequired);
        retrieverConfig.put("dense_fallback_enabled", denseFallbackEnabled);
        retrieverConfig.put("dense_embedding_device", "cpu");
        retrieverConfig.put("dense_embedding_batch_size", 32);
        retrieverConfig.put("rerank_enabled", rerankEnabled);
        retrieverConfig.put("retriever_candidate_pool_k", candidatePoolK);
        retrieverConfig.put("candidate_pool_k", candidatePoolK);
        retrieverConfig.put("retriever_fusion_weights", fusionWeights);
        return retrieverConfig;
    }

    private String normalizeRetrieverMode(String value) {
        if (value == null || value.isBlank()) {
            return RETRIEVER_MODE_HYBRID;
        }
        String normalized = value.trim().toLowerCase().replace("-", "_");
        if ("bm25".equals(normalized)) {
            return RETRIEVER_MODE_BM25_ONLY;
        }
        if ("dense".equals(normalized)) {
            return RETRIEVER_MODE_DENSE_ONLY;
        }
        if (!List.of(RETRIEVER_MODE_BM25_ONLY, RETRIEVER_MODE_DENSE_ONLY, RETRIEVER_MODE_HYBRID).contains(normalized)) {
            throw new IllegalArgumentException("unsupported retriever_mode: " + value);
        }
        return normalized;
    }

    private String normalizeRetrievalBackend(String value) {
        if (value == null || value.isBlank()) {
            return RETRIEVAL_BACKEND_LOCAL;
        }
        String normalized = value.trim().toLowerCase().replace("-", "_");
        if (!List.of(RETRIEVAL_BACKEND_LOCAL, RETRIEVAL_BACKEND_DB_ANN).contains(normalized)) {
            throw new IllegalArgumentException("unsupported retrieval_backend: " + value);
        }
        return normalized;
    }

    private void attachRetrieverConfig(Map<String, Object> config, Map<String, Object> retrieverConfig) {
        config.put("retriever_config", retrieverConfig);
        config.put("retriever_mode", retrieverConfig.get("retriever_mode"));
        config.put("dense_embedding_model", retrieverConfig.get("dense_embedding_model"));
        config.put("dense_embedding_required", retrieverConfig.get("dense_embedding_required"));
        config.put("dense_fallback_enabled", retrieverConfig.get("dense_fallback_enabled"));
        config.put("dense_embedding_device", retrieverConfig.get("dense_embedding_device"));
        config.put("dense_embedding_batch_size", retrieverConfig.get("dense_embedding_batch_size"));
        config.put("rerank_enabled", retrieverConfig.get("rerank_enabled"));
        config.put("retriever_candidate_pool_k", retrieverConfig.get("retriever_candidate_pool_k"));
        config.put("retriever_fusion_weights", retrieverConfig.get("retriever_fusion_weights"));
    }

    private Map<String, Object> forceBm25RetrieverConfig(Map<String, Object> source) {
        Map<String, Object> normalized = new LinkedHashMap<>(source);
        normalized.put("retriever_mode", RETRIEVER_MODE_BM25_ONLY);
        normalized.put("dense_embedding_required", false);
        normalized.put("dense_fallback_enabled", false);
        normalized.put("retriever_fusion_weights", Map.of(
                "dense", 0.0d,
                "bm25", 1.0d,
                "technical", 0.0d
        ));
        return normalized;
    }

    private void ensureDefaultEvalDataset() {
        if (repository.countEvalSamplesForDefaultDataset() <= 0) {
            return;
        }
        JsonNode categoryDistribution = repository.aggregateCategoryDistributionFromDefaultSamples();
        JsonNode singleMultiDistribution = repository.aggregateSingleMultiDistributionFromDefaultSamples();
        int totalItems = (int) repository.countEvalSamplesForDefaultDataset();
        UUID datasetId = repository.upsertEvalDataset(
                DEFAULT_DATASET_KEY,
                "기본 평가 데이터셋 (build-eval-dataset)",
                "build-eval-dataset 표본만 자동 동기화한 기본 데이터셋 (short-user 전용셋 제외)",
                "v1",
                totalItems,
                categoryDistribution,
                singleMultiDistribution
        );
        repository.refreshDefaultEvalDatasetItems(datasetId);
    }

    private enum StrategyScope {
        SPRING_TECHDOC,
        PYTHON_KR,
        UNKNOWN
    }

    private record SourceResolution(
            String sourceId,
            AdminConsoleRepository.SourceStrategyContext context,
            StrategyScope scope
    ) {
    }

    private record DatasetResolution(
            AdminConsoleRepository.DatasetStrategyContext context,
            StrategyScope scope
    ) {
    }

    private Set<String> resolveAllowedMethodCodesForMethodList(
            String sourceId,
            String sourceDocumentId,
            UUID datasetId
    ) {
        if (datasetId != null) {
            DatasetResolution datasetResolution = resolveDatasetScope(datasetId, false);
            return allowedMethodCodesForScope(datasetResolution.scope());
        }
        SourceResolution sourceResolution = resolveSourceScope(sourceId, sourceDocumentId, false);
        if (sourceResolution == null) {
            return ALL_METHOD_CODES;
        }
        if (!isAllowedSyntheticSourceForScope(sourceResolution.sourceId(), sourceResolution.scope())) {
            return Set.of();
        }
        return allowedMethodCodesForScope(sourceResolution.scope());
    }

    private void validateSyntheticSourceMethodRestriction(String methodCode, String sourceId, String sourceDocumentId) {
        SourceResolution sourceResolution = resolveSourceScope(sourceId, sourceDocumentId, true);
        validateSyntheticSourceAllowlist(methodCode, sourceResolution);
        Set<String> allowedMethods = allowedMethodCodesForScope(sourceResolution.scope());
        if (!allowedMethods.contains(methodCode)) {
            throw new IllegalArgumentException(
                    "method_code " + methodCode
                            + " is not allowed for source scope "
                            + scopeLabel(sourceResolution.scope())
                            + " (source_id=" + sourceResolution.sourceId()
                            + ", allowed=" + allowedMethods + ")"
            );
        }
    }

    private void validateDatasetMethodRestriction(UUID datasetId, List<String> methodCodes) {
        DatasetResolution datasetResolution = resolveDatasetScope(datasetId, true);
        Set<String> allowedMethods = allowedMethodCodesForScope(datasetResolution.scope());
        for (String methodCode : methodCodes) {
            if (!allowedMethods.contains(methodCode)) {
                throw new IllegalArgumentException(
                        "method_code " + methodCode
                                + " is not allowed for dataset "
                                + datasetResolution.context().datasetKey()
                                + " (dataset_id=" + datasetId
                                + ", scope=" + scopeLabel(datasetResolution.scope())
                                + ", allowed=" + allowedMethods + ")"
                );
            }
        }
    }

    private SourceResolution resolveSourceScope(String sourceId, String sourceDocumentId, boolean strict) {
        String normalizedSourceId = blankToNull(sourceId);
        String normalizedSourceDocumentId = blankToNull(sourceDocumentId);
        if (normalizedSourceId == null && normalizedSourceDocumentId == null) {
            if (strict) {
                throw new IllegalArgumentException("source_id or source_document_id is required");
            }
            return null;
        }

        String sourceIdFromDocument = null;
        if (normalizedSourceDocumentId != null) {
            sourceIdFromDocument = repository.findSourceIdByDocumentId(normalizedSourceDocumentId)
                    .orElseThrow(() -> new IllegalArgumentException("source document not found: " + normalizedSourceDocumentId));
        }

        if (normalizedSourceId != null
                && sourceIdFromDocument != null
                && !normalizedSourceId.equalsIgnoreCase(sourceIdFromDocument)) {
            throw new IllegalArgumentException(
                    "source_id and source_document_id mismatch: source_id=" + normalizedSourceId
                            + ", source_document_id=" + normalizedSourceDocumentId
                            + ", resolved_source_id=" + sourceIdFromDocument
            );
        }

        String effectiveSourceId = normalizedSourceId != null ? normalizedSourceId : sourceIdFromDocument;
        if (effectiveSourceId == null) {
            if (strict) {
                throw new IllegalArgumentException("unable to resolve source scope");
            }
            return null;
        }

        AdminConsoleRepository.SourceStrategyContext sourceContext = repository.findSourceStrategyContext(effectiveSourceId)
                .orElse(null);
        StrategyScope scope = inferSourceScope(effectiveSourceId, sourceContext);
        if (strict && scope == StrategyScope.UNKNOWN) {
            throw new IllegalArgumentException(
                    "unable to determine source strategy scope: source_id=" + effectiveSourceId
                            + " (expected spring-source or python-ko-source)"
            );
        }
        return new SourceResolution(effectiveSourceId, sourceContext, scope);
    }

    private DatasetResolution resolveDatasetScope(UUID datasetId, boolean strict) {
        AdminConsoleRepository.DatasetStrategyContext context = repository.findDatasetStrategyContext(datasetId)
                .orElseThrow(() -> new IllegalArgumentException("dataset not found: " + datasetId));
        StrategyScope scope = inferDatasetScope(context);
        if (strict && scope == StrategyScope.UNKNOWN) {
            throw new IllegalArgumentException(
                    "unable to determine dataset strategy scope: dataset_id=" + datasetId
                            + ", dataset_key=" + context.datasetKey()
                            + " (expected spring-techdoc or python-kr dataset)"
            );
        }
        return new DatasetResolution(context, scope);
    }

    private StrategyScope inferSourceScope(String sourceId, AdminConsoleRepository.SourceStrategyContext context) {
        String normalizedSourceId = normalizeHint(sourceId);
        String normalizedProductName = normalizeHint(context == null ? null : context.productName());
        String normalizedSourceName = normalizeHint(context == null ? null : context.sourceName());
        String normalizedSourceType = normalizeHint(context == null ? null : context.sourceType());

        boolean springHint = containsSpringHint(normalizedSourceId)
                || containsSpringHint(normalizedProductName)
                || containsSpringHint(normalizedSourceName);
        boolean pythonHint = containsPythonHint(normalizedSourceId)
                || containsPythonHint(normalizedProductName)
                || containsPythonHint(normalizedSourceName)
                || containsPythonHint(normalizedSourceType);
        boolean koreanHint = containsKoreanHint(normalizedSourceId)
                || containsKoreanHint(normalizedProductName)
                || containsKoreanHint(normalizedSourceName)
                || (context != null && context.hasKoDocuments());
        boolean englishHint = context != null && context.hasEnDocuments();

        if (springHint && !pythonHint) {
            return StrategyScope.SPRING_TECHDOC;
        }
        if (pythonHint && !springHint && (koreanHint || !englishHint)) {
            return StrategyScope.PYTHON_KR;
        }
        return StrategyScope.UNKNOWN;
    }

    private StrategyScope inferDatasetScope(AdminConsoleRepository.DatasetStrategyContext context) {
        String metadataProfile = normalizeHint(context.metadataStrategyProfile());
        if (SCOPE_LABEL_SPRING_TECHDOC.equals(metadataProfile)) {
            return StrategyScope.SPRING_TECHDOC;
        }
        if (SCOPE_LABEL_PYTHON_KR.equals(metadataProfile)) {
            return StrategyScope.PYTHON_KR;
        }

        String datasetKey = normalizeHint(context.datasetKey());
        String metadataLanguage = normalizeHint(context.metadataQueryLanguage());
        boolean springHint = context.hasSpringSamples() || containsSpringHint(datasetKey);
        boolean pythonHint = context.hasPythonSamples() || containsPythonHint(datasetKey);
        boolean koreanHint = context.hasKoQueries()
                || QUERY_LANGUAGE_KO.equals(metadataLanguage)
                || containsKoreanHint(datasetKey);

        if (pythonHint && !springHint && koreanHint) {
            return StrategyScope.PYTHON_KR;
        }
        if (springHint && !pythonHint) {
            return StrategyScope.SPRING_TECHDOC;
        }
        return StrategyScope.UNKNOWN;
    }

    private Set<String> allowedMethodCodesForScope(StrategyScope scope) {
        return switch (scope) {
            case SPRING_TECHDOC -> SPRING_TECHDOC_METHOD_CODES;
            case PYTHON_KR -> PYTHON_KR_METHOD_CODES;
            case UNKNOWN -> ALL_METHOD_CODES;
        };
    }

    private void validateSyntheticSourceAllowlist(String methodCode, SourceResolution sourceResolution) {
        if (isDisallowedSyntheticSource(sourceResolution.sourceId())) {
            throw new IllegalArgumentException("source_id is not allowed for synthetic generation: " + sourceResolution.sourceId());
        }
        Set<String> allowedSourceIds = allowedSourceIdsForMethod(methodCode);
        if (!allowedSourceIds.isEmpty() && !containsSourceId(allowedSourceIds, sourceResolution.sourceId())) {
            throw new IllegalArgumentException(
                    "source_id " + sourceResolution.sourceId()
                            + " is not allowed for method_code " + methodCode
                            + " (allowed=" + allowedSourceIds + ")"
            );
        }
    }

    private Set<String> allowedSourceIdsForMethod(String methodCode) {
        if (SPRING_TECHDOC_METHOD_CODES.contains(methodCode)) {
            return SPRING_TECHDOC_SOURCE_IDS;
        }
        if (PYTHON_KR_METHOD_CODES.contains(methodCode)) {
            return PYTHON_KR_SOURCE_IDS;
        }
        return Set.of();
    }

    private boolean isAllowedSyntheticSourceForScope(String sourceId, StrategyScope scope) {
        if (isDisallowedSyntheticSource(sourceId)) {
            return false;
        }
        Set<String> allowedSourceIds = switch (scope) {
            case SPRING_TECHDOC -> SPRING_TECHDOC_SOURCE_IDS;
            case PYTHON_KR -> PYTHON_KR_SOURCE_IDS;
            case UNKNOWN -> Set.of();
        };
        return allowedSourceIds.isEmpty() || containsSourceId(allowedSourceIds, sourceId);
    }

    private boolean isDisallowedSyntheticSource(String sourceId) {
        return containsSourceId(DISALLOWED_SYNTHETIC_SOURCE_IDS, sourceId);
    }

    private boolean containsSourceId(Set<String> sourceIds, String sourceId) {
        if (sourceId == null || sourceId.isBlank()) {
            return false;
        }
        return sourceIds.contains(sourceId.trim().toLowerCase(Locale.ROOT));
    }

    private String scopeLabel(StrategyScope scope) {
        return switch (scope) {
            case SPRING_TECHDOC -> SCOPE_LABEL_SPRING_TECHDOC;
            case PYTHON_KR -> SCOPE_LABEL_PYTHON_KR;
            case UNKNOWN -> "unknown";
        };
    }

    private boolean containsSpringHint(String normalizedText) {
        if (normalizedText == null || normalizedText.isBlank()) {
            return false;
        }
        return normalizedText.contains("spring")
                || normalizedText.contains("docs_spring");
    }

    private boolean containsPythonHint(String normalizedText) {
        if (normalizedText == null || normalizedText.isBlank()) {
            return false;
        }
        return normalizedText.contains("python");
    }

    private boolean containsKoreanHint(String normalizedText) {
        if (normalizedText == null || normalizedText.isBlank()) {
            return false;
        }
        return normalizedText.contains("ko")
                || normalizedText.contains("kr")
                || normalizedText.contains("korean");
    }

    private String normalizeHint(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private boolean isEnglishOnlyMethod(String methodCode) {
        String normalized = normalizeOptionalMethodCode(methodCode);
        return "E".equals(normalized) || "F".equals(normalized);
    }

    private String normalizeMethodCode(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("method_code is required");
        }
        return value.trim().toUpperCase();
    }

    private String normalizeOptionalMethodCode(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim().toUpperCase();
    }

    private String normalizeGatingPassStage(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim().toLowerCase();
        if (GATING_PASS_STAGE_REJECTED.equals(normalized)) {
            normalized = GATING_PASS_STAGE_FAILED_RULE;
        }
        if (!List.of(
                GATING_PASS_STAGE_FAILED_RULE,
                GATING_PASS_STAGE_PASSED_RULE,
                GATING_PASS_STAGE_PASSED_LLM,
                GATING_PASS_STAGE_PASSED_UTILITY,
                GATING_PASS_STAGE_PASSED_DIVERSITY,
                GATING_PASS_STAGE_PASSED_ALL
        ).contains(normalized)) {
            throw new IllegalArgumentException("unsupported pass_stage: " + value);
        }
        return normalized;
    }

    private String normalizeVersionName(String value) {
        if (value == null || value.isBlank()) {
            return "v" + Instant.now().toEpochMilli();
        }
        return value.trim();
    }

    private String normalizeEvalQueryLanguage(String requested, String datasetDefault) {
        String candidate = firstNonBlank(requested, datasetDefault, QUERY_LANGUAGE_KO);
        candidate = candidate == null ? QUERY_LANGUAGE_KO : candidate.trim().toLowerCase();
        if (!List.of(QUERY_LANGUAGE_KO, QUERY_LANGUAGE_EN).contains(candidate)) {
            throw new IllegalArgumentException("unsupported eval_query_language: " + requested);
        }
        return candidate;
    }

    private String normalizeGatingPreset(String value) {
        if (value == null || value.isBlank()) {
            return "full_gating";
        }
        String normalized = value.trim();
        if (!List.of("ungated", "rule_only", "rule_plus_llm", "full_gating").contains(normalized)) {
            throw new IllegalArgumentException("unsupported gating preset: " + value);
        }
        return normalized;
    }

    private boolean flagValue(Boolean requestValue, String preset, String stage) {
        if (requestValue != null) {
            return requestValue;
        }
        return switch (preset) {
            case "ungated" -> false;
            case "rule_only" -> "rule".equals(stage);
            case "rule_plus_llm" -> "rule".equals(stage) || "llm".equals(stage);
            default -> true;
        };
    }

    private List<String> normalizeMethodCodes(List<String> methodCodes) {
        if (methodCodes == null || methodCodes.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String methodCode : methodCodes) {
            if (methodCode == null || methodCode.isBlank()) {
                continue;
            }
            normalized.add(methodCode.trim().toUpperCase());
        }
        return List.copyOf(normalized);
    }

    private List<UUID> normalizeGenerationBatchIds(List<UUID> generationBatchIds, UUID singleGenerationBatchId) {
        LinkedHashSet<UUID> normalized = new LinkedHashSet<>();
        if (generationBatchIds != null) {
            for (UUID batchId : generationBatchIds) {
                if (batchId != null) {
                    normalized.add(batchId);
                }
            }
        }
        if (singleGenerationBatchId != null) {
            normalized.add(singleGenerationBatchId);
        }
        return List.copyOf(normalized);
    }

    private void applyLlmModelOverrides(Map<String, Object> config, String requestedLlmModel) {
        String llmModel = blankToNull(requestedLlmModel);
        if (llmModel == null) {
            return;
        }
        config.put("llm_model", llmModel);
        config.put("llm_summary_model", llmModel);
        config.put("llm_query_model", llmModel);
        config.put("llm_self_eval_model", llmModel);
        config.put("llm_rewrite_model", llmModel);
    }

    private String defaultCreatedBy(String value) {
        return value == null || value.isBlank() ? "admin-console" : value.trim();
    }

    private String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private int clampRange(int value, int min, int max, String fieldName) {
        if (value < min || value > max) {
            throw new IllegalArgumentException(fieldName + " must be between " + min + " and " + max);
        }
        return value;
    }

    private double clampRange(double value, double min, double max, String fieldName) {
        if (value < min || value > max) {
            throw new IllegalArgumentException(fieldName + " must be between " + min + " and " + max);
        }
        return value;
    }

    private Map<String, Object> resolveRuleConfig(String methodCode, AdminConsoleDtos.GatingRuleConfig request) {
        boolean englishOnly = isEnglishOnlyMethod(methodCode);
        Map<String, Object> ruleConfig = new LinkedHashMap<>();
        ruleConfig.put(
                "rule_min_len_short",
                request == null || request.minLengthShort() == null ? 4 : clampRange(request.minLengthShort(), 1, 400, "rule_min_len_short")
        );
        ruleConfig.put(
                "rule_max_len_short",
                request == null || request.maxLengthShort() == null ? 60 : clampRange(request.maxLengthShort(), 1, 400, "rule_max_len_short")
        );
        ruleConfig.put(
                "rule_min_len_long",
                request == null || request.minLengthLong() == null ? 8 : clampRange(request.minLengthLong(), 1, 400, "rule_min_len_long")
        );
        ruleConfig.put(
                "rule_max_len_long",
                request == null || request.maxLengthLong() == null ? 100 : clampRange(request.maxLengthLong(), 1, 400, "rule_max_len_long")
        );
        ruleConfig.put(
                "rule_min_tokens",
                request == null || request.minTokens() == null ? 2 : clampRange(request.minTokens(), 1, 120, "rule_min_tokens")
        );
        ruleConfig.put(
                "rule_max_tokens",
                request == null || request.maxTokens() == null ? 30 : clampRange(request.maxTokens(), 1, 120, "rule_max_tokens")
        );
        double minKoreanRatio = request == null || request.minKoreanRatio() == null
                ? (englishOnly ? 0.0d : 0.20d)
                : clampRange(request.minKoreanRatio(), 0.0d, 1.0d, "rule_min_korean_ratio");
        double minKoreanRatioCodeMixed = request == null || request.minKoreanRatio() == null
                ? (englishOnly ? 0.0d : 0.20d)
                : minKoreanRatio;
        ruleConfig.put("rule_min_korean_ratio", minKoreanRatio);
        ruleConfig.put("rule_min_korean_ratio_code_mixed", minKoreanRatioCodeMixed);
        if (englishOnly) {
            ruleConfig.put("rule_max_len_short_en", 120);
            ruleConfig.put("rule_max_len_long_en", 200);
            ruleConfig.put("rule_max_copy_ratio_en", 0.85d);
        }
        ruleConfig.put("rule_max_copy_ratio", 0.60d);
        return ruleConfig;
    }

    private Map<String, Double> resolveGatingWeights(AdminConsoleDtos.GatingWeightsConfig request) {
        double llmWeight = request == null || request.llmWeight() == null
                ? 0.35d
                : clampRange(request.llmWeight(), 0.0d, 1.0d, "llm_weight");
        double utilityWeight = request == null || request.utilityWeight() == null
                ? 0.50d
                : clampRange(request.utilityWeight(), 0.0d, 1.0d, "utility_weight");
        double diversityWeight = request == null || request.diversityWeight() == null
                ? 0.15d
                : clampRange(request.diversityWeight(), 0.0d, 1.0d, "diversity_weight");
        double sum = llmWeight + utilityWeight + diversityWeight;
        if (sum <= 0.0d) {
            llmWeight = 0.35d;
            utilityWeight = 0.50d;
            diversityWeight = 0.15d;
            sum = 1.0d;
        }
        Map<String, Double> gatingWeights = new LinkedHashMap<>();
        gatingWeights.put("llm", llmWeight / sum);
        gatingWeights.put("utility", utilityWeight / sum);
        gatingWeights.put("novelty", diversityWeight / sum);
        return gatingWeights;
    }

    private Map<String, Double> resolveUtilityScoreWeights(AdminConsoleDtos.GatingUtilityScoreConfig request) {
        Map<String, Double> weights = new LinkedHashMap<>();
        weights.put(
                "target_top1",
                request == null || request.targetTop1Score() == null
                        ? 1.00d
                        : clampRange(request.targetTop1Score(), 0.0d, 1.0d, "utility_target_top1_score")
        );
        weights.put(
                "target_top3",
                request == null || request.targetTop3Score() == null
                        ? 0.85d
                        : clampRange(request.targetTop3Score(), 0.0d, 1.0d, "utility_target_top3_score")
        );
        weights.put(
                "target_top5",
                request == null || request.targetTop5Score() == null
                        ? 0.70d
                        : clampRange(request.targetTop5Score(), 0.0d, 1.0d, "utility_target_top5_score")
        );
        weights.put(
                "target_top10",
                request == null || request.targetTop10Score() == null
                        ? 0.60d
                        : clampRange(request.targetTop10Score(), 0.0d, 1.0d, "utility_target_top10_score")
        );
        weights.put(
                "same_doc_top3",
                request == null || request.sameDocTop3Score() == null
                        ? 0.55d
                        : clampRange(request.sameDocTop3Score(), 0.0d, 1.0d, "utility_same_doc_top3_score")
        );
        weights.put(
                "same_doc_top5",
                request == null || request.sameDocTop5Score() == null
                        ? 0.40d
                        : clampRange(request.sameDocTop5Score(), 0.0d, 1.0d, "utility_same_doc_top5_score")
        );
        weights.put(
                "outside_top5",
                request == null || request.outsideTop5Score() == null
                        ? 0.00d
                        : clampRange(request.outsideTop5Score(), 0.0d, 1.0d, "utility_outside_top5_score")
        );
        weights.put(
                "multi_partial_bonus",
                request == null || request.multiPartialBonus() == null
                        ? 0.05d
                        : clampRange(request.multiPartialBonus(), 0.0d, 1.0d, "utility_multi_partial_bonus")
        );
        weights.put(
                "multi_full_bonus",
                request == null || request.multiFullBonus() == null
                        ? 0.12d
                        : clampRange(request.multiFullBonus(), 0.0d, 1.0d, "utility_multi_full_bonus")
        );
        return weights;
    }

    private Map<String, Object> baseExperimentConfig(String experimentKey, String methodCode) {
        boolean englishOnly = isEnglishOnlyMethod(methodCode);
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("experiment_key", experimentKey);
        config.put("category", "admin");
        config.put("description", "Admin console generated config");
        config.put("generation_strategy", methodCode);
        config.put("enable_code_mixed", "D".equalsIgnoreCase(methodCode));
        config.put("query_language", englishOnly ? QUERY_LANGUAGE_EN : QUERY_LANGUAGE_KO);
        config.put("query_language_profile", englishOnly ? QUERY_LANGUAGE_EN : ("D".equalsIgnoreCase(methodCode) ? "code_mixed" : QUERY_LANGUAGE_KO));
        config.put("enable_rule_filter", true);
        config.put("enable_llm_self_eval", true);
        config.put("enable_retrieval_utility", true);
        config.put("enable_diversity", true);
        config.put("enable_anti_copy", true);
        config.put("gating_preset", "full_gating");
        config.put("llm_provider", "gemini");
        config.put("llm_model", DEFAULT_LLM_MODEL);
        config.put("llm_summary_model", DEFAULT_LLM_MODEL);
        config.put("llm_query_model", DEFAULT_LLM_MODEL);
        config.put("llm_self_eval_model", DEFAULT_LLM_MODEL);
        config.put("llm_rewrite_model", DEFAULT_LLM_MODEL);
        config.put("llm_fallback_models", DEFAULT_LLM_FALLBACK_MODEL);
        config.put("llm_rpm", 1000);
        config.put("llm_tpm", 1_000_000);
        config.put("llm_rpd", 10_000);
        config.put("llm_batch_size", 20);
        config.put("memory_top_n", 5);
        config.put("rewrite_candidate_count", 3);
        config.put("rewrite_threshold", 0.10);
        config.put("rewrite_failure_policy", REWRITE_FAILURE_POLICY_FAIL_RUN);
        config.put("retrieval_top_k", DEFAULT_RAG_RETRIEVAL_TOP_K);
        config.put("rerank_top_n", 5);
        config.put("use_session_context", false);
        config.put("avg_queries_per_chunk", 2.0);
        config.put("max_total_queries", 40);
        config.put("rule_min_len_short", 4);
        config.put("rule_max_len_short", 60);
        config.put("rule_min_len_long", 8);
        config.put("rule_max_len_long", 100);
        config.put("rule_min_tokens", 2);
        config.put("rule_max_tokens", 30);
        config.put("rule_min_korean_ratio", englishOnly ? 0.0 : 0.20);
        config.put("rule_min_korean_ratio_code_mixed", englishOnly ? 0.0 : 0.20);
        config.put(
                "retrieval_utility_weights",
                Map.of(
                        "target_top1", 1.00,
                        "target_top3", 0.85,
                        "target_top5", 0.70,
                        "target_top10", 0.60,
                        "same_doc_top3", 0.55,
                        "same_doc_top5", 0.40,
                        "outside_top5", 0.00,
                        "multi_partial_bonus", 0.05,
                        "multi_full_bonus", 0.12
                )
        );
        config.put("gating_weights", Map.of("utility", 0.50, "llm", 0.35, "novelty", 0.15));
        config.put("utility_threshold", 0.70);
        config.put("diversity_threshold_same_chunk", 0.93);
        config.put("diversity_threshold_same_doc", 0.96);
        config.put("final_score_threshold", 0.75);
        config.put("random_seed", 31);
        return config;
    }

    private void writeExperimentConfig(String experimentName, Map<String, Object> config) {
        Path configPath = resolveRepoRoot().resolve("configs/experiments").resolve(experimentName + ".yaml").normalize();
        try {
            Files.createDirectories(configPath.getParent());
            Files.writeString(configPath, yaml.dump(config), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("failed to write experiment config: " + configPath, exception);
        }
    }

    private Path resolveRepoRoot() {
        Path configured = Path.of(pipelineProperties.repoRoot()).toAbsolutePath().normalize();
        if (Files.exists(configured.resolve("pipeline/cli.py"))) {
            return configured;
        }
        Path parent = configured.getParent();
        if (parent != null && Files.exists(parent.resolve("pipeline/cli.py"))) {
            return parent;
        }
        throw new IllegalStateException("failed to resolve repository root for admin console");
    }
}
