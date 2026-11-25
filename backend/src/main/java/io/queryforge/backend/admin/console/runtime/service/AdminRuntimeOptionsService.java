package io.queryforge.backend.admin.console.runtime.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.queryforge.backend.admin.console.runtime.dto.AdminRuntimeOptionsDtos.RuntimeCatalog;
import io.queryforge.backend.admin.console.runtime.dto.AdminRuntimeOptionsDtos.RuntimeOptionMetadata;
import io.queryforge.backend.admin.console.runtime.dto.AdminRuntimeOptionsDtos;
import io.queryforge.backend.rag.runtime.configuration.model.RagExecutionSettings;
import io.queryforge.backend.rag.runtime.configuration.model.RagExecutionSettings.Parameter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import static io.queryforge.backend.admin.console.configuration.support.ConsoleParameters.asTrimmedString;
import static io.queryforge.backend.admin.console.configuration.support.ConsoleParameters.blankToNull;
import static io.queryforge.backend.admin.console.configuration.support.ConsoleParameters.clampRange;
import static io.queryforge.backend.admin.console.configuration.support.ConsoleParameters.firstNonBlank;
import static io.queryforge.backend.rag.runtime.configuration.model.RagExecutionSettings.MEMORY_TOP_N;
import static io.queryforge.backend.rag.runtime.configuration.model.RagExecutionSettings.RERANK_TOP_N;
import static io.queryforge.backend.rag.runtime.configuration.model.RagExecutionSettings.RETRIEVAL_TOP_K;
import static io.queryforge.backend.rag.runtime.configuration.model.RagExecutionSettings.REWRITE_CANDIDATE_COUNT;
import static io.queryforge.backend.rag.runtime.configuration.model.RagExecutionSettings.REWRITE_THRESHOLD;

@Service
@RequiredArgsConstructor
public class AdminRuntimeOptionsService {

    private final ConsoleEnvironment environment;
    private static final String REWRITE_FAILURE_POLICY_FAIL_RUN = "fail_run";
    private static final String REWRITE_FAILURE_POLICY_SKIP_TO_RAW = "skip_to_raw";
    private static final String REWRITE_FAILURE_POLICY_HEURISTIC_FALLBACK = "heuristic_fallback";
    private static final String REWRITE_QUERY_PROFILE_COMPACT_ANCHOR = "compact_anchor";
    private static final String REWRITE_QUERY_PROFILE_DETAILED_INTENT = "detailed_intent";
    private static final String RETRIEVAL_BACKEND_LOCAL = "local";
    private static final String RETRIEVAL_BACKEND_DB_ANN = "db_ann";
    private static final String RETRIEVER_MODE_BM25_ONLY = "bm25_only";
    private static final String RETRIEVER_MODE_DENSE_ONLY = "dense_only";
    private static final String RETRIEVER_MODE_HYBRID = "hybrid";
    private static final String DEFAULT_LLM_MODEL = "gemini-2.5-flash-lite";
    private static final String DEFAULT_LLM_FALLBACK_MODEL = "gemini-2.5-flash-lite";
    private static final String DEFAULT_DENSE_EMBEDDING_MODEL = "intfloat/multilingual-e5-small";
    private static final String MODEL_CATALOG_RELATIVE_PATH = "configs/app/model_catalog.yml";
    private static final int DEFAULT_REWRITE_MEMORY_CANDIDATE_POOL_N = 20;
    private static final int DEFAULT_RETRIEVER_CANDIDATE_POOL_K = 50;
    private static final double DEFAULT_RAG_HYBRID_DENSE_WEIGHT = 0.60d;
    private static final double DEFAULT_RAG_HYBRID_BM25_WEIGHT = 0.32d;
    private static final double DEFAULT_RAG_HYBRID_TECHNICAL_WEIGHT = 0.08d;
    private final ObjectMapper objectMapper;

    private final Yaml yaml = new Yaml();

