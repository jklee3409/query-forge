package io.queryforge.backend.admin.console.repository;

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
public class AdminConsoleDomainScopeRepository {

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public boolean isGenerationMethodEnabledForDomain(UUID domainId, String methodCode) {
        if (domainId == null || methodCode == null || methodCode.isBlank()) {
            return true;
        }
        String sql = """
                SELECT COUNT(*)
                FROM tech_doc_domain_method_policy
                WHERE domain_id = :domainId
                  AND method_code = :methodCode
                  AND enabled IS TRUE
                """;
        Integer count = jdbcTemplate.queryForObject(
                sql,
                new MapSqlParameterSource()
                        .addValue("domainId", domainId)
                        .addValue("methodCode", methodCode.trim().toUpperCase()),
                Integer.class
        );
        return count != null && count > 0;
    }

    public List<String> findDomainSourceIds(UUID domainId) {
        if (domainId == null) {
            return List.of();
        }
        String sql = """
                SELECT source_id
                FROM tech_doc_domain_source
                WHERE domain_id = :domainId
                  AND active IS TRUE
                ORDER BY source_id
                """;
        return jdbcTemplate.query(
                sql,
                new MapSqlParameterSource("domainId", domainId),
                (rs, rowNum) -> rs.getString("source_id")
        );
    }

    public boolean sourceBelongsToDomain(UUID domainId, String sourceId) {
        if (domainId == null || sourceId == null || sourceId.isBlank()) {
            return true;
        }
        String sql = """
                SELECT COUNT(*)
                FROM tech_doc_domain_source
                WHERE domain_id = :domainId
                  AND source_id = :sourceId
                  AND active IS TRUE
                """;
        Integer count = jdbcTemplate.queryForObject(
                sql,
                new MapSqlParameterSource()
                        .addValue("domainId", domainId)
                        .addValue("sourceId", sourceId.trim()),
                Integer.class
        );
        return count != null && count > 0;
    }

    public Optional<AdminConsoleRepository.DomainLanguageContext> findDomainLanguageContext(UUID domainId) {
        if (domainId == null) {
            return Optional.empty();
        }
        String sql = """
                SELECT domain_id,
                       primary_language,
                       source_language
                FROM tech_doc_domain
                WHERE domain_id = :domainId
                LIMIT 1
                """;
        List<AdminConsoleRepository.DomainLanguageContext> rows = jdbcTemplate.query(
                sql,
                new MapSqlParameterSource("domainId", domainId),
                (rs, rowNum) -> new AdminConsoleRepository.DomainLanguageContext(
                        readUuid(rs, "domain_id"),
                        rs.getString("primary_language"),
                        rs.getString("source_language")
                )
        );
        return rows.stream().findFirst();
    }

    public boolean sourceDocumentBelongsToDomain(UUID domainId, String documentId) {
        if (domainId == null || documentId == null || documentId.isBlank()) {
            return true;
        }
        String sql = """
                SELECT COUNT(*)
                FROM corpus_documents
                WHERE document_id = :documentId
                  AND domain_id = :domainId
                """;
        Integer count = jdbcTemplate.queryForObject(
                sql,
                new MapSqlParameterSource()
                        .addValue("domainId", domainId)
                        .addValue("documentId", documentId.trim()),
                Integer.class
        );
        return count != null && count > 0;
    }

    public boolean generationBatchBelongsToDomain(UUID domainId, UUID batchId) {
        return rowBelongsToDomain("synthetic_query_generation_batch", "batch_id", domainId, batchId);
    }

    public boolean gatingBatchBelongsToDomain(UUID domainId, UUID batchId) {
        return rowBelongsToDomain("quality_gating_batch", "gating_batch_id", domainId, batchId);
    }

    public boolean evalDatasetBelongsToDomain(UUID domainId, UUID datasetId) {
        return rowBelongsToDomain("eval_dataset", "dataset_id", domainId, datasetId);
    }

    public Optional<String> findSourceIdByDocumentId(String documentId) {
        String sql = "SELECT source_id FROM corpus_documents WHERE document_id = :documentId";
        List<String> rows = jdbcTemplate.query(
                sql,
                new MapSqlParameterSource("documentId", documentId),
                (rs, rowNum) -> rs.getString("source_id")
        );
        return rows.stream().findFirst();
    }

