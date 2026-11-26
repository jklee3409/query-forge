package io.queryforge.backend.rag.runtime.configuration.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.queryforge.backend.admin.console.embedding.dto.ChunkEmbeddingAdminDtos;
import io.queryforge.backend.admin.console.embedding.service.ChunkEmbeddingAdminService;
import io.queryforge.backend.admin.console.runtime.dto.AdminRuntimeOptionsDtos;
import io.queryforge.backend.admin.console.runtime.service.AdminRuntimeOptionsService;
import io.queryforge.backend.rag.retrieval.candidate.dto.RetrievalDtos.RetrievalRuntime;
import io.queryforge.backend.rag.runtime.configuration.dto.ChatConfigDtos;
import io.queryforge.backend.rag.runtime.configuration.model.RagExecutionSettings;
import io.queryforge.backend.rag.runtime.configuration.repository.ChatRuntimeConfigRepository;
import io.queryforge.backend.rag.runtime.snapshot.dto.GatingSnapshotDtos;
import io.queryforge.backend.rag.runtime.snapshot.repository.GatingSnapshotRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import static io.queryforge.backend.rag.retrieval.candidate.dto.RetrievalDtos.DEFAULT_DENSE_EMBEDDING_MODEL;

@Service
@RequiredArgsConstructor
public class ChatRuntimeConfigService {

    private final GatingSnapshotRepository snapshotRepository;

    private static final Set<String> MODES = Set.of(
            "raw_only",
            "strategy_router",
            "selective_rewrite",
            "anchor_aware_rewrite",
            "agentic_multi_query",
            "selective_rewrite_with_session",
            "rewrite_always",
            "memory_only_gated",
            "memory_only_ungated"
    );
    private static final Set<String> GATING_PRESETS = Set.of("ungated", "rule_only", "rule_plus_llm", "full_gating");
    private static final Set<String> REWRITE_PROFILES = Set.of("compact_anchor", "detailed_intent");
    private static final Set<String> FAILURE_POLICIES = Set.of("fail_run", "skip_to_raw", "heuristic_fallback");
    private static final Set<String> RETRIEVAL_BACKENDS = Set.of("local", "db_ann");
    private static final Set<String> RETRIEVER_MODES = Set.of("bm25_only", "dense_only", "hybrid");
    private static final int MAX_SELECTED_SOURCE_GATING_BATCHES = 20;
    private final ChatRuntimeConfigRepository repository;
    private final ChunkEmbeddingAdminService chunkEmbeddingService;
    private final AdminRuntimeOptionsService runtimeOptionsService;
    private final ObjectMapper objectMapper;

    public List<ChatConfigDtos.ChatDomainOption> listChatDomains() {
        return repository.findActiveDomains();
    }

    public ChatConfigDtos.ChatRuntimeConfigResponse getConfig(UUID domainId) {
        if (domainId == null) {
            throw new IllegalArgumentException("domainId is required");
        }
        return repository.findConfig(domainId,
                        () -> runtimeOptionsService.defaultRagExecutionSettings(runtimeOptionsService.loadRuntimeCatalog()))
                .orElseThrow(() -> new IllegalArgumentException("active domain not found: " + domainId));
    }

    public List<ChatConfigDtos.ChatRuntimeConfigProvenanceRow> listConfigProvenance(UUID domainId, Integer limit) {
        if (domainId == null) {
            throw new IllegalArgumentException("domainId is required");
        }
        return repository.findProvenance(domainId, limit);
    }

    @Transactional
    public ChatConfigDtos.ChatRuntimeConfigResponse updateConfig(ChatConfigDtos.ChatRuntimeConfigRequest request) {
        return updateConfig(request, "manual", null, objectMapper.createObjectNode());
    }

