package io.queryforge.backend.admin.console.synthetic;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.queryforge.backend.admin.console.configuration.ConsoleExperimentConfig;
import io.queryforge.backend.admin.console.jobs.LlmJobService;
import io.queryforge.backend.admin.console.runtime.AdminRuntimeOptionsService;
import io.queryforge.backend.admin.console.runtime.AdminRuntimeOptionsService.RuntimeCatalog;
import io.queryforge.backend.admin.console.scope.ConsoleScopeService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static io.queryforge.backend.admin.console.configuration.ConsoleParameters.blankToNull;
import static io.queryforge.backend.admin.console.configuration.ConsoleParameters.clampRange;
import static io.queryforge.backend.admin.console.configuration.ConsoleParameters.defaultCreatedBy;
import static io.queryforge.backend.admin.console.configuration.ConsoleParameters.normalizeMethodCode;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SyntheticGenerationService {

    private final ConsoleScopeService scopeService;
    private final ConsoleExperimentConfig experimentConfig;
    private final AdminRuntimeOptionsService runtimeOptionsService;

    private static final int STRATEGY_AC_SUMMARY_MAX_OUTPUT_TOKENS = 2048;

    private static final int STRATEGY_AC_QUERY_MAX_OUTPUT_TOKENS = 4096;

    private static final int STRATEGY_A_TRANSLATION_MAX_OUTPUT_TOKENS = 2048;

    private static final int STRATEGY_B_TRANSLATION_MAX_OUTPUT_TOKENS = 2048;

    private static final int STRATEGY_B_SUMMARY_MAX_CHARS = 900;

    private static final int STRATEGY_B_QUERY_ORIGINAL_CHUNK_MAX_CHARS = 1800;

    private static final int STRATEGY_B_QUERY_TRANSLATED_CHUNK_MAX_CHARS = 1200;

    private static final int STRATEGY_B_QUERY_SUMMARY_MAX_CHARS = 900;

    private static final String LLM_EXECUTION_MODE_ONLINE = "online";

    private static final String LLM_EXECUTION_MODE_GEMINI_BATCH = "gemini_batch";

    private static final String GEMINI_BATCH_INPUT_MODE_INLINE = "inline";

    private static final String GEMINI_BATCH_INPUT_MODE_JSONL = "jsonl";

    private final AdminSyntheticMethodRepository syntheticMethodRepository;

    private final SyntheticGenerationRepository generationRepository;

    private final LlmJobService llmJobService;

    private final ObjectMapper objectMapper;

    public List<SyntheticGenerationDtos.SyntheticGenerationMethod> listGenerationMethods() {
        return listGenerationMethods(null, null, null, null);
    }

    public List<SyntheticGenerationDtos.SyntheticGenerationMethod> listGenerationMethods(
            String sourceId,
            String sourceDocumentId,
            UUID datasetId
    ) {
        return listGenerationMethods(sourceId, sourceDocumentId, datasetId, null);
    }

    public List<SyntheticGenerationDtos.SyntheticGenerationMethod> listGenerationMethods(
            String sourceId,
            String sourceDocumentId,
            UUID datasetId,
            UUID domainId
    ) {
        Set<String> allowedMethodCodes = scopeService.resolveAllowedMethodCodesForMethodList(sourceId, sourceDocumentId, datasetId, domainId);
        return syntheticMethodRepository.findGenerationMethods().stream()
                .filter(method -> allowedMethodCodes.contains(normalizeMethodCode(method.methodCode())))
                .toList();
    }

    public List<SyntheticGenerationDtos.SyntheticGenerationBatchRow> listGenerationBatches(Integer limit) {
        return listGenerationBatches(limit, null);
    }

    public List<SyntheticGenerationDtos.SyntheticGenerationBatchRow> listGenerationBatches(Integer limit, UUID domainId) {
        return generationRepository.findGenerationBatches(limit, domainId);
    }

    @Transactional
    public void deleteSyntheticGenerationBatch(UUID batchId) {
        SyntheticGenerationDtos.SyntheticGenerationBatchRow batch = generationRepository.findGenerationBatch(batchId)
                .orElseThrow(() -> new IllegalArgumentException("generation batch not found: " + batchId));
        String status = batch.status() == null ? "" : batch.status().trim().toLowerCase(Locale.ROOT);
        if (Set.of("planned", "queued", "running").contains(status)) {
            throw new IllegalArgumentException("running generation batch cannot be deleted. cancel the job first.");
        }
        generationRepository.deleteLlmJobsByGenerationBatch(batchId);
        generationRepository.deleteSyntheticQueriesByGenerationBatch(batchId);
        int removed = generationRepository.deleteGenerationBatch(batchId);
        if (removed <= 0) {
            throw new IllegalArgumentException("generation batch not found: " + batchId);
        }
    }

    public List<SyntheticGenerationDtos.SyntheticQueryRow> listSyntheticQueries(
            String methodCode,
            UUID batchId,
            String queryType,
            Boolean gated,
            Integer limit,
            Integer offset
    ) {
        return listSyntheticQueries(methodCode, batchId, queryType, gated, limit, offset, null);
    }

    public List<SyntheticGenerationDtos.SyntheticQueryRow> listSyntheticQueries(
            String methodCode,
            UUID batchId,
            String queryType,
            Boolean gated,
            Integer limit,
            Integer offset,
            UUID domainId
    ) {
        return generationRepository.findSyntheticQueries(methodCode, batchId, queryType, gated, limit, offset, domainId);
    }

    public SyntheticGenerationDtos.SyntheticQueryDetailResponse getSyntheticQueryDetail(String queryId) {
        return generationRepository.findSyntheticQueryDetail(queryId)
                .orElseThrow(() -> new IllegalArgumentException("synthetic query not found: " + queryId));
    }

    public SyntheticGenerationDtos.SyntheticStatsResponse getSyntheticStats(String methodCode, UUID batchId) {
        return getSyntheticStats(methodCode, batchId, null);
    }

    public SyntheticGenerationDtos.SyntheticStatsResponse getSyntheticStats(String methodCode, UUID batchId, UUID domainId) {
        return generationRepository.findSyntheticStats(methodCode, batchId, domainId);
    }

    @Transactional
    public SyntheticGenerationDtos.SyntheticGenerationBatchRow runSyntheticGeneration(SyntheticGenerationDtos.SyntheticBatchRunRequest request) {
        String methodCode = normalizeMethodCode(request.methodCode());
        UUID domainId = request.domainId();
        scopeService.validateSyntheticSourceMethodRestriction(domainId, methodCode, request.sourceId(), request.sourceDocumentId());
        scopeService.validateSyntheticDomainScope(domainId, methodCode, request.sourceId(), request.sourceDocumentId());
        SyntheticGenerationDtos.SyntheticGenerationMethod method = syntheticMethodRepository.findGenerationMethodByCode(methodCode)
                .orElseThrow(() -> new IllegalArgumentException("generation method not found: " + methodCode));
        RuntimeCatalog runtimeCatalog = runtimeOptionsService.loadRuntimeCatalog();
        runtimeOptionsService.validateLlmModelSelection(blankToNull(request.llmModel()), runtimeCatalog);

        String experimentName = "admin_gen_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        Map<String, Object> config = experimentConfig.baseExperimentConfig(experimentName, methodCode);
        if (domainId != null) {
            config.put("domain_id", domainId.toString());
        }
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
        if (blankToNull(request.sourceId()) == null && blankToNull(request.sourceDocumentId()) == null) {
            List<String> sourceIds = scopeService.allowedSourceIdsForRun(methodCode, domainId);
            if (!sourceIds.isEmpty()) {
                config.put("source_ids", sourceIds);
            }
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
        experimentConfig.applyLlmModelOverrides(config, request.llmModel());
        if (request.llmRpm() != null) {
            config.put("llm_rpm", clampRange(request.llmRpm(), 1, 1000, "llm_rpm"));
        }
        applySyntheticExecutionMode(config, methodCode, request.llmExecutionMode(), request.geminiBatchInputMode());
        applySyntheticGenerationStrategyDefaults(config, methodCode);

        UUID batchId = generationRepository.createGenerationBatch(
                method.generationMethodId(),
                domainId,
                normalizeVersionName(request.versionName()),
                blankToNull(request.sourceDocumentVersion()),
                defaultCreatedBy(request.createdBy()),
                objectMapper.valueToTree(config)
        );
        config.put("generation_batch_id", batchId.toString());
        experimentConfig.writeExperimentConfig(experimentName, config);
        llmJobService.createGenerationJob(batchId, experimentName, defaultCreatedBy(request.createdBy()));
        return generationRepository.findGenerationBatch(batchId)
                .orElseThrow(() -> new IllegalStateException("generation batch not found after completion: " + batchId));
    }

    private String normalizeVersionName(String value) {
        if (value == null || value.isBlank()) {
            return "v" + Instant.now().toEpochMilli();
        }
        return value.trim();
    }

    private void applySyntheticExecutionMode(
            Map<String, Object> config,
            String methodCode,
            String requestedExecutionMode,
            String requestedGeminiBatchInputMode
    ) {
        String executionMode = normalizeExecutionMode(requestedExecutionMode);
        String batchInputMode = normalizeGeminiBatchInputMode(requestedGeminiBatchInputMode);
        if (executionMode == null) {
            if (batchInputMode != null) {
                throw new IllegalArgumentException("gemini_batch_input_mode requires llm_execution_mode=gemini_batch");
            }
            return;
        }
        if (LLM_EXECUTION_MODE_ONLINE.equals(executionMode)) {
            if (batchInputMode != null) {
                throw new IllegalArgumentException("gemini_batch_input_mode is only valid with llm_execution_mode=gemini_batch");
            }
            config.put("llm_execution_mode", LLM_EXECUTION_MODE_ONLINE);
            return;
        }
        if (!"B".equalsIgnoreCase(methodCode)) {
            throw new IllegalArgumentException("llm_execution_mode=gemini_batch is currently supported only for Strategy B");
        }
        config.put("llm_execution_mode", LLM_EXECUTION_MODE_GEMINI_BATCH);
        config.put("gemini_batch_input_mode", batchInputMode == null ? GEMINI_BATCH_INPUT_MODE_INLINE : batchInputMode);
    }

    private String normalizeExecutionMode(String value) {
        String normalized = blankToNull(value);
        if (normalized == null) {
            return null;
        }
        normalized = normalized.toLowerCase(Locale.ROOT).replace("-", "_");
        if (!List.of(LLM_EXECUTION_MODE_ONLINE, LLM_EXECUTION_MODE_GEMINI_BATCH).contains(normalized)) {
            throw new IllegalArgumentException("llm_execution_mode must be one of: online, gemini_batch");
        }
        return normalized;
    }

    private String normalizeGeminiBatchInputMode(String value) {
        String normalized = blankToNull(value);
        if (normalized == null) {
            return null;
        }
        normalized = normalized.toLowerCase(Locale.ROOT).replace("-", "_");
        if (!List.of(GEMINI_BATCH_INPUT_MODE_INLINE, GEMINI_BATCH_INPUT_MODE_JSONL).contains(normalized)) {
            throw new IllegalArgumentException("gemini_batch_input_mode must be one of: inline, jsonl");
        }
        return normalized;
    }

    private void applySyntheticGenerationStrategyDefaults(Map<String, Object> config, String methodCode) {
        if ("A".equalsIgnoreCase(methodCode) || "C".equalsIgnoreCase(methodCode)) {
            config.putIfAbsent("llm_summary_max_output_tokens", STRATEGY_AC_SUMMARY_MAX_OUTPUT_TOKENS);
            config.putIfAbsent("llm_query_max_output_tokens", STRATEGY_AC_QUERY_MAX_OUTPUT_TOKENS);
        }
        if ("A".equalsIgnoreCase(methodCode)) {
            config.putIfAbsent("llm_translation_max_output_tokens", STRATEGY_A_TRANSLATION_MAX_OUTPUT_TOKENS);
        }
        if (!"B".equalsIgnoreCase(methodCode)) {
            return;
        }
        config.putIfAbsent("llm_translation_max_output_tokens", STRATEGY_B_TRANSLATION_MAX_OUTPUT_TOKENS);
        config.putIfAbsent("b_summary_max_chars", STRATEGY_B_SUMMARY_MAX_CHARS);
        config.putIfAbsent("b_query_original_chunk_max_chars", STRATEGY_B_QUERY_ORIGINAL_CHUNK_MAX_CHARS);
        config.putIfAbsent("b_query_translated_chunk_max_chars", STRATEGY_B_QUERY_TRANSLATED_CHUNK_MAX_CHARS);
        config.putIfAbsent("b_query_summary_max_chars", STRATEGY_B_QUERY_SUMMARY_MAX_CHARS);
    }
}
