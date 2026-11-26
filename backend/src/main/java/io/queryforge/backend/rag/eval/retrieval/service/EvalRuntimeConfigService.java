package io.queryforge.backend.rag.eval.retrieval.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.queryforge.backend.rag.eval.retrieval.dto.RagRetrievalEvalDtos;
import io.queryforge.backend.rag.eval.retrieval.exception.RagRetrievalEvalException;
import io.queryforge.backend.rag.retrieval.agentic.dto.AgenticRetrievalLimits;
import io.queryforge.backend.rag.runtime.configuration.dto.ChatConfigDtos;
import io.queryforge.backend.rag.runtime.configuration.model.RagExecutionSettings;
import io.queryforge.backend.rag.runtime.configuration.model.RagExecutionSettings.Parameter;
import io.queryforge.backend.rag.runtime.readiness.dto.ChatReadinessDtos;
import io.queryforge.backend.rag.runtime.snapshot.dto.GatingSnapshotDtos;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static io.queryforge.backend.rag.runtime.configuration.model.RagExecutionSettings.MEMORY_TOP_N;
import static io.queryforge.backend.rag.runtime.configuration.model.RagExecutionSettings.RERANK_TOP_N;
import static io.queryforge.backend.rag.runtime.configuration.model.RagExecutionSettings.RETRIEVAL_TOP_K;
import static io.queryforge.backend.rag.runtime.configuration.model.RagExecutionSettings.REWRITE_CANDIDATE_COUNT;
import static io.queryforge.backend.rag.runtime.configuration.model.RagExecutionSettings.REWRITE_THRESHOLD;

@Service
@RequiredArgsConstructor
class EvalRuntimeConfigService {

    private final ObjectMapper objectMapper;

    static boolean isOfficialV2(RagRetrievalEvalDtos.RagRetrievalEvalRequest request) {
        JsonNode version = request.runtimeConfig() == null ? null : request.runtimeConfig().get("eval_policy_version");
        if (version == null || version.isNull()) {
            return false;
        }
        if (!version.isIntegralNumber() || !version.canConvertToInt() || version.intValue() != 2) {
            throw new RagRetrievalEvalException("unsupported_eval_policy_version", "eval_policy_version must be 2");
        }
        return true;
    }