    public AdminRuntimeOptionsDtos.RuntimeOptionsResponse getRuntimeOptions() {
        RuntimeCatalog catalog = loadRuntimeCatalog();
        List<String> llmModels = fallbackIfEmpty(
                catalog.availableLlmModels(),
                collectRuntimeOptions(
                        nullableStringCandidates(
                                environment.readEnv("QUERY_FORGE_ADMIN_LLM_MODELS"),
                                environment.readEnv("QUERY_FORGE_LLM_MODEL"),
                                environment.readEnv("QUERY_FORGE_LLM_SUMMARY_MODEL"),
                                environment.readEnv("QUERY_FORGE_LLM_QUERY_MODEL"),
                                environment.readEnv("QUERY_FORGE_LLM_SELF_EVAL_MODEL"),
                                environment.readEnv("QUERY_FORGE_LLM_REWRITE_MODEL"),
                                environment.readEnv("QUERY_FORGE_LLM_FALLBACK_MODELS")
                        ),
                        List.of(DEFAULT_LLM_MODEL, DEFAULT_LLM_FALLBACK_MODEL)
                )
        );
        String envLlmModel = environment.readEnv("QUERY_FORGE_LLM_MODEL");
        String defaultLlmModel = firstNonBlank(
                llmModels.contains(envLlmModel) ? envLlmModel : null,
                llmModels.contains(catalog.defaultLlmModel()) ? catalog.defaultLlmModel() : null,
                llmModels.isEmpty() ? null : llmModels.getFirst()
        );

        List<String> denseEmbeddingModels = fallbackIfEmpty(
                catalog.availableDenseEmbeddingModels(),
                collectRuntimeOptions(
                        nullableStringCandidates(
                                environment.readEnv("QUERY_FORGE_ADMIN_DENSE_EMBEDDING_MODELS"),
                                environment.readEnv("QUERY_FORGE_LOCAL_EMBEDDING_MODEL"),
                                environment.readEnv("QUERY_FORGE_ANCHOR_EMBEDDING_MODEL")
                        ),
                        List.of(DEFAULT_DENSE_EMBEDDING_MODEL)
                )
        );
        String envDenseModel = environment.readEnv("QUERY_FORGE_LOCAL_EMBEDDING_MODEL");
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
        String defaultRetrieverMode = firstNonBlank(
                retrieverModes.contains(catalog.defaultRetrieverMode()) ? catalog.defaultRetrieverMode() : null,
                retrieverModes.contains(RETRIEVER_MODE_HYBRID) ? RETRIEVER_MODE_HYBRID : null,
                retrieverModes.isEmpty() ? null : retrieverModes.getFirst()
        );
        List<String> rewriteFailurePolicies = fallbackIfEmpty(
                catalog.availableRewriteFailurePolicies(),
                List.of(
                        REWRITE_FAILURE_POLICY_FAIL_RUN,
                        REWRITE_FAILURE_POLICY_SKIP_TO_RAW,
                        REWRITE_FAILURE_POLICY_HEURISTIC_FALLBACK
                )
        );
        List<String> rewriteQueryProfiles = fallbackIfEmpty(
                catalog.availableRewriteQueryProfiles(),
                List.of(REWRITE_QUERY_PROFILE_COMPACT_ANCHOR, REWRITE_QUERY_PROFILE_DETAILED_INTENT)
        );
        String defaultRewriteQueryProfile = firstNonBlank(
                rewriteQueryProfiles.contains(catalog.defaultRewriteQueryProfile()) ? catalog.defaultRewriteQueryProfile() : null,
                rewriteQueryProfiles.contains(REWRITE_QUERY_PROFILE_COMPACT_ANCHOR) ? REWRITE_QUERY_PROFILE_COMPACT_ANCHOR : null,
                rewriteQueryProfiles.isEmpty() ? null : rewriteQueryProfiles.getFirst()
        );
        return new AdminRuntimeOptionsDtos.RuntimeOptionsResponse(
                llmModels,
                defaultLlmModel,
                denseEmbeddingModels,
                defaultDenseEmbeddingModel,
                retrievalBackends,
                defaultRetrievalBackend,
                retrieverModes,
                rewriteFailurePolicies,
                rewriteQueryProfiles,
                defaultRewriteQueryProfile,
                catalog.llmProviderOptions().stream().map(this::toRuntimeOptionDto).toList(),
                catalog.llmModelOptions().stream().map(this::toRuntimeOptionDto).toList(),
                catalog.denseEmbeddingModelOptions().stream().map(this::toRuntimeOptionDto).toList(),
                catalog.retrievalBackendOptions().stream().map(this::toRuntimeOptionDto).toList(),
                catalog.retrieverModeOptions().stream().map(this::toRuntimeOptionDto).toList(),
                catalog.rewriteFailurePolicyOptions().stream().map(this::toRuntimeOptionDto).toList(),
                catalog.rewriteQueryProfileOptions().stream().map(this::toRuntimeOptionDto).toList(),
                catalog.defaultParameterRanges(),
                defaultRetrieverMode,
                objectMapper.valueToTree(catalog.retrieverModeDefaults())
        );
    }

