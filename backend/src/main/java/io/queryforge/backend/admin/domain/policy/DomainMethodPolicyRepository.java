package io.queryforge.backend.admin.domain.policy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DomainMethodPolicyRepository {

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public List<DomainMethodPolicy> findMethodPolicies(UUID domainId) {
        String sql = """
                SELECT p.method_code,
                       m.method_name,
                       m.active AS method_active,
                       p.enabled,
                       p.default_query_language,
                       p.metadata_json::text AS metadata_json,
                       p.created_at
                FROM tech_doc_domain_method_policy p
                JOIN synthetic_query_generation_method m ON m.method_code = p.method_code
                WHERE p.domain_id = :domainId
                ORDER BY p.method_code
                """;
        return jdbcTemplate.query(sql, Map.of("domainId", domainId), this::mapMethodPolicy);
    }

    private DomainMethodPolicy mapMethodPolicy(ResultSet rs, int rowNum) throws SQLException {
        Timestamp createdAt = rs.getTimestamp("created_at");
        return new DomainMethodPolicy(
                rs.getString("method_code"),
                rs.getString("method_name"),
                rs.getBoolean("method_active"),
                rs.getBoolean("enabled"),
                rs.getString("default_query_language"),
                readMetadata(rs.getString("metadata_json")),
                createdAt == null ? null : createdAt.toInstant()
        );
    }

    private JsonNode readMetadata(String raw) {
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