    RuntimeEvalContext resolve(
            RagRetrievalEvalDtos.RagRetrievalEvalRequest request,
            ChatConfigDtos.ChatRuntimeConfigResponse baseConfig,
            ChatReadinessDtos.ChatDomainReadinessResponse baseReadiness
    ) {
        JsonNode runtime = request.runtimeConfig();
        boolean official = isOfficialV2(request);
        RagExecutionSettings settings = resolveExecutionSettings(request, baseConfig.executionSettings());
        if (runtime == null || runtime.isNull() || runtime.isMissingNode() || !runtime.isObject()) {
            return new RuntimeEvalContext(baseConfig, baseReadiness, settings);
        }
        ObjectNode metadata = objectMapper.createObjectNode();
        if (baseConfig.metadata() != null && baseConfig.metadata().isObject()) {
            metadata.setAll((ObjectNode) baseConfig.metadata());
        }
        JsonNode runtimeMetadata = firstObject(runtime, "metadata", "metadata_json");
        if (runtimeMetadata != null && runtimeMetadata.isObject()) {
            metadata.setAll((ObjectNode) runtimeMetadata);
        }
        boolean routerEnabled = boolValue(
                runtime,
                boolValue(metadata, baseConfig.routerEnabled(), "routerEnabled", "router_enabled", "queryRouterEnabled"),
                "routerEnabled",
                "router_enabled",
                "query_router_enabled"
        );
        boolean agenticEnabled = boolValue(
                runtime,
                boolValue(metadata, false, "agenticMultiQueryEnabled", "agentic_multi_query_enabled", "queryRouterAgenticEnabled"),
                "agenticMultiQueryEnabled",
                "agentic_multi_query_enabled",
                "query_router_agentic_enabled"
        );
        metadata.put("routerEnabled", routerEnabled);
        metadata.put("agenticMultiQueryEnabled", agenticEnabled);

        JsonNode retrieverConfig = firstObject(runtime, "retrieverConfig", "retriever_config");
        JsonNode fusionWeights = firstObject(retrieverConfig, "retrieverFusionWeights", "retriever_fusion_weights");
        if (fusionWeights == null) {
            fusionWeights = firstObject(runtime, "retrieverFusionWeights", "retriever_fusion_weights");
        }
        String forcedMode = textValue(runtime, null, "forcedRetrievalMode", "forced_retrieval_mode", "java_forced_mode");
        String mode = textValue(runtime, null, "mode", "chat_mode", "runtime_mode");
        if (mode == null || mode.isBlank()) {
            mode = routerEnabled ? "strategy_router" : textValue(runtime, forcedMode, "retrieval_mode", "retrievalMode");
        }
        if (mode == null || mode.isBlank()) {
            mode = baseConfig.mode();
        }

        List<String> generationStrategies = stringList(
                runtime,
                baseConfig.generationStrategies(),
                "generationStrategies",
                "generation_strategies",
                "memory_generation_strategies",
                "source_generation_strategies"
        );
        List<UUID> sourceGatingBatchIds = uuidList(
                runtime,
                baseConfig.sourceGatingBatchIds(),
                "sourceGatingBatchIds",
                "source_gating_batch_ids"
        );
        UUID sourceGatingBatchId = uuidValue(
                runtime,
                firstUuid(baseConfig.sourceGatingBatchId(), sourceGatingBatchIds),
                "sourceGatingBatchId",
                "source_gating_batch_id",
                "snapshot_id"
        );
        sourceGatingBatchIds = GatingSnapshotDtos.withLegacyId(sourceGatingBatchIds, sourceGatingBatchId);
        List<UUID> sourceGatingRunIds = uuidList(
                runtime,
                baseConfig.sourceGatingRunIds(),
                "sourceGatingRunIds",
                "source_gating_run_ids"
        );
        UUID sourceGatingRunId = uuidValue(
                runtime,
                firstUuid(baseConfig.sourceGatingRunId(), sourceGatingRunIds),
                "sourceGatingRunId",
                "source_gating_run_id"
        );
        sourceGatingRunIds = GatingSnapshotDtos.withLegacyId(sourceGatingRunIds, sourceGatingRunId);
        if (official) {
            mode = request.forcedMode();
            SnapshotIds snapshots = "raw_only".equals(mode)
                    ? new SnapshotIds(List.of(), List.of())
                    : officialSnapshotIds(runtime, sourceGatingBatchIds, sourceGatingRunIds);
            sourceGatingBatchIds = snapshots.batchIds();
            sourceGatingRunIds = snapshots.runIds();
            sourceGatingBatchId = sourceGatingBatchIds.isEmpty() ? null : sourceGatingBatchIds.getFirst();
            sourceGatingRunId = sourceGatingRunIds.isEmpty() ? null : sourceGatingRunIds.getFirst();
        }
        ChatConfigDtos.ChatRuntimeConfigResponse config = new ChatConfigDtos.ChatRuntimeConfigResponse(
                baseConfig.domainId(),
                baseConfig.domainKey(),
                baseConfig.displayName(),
                baseConfig.sourceLanguage(),
                baseConfig.enabled(),
                mode,
                generationStrategies,
                textValue(runtime, baseConfig.gatingPreset(), "gatingPreset", "gating_preset"),
                sourceGatingBatchId,
                sourceGatingRunId,
                sourceGatingBatchIds,
                sourceGatingRunIds,
                textValue(runtime, baseConfig.rewriteQueryProfile(), "rewriteQueryProfile", "rewrite_query_profile"),
                boolValue(runtime, baseConfig.rewriteAnchorInjectionEnabled(), "rewriteAnchorInjectionEnabled", "rewrite_anchor_injection_enabled"),
                boolValue(runtime, baseConfig.useSessionContext(), "useSessionContext", "use_session_context"),
                textValue(runtime, baseConfig.retrievalBackend(), "retrievalBackend", "retrieval_backend"),
                textValue(
                        retrieverConfig,
                        textValue(runtime, baseConfig.denseEmbeddingModel(), "denseEmbeddingModel", "dense_embedding_model", "chunk_embedding_model"),
                        "denseEmbeddingModel",
                        "dense_embedding_model"
                ),
                textValue(
                        retrieverConfig,
                        textValue(runtime, baseConfig.retrieverMode(), "retrieverMode", "retriever_mode"),
                        "retrieverMode",
                        "retriever_mode"
                ),
                intValue(retrieverConfig, intValue(runtime, baseConfig.retrieverCandidatePoolK(), "retrieverCandidatePoolK", "retriever_candidate_pool_k"), "candidatePoolK", "candidate_pool_k", "retrieverCandidatePoolK", "retriever_candidate_pool_k"),
                doubleValue(fusionWeights, baseConfig.retrieverDenseWeight(), "dense", "denseWeight", "dense_weight"),
                doubleValue(fusionWeights, baseConfig.retrieverBm25Weight(), "bm25", "bm25Weight", "bm25_weight"),
                doubleValue(fusionWeights, baseConfig.retrieverTechnicalWeight(), "technical", "technicalWeight", "technical_weight"),
                settings.retrievalTopK(),
                settings.rerankTopN(),
                settings.memoryTopN(),
                settings.rewriteCandidateCount(),
                settings.rewriteThreshold(),
                textValue(runtime, baseConfig.rewriteFailurePolicy(), "rewriteFailurePolicy", "rewrite_failure_policy"),
                routerEnabled,
                metadata,
                baseConfig.updatedAt(),
                boolValue(runtime, baseConfig.readyForRewrite(), "readyForRewrite", "ready_for_rewrite"),
                baseConfig.readinessMessage()
        );
        ChatReadinessDtos.ChatDomainReadinessResponse readiness = official ? null : readinessForRuntimeOverride(
                runtime,
                baseReadiness,
                config
        );
        return new RuntimeEvalContext(config, readiness, settings);
    }

