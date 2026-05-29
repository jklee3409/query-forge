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
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AdminSyntheticMethodRepository {

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public List<AdminConsoleDtos.SyntheticGenerationMethod> findGenerationMethods() {
        String sql = """
                SELECT generation_method_id,
                       method_code,
                       method_name,
                       description,
                       active,
                       prompt_template_version,
                       summary_strategy,
                       translation_strategy,
                       query_language_strategy,
                       terminology_preservation_rule,
                       metadata::text AS metadata
                FROM synthetic_query_generation_method
                ORDER BY method_code
                """;
        return jdbcTemplate.query(sql, this::mapGenerationMethod);
    }

    public List<AdminConsoleDtos.SyntheticGenerationMethod> findGenerationMethods(UUID domainId) {
        if (domainId == null) {
            return findGenerationMethods();
        }
        String sql = """
                SELECT m.generation_method_id,
                       m.method_code,
                       m.method_name,
                       m.description,
                       m.active,
                       m.prompt_template_version,
                       m.summary_strategy,
                       m.translation_strategy,
                       m.query_language_strategy,
                       m.terminology_preservation_rule,
                       m.metadata::text AS metadata
                FROM synthetic_query_generation_method m
                WHERE EXISTS (
                    SELECT 1
                    FROM tech_doc_domain_method_policy p
                    WHERE p.domain_id = :domainId
                      AND p.method_code = m.method_code
                      AND p.enabled IS TRUE
                )
                ORDER BY m.method_code
                """;
        return jdbcTemplate.query(sql, new MapSqlParameterSource("domainId", domainId), this::mapGenerationMethod);
    }

    public Optional<AdminConsoleDtos.SyntheticGenerationMethod> findGenerationMethodByCode(String methodCode) {
        String sql = """
                SELECT generation_method_id,
                       method_code,
                       method_name,
                       description,
                       active,
                       prompt_template_version,
                       summary_strategy,
                       translation_strategy,
                       query_language_strategy,
                       terminology_preservation_rule,
                       metadata::text AS metadata
                FROM synthetic_query_generation_method
                WHERE method_code = :methodCode
                """;
        List<AdminConsoleDtos.SyntheticGenerationMethod> rows = jdbcTemplate.query(
                sql,
                new MapSqlParameterSource("methodCode", methodCode),
                this::mapGenerationMethod
        );
        return rows.stream().findFirst();
    }

    private AdminConsoleDtos.SyntheticGenerationMethod mapGenerationMethod(ResultSet rs, int rowNum) throws SQLException {
        return new AdminConsoleDtos.SyntheticGenerationMethod(
                readUuid(rs, "generation_method_id"),
                rs.getString("method_code"),
                rs.getString("method_name"),
                rs.getString("description"),
                rs.getBoolean("active"),
                rs.getString("prompt_template_version"),
                rs.getString("summary_strategy"),
                rs.getString("translation_strategy"),
                rs.getString("query_language_strategy"),
                rs.getString("terminology_preservation_rule"),
                readJson(rs, "metadata")
        );
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
            return objectMapper.createObjectNode();
        }
    }
}
