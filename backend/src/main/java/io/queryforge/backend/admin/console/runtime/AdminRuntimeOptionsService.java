package io.queryforge.backend.admin.console.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.queryforge.backend.admin.pipeline.AdminPipelineProperties;
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
import java.util.regex.Pattern;

import static io.queryforge.backend.admin.console.configuration.ConsoleParameters.asTrimmedString;
import static io.queryforge.backend.admin.console.configuration.ConsoleParameters.blankToNull;
import static io.queryforge.backend.admin.console.configuration.ConsoleParameters.clampRange;
import static io.queryforge.backend.admin.console.configuration.ConsoleParameters.firstNonBlank;

@Service
@RequiredArgsConstructor
public class AdminRuntimeOptionsService {

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

    private static final int DEFAULT_RAG_RETRIEVAL_TOP_K = 10;

    private static final double DEFAULT_REWRITE_THRESHOLD = 0.05d;

    private static final int DEFAULT_REWRITE_MEMORY_CANDIDATE_POOL_N = 20;

    private static final int DEFAULT_RETRIEVER_CANDIDATE_POOL_K = 50;

    private static final double DEFAULT_RAG_HYBRID_DENSE_WEIGHT = 0.60d;

    private static final double DEFAULT_RAG_HYBRID_BM25_WEIGHT = 0.32d;

    private static final double DEFAULT_RAG_HYBRID_TECHNICAL_WEIGHT = 0.08d;

    private static final Pattern NON_ALNUM_PATTERN = Pattern.compile("[^A-Z0-9]");

    private final AdminPipelineProperties pipelineProperties;

    private final ObjectMapper objectMapper;

    private final Yaml yaml = new Yaml();