    private SnapshotIds officialSnapshotIds(JsonNode runtime, List<UUID> defaultBatches, List<UUID> defaultRuns) {
        String[] batchListKeys = {"sourceGatingBatchIds", "source_gating_batch_ids"};
        String[] batchKeys = {"sourceGatingBatchId", "source_gating_batch_id", "snapshot_id"};
        String[] runListKeys = {"sourceGatingRunIds", "source_gating_run_ids"};
        String[] runKeys = {"sourceGatingRunId", "source_gating_run_id"};
        JsonNode batchList = override(runtime, batchListKeys);
        JsonNode runList = override(runtime, runListKeys);
        JsonNode batchId = override(runtime, batchKeys);
        JsonNode runId = override(runtime, runKeys);
        if (batchList == null && runList == null && batchId == null && runId == null) {
            return new SnapshotIds(defaultBatches, defaultRuns);
        }
        // Explicit overrides never intersect with the active chat snapshot.
        return new SnapshotIds(snapshotIds(batchList, batchId), snapshotIds(runList, runId));
    }

    private List<UUID> snapshotIds(JsonNode ids, JsonNode id) {
        List<UUID> values = new ArrayList<>();
        if (ids != null) {
            if (!ids.isArray()) {
                throw new RagRetrievalEvalException("invalid_eval_snapshot", "snapshot identifiers must be an array");
            }
            for (JsonNode value : ids) {
                values.add(snapshotId(value));
            }
        }
        return GatingSnapshotDtos.withLegacyId(List.copyOf(values), id == null ? null : snapshotId(id));
    }

    private UUID snapshotId(JsonNode value) {
        if (value.isTextual()) {
            try {
                return UUID.fromString(value.textValue().trim());
            } catch (IllegalArgumentException ignored) {
                // Reject a malformed explicit request before it can fall back to a different snapshot.
            }
        }
        throw new RagRetrievalEvalException("invalid_eval_snapshot", "snapshot identifier must be a UUID");
    }

    private JsonNode override(JsonNode runtime, String... keys) {
        for (String key : keys) {
            if (runtime.hasNonNull(key)) {
                return runtime.get(key);
            }
        }
        return null;
    }

    private record SnapshotIds(List<UUID> batchIds, List<UUID> runIds) {
    }