    @Transactional
    public ChatConfigDtos.ChatRuntimeConfigResponse updateConfig(
            ChatConfigDtos.ChatRuntimeConfigRequest request,
            String changeSource,
            UUID sourceRagTestRunId,
            JsonNode sourceConfig
    ) {
        if (request == null || request.domainId() == null) {
            throw new IllegalArgumentException("domainId is required");
        }
        ChatConfigDtos.ChatRuntimeConfigResponse current = getConfig(request.domainId());
        boolean enabled = request.enabled() == null ? current.enabled() : request.enabled();
        String mode = normalizeAllowed(request.mode(), current.mode(), MODES, "mode");
        String gatingPreset = normalizeAllowed(request.gatingPreset(), current.gatingPreset(), GATING_PRESETS, "gatingPreset");
        String rewriteQueryProfile = normalizeAllowed(
                request.rewriteQueryProfile(),
                current.rewriteQueryProfile(),
                REWRITE_PROFILES,
                "rewriteQueryProfile"
        );
        String rewriteFailurePolicy = normalizeAllowed(
                request.rewriteFailurePolicy(),
                current.rewriteFailurePolicy(),
                FAILURE_POLICIES,
                "rewriteFailurePolicy"
        );
        AdminRuntimeOptionsDtos.RuntimeOptionsResponse runtimeOptions = runtimeOptionsService.getRuntimeOptions();
        String retrievalBackend = normalizeAllowed(
                request.retrievalBackend(),
                current.retrievalBackend(),
                RETRIEVAL_BACKENDS,
                "retrievalBackend"
        );
        validateCatalogSelection(retrievalBackend, runtimeOptions.retrievalBackends(), "retrievalBackend");
        String retrieverMode = normalizeAllowed(
                request.retrieverMode(),
                current.retrieverMode(),
                RETRIEVER_MODES,
                "retrieverMode"
        );
        validateCatalogSelection(retrieverMode, runtimeOptions.retrieverModes(), "retrieverMode");
        String denseEmbeddingModel = firstNonBlank(
                request.denseEmbeddingModel(),
                current.denseEmbeddingModel(),
                runtimeOptions.defaultDenseEmbeddingModel(),
                DEFAULT_DENSE_EMBEDDING_MODEL
        );
        validateCatalogSelection(denseEmbeddingModel, runtimeOptions.denseEmbeddingModels(), "denseEmbeddingModel");
        int retrieverCandidatePoolK = RetrievalRuntime.validateCandidatePoolK(
                request.retrieverCandidatePoolK() == null ? current.retrieverCandidatePoolK() : request.retrieverCandidatePoolK(),
                "retrieverCandidatePoolK"
        );
        double retrieverDenseWeight = RetrievalRuntime.validateFusionWeight(
                request.retrieverDenseWeight() == null ? current.retrieverDenseWeight() : request.retrieverDenseWeight(),
                "retrieverDenseWeight"
        );
        double retrieverBm25Weight = RetrievalRuntime.validateFusionWeight(
                request.retrieverBm25Weight() == null ? current.retrieverBm25Weight() : request.retrieverBm25Weight(),
                "retrieverBm25Weight"
        );
        double retrieverTechnicalWeight = RetrievalRuntime.validateFusionWeight(
                request.retrieverTechnicalWeight() == null ? current.retrieverTechnicalWeight() : request.retrieverTechnicalWeight(),
                "retrieverTechnicalWeight"
        );
        double weightSum = retrieverDenseWeight + retrieverBm25Weight + retrieverTechnicalWeight;
        if (weightSum <= 0.0d) {
            throw new IllegalArgumentException("retriever fusion weights must have a positive sum");
        }
        if ("bm25_only".equals(retrieverMode)) {
            retrieverDenseWeight = 0.0d;
            retrieverBm25Weight = 1.0d;
            retrieverTechnicalWeight = 0.0d;
        } else if ("dense_only".equals(retrieverMode)) {
            retrieverDenseWeight = 1.0d;
            retrieverBm25Weight = 0.0d;
            retrieverTechnicalWeight = 0.0d;
        } else {
            retrieverDenseWeight = retrieverDenseWeight / weightSum;
            retrieverBm25Weight = retrieverBm25Weight / weightSum;
            retrieverTechnicalWeight = retrieverTechnicalWeight / weightSum;
        }
        validateDbAnnReadiness(retrievalBackend, retrieverMode, denseEmbeddingModel);
        List<String> enabledMethods = repository.findEnabledMethodCodes(request.domainId());
        List<String> generationStrategies = normalizeStrategies(
                request.generationStrategies() == null ? current.generationStrategies() : request.generationStrategies(),
                enabledMethods
        );
        boolean rewriteAnchorInjectionEnabled = request.rewriteAnchorInjectionEnabled() == null
                ? current.rewriteAnchorInjectionEnabled()
                : request.rewriteAnchorInjectionEnabled();
        boolean useSessionContext = request.useSessionContext() == null
                ? current.useSessionContext()
                : request.useSessionContext();
        RagExecutionSettings settings = runtimeOptionsService.resolveRagExecutionSettings(
                runtimeOptions.defaultParameterRanges(), current.executionSettings(), request.executionOverrides()
        );
        List<UUID> sourceGatingBatchIds = requestedSourceGatingBatchIds(request, current);
        UUID sourceGatingBatchId = sourceGatingBatchIds.isEmpty() ? null : sourceGatingBatchIds.getFirst();
        List<UUID> sourceGatingRunIds = List.of();
        UUID sourceGatingRunId = null;
        if ("raw_only".equals(mode)) {
            sourceGatingBatchIds = List.of();
            sourceGatingBatchId = null;
        } else {
            if (sourceGatingBatchIds.isEmpty()) {
                throw new IllegalArgumentException("at least one sourceGatingBatchId is required for rewrite-backed chat");
            }
            if (sourceGatingBatchIds.size() > MAX_SELECTED_SOURCE_GATING_BATCHES) {
                throw new IllegalArgumentException(
                        "sourceGatingBatchIds must contain at most " + MAX_SELECTED_SOURCE_GATING_BATCHES + " snapshots"
                );
            }
            LinkedHashSet<UUID> runIds = new LinkedHashSet<>();
            String resolvedGatingPreset = gatingPreset;
            for (UUID selectedBatchId : sourceGatingBatchIds) {
                GatingSnapshotDtos.GatingSnapshot snapshot = snapshotRepository.findGatingSnapshot(selectedBatchId)
                        .orElseThrow(() -> new IllegalArgumentException("gating batch not found: " + selectedBatchId));
                validateSnapshot(request.domainId(), gatingPreset, generationStrategies, snapshot);
                runIds.add(snapshot.sourceGatingRunId());
                resolvedGatingPreset = snapshot.gatingPreset();
            }
            sourceGatingRunIds = List.copyOf(runIds);
            sourceGatingRunId = sourceGatingRunIds.isEmpty() ? null : sourceGatingRunIds.getFirst();
            gatingPreset = resolvedGatingPreset;
        }
        ObjectNode metadata = mutableMetadata(request.metadata() == null ? current.metadata() : request.metadata());
        if (request.routerEnabled() != null) {
            metadata.put("routerEnabled", request.routerEnabled());
        }
        repository.upsertConfig(
                request.domainId(),
                enabled,
                mode,
                generationStrategies,
                gatingPreset,
                sourceGatingBatchId,
                sourceGatingRunId,
                sourceGatingBatchIds,
                sourceGatingRunIds,
                rewriteQueryProfile,
                rewriteAnchorInjectionEnabled,
                useSessionContext,
                retrievalBackend,
                denseEmbeddingModel,
                retrieverMode,
                retrieverCandidatePoolK,
                retrieverDenseWeight,
                retrieverBm25Weight,
                retrieverTechnicalWeight,
                settings.retrievalTopK(),
                settings.rerankTopN(),
                settings.memoryTopN(),
                settings.rewriteCandidateCount(),
                settings.rewriteThreshold(),
                rewriteFailurePolicy,
                metadata,
                blankToNull(request.updatedBy())
        );
        ChatConfigDtos.ChatRuntimeConfigResponse applied = getConfig(request.domainId());
        repository.insertProvenance(
                request.domainId(),
                changeSource,
                sourceRagTestRunId,
                sourceConfig,
                configSnapshot(current),
                configSnapshot(applied),
                buildConfigDiff(current, applied),
                blankToNull(request.updatedBy())
        );
        return applied;
    }

