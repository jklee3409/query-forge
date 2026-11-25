package io.queryforge.backend.admin.prompt.binding;

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
public class PromptBindingRepository {

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public List<PromptBindingDtos.PromptBindingRow> findBindings(String family) {
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
                WHERE (CAST(:family AS text) IS NULL OR b.prompt_family = CAST(:family AS text))
                ORDER BY b.binding_key
                """;
        return jdbcTemplate.query(
                sql,
                new MapSqlParameterSource("family", blankToNull(family)),
                bindingRowMapper()
        );
    }

    public Optional<PromptBindingDtos.PromptBindingRow> findBinding(String bindingKey) {
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
    public void updateBinding(String bindingKey, PromptBindingDtos.PromptBindingUpdateRequest request) {
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

    private RowMapper<PromptBindingDtos.PromptBindingRow> bindingRowMapper() {
        return (rs, rowNum) -> new PromptBindingDtos.PromptBindingRow(
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
