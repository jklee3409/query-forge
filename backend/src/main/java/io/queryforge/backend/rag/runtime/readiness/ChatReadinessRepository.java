package io.queryforge.backend.rag.runtime.readiness;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ChatReadinessRepository {

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public record GatingSnapshot(
            UUID gatingBatchId,
            UUID sourceGatingRunId,
            UUID domainId,
            String gatingPreset,
            String methodCode,
            String status
    ) {
    }

    public record DomainChunkEmbeddingStatus(
            long domainChunkCount,
            long materializedChunkCount,
            Instant latestUpdatedAt
    ) {
    }

    public record PromptBindingSummary(
            String bindingKey,
            boolean active,
            UUID activePromptAssetId,
            String activePromptName,
            String activePromptVersion,
            String activeContentHash
    ) {
    }

    public boolean existsConfig(UUID domainId) {
        String sql = """
                SELECT EXISTS (
                    SELECT 1
                    FROM chat_runtime_config
                    WHERE domain_id = :domainId
                )
                """;
        Boolean exists = jdbcTemplate.queryForObject(
                sql,
                new MapSqlParameterSource("domainId", domainId),
                Boolean.class
        );
        return Boolean.TRUE.equals(exists);
    }

    public Optional<GatingSnapshot> findGatingSnapshot(UUID gatingBatchId) {
        String sql = """
                SELECT qb.gating_batch_id,
                       qb.source_gating_run_id,
                       qb.domain_id,
                       qb.gating_preset,
                       m.method_code,
                       qb.status
                FROM quality_gating_batch qb
                LEFT JOIN synthetic_query_generation_method m
                  ON m.generation_method_id = qb.generation_method_id
                WHERE qb.gating_batch_id = :gatingBatchId
                """;
        return jdbcTemplate.query(
                        sql,
                        new MapSqlParameterSource("gatingBatchId", gatingBatchId),
                        (rs, rowNum) -> new GatingSnapshot(
                                readUuid(rs, "gating_batch_id"),
                                readUuid(rs, "source_gating_run_id"),
                                readUuid(rs, "domain_id"),
                                rs.getString("gating_preset"),
                                rs.getString("method_code"),
                                rs.getString("status")
                        )
                )
                .stream()
                .findFirst();
    }

    public DomainChunkEmbeddingStatus findDomainChunkEmbeddingStatus(UUID domainId, String embeddingModel) {
        String sql = """
                SELECT COUNT(c.chunk_id) AS domain_chunk_count,
                       COUNT(ce.chunk_embedding_id) AS materialized_chunk_count,
                       MAX(ce.updated_at) AS latest_updated_at
                FROM corpus_chunks c
                LEFT JOIN chunk_embeddings ce
                  ON ce.chunk_id = c.chunk_id
                 AND ce.embedding_model = :embeddingModel
                WHERE c.domain_id = :domainId
                """;
        return jdbcTemplate.queryForObject(
                sql,
                new MapSqlParameterSource()
                        .addValue("domainId", domainId)
                        .addValue("embeddingModel", embeddingModel),
                (rs, rowNum) -> new DomainChunkEmbeddingStatus(
                        rs.getLong("domain_chunk_count"),
                        rs.getLong("materialized_chunk_count"),
                        readInstant(rs, "latest_updated_at")
                )
        );
    }

    public long countAcceptedGatedQueries(
            UUID domainId,
            List<UUID> sourceGatingBatchIds,
            List<String> generationStrategies
    ) {
        List<UUID> batchIds = normalizeUuidList(sourceGatingBatchIds);
        if (batchIds.isEmpty()) {
            return 0L;
        }
        boolean strategyFilterEmpty = generationStrategies == null || generationStrategies.isEmpty();
        String sql = """
                SELECT COUNT(*)
                FROM synthetic_query_gating_result r
                WHERE r.domain_id = :domainId
                  AND r.gating_batch_id IN (:sourceGatingBatchIds)
                  AND r.accepted IS TRUE
                  AND (:strategyFilterEmpty IS TRUE OR r.generation_strategy IN (:generationStrategies))
                """;
        Long count = jdbcTemplate.queryForObject(
                sql,
                new MapSqlParameterSource()
                        .addValue("domainId", domainId)
                        .addValue("sourceGatingBatchIds", batchIds)
                        .addValue("strategyFilterEmpty", strategyFilterEmpty)
                        .addValue("generationStrategies", strategyFilterEmpty ? List.of("") : generationStrategies),
                Long.class
        );
        return count == null ? 0L : count;
    }

    public long countReadyMemoryEntries(
            UUID domainId,
            String gatingPreset,
            List<String> generationStrategies,
            List<UUID> sourceGatingRunIds,
            List<UUID> sourceGatingBatchIds
    ) {
        List<String> runIds = uuidTextArray(sourceGatingRunIds);
        List<String> batchIds = uuidTextArray(sourceGatingBatchIds);
        if (runIds.isEmpty() || batchIds.isEmpty()) {
            return 0L;
        }
        boolean strategyFilterEmpty = generationStrategies == null || generationStrategies.isEmpty();
        String sql = """
                SELECT COUNT(*)
                FROM memory_entries m
                JOIN synthetic_queries_gated g
                  ON g.gated_query_id = m.source_gated_query_id
                WHERE m.domain_id = :domainId
                  AND g.final_decision IS TRUE
                  AND (:gatingPreset IS NULL OR g.gating_preset = :gatingPreset)
                  AND (:strategyFilterEmpty IS TRUE OR m.generation_strategy IN (:generationStrategies))
                  AND m.metadata ->> 'source_gate_run_id' IN (:sourceGatingRunIds)
                  AND m.metadata ->> 'source_gating_batch_id' IN (:sourceGatingBatchIds)
                """;
        Long count = jdbcTemplate.queryForObject(
                sql,
                new MapSqlParameterSource()
                        .addValue("domainId", domainId)
                        .addValue("gatingPreset", gatingPreset)
                        .addValue("strategyFilterEmpty", strategyFilterEmpty)
                        .addValue("generationStrategies", strategyFilterEmpty ? List.of("") : generationStrategies)
                        .addValue("sourceGatingRunIds", runIds)
                        .addValue("sourceGatingBatchIds", batchIds),
                Long.class
        );
        return count == null ? 0L : count;
    }

    public Optional<PromptBindingSummary> findPromptBinding(String bindingKey) {
        String sql = """
                SELECT b.binding_key,
                       p.is_active,
                       b.active_prompt_asset_id,
                       p.prompt_name,
                       p.version,
                       p.content_hash
                FROM prompt_asset_binding b
                JOIN prompt_assets p
                  ON p.prompt_asset_id = b.active_prompt_asset_id
                WHERE b.binding_key = :bindingKey
                """;
        return jdbcTemplate.query(
                        sql,
                        new MapSqlParameterSource("bindingKey", bindingKey),
                        (rs, rowNum) -> new PromptBindingSummary(
                                rs.getString("binding_key"),
                                rs.getBoolean("is_active"),
                                readUuid(rs, "active_prompt_asset_id"),
                                rs.getString("prompt_name"),
                                rs.getString("version"),
                                rs.getString("content_hash")
                        )
                )
                .stream()
                .findFirst();
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

    private UUID readUuid(ResultSet rs, String column) throws SQLException {
        Object value = rs.getObject(column);
        if (value instanceof UUID uuid) {
            return uuid;
        }
        return value == null ? null : UUID.fromString(value.toString());
    }

    private Instant readInstant(ResultSet rs, String column) throws SQLException {
        Timestamp timestamp = rs.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }
}
