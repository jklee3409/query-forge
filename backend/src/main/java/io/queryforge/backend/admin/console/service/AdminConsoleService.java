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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AdminConsoleService {

    private static final String DEFAULT_DATASET_KEY = "human_eval_default";
    private static final String RUN_DISCIPLINE_OFFICIAL = "official";
    private static final String RUN_DISCIPLINE_EXPLORATORY = "exploratory";
    private static final String COMPARISON_GATING_EFFECT = "gating_effect";
    private static final String COMPARISON_REWRITE_EFFECT = "rewrite_effect";
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
    private static final String RETRIEVER_MODE_BM25_ONLY = "bm25_only";
    private static final String RETRIEVER_MODE_DENSE_ONLY = "dense_only";
    private static final String RETRIEVER_MODE_HYBRID = "hybrid";
    private static final String DEFAULT_DENSE_EMBEDDING_MODEL = "intfloat/multilingual-e5-small";
    private static final double DEFAULT_HYBRID_DENSE_WEIGHT = 0.58d;
    private static final double DEFAULT_HYBRID_BM25_WEIGHT = 0.34d;
    private static final double DEFAULT_HYBRID_TECHNICAL_WEIGHT = 0.08d;
    private static final int DEFAULT_RAG_RETRIEVAL_TOP_K = 10;
    private static final int DEFAULT_RETRIEVER_CANDIDATE_POOL_K = 50;
    private static final double DEFAULT_RAG_HYBRID_DENSE_WEIGHT = 0.60d;
    private static final double DEFAULT_RAG_HYBRID_BM25_WEIGHT = 0.32d;
    private static final double DEFAULT_RAG_HYBRID_TECHNICAL_WEIGHT = 0.08d;
    private static final int MAX_RAG_RUN_LABEL_LENGTH = 120;
    private static final ZoneId KOREA_ZONE_ID = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter RUN_LABEL_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
            .withZone(KOREA_ZONE_ID);

    private final AdminConsoleRepository repository;
    private final LlmJobService llmJobService;
    private final AdminPipelineProperties pipelineProperties;
    private final ObjectMapper objectMapper;
    private final Yaml yaml = new Yaml();

    public List<AdminConsoleDtos.SyntheticGenerationMethod> listGenerationMethods() {
        return repository.findGenerationMethods();
    }

    public List<AdminConsoleDtos.SyntheticGenerationBatchRow> listGenerationBatches(Integer limit) {
        return repository.findGenerationBatches(limit);
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

    @Transactional
    public AdminConsoleDtos.SyntheticGenerationBatchRow runSyntheticGeneration(AdminConsoleDtos.SyntheticBatchRunRequest request) {
        String methodCode = normalizeMethodCode(request.methodCode());
        AdminConsoleDtos.SyntheticGenerationMethod method = repository.findGenerationMethodByCode(methodCode)
                .orElseThrow(() -> new IllegalArgumentException("generation method not found: " + methodCode));

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
        if (request.llmModel() != null && !request.llmModel().isBlank()) {
            String model = request.llmModel().trim();
            config.put("llm_model", model);
            config.put("llm_summary_model", model);
            config.put("llm_query_model", model);
            config.put("llm_self_eval_model", model);
            config.put("llm_rewrite_model", model);
        }
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
        String methodCode = normalizeMethodCode(request.methodCode());
        if (request.generationBatchId() == null) {
            throw new IllegalArgumentException("generation_batch_id is required");
        }
        AdminConsoleDtos.SyntheticGenerationMethod method = repository.findGenerationMethodByCode(methodCode)
                .orElseThrow(() -> new IllegalArgumentException("generation method not found: " + methodCode));
        AdminConsoleDtos.SyntheticGenerationBatchRow generationBatch = repository.findGenerationBatch(request.generationBatchId())
                .orElseThrow(() -> new IllegalArgumentException("generation batch not found: " + request.generationBatchId()));
        if (generationBatch.methodCode() == null || !methodCode.equalsIgnoreCase(generationBatch.methodCode())) {
            throw new IllegalArgumentException(
                    "generation batch method mismatch: expected=" + methodCode + ", actual=" + generationBatch.methodCode()
            );
        }
        if (!"completed".equalsIgnoreCase(generationBatch.status())) {
            throw new IllegalArgumentException("generation batch is not completed: " + request.generationBatchId());
        }
        if (generationBatch.sourceGenerationRunId() == null) {
            throw new IllegalArgumentException("generation batch has no source_generation_run_id: " + request.generationBatchId());
        }

        UUID sourceGenerationRunId = generationBatch.sourceGenerationRunId();
        String gatingPreset = normalizeGatingPreset(request.gatingPreset());
        AdminConsoleDtos.GatingRunConfig requestConfig = request.config();
        AdminConsoleDtos.GatingStageFlags stageFlags = requestConfig == null ? null : requestConfig.stageFlags();
        AdminConsoleDtos.GatingThresholdConfig thresholdConfig = requestConfig == null ? null : requestConfig.thresholds();
        Map<String, Object> retrieverConfig = resolveRetrieverConfig(
                requestConfig == null ? null : requestConfig.retrieverConfig()
        );
        if ("E".equalsIgnoreCase(methodCode) && requestConfig != null && requestConfig.retrieverConfig() == null) {
            retrieverConfig = forceBm25RetrieverConfig(retrieverConfig);
        }
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
        Map<String, Object> ruleConfig = resolveRuleConfig(methodCode, requestConfig == null ? null : requestConfig.ruleConfig());
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

        repository.clearCompletedGatingResults(method.generationMethodId(), request.generationBatchId());

        UUID gatingBatchId = repository.createGatingBatch(
                gatingPreset,
                method.generationMethodId(),
                request.generationBatchId(),
                sourceGenerationRunId,
                defaultCreatedBy(request.createdBy()),
                objectMapper.valueToTree(stageConfig)
        );

        String experimentName = "admin_gate_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        Map<String, Object> config = baseExperimentConfig(experimentName, methodCode);
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
        config.put("gating_batch_id", gatingBatchId.toString());
        if (sourceGenerationRunId != null) {
            config.put("source_generation_run_id", sourceGenerationRunId.toString());
        }
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
        int retrievalTopK = request.retrievalTopK() != null && request.retrievalTopK() > 0
                ? request.retrievalTopK()
                : DEFAULT_RAG_RETRIEVAL_TOP_K;
        int rerankTopN = request.rerankTopN() != null && request.rerankTopN() > 0 ? request.rerankTopN() : 5;
        Map<String, Object> retrieverConfig = resolveRetrieverConfig(request.retrieverConfig(), true);
        String runLabel = resolveRagRunLabel(
                request.runName(),
                String.valueOf(retrieverConfig.get("retriever_mode"))
        );
        double threshold = request.threshold() != null ? request.threshold() : 0.10d;
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

        if (syntheticFreeBaseline) {
            gatingApplied = false;
            gatingPreset = "ungated";
            rewriteEnabled = false;
            selectiveRewrite = false;
            useSessionContext = false;
            stageCutoffEnabled = false;
            stageCutoffLevel = null;
        }

        String experimentName = "admin_eval_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
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
                request.topK(),
                threshold,
                retrievalTopK,
                rerankTopN,
                experimentName,
                defaultCreatedBy(request.createdBy())
        );

        Map<String, Object> config = baseExperimentConfig(
                experimentName,
                syntheticFreeBaseline ? SYNTHETIC_FREE_BASELINE_METHOD : methodCodes.getFirst()
        );
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
        config.put("retrieval_top_k", retrievalTopK);
        config.put("rerank_top_n", rerankTopN);
        attachRetrieverConfig(config, retrieverConfig);
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

        boolean requireExplicitSnapshot = RUN_DISCIPLINE_OFFICIAL.equals(runDiscipline);
        if (syntheticFreeBaseline) {
            config.put("retrieval_modes", List.of("raw_only"));
        } else if (RUN_DISCIPLINE_OFFICIAL.equals(runDiscipline) && COMPARISON_GATING_EFFECT.equals(officialComparisonType)) {
            Map<String, Object> comparisonSnapshots = new LinkedHashMap<>();
            UUID ungatedRunId = resolveSourceGatingRunId(methodCodes, "ungated", comparisonBatchIds.get("ungated"), true)
                    .orElseThrow(() -> new IllegalStateException("ungated snapshot source run not found"));
            UUID ruleOnlyRunId = resolveSourceGatingRunId(methodCodes, "rule_only", comparisonBatchIds.get("rule_only"), true)
                    .orElseThrow(() -> new IllegalStateException("rule_only snapshot source run not found"));
            UUID fullGatingRunId = resolveSourceGatingRunId(methodCodes, "full_gating", comparisonBatchIds.get("full_gating"), true)
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
                    sourceGatingBatchId,
                    true
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
                    sourceGatingBatchId,
                    requireExplicitSnapshot
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

    private Optional<UUID> findLatestMatchingGatingRun(List<String> methodCodes, String gatingPreset) {
        return repository.findGatingBatches(200).stream()
                .filter(item -> "completed".equalsIgnoreCase(item.status()))
                .filter(item -> item.sourceGatingRunId() != null)
                .filter(item -> gatingPreset.equalsIgnoreCase(item.gatingPreset()))
                .filter(item -> item.methodCode() == null || methodCodes.contains(item.methodCode().toUpperCase()))
                .map(AdminConsoleDtos.GatingBatchRow::sourceGatingRunId)
                .findFirst();
    }

    private Optional<UUID> resolveSourceGatingRunId(
            List<String> methodCodes,
            String gatingPreset,
            UUID sourceGatingBatchId,
            boolean requireExplicit
    ) {
        if (sourceGatingBatchId == null) {
            if (requireExplicit) {
                throw new IllegalArgumentException(
                        "official evaluation runs require explicit source_gating_batch_id (auto-latest disabled)"
                );
            }
            return findLatestMatchingGatingRun(methodCodes, gatingPreset);
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
        boolean englishOnly = "E".equalsIgnoreCase(methodCode);
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
        boolean englishOnly = "E".equalsIgnoreCase(methodCode);
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
        config.put("llm_model", "gemini-2.5-flash-lite");
        config.put("llm_summary_model", "gemini-2.5-flash-lite");
        config.put("llm_query_model", "gemini-2.5-flash-lite");
        config.put("llm_self_eval_model", "gemini-2.5-flash-lite");
        config.put("llm_rewrite_model", "gemini-2.5-flash-lite");
        config.put("llm_fallback_models", "gemini-2.5-flash");
        config.put("llm_rpm", 1000);
        config.put("llm_tpm", 1_000_000);
        config.put("llm_rpd", 10_000);
        config.put("llm_batch_size", 20);
        config.put("memory_top_n", 5);
        config.put("rewrite_candidate_count", 3);
        config.put("rewrite_threshold", 0.10);
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
