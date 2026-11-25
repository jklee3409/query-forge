package io.queryforge.backend.rag.runtime.readiness;

import io.queryforge.backend.rag.runtime.ChatConfigDtos;
import io.queryforge.backend.rag.runtime.ChatRuntimeConfigService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ChatReadinessService {

    private final ChatRuntimeConfigService configService;
    private final ChatReadinessRepository repository;
    private static final String DEFAULT_DENSE_EMBEDDING_MODEL = "intfloat/multilingual-e5-small";

    public ChatReadinessDtos.ChatDomainReadinessResponse getReadiness(UUID domainId) {
        ChatConfigDtos.ChatRuntimeConfigResponse config = configService.getConfig(domainId);
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

        List<UUID> selectedBatchIds = normalizeUuidList(config.sourceGatingBatchIds());
        if (selectedBatchIds.isEmpty() && config.sourceGatingBatchId() != null) {
            selectedBatchIds = List.of(config.sourceGatingBatchId());
        }
        List<UUID> configSourceGatingRunIds = normalizeUuidList(config.sourceGatingRunIds());
        if (configSourceGatingRunIds.isEmpty() && config.sourceGatingRunId() != null) {
            configSourceGatingRunIds = List.of(config.sourceGatingRunId());
        }
        List<ChatReadinessRepository.GatingSnapshot> snapshots = new ArrayList<>();
        for (UUID selectedBatchId : selectedBatchIds) {
            repository.findGatingSnapshot(selectedBatchId).ifPresent(snapshots::add);
        }
        ChatReadinessRepository.GatingSnapshot snapshot = snapshots.isEmpty() ? null : snapshots.getFirst();
        boolean selectedSnapshotPresent = !selectedBatchIds.isEmpty() && snapshots.size() == selectedBatchIds.size();
        LinkedHashSet<UUID> snapshotSourceRunSet = new LinkedHashSet<>();
        LinkedHashSet<String> snapshotMethods = new LinkedHashSet<>();
        for (ChatReadinessRepository.GatingSnapshot item : snapshots) {
            if (item.sourceGatingRunId() != null) {
                snapshotSourceRunSet.add(item.sourceGatingRunId());
            }
            String method = item.methodCode() == null ? "" : item.methodCode().trim().toUpperCase(Locale.ROOT);
            if (!method.isBlank()) {
                snapshotMethods.add(method);
            }
        }
        UUID snapshotSourceGatingRunId = snapshot == null ? null : snapshot.sourceGatingRunId();
        boolean sourceGatingRunPresent = selectedSnapshotPresent
                && !snapshots.isEmpty()
                && snapshots.stream().allMatch(item -> item.sourceGatingRunId() != null)
                && !configSourceGatingRunIds.isEmpty();
        boolean sourceGatingRunMatchesConfig = sourceGatingRunPresent
                && new LinkedHashSet<>(configSourceGatingRunIds).equals(snapshotSourceRunSet);
        boolean domainMismatch = selectedSnapshotPresent
                && snapshots.stream().anyMatch(item -> item.domainId() == null || !item.domainId().equals(config.domainId()));
        boolean gatingPresetMismatch = selectedSnapshotPresent
                && snapshots.stream().anyMatch(item -> !normalizeText(item.gatingPreset(), "").equals(normalizeText(config.gatingPreset(), "")));
        String snapshotMethod = snapshotMethods.isEmpty() ? null : String.join("+", snapshotMethods);
        boolean generationStrategyMismatch = !snapshotMethods.isEmpty()
                && snapshotMethods.stream().anyMatch(method -> !generationStrategies.contains(method));
        boolean snapshotStatusMismatch = selectedSnapshotPresent
                && snapshots.stream().anyMatch(item -> !"completed".equalsIgnoreCase(item.status()));
        String snapshotStatus = snapshots.isEmpty()
                ? null
                : (snapshots.stream().allMatch(item -> "completed".equalsIgnoreCase(item.status()))
                ? "completed"
                : "mixed");

        String embeddingModel = firstNonBlank(config.denseEmbeddingModel(), DEFAULT_DENSE_EMBEDDING_MODEL);
        ChatReadinessRepository.DomainChunkEmbeddingStatus embeddingStatus =
                repository.findDomainChunkEmbeddingStatus(config.domainId(), embeddingModel);
        long domainChunkCount = embeddingStatus == null ? 0L : embeddingStatus.domainChunkCount();
        long materializedChunkCount = embeddingStatus == null ? 0L : embeddingStatus.materializedChunkCount();
        long missingChunkCount = Math.max(0L, domainChunkCount - materializedChunkCount);
        String retrievalBackend = normalizeText(config.retrievalBackend(), "local");
        String retrieverMode = normalizeText(config.retrieverMode(), "hybrid");
        boolean dbAnnRequired = "db_ann".equals(retrievalBackend);
        boolean chunkEmbeddingsReady = !dbAnnRequired || (domainChunkCount > 0L && missingChunkCount == 0L);

        long acceptedGatedQueryCount = rewriteBackedMode && selectedSnapshotPresent
                ? repository.countAcceptedGatedQueries(config.domainId(), selectedBatchIds, generationStrategies)
                : 0L;
        long memoryCount = rewriteBackedMode && selectedSnapshotPresent
                ? repository.countReadyMemoryEntries(
                config.domainId(),
                config.gatingPreset(),
                generationStrategies,
                configSourceGatingRunIds,
                selectedBatchIds
        )
                : 0L;

        String promptBindingKey = promptBindingKey(config.rewriteQueryProfile());
        ChatReadinessDtos.PromptBindingReadiness promptBinding = repository.findPromptBinding(promptBindingKey)
                .map(row -> new ChatReadinessDtos.PromptBindingReadiness(
                        row.bindingKey(),
                        row.active(),
                        row.activePromptAssetId(),
                        row.activePromptName(),
                        row.activePromptVersion(),
                        row.activeContentHash()
                ))
                .orElseGet(() -> new ChatReadinessDtos.PromptBindingReadiness(
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
            if (selectedBatchIds.isEmpty()) {
                blockingReasons.add("at least one source_gating_batch_id is required for rewrite-backed chat");
            }
            if (!selectedSnapshotPresent) {
                blockingReasons.add("one or more selected source gating snapshots were not found");
            } else {
                if (snapshotStatusMismatch) {
                    blockingReasons.add("one or more selected source gating snapshots are not completed");
                }
                if (!sourceGatingRunPresent) {
                    blockingReasons.add("source_gating_run_id is missing for one or more selected snapshots");
                } else if (!sourceGatingRunMatchesConfig) {
                    blockingReasons.add("config source_gating_run_ids do not match selected snapshots");
                }
                if (domainMismatch) {
                    blockingReasons.add("one or more selected snapshots belong to another domain");
                }
                if (gatingPresetMismatch) {
                    blockingReasons.add("one or more selected snapshot gating presets differ from active config");
                }
                if (generationStrategyMismatch) {
                    blockingReasons.add("one or more selected snapshot generation strategies are not enabled in active config");
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
        return new ChatReadinessDtos.ChatDomainReadinessResponse(
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
                new ChatReadinessDtos.SnapshotReadiness(
                        config.sourceGatingBatchId(),
                        config.sourceGatingRunId(),
                        selectedBatchIds,
                        configSourceGatingRunIds,
                        selectedBatchIds.size(),
                        snapshotSourceGatingRunId,
                        selectedSnapshotPresent,
                        snapshotStatus,
                        snapshot == null ? null : snapshot.domainId(),
                        snapshot == null ? null : snapshot.gatingPreset(),
                        snapshotMethod,
                        sourceGatingRunPresent,
                        sourceGatingRunMatchesConfig,
                        domainMismatch,
                        gatingPresetMismatch,
                        generationStrategyMismatch
                ),
                new ChatReadinessDtos.ChunkEmbeddingReadiness(
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
                new ChatReadinessDtos.RetrievalReadiness(
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

    private List<UUID> normalizeUuidList(List<UUID> rawValues) {
        if (rawValues == null || rawValues.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<UUID> values = new LinkedHashSet<>();
        for (UUID value : rawValues) {
            if (value != null) {
                values.add(value);
            }
        }
        return List.copyOf(values);
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

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }
}