    public Optional<AdminConsoleRepository.SourceStrategyContext> findSourceStrategyContext(String sourceId) {
        String sql = """
                SELECT s.source_id,
                       s.source_type,
                       s.product_name,
                       s.source_name,
                       COALESCE(doc.has_ko, FALSE) AS has_ko_documents,
                       COALESCE(doc.has_en, FALSE) AS has_en_documents
                FROM corpus_sources s
                LEFT JOIN LATERAL (
                    SELECT BOOL_OR(LOWER(COALESCE(d.language_code, '')) = 'ko') AS has_ko,
                           BOOL_OR(LOWER(COALESCE(d.language_code, '')) = 'en') AS has_en
                    FROM corpus_documents d
                    WHERE d.source_id = s.source_id
                      AND d.is_active = TRUE
                ) doc ON TRUE
                WHERE s.source_id = :sourceId
                LIMIT 1
                """;
        List<AdminConsoleRepository.SourceStrategyContext> rows = jdbcTemplate.query(
                sql,
                new MapSqlParameterSource("sourceId", sourceId),
                (rs, rowNum) -> new AdminConsoleRepository.SourceStrategyContext(
                        rs.getString("source_id"),
                        rs.getString("source_type"),
                        rs.getString("product_name"),
                        rs.getString("source_name"),
                        rs.getBoolean("has_ko_documents"),
                        rs.getBoolean("has_en_documents")
                )
        );
        return rows.stream().findFirst();
    }

    public Optional<AdminConsoleRepository.DatasetStrategyContext> findDatasetStrategyContext(UUID datasetId) {
        String sql = """
                SELECT d.dataset_id,
                       d.dataset_key,
                       LOWER(COALESCE(d.metadata ->> 'strategy_profile', '')) AS metadata_strategy_profile,
                       LOWER(COALESCE(d.metadata ->> 'query_language', '')) AS metadata_query_language,
                       COALESCE(stats.has_spring, FALSE) AS has_spring_samples,
                       COALESCE(stats.has_python, FALSE) AS has_python_samples,
                       COALESCE(stats.has_ko_query, FALSE) AS has_ko_queries,
                       COALESCE(stats.has_en_query, FALSE) AS has_en_queries
                FROM eval_dataset d
                LEFT JOIN LATERAL (
                    SELECT BOOL_OR(LOWER(COALESCE(s.source_product, '')) LIKE '%spring%') AS has_spring,
                           BOOL_OR(LOWER(COALESCE(s.source_product, '')) LIKE '%python%') AS has_python,
                           BOOL_OR(
                               COALESCE(
                                   NULLIF(LOWER(s.query_language), ''),
                                   COALESCE(NULLIF(LOWER(d.metadata ->> 'query_language'), ''), 'ko')
                               ) = 'ko'
                           ) AS has_ko_query,
                           BOOL_OR(
                               COALESCE(
                                   NULLIF(LOWER(s.query_language), ''),
                                   COALESCE(NULLIF(LOWER(d.metadata ->> 'query_language'), ''), 'ko')
                               ) = 'en'
                           ) AS has_en_query
                    FROM eval_dataset_item i
                    JOIN eval_samples s
                      ON s.sample_id = i.sample_id
                    WHERE i.dataset_id = d.dataset_id
                      AND i.active = TRUE
                ) stats ON TRUE
                WHERE d.dataset_id = :datasetId
                LIMIT 1
                """;
        List<AdminConsoleRepository.DatasetStrategyContext> rows = jdbcTemplate.query(
                sql,
                new MapSqlParameterSource("datasetId", datasetId),
                (rs, rowNum) -> new AdminConsoleRepository.DatasetStrategyContext(
                        readUuid(rs, "dataset_id"),
                        rs.getString("dataset_key"),
                        rs.getString("metadata_strategy_profile"),
                        rs.getString("metadata_query_language"),
                        rs.getBoolean("has_spring_samples"),
                        rs.getBoolean("has_python_samples"),
                        rs.getBoolean("has_ko_queries"),
                        rs.getBoolean("has_en_queries")
                )
        );
        return rows.stream().findFirst();
    }

    private boolean rowBelongsToDomain(String tableName, String idColumn, UUID domainId, UUID rowId) {
        if (domainId == null || rowId == null) {
            return true;
        }
        String sql = """
                SELECT COUNT(*)
                FROM %s
                WHERE %s = :rowId
                  AND domain_id = :domainId
                """.formatted(tableName, idColumn);
        Integer count = jdbcTemplate.queryForObject(
                sql,
                new MapSqlParameterSource()
                        .addValue("rowId", rowId)
                        .addValue("domainId", domainId),
                Integer.class
        );
        return count != null && count > 0;
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
}
