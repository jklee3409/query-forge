package io.queryforge.backend.rag.runtime.configuration.repository;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.queryforge.backend.rag.runtime.configuration.dto.ChatConfigDtos;
import io.queryforge.backend.rag.runtime.configuration.model.RagExecutionSettings;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

@Repository
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ChatRuntimeConfigRepository {

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public List<ChatConfigDtos.ChatDomainOption> findActiveDomains() {
        String sql = """
                SELECT d.domain_id,
                       d.domain_key,
                       d.display_name,
                       d.source_language,
                       COALESCE(c.enabled, FALSE) AS chat_enabled
                FROM tech_doc_domain d
                LEFT JOIN chat_runtime_config c ON c.domain_id = d.domain_id
                WHERE d.status = 'active'
                ORDER BY d.display_name, d.domain_key
                """;
        return jdbcTemplate.query(sql, (rs, rowNum) -> new ChatConfigDtos.ChatDomainOption(
                readUuid(rs, "domain_id"),
                rs.getString("domain_key"),
                rs.getString("display_name"),
                rs.getString("source_language"),
                rs.getBoolean("chat_enabled")
        ));
    }

    public Optional<ChatConfigDtos.ChatRuntimeConfigResponse> findConfig(
            UUID domainId,
            Supplier<RagExecutionSettings> defaultSettings
    ) {
        String sql = """
                SELECT d.domain_id,
                       d.domain_key,
                       d.display_name,
                       d.source_language,
                       COALESCE(c.enabled, TRUE) AS enabled,
                       COALESCE(c.mode, 'selective_rewrite') AS mode,
                       COALESCE(c.generation_strategies, methods.method_codes, '[]'::jsonb)::text AS generation_strategies,
                       COALESCE(c.gating_preset, 'full_gating') AS gating_preset,
                       c.source_gating_batch_id,
                       c.source_gating_run_id,
                       COALESCE(
                           c.source_gating_batch_ids,
                           CASE
                               WHEN c.source_gating_batch_id IS NULL THEN '[]'::jsonb
                               ELSE jsonb_build_array(c.source_gating_batch_id::text)
                           END
                       )::text AS source_gating_batch_ids,
                       COALESCE(
                           c.source_gating_run_ids,
                           CASE
                               WHEN c.source_gating_run_id IS NULL THEN '[]'::jsonb
                               ELSE jsonb_build_array(c.source_gating_run_id::text)
                           END
                       )::text AS source_gating_run_ids,
                       COALESCE(c.rewrite_query_profile, 'compact_anchor') AS rewrite_query_profile,
                       COALESCE(c.rewrite_anchor_injection_enabled, FALSE) AS rewrite_anchor_injection_enabled,
                       COALESCE(c.use_session_context, FALSE) AS use_session_context,
                       COALESCE(c.retrieval_backend, 'local') AS retrieval_backend,
                       COALESCE(c.dense_embedding_model, 'intfloat/multilingual-e5-small') AS dense_embedding_model,
                       COALESCE(c.retriever_mode, 'hybrid') AS retriever_mode,
                       COALESCE(c.retriever_candidate_pool_k, 50) AS retriever_candidate_pool_k,
                       COALESCE(c.retriever_dense_weight, 0.60) AS retriever_dense_weight,
                       COALESCE(c.retriever_bm25_weight, 0.32) AS retriever_bm25_weight,
                       COALESCE(c.retriever_technical_weight, 0.08) AS retriever_technical_weight,
                       c.retrieval_top_k,
                       c.rerank_top_n,
                       c.memory_top_n,
                       c.rewrite_candidate_count,
                       c.rewrite_threshold,
                       COALESCE(c.rewrite_failure_policy, 'heuristic_fallback') AS rewrite_failure_policy,
                       COALESCE(c.metadata_json, '{}'::jsonb)::text AS metadata_json,
                       COALESCE(c.updated_at, d.updated_at) AS updated_at
                FROM tech_doc_domain d
                LEFT JOIN chat_runtime_config c ON c.domain_id = d.domain_id
                LEFT JOIN LATERAL (
                    SELECT jsonb_agg(p.method_code ORDER BY p.method_code) AS method_codes
                    FROM tech_doc_domain_method_policy p
                    WHERE p.domain_id = d.domain_id
                      AND p.enabled IS TRUE
                ) methods ON TRUE
                WHERE d.domain_id = :domainId
                  AND d.status = 'active'
                """;
        return jdbcTemplate.query(sql, new MapSqlParameterSource("domainId", domainId),
                        (rs, rowNum) -> mapConfig(rs, defaultSettings))
                .stream()
                .findFirst();
    }

    public List<String> findEnabledMethodCodes(UUID domainId) {
        String sql = """
                SELECT method_code
                FROM tech_doc_domain_method_policy
                WHERE domain_id = :domainId
                  AND enabled IS TRUE
                ORDER BY method_code
                """;
        return jdbcTemplate.queryForList(sql, new MapSqlParameterSource("domainId", domainId), String.class);
    }

    @Transactional
    public void insertProvenance(
            UUID domainId,
            String changeSource,
            UUID sourceRagTestRunId,
            JsonNode sourceConfig,
            JsonNode previousConfig,
            JsonNode appliedConfig,
            JsonNode diff,
            String updatedBy
    ) {
        String sql = """
                INSERT INTO chat_runtime_config_provenance (
                    provenance_id,
                    domain_id,
                    change_source,
                    source_rag_test_run_id,
                    source_config_json,
                    previous_config_json,
                    applied_config_json,
                    diff_json,
                    updated_by
                ) VALUES (
                    gen_random_uuid(),
                    :domainId,
                    :changeSource,
                    :sourceRagTestRunId,
                    CAST(:sourceConfig AS jsonb),
                    CAST(:previousConfig AS jsonb),
                    CAST(:appliedConfig AS jsonb),
                    CAST(:diff AS jsonb),
                    :updatedBy
                )
                """;
        jdbcTemplate.update(
                sql,
                new MapSqlParameterSource()
                        .addValue("domainId", domainId)
                        .addValue("changeSource", changeSource)
                        .addValue("sourceRagTestRunId", sourceRagTestRunId)
                        .addValue("sourceConfig", jsonText(sourceConfig))
                        .addValue("previousConfig", jsonText(previousConfig))
                        .addValue("appliedConfig", jsonText(appliedConfig))
                        .addValue("diff", jsonText(diff))
                        .addValue("updatedBy", updatedBy)
        );
    }

    public List<ChatConfigDtos.ChatRuntimeConfigProvenanceRow> findProvenance(UUID domainId, Integer limit) {
        String sql = """
                SELECT p.provenance_id,
                       p.domain_id,
                       d.domain_key,
                       d.display_name,
                       p.change_source,
                       p.source_rag_test_run_id,
                       r.run_label AS source_rag_test_run_label,
                       p.source_config_json::text AS source_config_json,
                       p.previous_config_json::text AS previous_config_json,
                       p.applied_config_json::text AS applied_config_json,
                       p.diff_json::text AS diff_json,
                       p.updated_by,
                       p.created_at
                FROM chat_runtime_config_provenance p
                JOIN tech_doc_domain d
                  ON d.domain_id = p.domain_id
                LEFT JOIN rag_test_run r
                  ON r.rag_test_run_id = p.source_rag_test_run_id
                WHERE p.domain_id = :domainId
                ORDER BY p.created_at DESC
                LIMIT :limit
                """;
        return jdbcTemplate.query(
                sql,
                new MapSqlParameterSource()
                        .addValue("domainId", domainId)
                        .addValue("limit", normalizeLimit(limit, 50)),
                (rs, rowNum) -> new ChatConfigDtos.ChatRuntimeConfigProvenanceRow(
                        readUuid(rs, "provenance_id"),
                        readUuid(rs, "domain_id"),
                        rs.getString("domain_key"),
                        rs.getString("display_name"),
                        rs.getString("change_source"),
                        readUuid(rs, "source_rag_test_run_id"),
                        rs.getString("source_rag_test_run_label"),
                        readJson(rs.getString("source_config_json")),
                        readJson(rs.getString("previous_config_json")),
                        readJson(rs.getString("applied_config_json")),
                        readJson(rs.getString("diff_json")),
                        rs.getString("updated_by"),
                        readInstant(rs, "created_at")
                )
        );
    }

    @Transactional
    public void upsertConfig(
            UUID domainId,
            boolean enabled,
            String mode,
            List<String> generationStrategies,
            String gatingPreset,
            UUID sourceGatingBatchId,
            UUID sourceGatingRunId,
            List<UUID> sourceGatingBatchIds,
            List<UUID> sourceGatingRunIds,
            String rewriteQueryProfile,
            boolean rewriteAnchorInjectionEnabled,
            boolean useSessionContext,
            String retrievalBackend,
            String denseEmbeddingModel,
            String retrieverMode,
            int retrieverCandidatePoolK,
            double retrieverDenseWeight,
            double retrieverBm25Weight,
            double retrieverTechnicalWeight,
            int retrievalTopK,
            int rerankTopN,
            int memoryTopN,
            int rewriteCandidateCount,
            double rewriteThreshold,
            String rewriteFailurePolicy,
            JsonNode metadata,
            String updatedBy
    ) {
        String sql = """
                INSERT INTO chat_runtime_config (
                    domain_id,
                    enabled,
                    mode,
                    generation_strategies,
                    gating_preset,
                    source_gating_batch_id,
                    source_gating_run_id,
                    source_gating_batch_ids,
                    source_gating_run_ids,
                    rewrite_query_profile,
                    rewrite_anchor_injection_enabled,
                    use_session_context,
                    retrieval_backend,
                    dense_embedding_model,
                    retriever_mode,
                    retriever_candidate_pool_k,
                    retriever_dense_weight,
                    retriever_bm25_weight,
                    retriever_technical_weight,
                    retrieval_top_k,
                    rerank_top_n,
                    memory_top_n,
                    rewrite_candidate_count,
                    rewrite_threshold,
                    rewrite_failure_policy,
                    metadata_json,
                    updated_by,
                    updated_at
                ) VALUES (
                    :domainId,
                    :enabled,
                    :mode,
                    CAST(:generationStrategies AS jsonb),
                    :gatingPreset,
                    :sourceGatingBatchId,
                    :sourceGatingRunId,
                    CAST(:sourceGatingBatchIds AS jsonb),
                    CAST(:sourceGatingRunIds AS jsonb),
                    :rewriteQueryProfile,
                    :rewriteAnchorInjectionEnabled,
                    :useSessionContext,
                    :retrievalBackend,
                    :denseEmbeddingModel,
                    :retrieverMode,
                    :retrieverCandidatePoolK,
                    :retrieverDenseWeight,
                    :retrieverBm25Weight,
                    :retrieverTechnicalWeight,
                    :retrievalTopK,
                    :rerankTopN,
                    :memoryTopN,
                    :rewriteCandidateCount,
                    :rewriteThreshold,
                    :rewriteFailurePolicy,
                    CAST(:metadata AS jsonb),
                    :updatedBy,
                    NOW()
                )
                ON CONFLICT (domain_id) DO UPDATE
                SET enabled = EXCLUDED.enabled,
                    mode = EXCLUDED.mode,
                    generation_strategies = EXCLUDED.generation_strategies,
                    gating_preset = EXCLUDED.gating_preset,
                    source_gating_batch_id = EXCLUDED.source_gating_batch_id,
                    source_gating_run_id = EXCLUDED.source_gating_run_id,
                    source_gating_batch_ids = EXCLUDED.source_gating_batch_ids,
                    source_gating_run_ids = EXCLUDED.source_gating_run_ids,
                    rewrite_query_profile = EXCLUDED.rewrite_query_profile,
                    rewrite_anchor_injection_enabled = EXCLUDED.rewrite_anchor_injection_enabled,
                    use_session_context = EXCLUDED.use_session_context,
                    retrieval_backend = EXCLUDED.retrieval_backend,
                    dense_embedding_model = EXCLUDED.dense_embedding_model,
                    retriever_mode = EXCLUDED.retriever_mode,
                    retriever_candidate_pool_k = EXCLUDED.retriever_candidate_pool_k,
                    retriever_dense_weight = EXCLUDED.retriever_dense_weight,
                    retriever_bm25_weight = EXCLUDED.retriever_bm25_weight,
                    retriever_technical_weight = EXCLUDED.retriever_technical_weight,
                    retrieval_top_k = EXCLUDED.retrieval_top_k,
                    rerank_top_n = EXCLUDED.rerank_top_n,
                    memory_top_n = EXCLUDED.memory_top_n,
                    rewrite_candidate_count = EXCLUDED.rewrite_candidate_count,
                    rewrite_threshold = EXCLUDED.rewrite_threshold,
                    rewrite_failure_policy = EXCLUDED.rewrite_failure_policy,
                    metadata_json = EXCLUDED.metadata_json,
                    updated_by = EXCLUDED.updated_by,
                    updated_at = NOW()
                """;
        jdbcTemplate.update(
                sql,
                new MapSqlParameterSource()
                        .addValue("domainId", domainId)
                        .addValue("enabled", enabled)
                        .addValue("mode", mode)
                        .addValue("generationStrategies", objectMapper.valueToTree(generationStrategies).toString())
                        .addValue("gatingPreset", gatingPreset)
                        .addValue("sourceGatingBatchId", sourceGatingBatchId)
                        .addValue("sourceGatingRunId", sourceGatingRunId)
                        .addValue("sourceGatingBatchIds", objectMapper.valueToTree(uuidTextArray(sourceGatingBatchIds)).toString())
                        .addValue("sourceGatingRunIds", objectMapper.valueToTree(uuidTextArray(sourceGatingRunIds)).toString())
                        .addValue("rewriteQueryProfile", rewriteQueryProfile)
                        .addValue("rewriteAnchorInjectionEnabled", rewriteAnchorInjectionEnabled)
                        .addValue("useSessionContext", useSessionContext)
                        .addValue("retrievalBackend", retrievalBackend)
                        .addValue("denseEmbeddingModel", denseEmbeddingModel)
                        .addValue("retrieverMode", retrieverMode)
                        .addValue("retrieverCandidatePoolK", retrieverCandidatePoolK)
                        .addValue("retrieverDenseWeight", retrieverDenseWeight)
                        .addValue("retrieverBm25Weight", retrieverBm25Weight)
                        .addValue("retrieverTechnicalWeight", retrieverTechnicalWeight)
                        .addValue("retrievalTopK", retrievalTopK)
                        .addValue("rerankTopN", rerankTopN)
                        .addValue("memoryTopN", memoryTopN)
                        .addValue("rewriteCandidateCount", rewriteCandidateCount)
                        .addValue("rewriteThreshold", rewriteThreshold)
                        .addValue("rewriteFailurePolicy", rewriteFailurePolicy)
                        .addValue("metadata", (metadata == null ? objectMapper.createObjectNode() : metadata).toString())
                        .addValue("updatedBy", updatedBy)
        );
    }

    private ChatConfigDtos.ChatRuntimeConfigResponse mapConfig(
            ResultSet rs,
            Supplier<RagExecutionSettings> defaultSettings
    ) throws SQLException {
        List<String> methods = readStringArray(rs.getString("generation_strategies"));
        UUID sourceGatingBatchId = readUuid(rs, "source_gating_batch_id");
        UUID sourceGatingRunId = readUuid(rs, "source_gating_run_id");
        List<UUID> sourceGatingBatchIds = readUuidArray(rs.getString("source_gating_batch_ids"));
        if (sourceGatingBatchIds.isEmpty() && sourceGatingBatchId != null) {
            sourceGatingBatchIds = List.of(sourceGatingBatchId);
        }
        List<UUID> sourceGatingRunIds = readUuidArray(rs.getString("source_gating_run_ids"));
        if (sourceGatingRunIds.isEmpty() && sourceGatingRunId != null) {
            sourceGatingRunIds = List.of(sourceGatingRunId);
        }
        boolean memoryMode = !"raw_only".equalsIgnoreCase(rs.getString("mode"));
        boolean ready = !memoryMode || (!sourceGatingBatchIds.isEmpty() && !sourceGatingRunIds.isEmpty());
        String readinessMessage = ready
                ? "ready"
                : "select a completed gating snapshot before enabling rewrite-backed chat";
        JsonNode metadata = readJson(rs.getString("metadata_json"));
        Integer retrievalTopK = rs.getObject("retrieval_top_k", Integer.class);
        RagExecutionSettings settings = retrievalTopK == null
                ? defaultSettings.get()
                : new RagExecutionSettings(
                        retrievalTopK,
                        rs.getInt("rerank_top_n"),
                        rs.getInt("memory_top_n"),
                        rs.getInt("rewrite_candidate_count"),
                        rs.getDouble("rewrite_threshold")
                );
        return new ChatConfigDtos.ChatRuntimeConfigResponse(
                readUuid(rs, "domain_id"),
                rs.getString("domain_key"),
                rs.getString("display_name"),
                rs.getString("source_language"),
                rs.getBoolean("enabled"),
                rs.getString("mode"),
                methods,
                rs.getString("gating_preset"),
                sourceGatingBatchId,
                sourceGatingRunId,
                sourceGatingBatchIds,
                sourceGatingRunIds,
                rs.getString("rewrite_query_profile"),
                rs.getBoolean("rewrite_anchor_injection_enabled"),
                rs.getBoolean("use_session_context"),
                rs.getString("retrieval_backend"),
                rs.getString("dense_embedding_model"),
                rs.getString("retriever_mode"),
                rs.getInt("retriever_candidate_pool_k"),
                rs.getDouble("retriever_dense_weight"),
                rs.getDouble("retriever_bm25_weight"),
                rs.getDouble("retriever_technical_weight"),
                settings.retrievalTopK(),
                settings.rerankTopN(),
                settings.memoryTopN(),
                settings.rewriteCandidateCount(),
                settings.rewriteThreshold(),
                rs.getString("rewrite_failure_policy"),
                routerEnabled(metadata),
                metadata,
                readInstant(rs, "updated_at"),
                ready,
                readinessMessage
        );
    }

    private boolean routerEnabled(JsonNode metadata) {
        if (metadata == null || metadata.isMissingNode() || metadata.isNull()) {
            return false;
        }
        return metadata.path("routerEnabled").asBoolean(false)
                || metadata.path("queryRouterEnabled").asBoolean(false)
                || metadata.path("query_router_enabled").asBoolean(false);
    }

    private List<String> readStringArray(String raw) {
        List<String> values = new ArrayList<>();
        JsonNode node = readJson(raw);
        if (node.isArray()) {
            for (JsonNode item : node) {
                String value = item.asText("").trim();
                if (!value.isBlank()) {
                    values.add(value);
                }
            }
        }
        return values;
    }

    private List<UUID> readUuidArray(String raw) {
        List<UUID> values = new ArrayList<>();
        JsonNode node = readJson(raw);
        if (node.isArray()) {
            for (JsonNode item : node) {
                UUID value = parseUuid(item.asText(""));
                if (value != null && !values.contains(value)) {
                    values.add(value);
                }
            }
        }
        return values;
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

    private List<String> uuidTextArray(List<UUID> rawValues) {
        List<UUID> values = normalizeUuidList(rawValues);
        if (values.isEmpty()) {
            return List.of();
        }
        return values.stream().map(UUID::toString).toList();
    }

    private String jsonText(JsonNode node) {
        return (node == null ? objectMapper.createObjectNode() : node).toString();
    }

    private int normalizeLimit(Integer limit, int fallback) {
        int value = limit == null ? fallback : limit;
        return Math.max(1, Math.min(value, 200));
    }

    private JsonNode readJson(String raw) {
        if (raw == null || raw.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            return objectMapper.readTree(raw);
        } catch (Exception ignored) {
            return objectMapper.createObjectNode();
        }
    }

    private UUID readUuid(ResultSet rs, String column) throws SQLException {
        Object value = rs.getObject(column);
        if (value instanceof UUID uuid) {
            return uuid;
        }
        return value == null ? null : UUID.fromString(value.toString());
    }

    private UUID parseUuid(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(raw.trim());
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private Instant readInstant(ResultSet rs, String column) throws SQLException {
        Timestamp timestamp = rs.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }
}
