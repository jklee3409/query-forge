package io.queryforge.backend.admin.console.repository;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.queryforge.backend.admin.console.model.AdminConsoleDtos;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AdminEvalDatasetRepository {

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public long countEvalSamples() {
        Long value = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM eval_samples", new MapSqlParameterSource(), Long.class);
        return value == null ? 0L : value;
    }

    public long countEvalSamplesForDefaultDataset() {
        String sql = """
                SELECT COUNT(*)
                FROM eval_samples s
                WHERE COALESCE(s.metadata ->> 'builder', '') = 'build-eval-dataset'
                   OR s.sample_id LIKE 'dev-human-%%'
                   OR s.sample_id LIKE 'test-human-%%'
                """;
        Long value = jdbcTemplate.queryForObject(sql, new MapSqlParameterSource(), Long.class);
        return value == null ? 0L : value;
    }

    public Optional<UUID> findEvalDatasetIdByKey(String datasetKey) {
        String sql = "SELECT dataset_id FROM eval_dataset WHERE dataset_key = :datasetKey";
        List<UUID> rows = jdbcTemplate.query(
                sql,
                new MapSqlParameterSource("datasetKey", datasetKey),
                (rs, rowNum) -> readUuid(rs, "dataset_id")
        );
        return rows.stream().findFirst();
    }

    @Transactional
    public UUID upsertEvalDataset(
            String datasetKey,
            String datasetName,
            String description,
            String version,
            int totalItems,
            JsonNode categoryDistribution,
            JsonNode singleMultiDistribution
    ) {
        UUID datasetId = findEvalDatasetIdByKey(datasetKey).orElse(UUID.randomUUID());
        String sql = """
                INSERT INTO eval_dataset (
                    dataset_id,
                    dataset_key,
                    dataset_name,
                    description,
                    version,
                    total_items,
                    category_distribution,
                    single_multi_distribution,
                    updated_at
                ) VALUES (
                    :datasetId,
                    :datasetKey,
                    :datasetName,
                    :description,
                    :version,
                    :totalItems,
                    CAST(:categoryDistribution AS jsonb),
                    CAST(:singleMultiDistribution AS jsonb),
                    NOW()
                )
                ON CONFLICT (dataset_key) DO UPDATE
                SET dataset_name = EXCLUDED.dataset_name,
                    description = EXCLUDED.description,
                    version = EXCLUDED.version,
                    total_items = EXCLUDED.total_items,
                    category_distribution = EXCLUDED.category_distribution,
                    single_multi_distribution = EXCLUDED.single_multi_distribution,
                    updated_at = NOW()
                """;
        jdbcTemplate.update(
                sql,
                new MapSqlParameterSource()
                        .addValue("datasetId", datasetId)
                        .addValue("datasetKey", datasetKey)
                        .addValue("datasetName", datasetName)
                        .addValue("description", description)
                        .addValue("version", version)
                        .addValue("totalItems", totalItems)
                        .addValue("categoryDistribution", categoryDistribution.toString())
                        .addValue("singleMultiDistribution", singleMultiDistribution.toString())
        );
        return datasetId;
    }

    @Transactional
    public void refreshEvalDatasetItems(UUID datasetId) {
        jdbcTemplate.update(
                "DELETE FROM eval_dataset_item WHERE dataset_id = :datasetId",
                new MapSqlParameterSource("datasetId", datasetId)
        );
        jdbcTemplate.update(
                """
                INSERT INTO eval_dataset_item (
                    dataset_item_id,
                    dataset_id,
                    sample_id,
                    query_category,
                    single_or_multi_chunk,
                    active
                )
                SELECT gen_random_uuid(),
                       :datasetId,
                       s.sample_id,
                       s.query_category,
                       s.single_or_multi_chunk,
                       TRUE
                FROM eval_samples s
                ORDER BY s.sample_id
                """,
                new MapSqlParameterSource("datasetId", datasetId)
        );
    }

    @Transactional
    public void refreshDefaultEvalDatasetItems(UUID datasetId) {
        jdbcTemplate.update(
                "DELETE FROM eval_dataset_item WHERE dataset_id = :datasetId",
                new MapSqlParameterSource("datasetId", datasetId)
        );
        jdbcTemplate.update(
                """
                INSERT INTO eval_dataset_item (
                    dataset_item_id,
                    dataset_id,
                    sample_id,
                    query_category,
                    single_or_multi_chunk,
                    active
                )
                SELECT gen_random_uuid(),
                       :datasetId,
                       s.sample_id,
                       s.query_category,
                       s.single_or_multi_chunk,
                       TRUE
                FROM eval_samples s
                WHERE COALESCE(s.metadata ->> 'builder', '') = 'build-eval-dataset'
                   OR s.sample_id LIKE 'dev-human-%%'
                   OR s.sample_id LIKE 'test-human-%%'
                ORDER BY s.sample_id
                """,
                new MapSqlParameterSource("datasetId", datasetId)
        );
    }

    public List<AdminConsoleDtos.EvalDatasetRow> findEvalDatasets() {
        return findEvalDatasets(null);
    }

    public List<AdminConsoleDtos.EvalDatasetRow> findEvalDatasets(UUID domainId) {
        String domainWhereClause = domainId == null ? "" : "WHERE d.domain_id = :domainId";
        String sql = """
                SELECT d.dataset_id,
                       d.dataset_key,
                       d.dataset_name,
                       d.version,
                       COALESCE(NULLIF(d.metadata ->> 'query_language', ''), items.query_language, 'ko') AS query_language,
                       COALESCE(NULLIF(d.metadata ->> 'strategy_profile', ''), '') AS metadata_strategy_profile,
                       COALESCE(items.total_items, d.total_items, 0) AS total_items,
                       d.category_distribution::text AS category_distribution,
                       d.single_multi_distribution::text AS single_multi_distribution,
                       d.created_at
                FROM eval_dataset d
                LEFT JOIN LATERAL (
                    SELECT COUNT(*) AS total_items,
                           CASE
                               WHEN COUNT(*) FILTER (
                                   WHERE COALESCE(
                                       NULLIF(LOWER(s.query_language), ''),
                                       COALESCE(NULLIF(LOWER(d.metadata ->> 'query_language'), ''), 'ko')
                                   ) = 'en'
                               ) > 0 THEN 'en'
                               ELSE 'ko'
                           END AS query_language
                    FROM eval_dataset_item i
                    JOIN eval_samples s
                      ON s.sample_id = i.sample_id
                    WHERE i.dataset_id = d.dataset_id
                      AND i.active = TRUE
                ) items ON TRUE
                %s
                ORDER BY d.created_at DESC
                """.formatted(domainWhereClause);
        MapSqlParameterSource params = new MapSqlParameterSource();
        if (domainId != null) {
            params.addValue("domainId", domainId);
        }
        return jdbcTemplate.query(
                sql,
                params,
                (rs, rowNum) -> new AdminConsoleDtos.EvalDatasetRow(
                        readUuid(rs, "dataset_id"),
                        rs.getString("dataset_key"),
                        rs.getString("dataset_name"),
                        rs.getString("version"),
                        rs.getString("query_language"),
                        rs.getString("metadata_strategy_profile"),
                        rs.getInt("total_items"),
                        readJson(rs, "category_distribution"),
                        readJson(rs, "single_multi_distribution"),
                        readInstant(rs, "created_at")
                )
        );
    }

    public Optional<String> findEvalDatasetKey(UUID datasetId) {
        if (datasetId == null) {
            return Optional.empty();
        }
        List<String> rows = jdbcTemplate.query(
                "SELECT dataset_key FROM eval_dataset WHERE dataset_id = :datasetId",
                new MapSqlParameterSource("datasetId", datasetId),
                (rs, rowNum) -> rs.getString("dataset_key")
        );
        return rows.stream().findFirst();
    }

    @Transactional
    public int deleteEvalDataset(UUID datasetId) {
        return jdbcTemplate.update(
                "DELETE FROM eval_dataset WHERE dataset_id = :datasetId",
                new MapSqlParameterSource("datasetId", datasetId)
        );
    }

    public List<AdminConsoleDtos.EvalDatasetItemRow> findEvalDatasetItems(UUID datasetId, Integer limit, Integer offset) {
        String sql = """
                SELECT i.dataset_id,
                       s.sample_id,
                       s.split,
                       s.query_category,
                       s.single_or_multi_chunk,
                       s.user_query_ko,
                       s.user_query_en,
                       COALESCE(NULLIF(s.query_language, ''), COALESCE(d.metadata ->> 'query_language', 'ko')) AS query_language,
                       s.dialog_context::text AS dialog_context,
                       s.metadata ->> 'target_method' AS target_method,
                       COALESCE(s.metadata -> 'evaluation_focus', '[]'::jsonb)::text AS evaluation_focus
                FROM eval_dataset_item i
                JOIN eval_samples s
                  ON s.sample_id = i.sample_id
                JOIN eval_dataset d
                  ON d.dataset_id = i.dataset_id
                WHERE i.dataset_id = :datasetId
                  AND i.active = TRUE
                ORDER BY s.sample_id
                LIMIT :limit OFFSET :offset
                """;
        return jdbcTemplate.query(
                sql,
                new MapSqlParameterSource()
                        .addValue("datasetId", datasetId)
                        .addValue("limit", normalizeLimit(limit, 500))
                        .addValue("offset", normalizeOffset(offset)),
                (rs, rowNum) -> new AdminConsoleDtos.EvalDatasetItemRow(
                        readUuid(rs, "dataset_id"),
                        rs.getString("sample_id"),
                        rs.getString("split"),
                        rs.getString("query_category"),
                        rs.getString("single_or_multi_chunk"),
                        rs.getString("user_query_ko"),
                        rs.getString("user_query_en"),
                        rs.getString("query_language"),
                        readJson(rs, "dialog_context"),
                        rs.getString("target_method"),
                        readJson(rs, "evaluation_focus")
                )
        );
    }

    public Optional<String> findEvalDatasetQueryLanguage(UUID datasetId) {
        String sql = """
                SELECT COALESCE(
                           NULLIF(d.metadata ->> 'query_language', ''),
                           NULLIF(
                               (
                                   SELECT CASE
                                              WHEN COUNT(*) FILTER (WHERE COALESCE(NULLIF(s.query_language, ''), 'ko') = 'en') > 0
                                                  THEN 'en'
                                              ELSE 'ko'
                                          END
                                   FROM eval_dataset_item i
                                   JOIN eval_samples s
                                     ON s.sample_id = i.sample_id
                                   WHERE i.dataset_id = d.dataset_id
                                     AND i.active = TRUE
                               ),
                               ''
                           ),
                           'ko'
                       ) AS query_language
                FROM eval_dataset d
                WHERE d.dataset_id = :datasetId
                """;
        List<String> rows = jdbcTemplate.query(
                sql,
                new MapSqlParameterSource("datasetId", datasetId),
                (rs, rowNum) -> rs.getString("query_language")
        );
        return rows.stream().findFirst();
    }

    private int normalizeLimit(Integer value, int defaultLimit) {
        if (value == null || value <= 0) {
            return defaultLimit;
        }
        return Math.min(value, 1000);
    }

    private int normalizeOffset(Integer value) {
        if (value == null || value < 0) {
            return 0;
        }
        return value;
    }

    private UUID readUuid(ResultSet rs, String column) throws SQLException {
        Object value = rs.getObject(column);
        if (value == null) {
            return null;
        }
        if (value instanceof UUID uuid) {
            return uuid;
        }
        return UUID.fromString(String.valueOf(value));
    }

    private Instant readInstant(ResultSet rs, String column) throws SQLException {
        Timestamp timestamp = rs.getTimestamp(column);
        return timestamp != null ? timestamp.toInstant() : null;
    }

    private JsonNode readJson(ResultSet rs, String column) throws SQLException {
        return readJson(rs.getString(column));
    }

    private JsonNode readJson(String raw) {
        if (raw == null || raw.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            return objectMapper.readTree(raw);
        } catch (Exception exception) {
            return objectMapper.valueToTree(Map.of("raw", raw));
        }
    }
}
