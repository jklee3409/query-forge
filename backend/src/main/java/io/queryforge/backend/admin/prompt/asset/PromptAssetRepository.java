package io.queryforge.backend.admin.prompt.asset;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.RowMapper;
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
public class PromptAssetRepository {

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public List<PromptAssetDtos.PromptAssetRow> findAssets(String family, boolean activeOnly) {
        String sql = """
                SELECT prompt_asset_id,
                       prompt_family,
                       prompt_name,
                       version,
                       content_path,
                       content_hash,
                       is_active,
                       storage_backend,
                       content_body IS NOT NULL AS has_content_body,
                       parent_prompt_asset_id,
                       metadata::text AS metadata,
                       updated_by,
                       created_at,
                       updated_at
                FROM prompt_assets
                WHERE (CAST(:family AS text) IS NULL OR prompt_family = CAST(:family AS text))
                  AND (CAST(:activeOnly AS boolean) IS FALSE OR is_active IS TRUE)
                ORDER BY prompt_family, prompt_name, version DESC, created_at DESC
                """;
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("family", blankToNull(family))
                .addValue("activeOnly", activeOnly);
        return jdbcTemplate.query(sql, params, assetRowMapper());
    }

    public Optional<PromptAssetDtos.PromptAssetDetail> findAsset(UUID promptAssetId) {
        String sql = """
                SELECT prompt_asset_id,
                       prompt_family,
                       prompt_name,
                       version,
                       content_path,
                       content_hash,
                       is_active,
                       storage_backend,
                       content_body,
                       content_body IS NOT NULL AS has_content_body,
                       parent_prompt_asset_id,
                       metadata::text AS metadata,
                       updated_by,
                       created_at,
                       updated_at
                FROM prompt_assets
                WHERE prompt_asset_id = :promptAssetId
                """;
        return jdbcTemplate.query(
                        sql,
                        new MapSqlParameterSource("promptAssetId", promptAssetId),
                        (rs, rowNum) -> new PromptAssetDtos.PromptAssetDetail(
                                mapAssetRow(rs),
                                rs.getString("content_body")
                        )
                )
                .stream()
                .findFirst();
    }

    @Transactional
    public UUID createRevision(UUID basePromptAssetId, PromptAssetDtos.PromptRevisionRequest request) {
        String sql = """
                INSERT INTO prompt_assets (
                    prompt_family,
                    prompt_name,
                    version,
                    content_path,
                    content_hash,
                    is_active,
                    metadata,
                    storage_backend,
                    content_body,
                    parent_prompt_asset_id,
                    updated_by,
                    updated_at
                )
                SELECT prompt_family,
                       prompt_name,
                       :version,
                       content_path,
                       encode(sha256(convert_to(:contentBody, 'UTF8')), 'hex'),
                       TRUE,
                       CASE WHEN :metadata IS NULL THEN metadata ELSE CAST(:metadata AS jsonb) END,
                       'db',
                       :contentBody,
                       prompt_asset_id,
                       :updatedBy,
                       NOW()
                FROM prompt_assets
                WHERE prompt_asset_id = :basePromptAssetId
                RETURNING prompt_asset_id
                """;
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("basePromptAssetId", basePromptAssetId)
                .addValue("version", request.version())
                .addValue("contentBody", request.contentBody())
                .addValue("metadata", request.metadata() == null ? null : request.metadata().toString())
                .addValue("updatedBy", request.updatedBy());
        return jdbcTemplate.queryForObject(sql, params, UUID.class);
    }

    @Transactional
    public void updateAsset(UUID promptAssetId, PromptAssetDtos.PromptAssetUpdateRequest request) {
        String sql = """
                UPDATE prompt_assets
                SET is_active = COALESCE(:active, is_active),
                    content_body = COALESCE(:contentBody, content_body),
                    content_hash = CASE
                        WHEN :contentBody IS NULL THEN content_hash
                        ELSE encode(sha256(convert_to(:contentBody, 'UTF8')), 'hex')
                    END,
                    storage_backend = CASE
                        WHEN :contentBody IS NULL THEN storage_backend
                        ELSE 'db'
                    END,
                    metadata = CASE
                        WHEN :metadata IS NULL THEN metadata
                        ELSE CAST(:metadata AS jsonb)
                    END,
                    updated_by = COALESCE(:updatedBy, updated_by),
                    updated_at = NOW()
                WHERE prompt_asset_id = :promptAssetId
                """;
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("promptAssetId", promptAssetId)
                .addValue("active", request.active())
                .addValue("contentBody", request.contentBody())
                .addValue("metadata", request.metadata() == null ? null : request.metadata().toString())
                .addValue("updatedBy", request.updatedBy());
        jdbcTemplate.update(sql, params);
    }

    @Transactional
    public void deactivateAsset(UUID promptAssetId) {
        jdbcTemplate.update(
                """
                UPDATE prompt_assets
                SET is_active = FALSE,
                    updated_at = NOW()
                WHERE prompt_asset_id = :promptAssetId
                """,
                new MapSqlParameterSource("promptAssetId", promptAssetId)
        );
    }

    private RowMapper<PromptAssetDtos.PromptAssetRow> assetRowMapper() {
        return (rs, rowNum) -> mapAssetRow(rs);
    }

    private PromptAssetDtos.PromptAssetRow mapAssetRow(ResultSet rs) throws SQLException {
        return new PromptAssetDtos.PromptAssetRow(
                rs.getObject("prompt_asset_id", UUID.class),
                rs.getString("prompt_family"),
                rs.getString("prompt_name"),
                rs.getString("version"),
                rs.getString("content_path"),
                rs.getString("content_hash"),
                rs.getBoolean("is_active"),
                rs.getString("storage_backend"),
                rs.getBoolean("has_content_body"),
                rs.getObject("parent_prompt_asset_id", UUID.class),
                readJson(rs, "metadata"),
                rs.getString("updated_by"),
                readInstant(rs, "created_at"),
                readInstant(rs, "updated_at")
        );
    }

    private JsonNode readJson(ResultSet rs, String column) throws SQLException {
        String raw = rs.getString(column);
        if (raw == null || raw.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            return objectMapper.readTree(raw);
        } catch (Exception exception) {
            return objectMapper.valueToTree(Map.of("raw", raw));
        }
    }

    private Instant readInstant(ResultSet rs, String column) throws SQLException {
        Timestamp timestamp = rs.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
