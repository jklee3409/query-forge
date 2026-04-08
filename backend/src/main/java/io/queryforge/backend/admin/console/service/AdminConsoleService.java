package io.queryforge.backend.admin.console.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.queryforge.backend.admin.console.model.AdminConsoleDtos;
import io.queryforge.backend.admin.console.repository.AdminConsoleRepository;
import io.queryforge.backend.admin.pipeline.config.AdminPipelineProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
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
            config.put("max_total_queries", clampRange(request.maxTotalQueries(), 1, 500, "max_total_queries"));
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
            config.put("llm_rpm", clampRange(request.llmRpm(), 1, 120, "llm_rpm"));
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

    public AdminConsoleDtos.GatingFunnelResponse getGatingFunnel(UUID gatingBatchId) {
        return repository.findGatingFunnel(gatingBatchId);
    }

    public List<AdminConsoleDtos.GatingResultRow> listGatingResults(
            UUID gatingBatchId,
            String queryType,
            Integer limit,
            Integer offset
    ) {
        return repository.findGatingResults(gatingBatchId, queryType, limit, offset);
    }

    @Transactional
    public AdminConsoleDtos.GatingBatchRow runGating(AdminConsoleDtos.GatingBatchRunRequest request) {
        String methodCode = normalizeMethodCode(request.methodCode());
        AdminConsoleDtos.SyntheticGenerationMethod method = repository.findGenerationMethodByCode(methodCode)
                .orElseThrow(() -> new IllegalArgumentException("generation method not found: " + methodCode));
        AdminConsoleDtos.SyntheticGenerationBatchRow generationBatch = request.generationBatchId() == null
                ? null
                : repository.findGenerationBatch(request.generationBatchId())
                .orElseThrow(() -> new IllegalArgumentException("generation batch not found: " + request.generationBatchId()));

        UUID sourceGenerationRunId = generationBatch != null ? generationBatch.sourceGenerationRunId() : null;
        String gatingPreset = normalizeGatingPreset(request.gatingPreset());
        Map<String, Object> stageConfig = new LinkedHashMap<>();
        stageConfig.put("enable_rule_filter", flagValue(request.enableRuleFilter(), gatingPreset, "rule"));
        stageConfig.put("enable_llm_self_eval", flagValue(request.enableLlmSelfEval(), gatingPreset, "llm"));
        stageConfig.put("enable_retrieval_utility", flagValue(request.enableRetrievalUtility(), gatingPreset, "utility"));
        stageConfig.put("enable_diversity", flagValue(request.enableDiversity(), gatingPreset, "diversity"));

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

    @Transactional
    public AdminConsoleDtos.RagTestRunRow runRagTest(AdminConsoleDtos.RagTestRunRequest request) {
        ensureDefaultEvalDataset();
        if (request.datasetId() == null) {
            throw new IllegalArgumentException("dataset_id is required");
        }
        List<String> methodCodes = normalizeMethodCodes(request.methodCodes());
        if (methodCodes.isEmpty()) {
            throw new IllegalArgumentException("at least one generation method is required");
        }
        List<UUID> batchIds = request.generationBatchIds() == null ? List.of() : request.generationBatchIds();
        boolean gatingApplied = request.gatingApplied() == null || request.gatingApplied();
        String gatingPreset = gatingApplied ? normalizeGatingPreset(request.gatingPreset()) : "ungated";
        boolean rewriteEnabled = request.rewriteEnabled() == null || request.rewriteEnabled();
        boolean selectiveRewrite = request.selectiveRewrite() == null || request.selectiveRewrite();
        boolean useSessionContext = request.useSessionContext() != null && request.useSessionContext();
        int retrievalTopK = request.retrievalTopK() != null && request.retrievalTopK() > 0 ? request.retrievalTopK() : 20;
        int rerankTopN = request.rerankTopN() != null && request.rerankTopN() > 0 ? request.rerankTopN() : 5;
        double threshold = request.threshold() != null ? request.threshold() : 0.05d;

        String experimentName = "admin_eval_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        JsonNode methodCodesNode = objectMapper.valueToTree(methodCodes);
        JsonNode batchIdsNode = objectMapper.valueToTree(batchIds);
        UUID runId = repository.createRagTestRun(
                "RAG 테스트 " + Instant.now(),
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

        Map<String, Object> config = baseExperimentConfig(experimentName, methodCodes.getFirst());
        config.put("dataset_id", request.datasetId().toString());
        config.put("memory_generation_strategies", methodCodes);
        config.put("gating_preset", gatingPreset);
        config.put("rewrite_enabled", rewriteEnabled);
        config.put("selective_rewrite", selectiveRewrite);
        config.put("use_session_context", useSessionContext);
        config.put("rewrite_threshold", threshold);
        config.put("retrieval_top_k", retrievalTopK);
        config.put("rerank_top_n", rerankTopN);
        config.put("retrieval_modes", resolveRetrievalModes(rewriteEnabled, selectiveRewrite, useSessionContext));

        Optional<UUID> sourceGatingRunId = findLatestMatchingGatingRun(methodCodes, gatingPreset);
        sourceGatingRunId.ifPresent(uuid -> config.put("source_gating_run_id", uuid.toString()));

        writeExperimentConfig(experimentName, config);
        repository.upsertRagTestRunConfig(runId, objectMapper.valueToTree(config));
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

    private List<String> resolveRetrievalModes(boolean rewriteEnabled, boolean selectiveRewrite, boolean useSessionContext) {
        if (!rewriteEnabled) {
            return List.of("raw_only");
        }
        if (!selectiveRewrite) {
            return List.of("rewrite_always");
        }
        if (useSessionContext) {
            return List.of("selective_rewrite_with_session");
        }
        return List.of("selective_rewrite");
    }

    private void ensureDefaultEvalDataset() {
        if (repository.countEvalSamples() <= 0) {
            return;
        }
        JsonNode categoryDistribution = repository.aggregateCategoryDistributionFromSamples();
        JsonNode singleMultiDistribution = repository.aggregateSingleMultiDistributionFromSamples();
        int totalItems = (int) repository.countEvalSamples();
        UUID datasetId = repository.upsertEvalDataset(
                DEFAULT_DATASET_KEY,
                "기본 평가 데이터셋 (eval_samples)",
                "eval_samples 전체를 자동 동기화한 기본 데이터셋",
                "v1",
                totalItems,
                categoryDistribution,
                singleMultiDistribution
        );
        repository.refreshEvalDatasetItems(datasetId);
    }

    private String normalizeMethodCode(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("method_code is required");
        }
        return value.trim().toUpperCase();
    }

    private String normalizeVersionName(String value) {
        if (value == null || value.isBlank()) {
            return "v" + Instant.now().toEpochMilli();
        }
        return value.trim();
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

    private Map<String, Object> baseExperimentConfig(String experimentKey, String methodCode) {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("experiment_key", experimentKey);
        config.put("category", "admin");
        config.put("description", "Admin console generated config");
        config.put("generation_strategy", methodCode);
        config.put("enable_code_mixed", "D".equalsIgnoreCase(methodCode));
        config.put("enable_rule_filter", true);
        config.put("enable_llm_self_eval", true);
        config.put("enable_retrieval_utility", true);
        config.put("enable_diversity", true);
        config.put("enable_anti_copy", true);
        config.put("gating_preset", "full_gating");
        config.put("llm_provider", "groq");
        config.put("llm_model", "llama-3.1-8b-instant");
        config.put("llm_summary_model", "llama-3.1-8b-instant");
        config.put("llm_query_model", "llama-3.1-8b-instant");
        config.put("llm_self_eval_model", "llama-3.1-8b-instant");
        config.put("llm_rewrite_model", "llama-3.1-8b-instant");
        config.put("llm_rpm", 20);
        config.put("llm_batch_size", 20);
        config.put("memory_top_n", 5);
        config.put("rewrite_candidate_count", 3);
        config.put("rewrite_threshold", 0.05);
        config.put("retrieval_top_k", 20);
        config.put("rerank_top_n", 5);
        config.put("use_session_context", false);
        config.put("avg_queries_per_chunk", 2.0);
        config.put("max_total_queries", 40);
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