    private JsonNode configSnapshot(ChatConfigDtos.ChatRuntimeConfigResponse config) {
        return objectMapper.valueToTree(config);
    }

    private ObjectNode buildConfigDiff(
            ChatConfigDtos.ChatRuntimeConfigResponse before,
            ChatConfigDtos.ChatRuntimeConfigResponse after
    ) {
        ObjectNode diff = objectMapper.createObjectNode();
        ArrayNode changedFields = diff.putArray("changed_fields");
        ObjectNode changes = diff.putObject("changes");
        addDiff(changedFields, changes, "enabled", before.enabled(), after.enabled());
        addDiff(changedFields, changes, "mode", before.mode(), after.mode());
        addDiff(changedFields, changes, "generationStrategies", before.generationStrategies(), after.generationStrategies());
        addDiff(changedFields, changes, "gatingPreset", before.gatingPreset(), after.gatingPreset());
        addDiff(changedFields, changes, "sourceGatingBatchId", before.sourceGatingBatchId(), after.sourceGatingBatchId());
        addDiff(changedFields, changes, "sourceGatingRunId", before.sourceGatingRunId(), after.sourceGatingRunId());
        addDiff(changedFields, changes, "sourceGatingBatchIds", before.sourceGatingBatchIds(), after.sourceGatingBatchIds());
        addDiff(changedFields, changes, "sourceGatingRunIds", before.sourceGatingRunIds(), after.sourceGatingRunIds());
        addDiff(changedFields, changes, "rewriteQueryProfile", before.rewriteQueryProfile(), after.rewriteQueryProfile());
        addDiff(
                changedFields,
                changes,
                "rewriteAnchorInjectionEnabled",
                before.rewriteAnchorInjectionEnabled(),
                after.rewriteAnchorInjectionEnabled()
        );
        addDiff(changedFields, changes, "useSessionContext", before.useSessionContext(), after.useSessionContext());
        addDiff(changedFields, changes, "retrievalBackend", before.retrievalBackend(), after.retrievalBackend());
        addDiff(changedFields, changes, "denseEmbeddingModel", before.denseEmbeddingModel(), after.denseEmbeddingModel());
        addDiff(changedFields, changes, "retrieverMode", before.retrieverMode(), after.retrieverMode());
        addDiff(changedFields, changes, "retrieverCandidatePoolK", before.retrieverCandidatePoolK(), after.retrieverCandidatePoolK());
        addDiff(changedFields, changes, "retrieverDenseWeight", before.retrieverDenseWeight(), after.retrieverDenseWeight());
        addDiff(changedFields, changes, "retrieverBm25Weight", before.retrieverBm25Weight(), after.retrieverBm25Weight());
        addDiff(changedFields, changes, "retrieverTechnicalWeight", before.retrieverTechnicalWeight(), after.retrieverTechnicalWeight());
        addDiff(changedFields, changes, "retrievalTopK", before.retrievalTopK(), after.retrievalTopK());
        addDiff(changedFields, changes, "rerankTopN", before.rerankTopN(), after.rerankTopN());
        addDiff(changedFields, changes, "memoryTopN", before.memoryTopN(), after.memoryTopN());
        addDiff(changedFields, changes, "rewriteCandidateCount", before.rewriteCandidateCount(), after.rewriteCandidateCount());
        addDiff(changedFields, changes, "rewriteThreshold", before.rewriteThreshold(), after.rewriteThreshold());
        addDiff(changedFields, changes, "rewriteFailurePolicy", before.rewriteFailurePolicy(), after.rewriteFailurePolicy());
        addDiff(changedFields, changes, "routerEnabled", before.routerEnabled(), after.routerEnabled());
        addDiff(changedFields, changes, "metadata", before.metadata(), after.metadata());
        diff.put("changed_count", changedFields.size());
        return diff;
    }

