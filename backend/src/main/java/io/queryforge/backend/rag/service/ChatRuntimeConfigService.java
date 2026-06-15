package io.queryforge.backend.rag.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.queryforge.backend.rag.model.ChatRuntimeDtos;
import io.queryforge.backend.rag.repository.ChatRuntimeConfigRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

    private final ChatRuntimeConfigRepository repository;
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

    private double configDouble(JsonNode config, String field, double fallback) {
        JsonNode value = config == null ? null : config.get(field);
        return value == null || value.isNull() ? fallback : value.asDouble(fallback);
    }

    private String configText(JsonNode config, String field, String fallback) {
        JsonNode value = config == null ? null : config.get(field);
        String raw = value == null || value.isNull() ? null : value.asText();
        return raw == null || raw.isBlank() ? fallback : raw.trim();
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

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