    public AdminRuntimeOptionsDtos.RuntimeOptionsResponse getRuntimeOptions() {
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

    public void validateRewriteStageLlmConfig(Map<String, Object> config, boolean rewriteEnabled) {
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
    public RuntimeCatalog loadRuntimeCatalog() {
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
        ranges.put("retrieval_top_k", new AdminRuntimeOptionsDtos.RuntimeParameterRange(1.0d, 100.0d, (double) DEFAULT_RAG_RETRIEVAL_TOP_K));
        ranges.put("rerank_top_n", new AdminRuntimeOptionsDtos.RuntimeParameterRange(1.0d, 100.0d, 5.0d));
        ranges.put("memory_top_n", new AdminRuntimeOptionsDtos.RuntimeParameterRange(1.0d, 50.0d, 5.0d));
        ranges.put("rewrite_candidate_count", new AdminRuntimeOptionsDtos.RuntimeParameterRange(1.0d, 2.0d, 2.0d));
        ranges.put("agentic_max_subqueries", new AdminRuntimeOptionsDtos.RuntimeParameterRange(1.0d, 4.0d, 4.0d));
        ranges.put("agentic_rrf_k", new AdminRuntimeOptionsDtos.RuntimeParameterRange(1.0d, 500.0d, 60.0d));
        ranges.put("rewrite_threshold", new AdminRuntimeOptionsDtos.RuntimeParameterRange(0.0d, 1.0d, DEFAULT_REWRITE_THRESHOLD));
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

    private boolean parseBooleanOrDefault(Object value, boolean fallback) {
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

    private int intDefault(Object value, int fallback) {
        Double parsed = parseNumber(value);
        return parsed == null ? fallback : parsed.intValue();
    }

    private double doubleDefault(Object value, double fallback) {
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

    public void validateRetrieverSelection(Map<String, Object> retrieverConfig, RuntimeCatalog catalog) {
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

    public void validateRetrievalBackendSelection(String retrievalBackend, RuntimeCatalog catalog) {
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

    public record RuntimeCatalog(
            List<RuntimeOptionMetadata> llmProviderOptions,
            List<RuntimeOptionMetadata> llmModelOptions,
            List<RuntimeOptionMetadata> denseEmbeddingModelOptions,
            List<RuntimeOptionMetadata> retrievalBackendOptions,
            List<RuntimeOptionMetadata> retrieverModeOptions,
            List<RuntimeOptionMetadata> rewriteFailurePolicyOptions,
            List<RuntimeOptionMetadata> rewriteQueryProfileOptions,
            Map<String, AdminRuntimeOptionsDtos.RuntimeParameterRange> defaultParameterRanges,
            Map<String, Map<String, Object>> retrieverModeDefaults
    ) {
        public List<String> availableLlmModels() {
            return llmModelOptions.stream()
                    .filter(this::isSelectable)
                    .map(RuntimeOptionMetadata::code)
                    .toList();
        }

        public List<String> availableDenseEmbeddingModels() {
            return denseEmbeddingModelOptions.stream()
                    .filter(this::isSelectable)
                    .map(RuntimeOptionMetadata::code)
                    .toList();
        }

        public List<String> availableRetrievalBackends() {
            return retrievalBackendOptions.stream()
                    .filter(this::isSelectable)
                    .map(RuntimeOptionMetadata::code)
                    .toList();
        }

        public List<String> availableRetrieverModes() {
            return retrieverModeOptions.stream()
                    .filter(this::isSelectable)
                    .map(RuntimeOptionMetadata::code)
                    .toList();
        }

        public List<String> availableRewriteFailurePolicies() {
            return rewriteFailurePolicyOptions.stream()
                    .filter(this::isSelectable)
                    .map(RuntimeOptionMetadata::code)
                    .toList();
        }

        public List<String> availableRewriteQueryProfiles() {
            return rewriteQueryProfileOptions.stream()
                    .filter(this::isSelectable)
                    .map(RuntimeOptionMetadata::code)
                    .toList();
        }

        public String defaultLlmModel() {
            return defaultCode(llmModelOptions);
        }

        public String defaultDenseEmbeddingModel() {
            return defaultCode(denseEmbeddingModelOptions);
        }

        public String defaultRetrievalBackend() {
            return defaultCode(retrievalBackendOptions);
        }

        public String defaultRetrieverMode() {
            return defaultCode(retrieverModeOptions);
        }

        public String defaultRewriteQueryProfile() {
            return defaultCode(rewriteQueryProfileOptions);
        }

        public Map<String, Object> retrieverModeDefault(String mode) {
            if (mode == null || mode.isBlank()) {
                return Map.of();
            }
            String normalized = mode.trim().toLowerCase().replace("-", "_");
            return retrieverModeDefaults.getOrDefault(normalized, Map.of());
        }

        public RuntimeOptionMetadata findLlmProvider(String provider) {
            return findExact(llmProviderOptions, provider);
        }

        public RuntimeOptionMetadata findLlmModel(String model) {
            return findExact(llmModelOptions, model);
        }

        public RuntimeOptionMetadata findDenseEmbeddingModel(String model) {
            return findExact(denseEmbeddingModelOptions, model);
        }

        public RuntimeOptionMetadata findRetrievalBackend(String backend) {
            return findNormalized(retrievalBackendOptions, backend);
        }

        public RuntimeOptionMetadata findRetrieverMode(String mode) {
            return findNormalized(retrieverModeOptions, mode);
        }

        public RuntimeOptionMetadata findRewriteFailurePolicy(String policy) {
            return findNormalized(rewriteFailurePolicyOptions, policy);
        }

        public RuntimeOptionMetadata findRewriteQueryProfile(String profile) {
            return findNormalized(rewriteQueryProfileOptions, profile);
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

    public record RuntimeOptionMetadata(
            String code,
            String label,
            String provider,
            String status,
            String availability,
            String reason,
            boolean defaultSelected
    ) {
    }

    public Map<String, Object> resolveRetrieverConfig(AdminRuntimeOptionsDtos.RetrieverConfigRequest request) {
        return resolveRetrieverConfig(request, false, loadRuntimeCatalog());
    }

    public Map<String, Object> resolveRetrieverConfig(
            AdminRuntimeOptionsDtos.RetrieverConfigRequest request,
            boolean fixedModePreset
    ) {
        return resolveRetrieverConfig(request, fixedModePreset, loadRuntimeCatalog());
    }

    public Map<String, Object> resolveRetrieverConfig(
            AdminRuntimeOptionsDtos.RetrieverConfigRequest request,
            boolean fixedModePreset,
            RuntimeCatalog runtimeCatalog
    ) {
        String defaultMode = firstNonBlank(runtimeCatalog.defaultRetrieverMode(), RETRIEVER_MODE_HYBRID);
        String mode = normalizeRetrieverMode(request == null ? defaultMode : firstNonBlank(request.retrieverMode(), defaultMode));
        Map<String, Object> modeDefaults = runtimeCatalog.retrieverModeDefault(mode);
        String denseModel;
        if (fixedModePreset || request == null || request.denseEmbeddingModel() == null || request.denseEmbeddingModel().isBlank()) {
            denseModel = firstNonBlank(runtimeCatalog.defaultDenseEmbeddingModel(), DEFAULT_DENSE_EMBEDDING_MODEL);
        } else {
            denseModel = request.denseEmbeddingModel().trim();
        }
        boolean denseRequired;
        if (fixedModePreset || request == null || request.denseEmbeddingRequired() == null) {
            denseRequired = parseBooleanOrDefault(
                    modeDefaults.get("dense_embedding_required"),
                    !RETRIEVER_MODE_BM25_ONLY.equals(mode)
            );
        } else {
            denseRequired = request.denseEmbeddingRequired();
        }
        if (RETRIEVER_MODE_BM25_ONLY.equals(mode)) {
            denseRequired = false;
        }
        boolean denseFallbackEnabled = fixedModePreset
                ? false
                : request == null || request.denseFallbackEnabled() == null
                ? parseBooleanOrDefault(modeDefaults.get("dense_fallback_enabled"), false)
                : request.denseFallbackEnabled();
        boolean rerankEnabled = fixedModePreset
                ? false
                : request == null || request.rerankEnabled() == null
                ? parseBooleanOrDefault(modeDefaults.get("rerank_enabled"), false)
                : request.rerankEnabled();
        int candidatePoolK = fixedModePreset || request == null || request.candidatePoolK() == null
                ? intDefault(
                modeDefaults.get("retriever_candidate_pool_k"),
                runtimeDefaultInt(runtimeCatalog, "retriever_candidate_pool_k", DEFAULT_RETRIEVER_CANDIDATE_POOL_K)
        )
                : clampRange(request.candidatePoolK(), 1, 500, "retriever_candidate_pool_k");

        Map<?, ?> defaultWeights = modeDefaults.get("retriever_fusion_weights") instanceof Map<?, ?> weights
                ? weights
                : Map.of();
        double defaultDenseWeight = doubleDefault(defaultWeights.get("dense"), DEFAULT_RAG_HYBRID_DENSE_WEIGHT);
        double defaultBm25Weight = doubleDefault(defaultWeights.get("bm25"), DEFAULT_RAG_HYBRID_BM25_WEIGHT);
        double defaultTechnicalWeight = doubleDefault(defaultWeights.get("technical"), DEFAULT_RAG_HYBRID_TECHNICAL_WEIGHT);
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

    public String normalizeRetrievalBackend(String value) {
        return normalizeRetrievalBackend(value, loadRuntimeCatalog());
    }

    public String normalizeRetrievalBackend(String value, RuntimeCatalog runtimeCatalog) {
        if (value == null || value.isBlank()) {
            return firstNonBlank(runtimeCatalog.defaultRetrievalBackend(), RETRIEVAL_BACKEND_LOCAL);
        }
        String normalized = value.trim().toLowerCase().replace("-", "_");
        if (!List.of(RETRIEVAL_BACKEND_LOCAL, RETRIEVAL_BACKEND_DB_ANN).contains(normalized)) {
            throw new IllegalArgumentException("unsupported retrieval_backend: " + value);
        }
        return normalized;
    }

    public void attachRetrieverConfig(Map<String, Object> config, Map<String, Object> retrieverConfig) {
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

    public Path resolveRepoRoot() {
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
