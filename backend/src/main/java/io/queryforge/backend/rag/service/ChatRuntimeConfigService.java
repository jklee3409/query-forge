package io.queryforge.backend.rag.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.queryforge.backend.admin.console.model.AdminConsoleDtos;
import io.queryforge.backend.admin.console.service.AdminConsoleService;
import io.queryforge.backend.rag.model.ChatRuntimeDtos;
import io.queryforge.backend.rag.repository.ChatRuntimeConfigRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ChatRuntimeConfigService {

    private static final Set<String> MODES = Set.of(
            "raw_only",
            "selective_rewrite",
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
    private static final String DEFAULT_DENSE_EMBEDDING_MODEL = "intfloat/multilingual-e5-small";

    private final ChatRuntimeConfigRepository repository;
    private final AdminConsoleService adminConsoleService;
    private final ObjectMapper objectMapper;

    public List<ChatRuntimeDtos.ChatDomainOption> listChatDomains() {
        return repository.findActiveDomains();
    }

    public ChatRuntimeDtos.ChatRuntimeConfigResponse getConfig(UUID domainId) {
        if (domainId == null) {
            throw new IllegalArgumentException("domainId is required");
        }
        return repository.findConfig(domainId)
                .orElseThrow(() -> new IllegalArgumentException("active domain not found: " + domainId));
    }

    public ChatRuntimeDtos.ChatDomainReadinessResponse getReadiness(UUID domainId) {
        ChatRuntimeDtos.ChatRuntimeConfigResponse config = getConfig(domainId);
        boolean activeConfigPresent = repository.existsConfig(domainId);
        String mode = normalizeText(config.mode(), "selective_rewrite");
        boolean rewriteBackedMode = !"raw_only".equals(mode);
        List<String> generationStrategies = config.generationStrategies() == null
                ? List.of()
                : config.generationStrategies().stream()
                .filter(value -> value != null && !value.isBlank())
                .map(value -> value.trim().toUpperCase(Locale.ROOT))
                .distinct()
                .toList();

        ChatRuntimeConfigRepository.GatingSnapshot snapshot = config.sourceGatingBatchId() == null
                ? null
                : repository.findGatingSnapshot(config.sourceGatingBatchId()).orElse(null);
        boolean selectedSnapshotPresent = snapshot != null;
        UUID snapshotSourceGatingRunId = snapshot == null ? null : snapshot.sourceGatingRunId();
        boolean sourceGatingRunPresent = config.sourceGatingRunId() != null && snapshotSourceGatingRunId != null;
        boolean sourceGatingRunMatchesConfig = sourceGatingRunPresent
                && Objects.equals(config.sourceGatingRunId(), snapshotSourceGatingRunId);
        boolean domainMismatch = selectedSnapshotPresent
                && (snapshot.domainId() == null || !snapshot.domainId().equals(config.domainId()));
        boolean gatingPresetMismatch = selectedSnapshotPresent
                && !normalizeText(snapshot.gatingPreset(), "").equals(normalizeText(config.gatingPreset(), ""));
        String snapshotMethod = snapshot == null || snapshot.methodCode() == null
                ? null
                : snapshot.methodCode().trim().toUpperCase(Locale.ROOT);
        boolean generationStrategyMismatch = snapshotMethod != null
                && !snapshotMethod.isBlank()
                && !generationStrategies.contains(snapshotMethod);

        String embeddingModel = firstNonBlank(config.denseEmbeddingModel(), DEFAULT_DENSE_EMBEDDING_MODEL);
        ChatRuntimeConfigRepository.DomainChunkEmbeddingStatus embeddingStatus =
                repository.findDomainChunkEmbeddingStatus(config.domainId(), embeddingModel);
        long domainChunkCount = embeddingStatus == null ? 0L : embeddingStatus.domainChunkCount();
        long materializedChunkCount = embeddingStatus == null ? 0L : embeddingStatus.materializedChunkCount();
        long missingChunkCount = Math.max(0L, domainChunkCount - materializedChunkCount);
        String retrievalBackend = normalizeText(config.retrievalBackend(), "local");
        String retrieverMode = normalizeText(config.retrieverMode(), "hybrid");
        boolean dbAnnRequired = "db_ann".equals(retrievalBackend);
        boolean chunkEmbeddingsReady = !dbAnnRequired || (domainChunkCount > 0L && missingChunkCount == 0L);

        long acceptedGatedQueryCount = rewriteBackedMode && selectedSnapshotPresent
                ? repository.countAcceptedGatedQueries(config.domainId(), config.sourceGatingBatchId(), generationStrategies)
                : 0L;
        long memoryCount = rewriteBackedMode && selectedSnapshotPresent
                ? repository.countReadyMemoryEntries(
                config.domainId(),
                config.gatingPreset(),
                generationStrategies,
                config.sourceGatingRunId(),
                config.sourceGatingBatchId()
        )
                : 0L;

        String promptBindingKey = promptBindingKey(config.rewriteQueryProfile());
        ChatRuntimeDtos.PromptBindingReadiness promptBinding = repository.findPromptBinding(promptBindingKey)
                .map(row -> new ChatRuntimeDtos.PromptBindingReadiness(
                        row.bindingKey(),
                        row.active(),
                        row.activePromptAssetId(),
                        row.activePromptName(),
                        row.activePromptVersion(),
                        row.activeContentHash()
                ))
                .orElseGet(() -> new ChatRuntimeDtos.PromptBindingReadiness(
                        promptBindingKey,
                        false,
                        null,
                        null,
                        null,
                        null
                ));

        List<String> blockingReasons = new ArrayList<>();
        if (!activeConfigPresent) {
            blockingReasons.add("active chat_runtime_config is missing");
        }
        if (!config.enabled()) {
            blockingReasons.add("chat config is disabled");
        }
        if (generationStrategies.isEmpty()) {
            blockingReasons.add("no generation strategy is selected for this domain");
        }
        if (dbAnnRequired && "bm25_only".equals(retrieverMode)) {
            blockingReasons.add("db_ann retrieval backend requires dense_only or hybrid retriever mode");
        }
        if (dbAnnRequired && !chunkEmbeddingsReady) {
            blockingReasons.add("domain chunk embeddings are not fully materialized for " + embeddingModel);
        }
        if (rewriteBackedMode) {
            if (config.sourceGatingBatchId() == null) {
                blockingReasons.add("source_gating_batch_id is required for rewrite-backed chat");
            }
            if (!selectedSnapshotPresent) {
                blockingReasons.add("selected source gating snapshot was not found");
            } else {
                if (!"completed".equalsIgnoreCase(snapshot.status())) {
                    blockingReasons.add("selected source gating snapshot is not completed");
                }
                if (!sourceGatingRunPresent) {
                    blockingReasons.add("source_gating_run_id is missing");
                } else if (!sourceGatingRunMatchesConfig) {
                    blockingReasons.add("config source_gating_run_id does not match selected snapshot");
                }
                if (domainMismatch) {
                    blockingReasons.add("selected snapshot belongs to another domain");
                }
                if (gatingPresetMismatch) {
                    blockingReasons.add("selected snapshot gating preset differs from active config");
                }
                if (generationStrategyMismatch) {
                    blockingReasons.add("selected snapshot generation strategy is not enabled in active config");
                }
            }
            if (!promptBinding.active()) {
                blockingReasons.add("active rewrite prompt binding is missing or inactive: " + promptBindingKey);
            }
            if (acceptedGatedQueryCount <= 0L) {
                blockingReasons.add("selected snapshot has no accepted gated queries for this domain/config");
            }
            if (memoryCount <= 0L) {
                blockingReasons.add("selected snapshot has no built synthetic memory for this domain/config");
            }
        }

        boolean readyForRewrite = blockingReasons.isEmpty();
        return new ChatRuntimeDtos.ChatDomainReadinessResponse(
                config.domainId(),
                config.domainKey(),
                config.displayName(),
                config.sourceLanguage(),
                activeConfigPresent,
                config.enabled(),
                mode,
                rewriteBackedMode,
                generationStrategies,
                config.gatingPreset(),
                new ChatRuntimeDtos.SnapshotReadiness(
                        config.sourceGatingBatchId(),
                        config.sourceGatingRunId(),
                        snapshotSourceGatingRunId,
                        selectedSnapshotPresent,
                        snapshot == null ? null : snapshot.status(),
                        snapshot == null ? null : snapshot.domainId(),
                        snapshot == null ? null : snapshot.gatingPreset(),
                        snapshotMethod,
                        sourceGatingRunPresent,
                        sourceGatingRunMatchesConfig,
                        domainMismatch,
                        gatingPresetMismatch,
                        generationStrategyMismatch
                ),
                new ChatRuntimeDtos.ChunkEmbeddingReadiness(
                        dbAnnRequired,
                        embeddingModel,
                        domainChunkCount,
                        materializedChunkCount,
                        missingChunkCount,
                        chunkEmbeddingsReady,
                        embeddingStatus == null ? null : embeddingStatus.latestUpdatedAt()
                ),
                memoryCount,
                acceptedGatedQueryCount,
                promptBinding,
                new ChatRuntimeDtos.RetrievalReadiness(
                        config.retrievalBackend(),
                        embeddingModel,
                        config.retrieverMode(),
                        config.retrieverCandidatePoolK(),
                        config.retrieverDenseWeight(),
                        config.retrieverBm25Weight(),
                        config.retrieverTechnicalWeight()
                ),
                readyForRewrite,
                List.copyOf(blockingReasons),
                Instant.now()
        );
    }

    public List<ChatRuntimeDtos.ChatRuntimeConfigProvenanceRow> listConfigProvenance(UUID domainId, Integer limit) {
        if (domainId == null) {
            throw new IllegalArgumentException("domainId is required");
        }
        return repository.findProvenance(domainId, limit);
    }

    @Transactional
    public ChatRuntimeDtos.ChatRuntimeConfigResponse updateConfig(ChatRuntimeDtos.ChatRuntimeConfigRequest request) {
        return updateConfig(request, "manual", null, objectMapper.createObjectNode());
    }

    private ChatRuntimeDtos.ChatRuntimeConfigResponse updateConfig(
            ChatRuntimeDtos.ChatRuntimeConfigRequest request,
            String changeSource,
            UUID sourceRagTestRunId,
            JsonNode sourceConfig
    ) {
        if (request == null || request.domainId() == null) {
            throw new IllegalArgumentException("domainId is required");
        }
        ChatRuntimeDtos.ChatRuntimeConfigResponse current = getConfig(request.domainId());
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
        AdminConsoleDtos.RuntimeOptionsResponse runtimeOptions = adminConsoleService.getRuntimeOptions();
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
        int retrieverCandidatePoolK = bounded(
                request.retrieverCandidatePoolK(),
                current.retrieverCandidatePoolK(),
                1,
                500,
                "retrieverCandidatePoolK"
        );
        double retrieverDenseWeight = boundedDouble(
                request.retrieverDenseWeight(),
                current.retrieverDenseWeight(),
                0.0,
                1.0,
                "retrieverDenseWeight"
        );
        double retrieverBm25Weight = boundedDouble(
                request.retrieverBm25Weight(),
                current.retrieverBm25Weight(),
                0.0,
                1.0,
                "retrieverBm25Weight"
        );
        double retrieverTechnicalWeight = boundedDouble(
                request.retrieverTechnicalWeight(),
                current.retrieverTechnicalWeight(),
                0.0,
                1.0,
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
        int retrievalTopK = bounded(request.retrievalTopK(), current.retrievalTopK(), 1, 100, "retrievalTopK");
        int rerankTopN = bounded(request.rerankTopN(), current.rerankTopN(), 1, 100, "rerankTopN");
        int memoryTopN = bounded(request.memoryTopN(), current.memoryTopN(), 1, 50, "memoryTopN");
        int rewriteCandidateCount = bounded(
                request.rewriteCandidateCount(),
                current.rewriteCandidateCount(),
                1,
                2,
                "rewriteCandidateCount"
        );
        double rewriteThreshold = boundedDouble(
                request.rewriteThreshold(),
                current.rewriteThreshold(),
                0.0,
                1.0,
                "rewriteThreshold"
        );
        UUID sourceGatingBatchId = request.sourceGatingBatchId();
        UUID sourceGatingRunId = null;
        if ("raw_only".equals(mode)) {
            sourceGatingBatchId = null;
        } else {
            if (sourceGatingBatchId == null) {
                throw new IllegalArgumentException("sourceGatingBatchId is required for rewrite-backed chat");
            }
            UUID selectedBatchId = sourceGatingBatchId;
            ChatRuntimeConfigRepository.GatingSnapshot snapshot = repository.findGatingSnapshot(sourceGatingBatchId)
                    .orElseThrow(() -> new IllegalArgumentException("gating batch not found: " + selectedBatchId));
            validateSnapshot(request.domainId(), gatingPreset, generationStrategies, snapshot);
            sourceGatingRunId = snapshot.sourceGatingRunId();
            gatingPreset = snapshot.gatingPreset();
        }
        JsonNode metadata = request.metadata() == null ? objectMapper.createObjectNode() : request.metadata();
        repository.upsertConfig(
                request.domainId(),
                enabled,
                mode,
                generationStrategies,
                gatingPreset,
                sourceGatingBatchId,
                sourceGatingRunId,
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
                retrievalTopK,
                rerankTopN,
                memoryTopN,
                rewriteCandidateCount,
                rewriteThreshold,
                rewriteFailurePolicy,
                metadata,
                blankToNull(request.updatedBy())
        );
        ChatRuntimeDtos.ChatRuntimeConfigResponse applied = getConfig(request.domainId());
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

    @Transactional
    public ChatRuntimeDtos.ChatRuntimeConfigResponse applyRagRunConfig(
            ChatRuntimeDtos.ApplyChatConfigFromRagRunRequest request
    ) {
        if (request == null || request.ragTestRunId() == null) {
            throw new IllegalArgumentException("ragTestRunId is required");
        }
        ChatRuntimeConfigRepository.RagRunApplySnapshot run = repository.findRagRunApplySnapshot(request.ragTestRunId())
                .orElseThrow(() -> new IllegalArgumentException("rag test run not found: " + request.ragTestRunId()));
        if (!"completed".equalsIgnoreCase(run.status())) {
            throw new IllegalArgumentException("only completed RAG test runs can be applied to chat");
        }
        if (run.domainId() == null) {
            throw new IllegalArgumentException("RAG test run has no domain_id; rerun it inside a domain workspace");
        }

        ChatRuntimeDtos.ChatRuntimeConfigResponse current = getConfig(run.domainId());
        JsonNode config = run.configJson() == null ? objectMapper.createObjectNode() : run.configJson();
        boolean rewriteEnabled = configBoolean(run.rewriteEnabled(), config, "rewrite_enabled", false);
        boolean selectiveRewrite = configBoolean(run.selectiveRewrite(), config, "selective_rewrite", true);
        boolean useSessionContext = configBoolean(run.useSessionContext(), config, "use_session_context", false);
        String mode = modeFromRagRun(rewriteEnabled, selectiveRewrite, useSessionContext);
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
        JsonNode retrieverConfig = config.path("retriever_config");
        JsonNode fusionWeights = firstPresentObject(
                retrieverConfig.path("retriever_fusion_weights"),
                config.path("retriever_fusion_weights")
        );
        ChatRuntimeDtos.ChatRuntimeConfigRequest applyRequest = new ChatRuntimeDtos.ChatRuntimeConfigRequest(
                run.domainId(),
                true,
                mode,
                generationStrategies,
                gatingPreset,
                sourceGatingBatchId,
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
                current.metadata() == null ? objectMapper.createObjectNode() : current.metadata(),
                blankToNull(request.updatedBy())
        );
        return updateConfig(
                applyRequest,
                "apply_rag_run",
                run.ragTestRunId(),
                sourceConfigFromRagRun(run, config)
        );
    }

    private ObjectNode sourceConfigFromRagRun(
            ChatRuntimeConfigRepository.RagRunApplySnapshot run,
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

    private JsonNode configSnapshot(ChatRuntimeDtos.ChatRuntimeConfigResponse config) {
        return objectMapper.valueToTree(config);
    }

    private ObjectNode buildConfigDiff(
            ChatRuntimeDtos.ChatRuntimeConfigResponse before,
            ChatRuntimeDtos.ChatRuntimeConfigResponse after
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
        addDiff(changedFields, changes, "metadata", before.metadata(), after.metadata());
        diff.put("changed_count", changedFields.size());
        return diff;
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

    private void validateSnapshot(
            UUID domainId,
            String gatingPreset,
            List<String> generationStrategies,
            ChatRuntimeConfigRepository.GatingSnapshot snapshot
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

    private String promptBindingKey(String rewriteQueryProfile) {
        String profile = rewriteQueryProfile == null ? "" : rewriteQueryProfile.trim().toLowerCase(Locale.ROOT);
        if ("detailed_intent".equals(profile)) {
            return "rag_rewrite.detailed_intent.ko";
        }
        return "rag_rewrite.ko";
    }

    private String normalizeText(String value, String fallback) {
        String normalized = value == null || value.isBlank() ? fallback : value;
        return normalized == null ? "" : normalized.trim().toLowerCase(Locale.ROOT).replace("-", "_");
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
        AdminConsoleDtos.ChunkEmbeddingMaterializationStatusResponse status =
                adminConsoleService.getChunkEmbeddingMaterializationStatus(denseEmbeddingModel);
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

    private int bounded(Integer raw, int fallback, int min, int max, String fieldName) {
        int value = raw == null ? fallback : raw;
        if (value < min || value > max) {
            throw new IllegalArgumentException(fieldName + " must be between " + min + " and " + max);
        }
        return value;
    }

    private double boundedDouble(Double raw, double fallback, double min, double max, String fieldName) {
        double value = raw == null ? fallback : raw;
        if (value < min || value > max) {
            throw new IllegalArgumentException(fieldName + " must be between " + min + " and " + max);
        }
        return value;
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
