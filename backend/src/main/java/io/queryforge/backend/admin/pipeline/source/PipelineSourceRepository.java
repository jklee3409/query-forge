package io.queryforge.backend.admin.pipeline.source;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Repository
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PipelineSourceRepository {

    private final ObjectMapper objectMapper;
    private final NamedParameterJdbcTemplate jdbcTemplate;

    @Transactional
    public void upsertSourceDefinition(
            String sourceId,
            String sourceType,
            String productName,
            String sourceName,
            String baseUrl,
            List<String> includePatterns,
            List<String> excludePatterns,
            String defaultVersion,
            boolean enabled
    ) {
        executeUpdate(
                """
                INSERT INTO corpus_sources (
                    source_id,
                    source_type,
                    product_name,
                    source_name,
                    base_url,
                    include_patterns,
                    exclude_patterns,
                    default_version,
                    enabled,
                    created_at,
                    updated_at
                ) VALUES (
                    :sourceId,
                    :sourceType,
                    :productName,
                    :sourceName,
                    :baseUrl,
                    CAST(:includePatterns AS jsonb),
                    CAST(:excludePatterns AS jsonb),
                    :defaultVersion,
                    :enabled,
                    NOW(),
                    NOW()
                )
                ON CONFLICT (source_id) DO UPDATE
                SET source_type = EXCLUDED.source_type,
                    product_name = EXCLUDED.product_name,
                    source_name = EXCLUDED.source_name,
                    base_url = EXCLUDED.base_url,
                    include_patterns = EXCLUDED.include_patterns,
                    exclude_patterns = EXCLUDED.exclude_patterns,
                    default_version = COALESCE(corpus_sources.default_version, EXCLUDED.default_version),
                    enabled = EXCLUDED.enabled,
                    updated_at = NOW()
                """,
                params(
                        "sourceId", sourceId,
                        "sourceType", sourceType,
                        "productName", productName,
                        "sourceName", sourceName,
                        "baseUrl", baseUrl,
                        "includePatterns", writeJson(includePatterns),
                        "excludePatterns", writeJson(excludePatterns),
                        "defaultVersion", defaultVersion,
                        "enabled", enabled
                )
        );
    }

    @Transactional
    public void assignConfiguredSourceDomain(String sourceId, String domainKey) {
        Map<String, Object> parameters = params("sourceId", sourceId, "domainKey", domainKey);
        executeUpdate(
                """
                UPDATE corpus_sources cs
                SET domain_id = d.domain_id,
                    updated_at = NOW()
                FROM tech_doc_domain d
                WHERE cs.source_id = :sourceId
                  AND d.domain_key = :domainKey
                  AND cs.domain_id IS DISTINCT FROM d.domain_id
                """,
                parameters
        );
        executeUpdate(
                """
                INSERT INTO tech_doc_domain_source (domain_id, source_id, source_role, active)
                SELECT d.domain_id, cs.source_id, 'primary', TRUE
                FROM tech_doc_domain d
                JOIN corpus_sources cs ON cs.source_id = :sourceId
                WHERE d.domain_key = :domainKey
                ON CONFLICT (source_id) DO UPDATE
                SET domain_id = EXCLUDED.domain_id,
                    source_role = EXCLUDED.source_role,
                    active = EXCLUDED.active
                """,
                parameters
        );
    }

    private void executeUpdate(String sql, Map<String, Object> parameters) {
        jdbcTemplate.update(sql, new MapSqlParameterSource(parameters));
    }

    private Map<String, Object> params(Object... keyValuePairs) {
        Map<String, Object> parameters = new LinkedHashMap<>();
        for (int index = 0; index < keyValuePairs.length; index += 2) {
            parameters.put(String.valueOf(keyValuePairs[index]), keyValuePairs[index + 1]);
        }
        return parameters;
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Failed to serialize JSON payload.", exception);
        }
    }
}