    private RagExecutionSettings resolveExecutionSettings(
            RagRetrievalEvalDtos.RagRetrievalEvalRequest request,
            RagExecutionSettings base
    ) {
        JsonNode runtime = request.runtimeConfig();
        Integer retrievalTopK = request.topK();
        if (retrievalTopK == null) {
            retrievalTopK = integerSetting(runtime, RETRIEVAL_TOP_K, "topK", "top_k");
        }
        Integer rerankTopN = integerSetting(runtime, RERANK_TOP_N);
        if (rerankTopN == null) {
            // A topK-only request historically sets both limits. An experiment can supply its own rerank limit.
            rerankTopN = request.topK();
        }
        JsonNode threshold = executionValue(runtime, REWRITE_THRESHOLD, "threshold");
        if (threshold != null && !threshold.isNumber()) {
            throw new IllegalArgumentException("rewriteThreshold must be a number");
        }
        return base.withOverrides(new RagExecutionSettings.Overrides(
                retrievalTopK,
                rerankTopN,
                integerSetting(runtime, MEMORY_TOP_N),
                integerSetting(runtime, REWRITE_CANDIDATE_COUNT),
                threshold == null ? null : threshold.doubleValue()
        ));
    }

    private Integer integerSetting(JsonNode runtime, Parameter parameter, String... legacyKeys) {
        JsonNode value = executionValue(runtime, parameter, legacyKeys);
        if (value == null) {
            return null;
        }
        if (!value.canConvertToInt() || !value.canConvertToExactIntegral()) {
            throw new IllegalArgumentException(parameter.fieldName() + " must be an integer");
        }
        return value.intValue();
    }

    private JsonNode executionValue(JsonNode runtime, Parameter parameter, String... legacyKeys) {
        if (runtime == null || !runtime.isObject()) {
            return null;
        }
        JsonNode value = runtime.get(parameter.fieldName());
        if (value == null || value.isNull()) {
            value = runtime.get(parameter.key());
        }
        if (value != null && !value.isNull()) {
            return value;
        }
        for (String key : legacyKeys) {
            value = runtime.get(key);
            if (value != null && !value.isNull()) {
                return value;
            }
        }
        return null;
    }

    private ChatReadinessDtos.ChatDomainReadinessResponse readinessForRuntimeOverride(
            JsonNode runtime,
            ChatReadinessDtos.ChatDomainReadinessResponse base,
            ChatConfigDtos.ChatRuntimeConfigResponse config
    ) {
        boolean rewriteBacked = !"raw_only".equals(normalizedMode(config.mode()));
        boolean hasSnapshot = !nonNullList(config.sourceGatingRunIds()).isEmpty()
                || !nonNullList(config.sourceGatingBatchIds()).isEmpty();
        boolean ready = boolValue(runtime, base.readyForRewrite() || !rewriteBacked || hasSnapshot, "readyForRewrite", "ready_for_rewrite");
        return new ChatReadinessDtos.ChatDomainReadinessResponse(
                config.domainId(),
                config.domainKey(),
                config.displayName(),
                config.sourceLanguage(),
                base.activeConfigPresent(),
                config.enabled(),
                config.mode(),
                rewriteBacked,
                config.generationStrategies(),
                config.gatingPreset(),
                base.snapshot(),
                base.chunkEmbeddings(),
                base.memoryCount(),
                base.acceptedGatedQueryCount(),
                base.promptBinding(),
                base.retrieval(),
                ready,
                ready ? List.of() : nonNullList(base.blockingReasons()),
                base.checkedAt()
        );
    }

    private JsonNode firstObject(JsonNode source, String... keys) {
        if (source == null || source.isNull() || source.isMissingNode()) {
            return null;
        }
        for (String key : keys) {
            JsonNode value = source.get(key);
            if (value != null && value.isObject()) {
                return value;
            }
        }
        return null;
    }

    private String textValue(JsonNode source, String fallback, String... keys) {
        if (source == null || source.isNull() || source.isMissingNode()) {
            return fallback;
        }
        for (String key : keys) {
            JsonNode value = source.get(key);
            if (value != null && !value.isNull() && !value.asText("").isBlank()) {
                return value.asText().trim();
            }
        }
        return fallback;
    }

