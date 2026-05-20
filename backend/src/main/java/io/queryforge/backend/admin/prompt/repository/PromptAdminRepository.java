package io.queryforge.backend.admin.prompt.repository;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.queryforge.backend.admin.prompt.model.PromptAdminDtos;
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
public class PromptAdminRepository {

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public List<PromptAdminDtos.PromptAssetRow> findAssets(String family, boolean activeOnly) {
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
                WHERE (:family IS NULL OR prompt_family = :family)
                  AND (:activeOnly IS FALSE OR is_active IS TRUE)
                ORDER BY prompt_family, prompt_name, version DESC, created_at DESC
                """;
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("family", blankToNull(family))
                .addValue("activeOnly", activeOnly);
        return jdbcTemplate.query(sql, params, assetRowMapper());
    }

    public Optional<PromptAdminDtos.PromptAssetDetail> findAsset(UUID promptAssetId) {
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
                        (rs, rowNum) -> new PromptAdminDtos.PromptAssetDetail(
                                mapAssetRow(rs),
                                rs.getString("content_body")
                        )
                )
                .stream()
                .findFirst();
    }

    public List<PromptAdminDtos.PromptBindingRow> findBindings(String family) {
        String sql = """
                SELECT b.binding_key,
                       b.prompt_family,
                       b.active_prompt_asset_id,
                       p.prompt_name AS active_prompt_name,
                       p.version AS active_prompt_version,
                       p.content_hash AS active_content_hash,
                       b.fallback_prompt_asset_ids::text AS fallback_prompt_asset_ids,
                       b.description,
                       b.metadata_json::text AS metadata_json,
                       b.updated_by,
                       b.updated_at
                FROM prompt_asset_binding b
                JOIN prompt_assets p ON p.prompt_asset_id = b.active_prompt_asset_id
                WHERE (:family IS NULL OR b.prompt_family = :family)
                ORDER BY b.binding_key
                """;
        return jdbcTemplate.query(
                sql,
                new MapSqlParameterSource("family", blankToNull(family)),
                bindingRowMapper()
        );
    }

    public Optional<PromptAdminDtos.PromptBindingRow> findBinding(String bindingKey) {
        String sql = """
                SELECT b.binding_key,
                       b.prompt_family,
                       b.active_prompt_asset_id,
                       p.prompt_name AS active_prompt_name,
                       p.version AS active_prompt_version,
                       p.content_hash AS active_content_hash,
                       b.fallback_prompt_asset_ids::text AS fallback_prompt_asset_ids,
                       b.description,
                       b.metadata_json::text AS metadata_json,
                       b.updated_by,
                       b.updated_at
                FROM prompt_asset_binding b
                JOIN prompt_assets p ON p.prompt_asset_id = b.active_prompt_asset_id
                WHERE b.binding_key = :bindingKey
                """;
        return jdbcTemplate.query(
                        sql,
                        new MapSqlParameterSource("bindingKey", bindingKey),
                        bindingRowMapper()
                )
                .stream()
                .findFirst();
    }

    @Transactional
    public UUID createRevision(UUID basePromptAssetId, PromptAdminDtos.PromptRevisionRequest request) {
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
    public void updateAsset(UUID promptAssetId, PromptAdminDtos.PromptAssetUpdateRequest request) {
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

    @Transactional
    public void updateBinding(String bindingKey, PromptAdminDtos.PromptBindingUpdateRequest request) {
        String sql = """
                UPDATE prompt_asset_binding
                SET active_prompt_asset_id = COALESCE(:activePromptAssetId, active_prompt_asset_id),
                    fallback_prompt_asset_ids = CASE
                        WHEN :fallbackPromptAssetIds IS NULL THEN fallback_prompt_asset_ids
                        ELSE CAST(:fallbackPromptAssetIds AS jsonb)
                    END,
                    description = COALESCE(:description, description),
                    metadata_json = CASE
                        WHEN :metadata IS NULL THEN metadata_json
                        ELSE CAST(:metadata AS jsonb)
                    END,
                    updated_by = COALESCE(:updatedBy, updated_by),
                    updated_at = NOW()
                WHERE binding_key = :bindingKey
                """;
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("bindingKey", bindingKey)
                .addValue("activePromptAssetId", request.activePromptAssetId())
                .addValue(
                        "fallbackPromptAssetIds",
                        request.fallbackPromptAssetIds() == null
                                ? null
                                : objectMapper.valueToTree(request.fallbackPromptAssetIds()).toString()
                )
                .addValue("description", blankToNull(request.description()))
                .addValue("metadata", request.metadata() == null ? null : request.metadata().toString())
                .addValue("updatedBy", request.updatedBy());
        jdbcTemplate.update(sql, params);
    }

    private RowMapper<PromptAdminDtos.PromptAssetRow> assetRowMapper() {
        return (rs, rowNum) -> mapAssetRow(rs);
    }

    private PromptAdminDtos.PromptAssetRow mapAssetRow(ResultSet rs) throws SQLException {
        return new PromptAdminDtos.PromptAssetRow(
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

    private RowMapper<PromptAdminDtos.PromptBindingRow> bindingRowMapper() {
        return (rs, rowNum) -> new PromptAdminDtos.PromptBindingRow(
                rs.getString("binding_key"),
                rs.getString("prompt_family"),
                rs.getObject("active_prompt_asset_id", UUID.class),
                rs.getString("active_prompt_name"),
                rs.getString("active_prompt_version"),
                rs.getString("active_content_hash"),
                readJson(rs, "fallback_prompt_asset_ids"),
                rs.getString("description"),
                readJson(rs, "metadata_json"),
                rs.getString("updated_by"),
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
