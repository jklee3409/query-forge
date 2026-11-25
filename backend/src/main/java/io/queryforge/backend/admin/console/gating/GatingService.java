package io.queryforge.backend.admin.console.gating;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.queryforge.backend.admin.console.configuration.ConsoleExperimentConfig;
import io.queryforge.backend.admin.console.jobs.LlmJobService;
import io.queryforge.backend.admin.console.runtime.AdminRetrieverConfiguration;
import io.queryforge.backend.admin.console.runtime.AdminRuntimeOptionsService;
import io.queryforge.backend.admin.console.runtime.AdminRuntimeOptionsService.RuntimeCatalog;
import io.queryforge.backend.admin.console.scope.ConsoleScopeService;
import io.queryforge.backend.admin.console.synthetic.AdminSyntheticMethodRepository;
import io.queryforge.backend.admin.console.synthetic.SyntheticGenerationDtos;
import io.queryforge.backend.admin.console.synthetic.SyntheticGenerationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.queryforge.backend.admin.console.configuration.ConsoleParameters.blankToNull;
import static io.queryforge.backend.admin.console.configuration.ConsoleParameters.clampRange;
import static io.queryforge.backend.admin.console.configuration.ConsoleParameters.defaultCreatedBy;
import static io.queryforge.backend.admin.console.configuration.ConsoleParameters.isEnglishOnlyMethod;
import static io.queryforge.backend.admin.console.configuration.ConsoleParameters.normalizeGatingPreset;
import static io.queryforge.backend.admin.console.configuration.ConsoleParameters.normalizeMethodCode;
import static io.queryforge.backend.admin.console.configuration.ConsoleParameters.normalizeMethodCodes;
import static io.queryforge.backend.admin.console.configuration.ConsoleParameters.normalizeOptionalMethodCode;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class GatingService {

    private final ConsoleExperimentConfig experimentConfig;
    private final AdminRuntimeOptionsService runtimeOptionsService;
    private final AdminRetrieverConfiguration retrieverConfiguration;
    private final ConsoleScopeService scopeService;
    private static final String GATING_PASS_STAGE_FAILED_RULE = "failed_rule";
    private static final String GATING_PASS_STAGE_REJECTED = "rejected";
    private static final String GATING_PASS_STAGE_PASSED_RULE = "passed_rule";
    private static final String GATING_PASS_STAGE_PASSED_LLM = "passed_llm";
    private static final String GATING_PASS_STAGE_PASSED_UTILITY = "passed_utility";
    private static final String GATING_PASS_STAGE_PASSED_DIVERSITY = "passed_diversity";
    private static final String GATING_PASS_STAGE_PASSED_ALL = "passed_all";
    private static final String RETRIEVER_MODE_BM25_ONLY = "bm25_only";
    private final GatingRepository gatingRepository;
    private final AdminSyntheticMethodRepository syntheticMethodRepository;
    private final SyntheticGenerationRepository generationRepository;
    private final LlmJobService llmJobService;
    private final ObjectMapper objectMapper;

    public List<GatingDtos.GatingBatchRow> listGatingBatches(Integer limit) {
        return listGatingBatches(limit, null);
    }

    public List<GatingDtos.GatingBatchRow> listGatingBatches(Integer limit, UUID domainId) {
        return gatingRepository.findGatingBatches(limit, domainId);
    }

    public GatingDtos.GatingFunnelResponse getGatingFunnel(UUID gatingBatchId, String methodCode) {
        return gatingRepository.findGatingFunnel(gatingBatchId, normalizeOptionalMethodCode(methodCode));
    }

    public List<GatingDtos.GatingResultRow> listGatingResults(
            UUID gatingBatchId,
            String methodCode,
            String passStage,
            String queryType,
            Integer limit,
            Integer offset
    ) {
        return gatingRepository.findGatingResults(
                gatingBatchId,
                normalizeOptionalMethodCode(methodCode),
                normalizeGatingPassStage(passStage),
                queryType,
                limit,
                offset
        );
    }

    @Transactional
    public GatingDtos.GatingBatchRow runGating(GatingDtos.GatingBatchRunRequest request) {
        RuntimeCatalog runtimeCatalog = runtimeOptionsService.loadRuntimeCatalog();
        UUID domainId = request.domainId();
        List<UUID> selectedBatchIds = normalizeGenerationBatchIds(request.generationBatchIds(), request.generationBatchId());
        if (selectedBatchIds.isEmpty()) {
            throw new IllegalArgumentException("generation_batch_id is required");
        }
        List<SyntheticGenerationDtos.SyntheticGenerationBatchRow> selectedBatches = new ArrayList<>();
        LinkedHashSet<String> derivedMethodCodes = new LinkedHashSet<>();
        LinkedHashSet<UUID> sourceGenerationRunIds = new LinkedHashSet<>();
        Map<String, SyntheticGenerationDtos.SyntheticGenerationMethod> methodByCode = new LinkedHashMap<>();
        for (UUID generationBatchId : selectedBatchIds) {
            SyntheticGenerationDtos.SyntheticGenerationBatchRow batch = generationRepository.findGenerationBatch(generationBatchId)
                    .orElseThrow(() -> new IllegalArgumentException("generation batch not found: " + generationBatchId));
            if (!"completed".equalsIgnoreCase(batch.status())) {
                throw new IllegalArgumentException("generation batch is not completed: " + generationBatchId);
            }
            if (batch.sourceGenerationRunId() == null) {
                throw new IllegalArgumentException("generation batch has no source_generation_run_id: " + generationBatchId);
            }
            scopeService.validateGenerationBatchDomain(domainId, generationBatchId);
            String batchMethodCode = normalizeMethodCode(batch.methodCode());
            SyntheticGenerationDtos.SyntheticGenerationMethod method = methodByCode.computeIfAbsent(
                    batchMethodCode,
                    key -> syntheticMethodRepository.findGenerationMethodByCode(key)
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
        SyntheticGenerationDtos.SyntheticGenerationMethod primaryMethod = methodByCode.get(primaryMethodCode);
        if (primaryMethod == null) {
            String resolvedPrimaryMethodCode = primaryMethodCode;
            primaryMethod = syntheticMethodRepository.findGenerationMethodByCode(resolvedPrimaryMethodCode)
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
        GatingDtos.GatingRunConfig requestConfig = request.config();
        GatingDtos.GatingStageFlags stageFlags = requestConfig == null ? null : requestConfig.stageFlags();
        GatingDtos.GatingThresholdConfig thresholdConfig = requestConfig == null ? null : requestConfig.thresholds();
        Map<String, Object> retrieverConfig = retrieverConfiguration.resolveRetrieverConfig(
                requestConfig == null ? null : requestConfig.retrieverConfig()
        );
        if (singleEnglishMethod && requestConfig != null && requestConfig.retrieverConfig() == null) {
            retrieverConfig = forceBm25RetrieverConfig(retrieverConfig);
        }
        runtimeOptionsService.validateLlmModelSelection(blankToNull(request.llmModel()), runtimeCatalog);
        retrieverConfiguration.validateRetrieverSelection(retrieverConfig, runtimeCatalog);
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
        if (domainId != null) {
            stageConfig.put("domain_id", domainId.toString());
        }
        if (sourceGenerationRunIdStrings.size() == 1) {
            stageConfig.put("source_generation_run_id", sourceGenerationRunIdStrings.getFirst());
        }

        for (SyntheticGenerationDtos.SyntheticGenerationBatchRow batch : selectedBatches) {
            String batchMethodCode = normalizeMethodCode(batch.methodCode());
            SyntheticGenerationDtos.SyntheticGenerationMethod batchMethod = methodByCode.get(batchMethodCode);
            if (batchMethod == null) {
                throw new IllegalStateException("generation method not loaded: " + batchMethodCode);
            }
            gatingRepository.clearCompletedGatingResults(batchMethod.generationMethodId(), batch.batchId());
        }

        UUID gatingBatchId = gatingRepository.createGatingBatch(
                gatingPreset,
                primaryMethod.generationMethodId(),
                primaryGenerationBatchId,
                primarySourceGenerationRunId,
                domainId,
                defaultCreatedBy(request.createdBy()),
                objectMapper.valueToTree(stageConfig)
        );

        String experimentName = "admin_gate_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        Map<String, Object> config = experimentConfig.baseExperimentConfig(experimentName, primaryMethodCode);
        if (domainId != null) {
            config.put("domain_id", domainId.toString());
        }
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
        retrieverConfiguration.attachRetrieverConfig(config, retrieverConfig);
        experimentConfig.applyLlmModelOverrides(config, request.llmModel());
        config.put("gating_batch_id", gatingBatchId.toString());
        experimentConfig.writeExperimentConfig(experimentName, config);
        llmJobService.createGatingJob(gatingBatchId, experimentName, defaultCreatedBy(request.createdBy()));
        return gatingRepository.findGatingBatch(gatingBatchId)
                .orElseThrow(() -> new IllegalStateException("gating batch not found after completion: " + gatingBatchId));
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

    private Map<String, Object> resolveRuleConfig(String methodCode, GatingDtos.GatingRuleConfig request) {
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

    private Map<String, Double> resolveGatingWeights(GatingDtos.GatingWeightsConfig request) {
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

    private Map<String, Double> resolveUtilityScoreWeights(GatingDtos.GatingUtilityScoreConfig request) {
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
}
