package io.queryforge.backend.admin.console.configuration.service;

import io.queryforge.backend.admin.console.runtime.dto.AdminRuntimeOptionsDtos.RuntimeCatalog;
import io.queryforge.backend.admin.console.runtime.service.AdminRuntimeOptionsService;
import io.queryforge.backend.admin.console.runtime.service.ConsoleEnvironment;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static io.queryforge.backend.admin.console.configuration.support.ConsoleParameters.blankToNull;
import static io.queryforge.backend.admin.console.configuration.support.ConsoleParameters.isEnglishOnlyMethod;

@Service
@RequiredArgsConstructor
public class ConsoleExperimentConfig {

    private final AdminRuntimeOptionsService runtimeOptionsService;
    private final ConsoleEnvironment environment;
    private static final String REWRITE_FAILURE_POLICY_FAIL_RUN = "fail_run";
    private static final String REWRITE_QUERY_PROFILE_COMPACT_ANCHOR = "compact_anchor";
    private static final String REWRITE_QUERY_PROFILE_DETAILED_INTENT = "detailed_intent";
    private static final String QUERY_LANGUAGE_KO = "ko";
    private static final String QUERY_LANGUAGE_EN = "en";
    private static final String DEFAULT_LLM_MODEL = "gemini-2.5-flash-lite";
    private static final String DEFAULT_LLM_FALLBACK_MODEL = "gemini-2.5-flash-lite";
    private static final int DEFAULT_RAG_RETRIEVAL_TOP_K = 10;
    private static final double DEFAULT_REWRITE_THRESHOLD = 0.05d;
    private static final int DEFAULT_REWRITE_MEMORY_CANDIDATE_POOL_N = 20;

    private final Yaml yaml = new Yaml();

