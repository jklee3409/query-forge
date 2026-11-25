package io.queryforge.backend.rag.runtime.promotion;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.queryforge.backend.rag.runtime.configuration.ChatConfigDtos;
import io.queryforge.backend.rag.runtime.configuration.ChatRuntimeConfigService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ChatConfigPromotionService {

    private final ChatRuntimeConfigService configService;
    private final ChatConfigPromotionRepository repository;
    private final ObjectMapper objectMapper;

    @Transactional
    public ChatConfigDtos.ChatRuntimeConfigResponse applyRagRunConfig(
            ChatConfigPromotionDtos.ApplyChatConfigFromRagRunRequest request
    ) {
        if (request == null || request.ragTestRunId() == null) {
            throw new IllegalArgumentException("ragTestRunId is required");
        }
        ChatConfigPromotionRepository.RagRunApplySnapshot run = repository.findRagRunApplySnapshot(request.ragTestRunId())
                .orElseThrow(() -> new IllegalArgumentException("rag test run not found: " + request.ragTestRunId()));
        if (!"completed".equalsIgnoreCase(run.status())) {
            throw new IllegalArgumentException("only completed RAG test runs can be applied to chat");
        }
        if (run.domainId() == null) {
            throw new IllegalArgumentException("RAG test run has no domain_id; rerun it inside a domain workspace");
        }

        ChatConfigDtos.ChatRuntimeConfigResponse current = configService.getConfig(run.domainId());
        JsonNode config = run.configJson() == null ? objectMapper.createObjectNode() : run.configJson();
        boolean rewriteEnabled = configBoolean(run.rewriteEnabled(), config, "rewrite_enabled", false);
        boolean selectiveRewrite = configBoolean(run.selectiveRewrite(), config, "selective_rewrite", true);
        boolean useSessionContext = configBoolean(run.useSessionContext(), config, "use_session_context", false);
        boolean routerEnabled = configBoolean(null, config, "router_enabled", current.routerEnabled())
                || configBoolean(null, config, "routerEnabled", false);
        String mode = routerEnabled
                ? "strategy_router"
                : configText(
                config,
                "forced_retrieval_mode",
                modeFromRagRun(rewriteEnabled, selectiveRewrite, useSessionContext)
        );
        String gatingPreset = configText(config, "gating_preset", run.gatingPreset());
        List<String> generationStrategies = firstNonEmpty(
                readStringList(run.generationMethodCodes()),
                readStringList(config.path("memory_generation_strategies")),
                readStringList(config.path("source_generation_strategies")),
                current.generationStrategies()
        );
        UUID sourceGatingBatchId = null;
        if (!"raw_only".equals(mode)) {
            sourceGatingBatchId = firstNonNull(
                    configUuid(config, "source_gating_batch_id"),
                    comparisonSnapshotUuid(config, gatingPreset),
                    configUuid(config, "snapshot_id")
            );
            if (sourceGatingBatchId == null) {
                throw new IllegalArgumentException("RAG test run has no single source gating snapshot to apply to chat");
            }
        }
        String rewriteQueryProfile = configText(config, "rewrite_query_profile", current.rewriteQueryProfile());
        String rewriteFailurePolicy = configText(config, "rewrite_failure_policy", current.rewriteFailurePolicy());
        boolean rewriteAnchorInjectionEnabled = rewriteEnabled && configBoolean(
                run.rewriteAnchorInjectionEnabled(),
                config,
                "rewrite_anchor_injection_enabled",
                current.rewriteAnchorInjectionEnabled()
        );
        ObjectNode metadata = mutableMetadata(current.metadata());
        boolean configuredAgenticMultiQueryEnabled = configBoolean(
                null,
                config,
                "agentic_multi_query_enabled",
                configBoolean(null, config, "agenticMultiQueryEnabled", metadata.path("agenticMultiQueryEnabled").asBoolean(false))
        );
        boolean agenticMultiQueryEnabled = "agentic_multi_query".equals(mode)
                || (routerEnabled && configuredAgenticMultiQueryEnabled);
        int agenticMaxSubqueries = Math.max(
                1,
                Math.min(
                        4,
                        configInt(config, "agentic_max_subqueries", configInt(config, "maxSubqueries", metadata.path("maxSubqueries").asInt(3)))
                )
        );
        int agenticRrfK = Math.max(
                1,
                configInt(config, "agentic_rrf_k", configInt(config, "rrfK", metadata.path("rrfK").asInt(60)))
        );
        metadata.put("routerEnabled", routerEnabled);
        metadata.put("agenticMultiQueryEnabled", agenticMultiQueryEnabled);
        metadata.put("maxSubqueries", agenticMaxSubqueries);
        metadata.put("agenticMaxSubqueries", agenticMaxSubqueries);
        metadata.put("rrfK", agenticRrfK);
        metadata.put("agenticRrfK", agenticRrfK);
        JsonNode retrieverConfig = config.path("retriever_config");
        JsonNode fusionWeights = firstPresentObject(
                retrieverConfig.path("retriever_fusion_weights"),
                config.path("retriever_fusion_weights")
        );
        ChatConfigDtos.ChatRuntimeConfigRequest applyRequest = new ChatConfigDtos.ChatRuntimeConfigRequest(
                run.domainId(),
                true,
                mode,
                generationStrategies,
                gatingPreset,
                sourceGatingBatchId,
                sourceGatingBatchId == null ? List.of() : List.of(sourceGatingBatchId),
                rewriteQueryProfile,
                rewriteAnchorInjectionEnabled,
                useSessionContext,
                configText(config, "retrieval_backend", current.retrievalBackend()),
                firstNonBlank(
                        configText(retrieverConfig, "dense_embedding_model", null),
                        configText(config, "dense_embedding_model", null),
                        current.denseEmbeddingModel()
                ),
                firstNonBlank(
                        configText(retrieverConfig, "retriever_mode", null),
                        configText(config, "retriever_mode", null),
                        current.retrieverMode()
                ),
                firstNonNull(
                        configIntOrNull(retrieverConfig, "retriever_candidate_pool_k"),
                        configIntOrNull(config, "retriever_candidate_pool_k"),
                        current.retrieverCandidatePoolK()
                ),
                configDouble(fusionWeights, "dense", current.retrieverDenseWeight()),
                configDouble(fusionWeights, "bm25", current.retrieverBm25Weight()),
                configDouble(fusionWeights, "technical", current.retrieverTechnicalWeight()),
                firstNonNull(run.retrievalTopK(), configInt(config, "retrieval_top_k", current.retrievalTopK())),
                firstNonNull(run.rerankTopN(), configInt(config, "rerank_top_n", current.rerankTopN())),
                configInt(config, "memory_top_n", current.memoryTopN()),
                configInt(config, "rewrite_candidate_count", current.rewriteCandidateCount()),
                firstNonNull(run.threshold(), configDouble(config, "rewrite_threshold", current.rewriteThreshold())),
                rewriteFailurePolicy,
                routerEnabled,
                metadata,
                blankToNull(request.updatedBy())
        );
        return configService.updateConfig(
                applyRequest,
                "apply_rag_run",
                run.ragTestRunId(),
                sourceConfigFromRagRun(run, config)
        );
    }

    private ObjectNode sourceConfigFromRagRun(
            ChatConfigPromotionRepository.RagRunApplySnapshot run,
            JsonNode config
    ) {
        ObjectNode source = objectMapper.createObjectNode();
        source.put("rag_test_run_id", run.ragTestRunId().toString());
        source.put("status", run.status());
        source.put("gating_preset", run.gatingPreset());
        source.set("generation_method_codes", run.generationMethodCodes());
        source.set("config_json", config == null ? objectMapper.createObjectNode() : config);
        return source;
    }

    private ObjectNode mutableMetadata(JsonNode metadata) {
        if (metadata != null && metadata.isObject()) {
            return metadata.deepCopy();
        }
        return objectMapper.createObjectNode();
    }

    private String modeFromRagRun(boolean rewriteEnabled, boolean selectiveRewrite, boolean useSessionContext) {
        if (!rewriteEnabled) {
            return "raw_only";
        }
        if (selectiveRewrite && useSessionContext) {
            return "selective_rewrite_with_session";
        }
        if (selectiveRewrite) {
            return "selective_rewrite";
        }
        return "rewrite_always";
    }

    @SafeVarargs
    private final <T> T firstNonNull(T... values) {
        for (T value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    @SafeVarargs
    private final List<String> firstNonEmpty(List<String>... values) {
        for (List<String> value : values) {
            if (value != null && !value.isEmpty()) {
                return value;
            }
        }
        return List.of();
    }

    private boolean configBoolean(Boolean runValue, JsonNode config, String field, boolean fallback) {
        if (runValue != null) {
            return runValue;
        }
        JsonNode value = config == null ? null : config.get(field);
        return value == null || value.isNull() ? fallback : value.asBoolean(fallback);
    }

    private int configInt(JsonNode config, String field, int fallback) {
        JsonNode value = config == null ? null : config.get(field);
        return value == null || value.isNull() ? fallback : value.asInt(fallback);
    }

    private Integer configIntOrNull(JsonNode config, String field) {
        JsonNode value = config == null ? null : config.get(field);
        return value == null || value.isNull() ? null : value.asInt();
    }

    private double configDouble(JsonNode config, String field, double fallback) {
        JsonNode value = config == null ? null : config.get(field);
        return value == null || value.isNull() ? fallback : value.asDouble(fallback);
    }

    private String configText(JsonNode config, String field, String fallback) {
        JsonNode value = config == null ? null : config.get(field);
        String raw = value == null || value.isNull() ? null : value.asText();
        return raw == null || raw.isBlank() ? fallback : raw.trim();
    }

    private JsonNode firstPresentObject(JsonNode first, JsonNode second) {
        if (first != null && first.isObject()) {
            return first;
        }
        if (second != null && second.isObject()) {
            return second;
        }
        return objectMapper.createObjectNode();
    }

    private UUID configUuid(JsonNode config, String field) {
        JsonNode value = config == null ? null : config.get(field);
        return value == null || value.isNull() ? null : parseUuid(value.asText());
    }

    private UUID comparisonSnapshotUuid(JsonNode config, String gatingPreset) {
        JsonNode snapshots = config == null ? null : config.get("comparison_snapshots");
        if (snapshots == null || !snapshots.isObject()) {
            return null;
        }
        LinkedHashSet<String> keys = new LinkedHashSet<>();
        if (gatingPreset != null && !gatingPreset.isBlank()) {
            keys.add(gatingPreset.trim());
        }
        keys.add("full_gating");
        keys.add("rule_plus_llm");
        keys.add("rule_only");
        keys.add("ungated");
        for (String key : keys) {
            UUID value = parseUuid(snapshots.path(key).path("gating_batch_id").asText(null));
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private UUID parseUuid(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String value = raw.trim();
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            value = value.substring(1, value.length() - 1);
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private List<String> readStringList(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return List.of();
        }
        LinkedHashSet<String> values = new LinkedHashSet<>();
        if (node.isArray()) {
            for (JsonNode item : node) {
                addStringValue(values, item.asText(""));
            }
        } else {
            addStringValue(values, node.asText(""));
        }
        return List.copyOf(values);
    }

    private void addStringValue(LinkedHashSet<String> values, String raw) {
        if (raw == null || raw.isBlank()) {
            return;
        }
        values.add(raw.trim());
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