    private ObjectNode mutableMetadata(JsonNode metadata) {
        if (metadata != null && metadata.isObject()) {
            return metadata.deepCopy();
        }
        return objectMapper.createObjectNode();
    }

    private void addDiff(ArrayNode changedFields, ObjectNode changes, String field, Object before, Object after) {
        if (Objects.equals(before, after)) {
            return;
        }
        changedFields.add(field);
        ObjectNode change = changes.putObject(field);
        change.set("before", objectMapper.valueToTree(before));
        change.set("after", objectMapper.valueToTree(after));
    }

    private List<UUID> requestedSourceGatingBatchIds(
            ChatConfigDtos.ChatRuntimeConfigRequest request,
            ChatConfigDtos.ChatRuntimeConfigResponse current
    ) {
        boolean provided = request.sourceGatingBatchIds() != null || request.sourceGatingBatchId() != null;
        List<UUID> values = provided
                ? GatingSnapshotDtos.distinctIds(request.sourceGatingBatchIds())
                : GatingSnapshotDtos.distinctIds(current.sourceGatingBatchIds());
        UUID fallback = provided ? request.sourceGatingBatchId() : current.sourceGatingBatchId();
        return GatingSnapshotDtos.withLegacyId(values, fallback);
    }

    private void validateSnapshot(
            UUID domainId,
            String gatingPreset,
            List<String> generationStrategies,
            GatingSnapshotDtos.GatingSnapshot snapshot
    ) {
        if (!"completed".equalsIgnoreCase(snapshot.status())) {
            throw new IllegalArgumentException("gating batch must be completed");
        }
        if (snapshot.domainId() == null || !snapshot.domainId().equals(domainId)) {
            throw new IllegalArgumentException("gating batch does not belong to selected domain");
        }
        if (snapshot.sourceGatingRunId() == null) {
            throw new IllegalArgumentException("gating batch has no source_gating_run_id");
        }
        if (!snapshot.gatingPreset().equalsIgnoreCase(gatingPreset)) {
            throw new IllegalArgumentException("gatingPreset must match selected snapshot");
        }
        if (snapshot.methodCode() != null
                && !generationStrategies.isEmpty()
                && !generationStrategies.contains(snapshot.methodCode().toUpperCase(Locale.ROOT))) {
            throw new IllegalArgumentException("selected snapshot method is not included in generationStrategies");
        }
    }