    public void applyLlmModelOverrides(Map<String, Object> config, String requestedLlmModel) {
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

    public void applyRewriteLlmModelOverride(Map<String, Object> config, String requestedRewriteLlmModel) {
        String rewriteLlmModel = blankToNull(requestedRewriteLlmModel);
        if (rewriteLlmModel == null) {
            return;
        }
        config.put("llm_rewrite_model", rewriteLlmModel);
    }

    private Map<String, Object> relaxedShortUserRewriteAdoptionPolicy() {
        RuntimeCatalog runtimeCatalog = runtimeOptionsService.loadRuntimeCatalog();
        double rewriteDefault = runtimeOptionsService.runtimeDefaultDouble(runtimeCatalog, "rewrite_threshold", DEFAULT_REWRITE_THRESHOLD);
        return relaxedShortUserRewriteAdoptionPolicy(rewriteDefault);
    }

    private Map<String, Object> relaxedShortUserRewriteAdoptionPolicy(double minImprovement) {
        Map<String, Object> shortUserThresholds = new LinkedHashMap<>();
        shortUserThresholds.put("min_improvement", minImprovement);
        shortUserThresholds.put("preservation_floor", 0.50d);
        shortUserThresholds.put("max_length_ratio", 4.00d);
        shortUserThresholds.put("max_compact_query_chars", 160);
        shortUserThresholds.put("underspecified_memory_norm_cutoff", 0.66d);
        shortUserThresholds.put("raw_loss_guard_confidence_floor", 0.86d);
        shortUserThresholds.put("raw_loss_guard_min_overlap_ratio", 0.50d);
        shortUserThresholds.put("raw_loss_guard_allow_source_memory_improved", true);
        shortUserThresholds.put("raw_loss_guard_require_top1_loss", false);

        Map<String, Object> shortUserPenalties = new LinkedHashMap<>();
        shortUserPenalties.put("verbosity_per_extra_ratio", 0.015d);
        shortUserPenalties.put("memory_target_missing", 0.12d);

        Map<String, Object> shortUserBonuses = new LinkedHashMap<>();
        shortUserBonuses.put("memory_target_presence", 0.14d);
        shortUserBonuses.put("source_memory_target_hit_margin", 0.12d);
        shortUserBonuses.put("source_memory_target_selection", 0.22d);

        Map<String, Object> shortUserPolicy = new LinkedHashMap<>();
        shortUserPolicy.put("thresholds", shortUserThresholds);
        shortUserPolicy.put("penalties", shortUserPenalties);
        shortUserPolicy.put("bonuses", shortUserBonuses);

        Map<String, Object> categoryOverrides = new LinkedHashMap<>();
        categoryOverrides.put("short_user", shortUserPolicy);

        Map<String, Object> policy = new LinkedHashMap<>();
        policy.put("category_overrides", categoryOverrides);
        return policy;
    }

    private Map<String, Object> detailedIntentRewriteAdoptionPolicy() {
        RuntimeCatalog runtimeCatalog = runtimeOptionsService.loadRuntimeCatalog();
        double rewriteDefault = runtimeOptionsService.runtimeDefaultDouble(runtimeCatalog, "rewrite_threshold", DEFAULT_REWRITE_THRESHOLD);
        Map<String, Object> thresholds = new LinkedHashMap<>();
        thresholds.put("min_improvement", rewriteDefault);
        thresholds.put("preservation_floor", 0.62d);
        thresholds.put("max_length_ratio", 6.00d);
        thresholds.put("max_compact_query_chars", 260);
        thresholds.put("underspecified_memory_norm_cutoff", 0.66d);
        thresholds.put("raw_loss_guard_confidence_floor", 0.78d);
        thresholds.put("raw_loss_guard_min_overlap_ratio", 0.20d);

        Map<String, Object> penalties = new LinkedHashMap<>();
        penalties.put("verbosity_per_extra_ratio", 0.008d);
        penalties.put("memory_target_missing", 0.04d);

        Map<String, Object> bonuses = new LinkedHashMap<>();
        bonuses.put("memory_target_presence", 0.08d);
        bonuses.put("source_memory_target_hit_margin", rewriteDefault);

        Map<String, Object> shortUserPolicy = new LinkedHashMap<>();
        shortUserPolicy.put("thresholds", thresholds);
        shortUserPolicy.put("penalties", penalties);
        shortUserPolicy.put("bonuses", bonuses);

        Map<String, Object> categoryOverrides = new LinkedHashMap<>();
        categoryOverrides.put("short_user", shortUserPolicy);

        Map<String, Object> policy = new LinkedHashMap<>();
        policy.put("thresholds", new LinkedHashMap<>(thresholds));
        policy.put("penalties", new LinkedHashMap<>(penalties));
        policy.put("bonuses", new LinkedHashMap<>(bonuses));
        policy.put("category_overrides", categoryOverrides);
        return policy;
    }

    public Map<String, Object> rewriteAdoptionPolicyForProfile(String rewriteQueryProfile, double minImprovement) {
        if (REWRITE_QUERY_PROFILE_DETAILED_INTENT.equals(rewriteQueryProfile)) {
            return detailedIntentRewriteAdoptionPolicy();
        }
        return relaxedShortUserRewriteAdoptionPolicy(minImprovement);
    }

    public Map<String, Object> baseExperimentConfig(String experimentKey, String methodCode) {
        boolean englishOnly = isEnglishOnlyMethod(methodCode);
        RuntimeCatalog runtimeCatalog = runtimeOptionsService.loadRuntimeCatalog();
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
        config.put("rewrite_candidate_count", 2);
        config.put("rewrite_threshold", runtimeOptionsService.runtimeDefaultDouble(runtimeCatalog, "rewrite_threshold", DEFAULT_REWRITE_THRESHOLD));
        config.put(
                "rewrite_memory_candidate_pool_n",
                runtimeOptionsService.runtimeDefaultInt(runtimeCatalog, "rewrite_memory_candidate_pool_n", DEFAULT_REWRITE_MEMORY_CANDIDATE_POOL_N)
        );
        config.put("rewrite_failure_policy", REWRITE_FAILURE_POLICY_FAIL_RUN);
        config.put("rewrite_query_profile", REWRITE_QUERY_PROFILE_COMPACT_ANCHOR);
        config.put("rewrite_adoption_policy", relaxedShortUserRewriteAdoptionPolicy());
        config.put("memory_lookup_intent_preserving_enabled", true);
        config.put("memory_lookup_hint_token_max", 3);
        config.put("memory_lookup_retrieval_strategy", "max_score");
        config.put("rewrite_memory_hint_retrieval_enabled", false);
        config.put("retrieval_top_k", runtimeOptionsService.runtimeDefaultInt(runtimeCatalog, "retrieval_top_k", DEFAULT_RAG_RETRIEVAL_TOP_K));
        config.put("rerank_top_n", runtimeOptionsService.runtimeDefaultInt(runtimeCatalog, "rerank_top_n", 5));
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

    public void writeExperimentConfig(String experimentName, Map<String, Object> config) {
        Path configPath = environment.resolveRepoRoot().resolve("configs/experiments").resolve(experimentName + ".yaml").normalize();
        try {
            Files.createDirectories(configPath.getParent());
            Files.writeString(configPath, yaml.dump(config), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("failed to write experiment config: " + configPath, exception);
        }
    }
}