    @SuppressWarnings("unchecked")
    public RuntimeCatalog loadRuntimeCatalog() {
        Path catalogPath = environment.resolveProjectFile(MODEL_CATALOG_RELATIVE_PATH);
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
            List<RuntimeOptionMetadata> rewriteQueryProfiles = parseRuntimeOptions(rootMap.get("rewrite_query_profiles"), true);
            Map<String, AdminRuntimeOptionsDtos.RuntimeParameterRange> parameterRanges = parseDefaultParameterRanges(
                    rootMap.get("default_parameter_ranges")
            );
            Map<String, Map<String, Object>> retrieverModeDefaults = parseRetrieverModeDefaults(
                    rootMap.get("retriever_mode_defaults")
            );
            return new RuntimeCatalog(
                    llmProviders,
                    llmModels,
                    denseModels,
                    retrievalBackends,
                    retrieverModes,
                    rewritePolicies,
                    rewriteQueryProfiles,
                    parameterRanges,
                    retrieverModeDefaults
            );
        } catch (IOException | RuntimeException exception) {
            throw new IllegalStateException("failed to load model catalog: " + catalogPath, exception);
        }
    }

    private RuntimeCatalog buildFallbackRuntimeCatalog() {
        List<RuntimeOptionMetadata> llmModels = collectRuntimeOptions(
                nullableStringCandidates(
                        environment.readEnv("QUERY_FORGE_ADMIN_LLM_MODELS"),
                        environment.readEnv("QUERY_FORGE_LLM_MODEL"),
                        environment.readEnv("QUERY_FORGE_LLM_SUMMARY_MODEL"),
                        environment.readEnv("QUERY_FORGE_LLM_QUERY_MODEL"),
                        environment.readEnv("QUERY_FORGE_LLM_SELF_EVAL_MODEL"),
                        environment.readEnv("QUERY_FORGE_LLM_REWRITE_MODEL"),
                        environment.readEnv("QUERY_FORGE_LLM_FALLBACK_MODELS")
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
                        environment.readEnv("QUERY_FORGE_ADMIN_DENSE_EMBEDDING_MODELS"),
                        environment.readEnv("QUERY_FORGE_LOCAL_EMBEDDING_MODEL"),
                        environment.readEnv("QUERY_FORGE_ANCHOR_EMBEDDING_MODEL")
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
        List<RuntimeOptionMetadata> rewriteQueryProfiles = List.of(
                new RuntimeOptionMetadata(REWRITE_QUERY_PROFILE_COMPACT_ANCHOR, "Compact anchor", null, "active", "available", null, true),
                new RuntimeOptionMetadata(REWRITE_QUERY_PROFILE_DETAILED_INTENT, "Detailed intent", null, "active", "available", null, false)
        );
        Map<String, AdminRuntimeOptionsDtos.RuntimeParameterRange> ranges = defaultRuntimeParameterRanges();
        return new RuntimeCatalog(
                List.of(),
                llmModels,
                denseModels,
                retrievalBackends,
                retrieverModes,
                rewritePolicies,
                rewriteQueryProfiles,
                ranges,
                fallbackRetrieverModeDefaults()
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
    private Map<String, AdminRuntimeOptionsDtos.RuntimeParameterRange> parseDefaultParameterRanges(Object raw) {
        if (!(raw instanceof Map<?, ?> map)) {
            return defaultRuntimeParameterRanges();
        }
        LinkedHashMap<String, AdminRuntimeOptionsDtos.RuntimeParameterRange> ranges = new LinkedHashMap<>(defaultRuntimeParameterRanges());
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
            ranges.put(key, new AdminRuntimeOptionsDtos.RuntimeParameterRange(min, max, defaultValue));
        }
        for (Parameter parameter : RagExecutionSettings.PARAMETERS) {
            var range = ranges.get(parameter.key());
            double min = range.min() == null ? parameter.min() : range.min();
            double max = range.max() == null ? parameter.max() : range.max();
            double defaultValue = range.defaultValue() == null ? parameter.defaultValue() : range.defaultValue();
            parameter.validate(min);
            parameter.validate(max);
            parameter.validate(defaultValue, min, max);
            if (parameter != REWRITE_THRESHOLD
                    && (min != Math.rint(min) || max != Math.rint(max) || defaultValue != Math.rint(defaultValue))) {
                throw new IllegalArgumentException(parameter.key() + " requires integer bounds and default");
            }
            ranges.put(parameter.key(), new AdminRuntimeOptionsDtos.RuntimeParameterRange(min, max, defaultValue));
        }
        return Map.copyOf(ranges);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Map<String, Object>> parseRetrieverModeDefaults(Object raw) {
        if (!(raw instanceof Map<?, ?> map)) {
            return fallbackRetrieverModeDefaults();
        }
        LinkedHashMap<String, Map<String, Object>> defaults = new LinkedHashMap<>();
        Map<String, Object> source = (Map<String, Object>) map;
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            String mode = normalizeRetrieverMode(String.valueOf(entry.getKey()));
            if (!(entry.getValue() instanceof Map<?, ?> rawValue)) {
                continue;
            }
            defaults.put(mode, sanitizeRetrieverModeDefault((Map<String, Object>) rawValue));
        }
        if (defaults.isEmpty()) {
            return fallbackRetrieverModeDefaults();
        }
        return Map.copyOf(defaults);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> sanitizeRetrieverModeDefault(Map<String, Object> raw) {
        LinkedHashMap<String, Object> sanitized = new LinkedHashMap<>();
        sanitized.put(
                "dense_embedding_required",
                parseBooleanOrDefault(raw.get("dense_embedding_required"), true)
        );
        sanitized.put(
                "dense_fallback_enabled",
                parseBooleanOrDefault(raw.get("dense_fallback_enabled"), false)
        );
        sanitized.put(
                "rerank_enabled",
                parseBooleanOrDefault(raw.get("rerank_enabled"), false)
        );
        int candidatePoolK = intDefault(
                raw.get("retriever_candidate_pool_k"),
                DEFAULT_RETRIEVER_CANDIDATE_POOL_K
        );
        sanitized.put("retriever_candidate_pool_k", clampRange(candidatePoolK, 1, 500, "retriever_candidate_pool_k"));
        Object weightsNode = raw.get("retriever_fusion_weights");
        Map<String, Object> rawWeights = weightsNode instanceof Map<?, ?> weights
                ? (Map<String, Object>) weights
                : Map.of();
        LinkedHashMap<String, Double> weights = new LinkedHashMap<>();
        weights.put("dense", doubleDefault(rawWeights.get("dense"), DEFAULT_RAG_HYBRID_DENSE_WEIGHT));
        weights.put("bm25", doubleDefault(rawWeights.get("bm25"), DEFAULT_RAG_HYBRID_BM25_WEIGHT));
        weights.put("technical", doubleDefault(rawWeights.get("technical"), DEFAULT_RAG_HYBRID_TECHNICAL_WEIGHT));
        sanitized.put("retriever_fusion_weights", weights);
        return Map.copyOf(sanitized);
    }

    private Map<String, Map<String, Object>> fallbackRetrieverModeDefaults() {
        LinkedHashMap<String, Map<String, Object>> defaults = new LinkedHashMap<>();
        defaults.put(
                RETRIEVER_MODE_BM25_ONLY,
                Map.of(
                        "dense_embedding_required", false,
                        "dense_fallback_enabled", false,
                        "rerank_enabled", false,
                        "retriever_candidate_pool_k", DEFAULT_RETRIEVER_CANDIDATE_POOL_K,
                        "retriever_fusion_weights", Map.of("dense", 0.0d, "bm25", 1.0d, "technical", 0.0d)
                )
        );
        defaults.put(
                RETRIEVER_MODE_DENSE_ONLY,
                Map.of(
                        "dense_embedding_required", true,
                        "dense_fallback_enabled", false,
                        "rerank_enabled", false,
                        "retriever_candidate_pool_k", DEFAULT_RETRIEVER_CANDIDATE_POOL_K,
                        "retriever_fusion_weights", Map.of("dense", 1.0d, "bm25", 0.0d, "technical", 0.0d)
                )
        );
        defaults.put(
                RETRIEVER_MODE_HYBRID,
                Map.of(
                        "dense_embedding_required", true,
                        "dense_fallback_enabled", false,
                        "rerank_enabled", false,
                        "retriever_candidate_pool_k", DEFAULT_RETRIEVER_CANDIDATE_POOL_K,
                        "retriever_fusion_weights",
                        Map.of(
                                "dense", DEFAULT_RAG_HYBRID_DENSE_WEIGHT,
                                "bm25", DEFAULT_RAG_HYBRID_BM25_WEIGHT,
                                "technical", DEFAULT_RAG_HYBRID_TECHNICAL_WEIGHT
                        )
                )
        );
        return Map.copyOf(defaults);
    }

    private Map<String, AdminRuntimeOptionsDtos.RuntimeParameterRange> defaultRuntimeParameterRanges() {
        LinkedHashMap<String, AdminRuntimeOptionsDtos.RuntimeParameterRange> ranges = new LinkedHashMap<>();
        for (Parameter parameter : RagExecutionSettings.PARAMETERS) {
            ranges.put(parameter.key(), new AdminRuntimeOptionsDtos.RuntimeParameterRange(
                    parameter.min(), parameter.max(), parameter.defaultValue()
            ));
        }
        ranges.put("agentic_max_subqueries", new AdminRuntimeOptionsDtos.RuntimeParameterRange(1.0d, 4.0d, 4.0d));
        ranges.put("agentic_rrf_k", new AdminRuntimeOptionsDtos.RuntimeParameterRange(1.0d, 500.0d, 60.0d));
        ranges.put("rewrite_memory_candidate_pool_n", new AdminRuntimeOptionsDtos.RuntimeParameterRange(1.0d, 100.0d, (double) DEFAULT_REWRITE_MEMORY_CANDIDATE_POOL_N));
        ranges.put("retriever_candidate_pool_k", new AdminRuntimeOptionsDtos.RuntimeParameterRange(1.0d, 500.0d, (double) DEFAULT_RETRIEVER_CANDIDATE_POOL_K));
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

    boolean parseBooleanOrDefault(Object value, boolean fallback) {
        if (value == null) {
            return fallback;
        }
        if (value instanceof Boolean bool) {
            return bool;
        }
        String normalized = String.valueOf(value).trim();
        if (normalized.isBlank()) {
            return fallback;
        }
        return "true".equalsIgnoreCase(normalized);
    }

    int intDefault(Object value, int fallback) {
        Double parsed = parseNumber(value);
        return parsed == null ? fallback : parsed.intValue();
    }

    double doubleDefault(Object value, double fallback) {
        Double parsed = parseNumber(value);
        return parsed == null ? fallback : parsed;
    }

    public double runtimeDefaultDouble(RuntimeCatalog catalog, String key, double fallback) {
        if (catalog == null || key == null || key.isBlank()) {
            return fallback;
        }
        AdminRuntimeOptionsDtos.RuntimeParameterRange range = catalog.defaultParameterRanges().get(key);
        return range == null || range.defaultValue() == null ? fallback : range.defaultValue();
    }

    public int runtimeDefaultInt(RuntimeCatalog catalog, String key, int fallback) {
        return (int) Math.round(runtimeDefaultDouble(catalog, key, fallback));
    }

    public RagExecutionSettings defaultRagExecutionSettings(RuntimeCatalog catalog) {
        return new RagExecutionSettings(
                (int) executionDefault(catalog, RETRIEVAL_TOP_K),
                (int) executionDefault(catalog, RERANK_TOP_N),
                (int) executionDefault(catalog, MEMORY_TOP_N),
                (int) executionDefault(catalog, REWRITE_CANDIDATE_COUNT),
                executionDefault(catalog, REWRITE_THRESHOLD)
        );
    }

    public RagExecutionSettings resolveRagExecutionSettings(
            Map<String, AdminRuntimeOptionsDtos.RuntimeParameterRange> ranges,
            RagExecutionSettings base,
            RagExecutionSettings.Overrides overrides
    ) {
        validateExecutionOverride(ranges, RETRIEVAL_TOP_K, overrides.retrievalTopK());
        validateExecutionOverride(ranges, RERANK_TOP_N, overrides.rerankTopN());
        validateExecutionOverride(ranges, MEMORY_TOP_N, overrides.memoryTopN());
        validateExecutionOverride(ranges, REWRITE_CANDIDATE_COUNT, overrides.rewriteCandidateCount());
        validateExecutionOverride(ranges, REWRITE_THRESHOLD, overrides.rewriteThreshold());
        return base.withOverrides(overrides);
    }

    private double executionDefault(RuntimeCatalog catalog, Parameter parameter) {
        return runtimeDefaultDouble(catalog, parameter.key(), parameter.defaultValue());
    }

    private void validateExecutionOverride(
            Map<String, AdminRuntimeOptionsDtos.RuntimeParameterRange> ranges,
            Parameter parameter,
            Number value
    ) {
        var range = ranges.get(parameter.key());
        if (value != null && range != null) {
            parameter.validate(value.doubleValue(), range.min(), range.max());
        }
    }

    private AdminRuntimeOptionsDtos.RuntimeOption toRuntimeOptionDto(RuntimeOptionMetadata option) {
        return new AdminRuntimeOptionsDtos.RuntimeOption(
                option.code(),
                option.label(),
                option.provider(),
                option.status(),
                option.availability(),
                option.reason(),
                option.defaultSelected()
        );
    }

    public void validateLlmModelSelection(String llmModel, RuntimeCatalog catalog) {
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

    public void validateRewriteFailurePolicySelection(String policy, RuntimeCatalog catalog) {
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

    public void validateRewriteQueryProfileSelection(String profile, RuntimeCatalog catalog) {
        RuntimeOptionMetadata option = catalog.findRewriteQueryProfile(profile);
        if (option == null) {
            throw new IllegalArgumentException("rewrite_query_profile is not allowed by catalog: " + profile);
        }
        if (!isSelectable(option)) {
            throw new IllegalArgumentException(
                    "rewrite_query_profile is not selectable by catalog: " + profile + appendReason(option.reason())
            );
        }
    }

    public boolean isSelectable(RuntimeOptionMetadata option) {
        String availability = firstNonBlank(option.availability(), "available").toLowerCase();
        String status = firstNonBlank(option.status(), "active").toLowerCase();
        if (List.of("unavailable", "disabled", "blocked").contains(availability)) {
            return false;
        }
        return !List.of("disabled", "blocked").contains(status);
    }

    public String appendReason(String reason) {
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

    public String normalizeRetrieverMode(String value) {
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
}