    private String normalizeAllowed(String raw, String fallback, Set<String> allowed, String fieldName) {
        String value = raw == null || raw.isBlank() ? fallback : raw.trim().toLowerCase(Locale.ROOT);
        if (!allowed.contains(value)) {
            throw new IllegalArgumentException("unsupported " + fieldName + ": " + value);
        }
        return value;
    }

    private void validateCatalogSelection(String value, List<String> allowedValues, String fieldName) {
        if (allowedValues == null || allowedValues.isEmpty()) {
            return;
        }
        if (!allowedValues.contains(value)) {
            throw new IllegalArgumentException(fieldName + " is not allowed by runtime catalog: " + value);
        }
    }

    private void validateDbAnnReadiness(String retrievalBackend, String retrieverMode, String denseEmbeddingModel) {
        if (!"db_ann".equals(retrievalBackend)) {
            return;
        }
        if ("bm25_only".equals(retrieverMode)) {
            throw new IllegalArgumentException("db_ann retrievalBackend requires retrieverMode=dense_only or hybrid");
        }
        if (denseEmbeddingModel == null || denseEmbeddingModel.isBlank()) {
            throw new IllegalArgumentException("db_ann retrievalBackend requires denseEmbeddingModel");
        }
        ChunkEmbeddingAdminDtos.ChunkEmbeddingMaterializationStatusResponse status =
                chunkEmbeddingService.getChunkEmbeddingMaterializationStatus(denseEmbeddingModel);
        if (!status.ready()) {
            throw new IllegalArgumentException(
                    "chunk embeddings are not materialized for chat db_ann retrieval: embedding_model="
                            + denseEmbeddingModel
                            + ", materialized_chunks=" + status.materializedChunkCount()
                            + ", total_chunks=" + status.totalChunkCount()
            );
        }
    }

    private List<String> normalizeStrategies(List<String> rawValues, List<String> enabledMethods) {
        Set<String> enabled = new LinkedHashSet<>();
        for (String method : enabledMethods) {
            if (method != null && !method.isBlank()) {
                enabled.add(method.trim().toUpperCase(Locale.ROOT));
            }
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String raw : rawValues == null ? List.<String>of() : rawValues) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            String value = raw.trim().toUpperCase(Locale.ROOT);
            if (!enabled.contains(value)) {
                throw new IllegalArgumentException("generation strategy is not enabled for domain: " + value);
            }
            normalized.add(value);
        }
        if (normalized.isEmpty()) {
            normalized.addAll(enabled);
        }
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("at least one generation strategy is required");
        }
        return List.copyOf(normalized);
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