    private boolean boolValue(JsonNode source, boolean fallback, String... keys) {
        if (source == null || source.isNull() || source.isMissingNode()) {
            return fallback;
        }
        for (String key : keys) {
            JsonNode value = source.get(key);
            if (value != null && !value.isNull()) {
                return value.asBoolean(fallback);
            }
        }
        return fallback;
    }

    private static int intValue(JsonNode source, int fallback, String... keys) {
        if (source == null || source.isNull() || source.isMissingNode()) {
            return fallback;
        }
        for (String key : keys) {
            JsonNode value = source.get(key);
            if (value != null && !value.isNull() && value.canConvertToInt()) {
                return value.asInt(fallback);
            }
        }
        return fallback;
    }

    private double doubleValue(JsonNode source, double fallback, String... keys) {
        if (source == null || source.isNull() || source.isMissingNode()) {
            return fallback;
        }
        for (String key : keys) {
            JsonNode value = source.get(key);
            if (value != null && !value.isNull() && value.isNumber()) {
                return value.asDouble(fallback);
            }
        }
        return fallback;
    }

    private UUID uuidValue(JsonNode source, UUID fallback, String... keys) {
        String text = textValue(source, null, keys);
        if (text == null) {
            return fallback;
        }
        try {
            return UUID.fromString(text);
        } catch (IllegalArgumentException ignored) {
            return fallback;
        }
    }

    private UUID firstUuid(UUID fallback, List<UUID> values) {
        return fallback != null ? fallback : (values == null || values.isEmpty() ? null : values.getFirst());
    }

    private List<UUID> uuidList(JsonNode source, List<UUID> fallback, String... keys) {
        if (source == null || source.isNull() || source.isMissingNode()) {
            return nonNullList(fallback);
        }
        for (String key : keys) {
            JsonNode value = source.get(key);
            if (value == null || !value.isArray()) {
                continue;
            }
            java.util.ArrayList<UUID> result = new java.util.ArrayList<>();
            for (JsonNode item : value) {
                try {
                    result.add(UUID.fromString(item.asText()));
                } catch (IllegalArgumentException ignored) {
                    // Ignore invalid override entries and keep the rest of the request usable.
                }
            }
            return List.copyOf(result);
        }
        return nonNullList(fallback);
    }

    private List<String> stringList(JsonNode source, List<String> fallback, String... keys) {
        if (source == null || source.isNull() || source.isMissingNode()) {
            return nonNullList(fallback);
        }
        for (String key : keys) {
            JsonNode value = source.get(key);
            if (value == null || !value.isArray()) {
                continue;
            }
            java.util.ArrayList<String> result = new java.util.ArrayList<>();
            for (JsonNode item : value) {
                String text = item.asText("");
                if (!text.isBlank()) {
                    result.add(text.trim().toUpperCase(Locale.ROOT));
                }
            }
            return List.copyOf(result);
        }
        return nonNullList(fallback);
    }

    static AgenticRetrievalLimits agenticSettings(JsonNode metadata) {
        int maxSubqueries = intValue(metadata, AgenticRetrievalLimits.DEFAULT_EVAL_MAX_SUBQUERIES, "agenticMaxSubqueries", "agentic_max_subqueries", "strategyRouterAgenticMaxSubqueries", "strategy_router_agentic_max_subqueries");
        int rrfK = intValue(metadata, AgenticRetrievalLimits.DEFAULT_RRF_K, "agenticRrfK", "agentic_rrf_k", "rrfK", "rrf_k");
        return new AgenticRetrievalLimits(maxSubqueries, rrfK);
    }

    record RuntimeEvalContext(
            ChatConfigDtos.ChatRuntimeConfigResponse config,
            ChatReadinessDtos.ChatDomainReadinessResponse readiness,
            RagExecutionSettings settings
    ) {
    }

    static <T> List<T> nonNullList(List<T> values) {
        return values == null ? List.of() : values;
    }

    static String normalizedMode(String mode) {
        return normalizeText(mode, "selective_rewrite");
    }

    static String normalizeText(String value, String fallback) {
        String normalized = value == null || value.isBlank() ? fallback : value;
        return normalized == null ? "" : normalized.trim().toLowerCase(Locale.ROOT).replace("-", "_");
    }
}
