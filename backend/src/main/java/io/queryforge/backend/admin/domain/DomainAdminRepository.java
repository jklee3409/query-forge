package io.queryforge.backend.admin.domain;

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
public class DomainAdminRepository {

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public List<DomainIdentityDtos.DomainSummary> findDomains() {
        String sql = """
                SELECT d.domain_id,
                       d.domain_key,
                       d.display_name,
                       d.description,
                       d.primary_language,
                       d.source_language,
                       d.status,
                       d.metadata_json::text AS metadata_json,
                       d.created_at,
                       d.updated_at,
                       COALESCE(source_stats.source_count, 0) AS source_count,
                       COALESCE(corpus_stats.active_document_count, 0) AS active_document_count,
                       COALESCE(corpus_stats.active_chunk_count, 0) AS active_chunk_count,
                       COALESCE(batch_stats.generation_batch_count, 0) AS generation_batch_count,
                       COALESCE(dataset_stats.eval_dataset_count, 0) AS eval_dataset_count,
                       COALESCE(rag_stats.rag_test_run_count, 0) AS rag_test_run_count
                FROM tech_doc_domain d
                LEFT JOIN LATERAL (
                    SELECT COUNT(*) AS source_count
                    FROM tech_doc_domain_source ds
                    WHERE ds.domain_id = d.domain_id
                      AND ds.active IS TRUE
                ) source_stats ON TRUE
                LEFT JOIN LATERAL (
                    SELECT COUNT(DISTINCT doc.document_id) FILTER (WHERE doc.is_active) AS active_document_count,
                           COUNT(DISTINCT ch.chunk_id) AS active_chunk_count
                    FROM corpus_sources s
                    LEFT JOIN corpus_documents doc ON doc.source_id = s.source_id
                    LEFT JOIN corpus_chunks ch ON ch.document_id = doc.document_id
                    WHERE s.domain_id = d.domain_id
                ) corpus_stats ON TRUE
                LEFT JOIN LATERAL (
                    SELECT COUNT(*) AS generation_batch_count
                    FROM synthetic_query_generation_batch b
                    WHERE b.domain_id = d.domain_id
                ) batch_stats ON TRUE
                LEFT JOIN LATERAL (
                    SELECT COUNT(*) AS eval_dataset_count
                    FROM eval_dataset ed
                    WHERE ed.domain_id = d.domain_id
                ) dataset_stats ON TRUE
                LEFT JOIN LATERAL (
                    SELECT COUNT(*) AS rag_test_run_count
                    FROM rag_test_run r
                    WHERE r.domain_id = d.domain_id
                ) rag_stats ON TRUE
                ORDER BY d.status, d.display_name, d.domain_key
                """;
        return jdbcTemplate.query(sql, summaryRowMapper());
    }

    public Optional<DomainIdentityDtos.DomainSummary> findDomain(String domainRef) {
        String sql = """
                SELECT *
                FROM (
                    SELECT d.domain_id,
                           d.domain_key,
                           d.display_name,
                           d.description,
                           d.primary_language,
                           d.source_language,
                           d.status,
                           d.metadata_json::text AS metadata_json,
                           d.created_at,
                           d.updated_at,
                           COALESCE(source_stats.source_count, 0) AS source_count,
                           COALESCE(corpus_stats.active_document_count, 0) AS active_document_count,
                           COALESCE(corpus_stats.active_chunk_count, 0) AS active_chunk_count,
                           COALESCE(batch_stats.generation_batch_count, 0) AS generation_batch_count,
                           COALESCE(dataset_stats.eval_dataset_count, 0) AS eval_dataset_count,
                           COALESCE(rag_stats.rag_test_run_count, 0) AS rag_test_run_count
                    FROM tech_doc_domain d
                    LEFT JOIN LATERAL (
                        SELECT COUNT(*) AS source_count
                        FROM tech_doc_domain_source ds
                        WHERE ds.domain_id = d.domain_id
                          AND ds.active IS TRUE
                    ) source_stats ON TRUE
                    LEFT JOIN LATERAL (
                        SELECT COUNT(DISTINCT doc.document_id) FILTER (WHERE doc.is_active) AS active_document_count,
                               COUNT(DISTINCT ch.chunk_id) AS active_chunk_count
                        FROM corpus_sources s
                        LEFT JOIN corpus_documents doc ON doc.source_id = s.source_id
                        LEFT JOIN corpus_chunks ch ON ch.document_id = doc.document_id
                        WHERE s.domain_id = d.domain_id
                    ) corpus_stats ON TRUE
                    LEFT JOIN LATERAL (
                        SELECT COUNT(*) AS generation_batch_count
                        FROM synthetic_query_generation_batch b
                        WHERE b.domain_id = d.domain_id
                    ) batch_stats ON TRUE
                    LEFT JOIN LATERAL (
                        SELECT COUNT(*) AS eval_dataset_count
                        FROM eval_dataset ed
                        WHERE ed.domain_id = d.domain_id
                    ) dataset_stats ON TRUE
                    LEFT JOIN LATERAL (
                        SELECT COUNT(*) AS rag_test_run_count
                        FROM rag_test_run r
                        WHERE r.domain_id = d.domain_id
                    ) rag_stats ON TRUE
                ) d
                WHERE d.domain_key = :domainRef
                   OR d.domain_id::text = :domainRef
                """;
        return jdbcTemplate.query(sql, new MapSqlParameterSource("domainRef", domainRef), summaryRowMapper())
                .stream()
                .findFirst();
    }

    @Transactional
    public UUID createDomain(DomainIdentityDtos.DomainCreateRequest request) {
        String sql = """
                INSERT INTO tech_doc_domain (
                    domain_key,
                    display_name,
                    description,
                    primary_language,
                    source_language,
                    metadata_json,
                    created_by
                )
                VALUES (
                    :domainKey,
                    :displayName,
                    :description,
                    :primaryLanguage,
                    :sourceLanguage,
                    CAST(:metadata AS jsonb),
                    :createdBy
                )
                RETURNING domain_id
                """;
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("domainKey", request.domainKey())
                .addValue("displayName", request.displayName())
                .addValue("description", request.description())
                .addValue("primaryLanguage", request.primaryLanguage())
                .addValue("sourceLanguage", request.sourceLanguage())
                .addValue("metadata", jsonString(request.metadata()))
                .addValue("createdBy", request.createdBy());
        return jdbcTemplate.queryForObject(sql, params, UUID.class);
    }

    @Transactional
    public void updateDomain(UUID domainId, DomainIdentityDtos.DomainUpdateRequest request) {
        String sql = """
                UPDATE tech_doc_domain
                SET display_name = COALESCE(:displayName, display_name),
                    description = COALESCE(:description, description),
                    primary_language = COALESCE(:primaryLanguage, primary_language),
                    source_language = COALESCE(:sourceLanguage, source_language),
                    status = COALESCE(:status, status),
                    metadata_json = COALESCE(CAST(:metadata AS jsonb), metadata_json),
                    updated_at = NOW()
                WHERE domain_id = :domainId
                """;
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("domainId", domainId)
                .addValue("displayName", blankToNull(request.displayName()))
                .addValue("description", request.description())
                .addValue("primaryLanguage", blankToNull(request.primaryLanguage()))
                .addValue("sourceLanguage", blankToNull(request.sourceLanguage()))
                .addValue("status", blankToNull(request.status()))
                .addValue("metadata", request.metadata() == null ? null : jsonString(request.metadata()));
        jdbcTemplate.update(sql, params);
    }

    private RowMapper<DomainIdentityDtos.DomainSummary> summaryRowMapper() {
        return (rs, rowNum) -> new DomainIdentityDtos.DomainSummary(
                rs.getObject("domain_id", UUID.class),
                rs.getString("domain_key"),
                rs.getString("display_name"),
                rs.getString("description"),
                rs.getString("primary_language"),
                rs.getString("source_language"),
                rs.getString("status"),
                readJson(rs, "metadata_json"),
                rs.getLong("source_count"),
                rs.getLong("active_document_count"),
                rs.getLong("active_chunk_count"),
                rs.getLong("generation_batch_count"),
                rs.getLong("eval_dataset_count"),
                rs.getLong("rag_test_run_count"),
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

    private String jsonString(JsonNode node) {
        return node == null ? "{}" : node.toString();
    }

    private Instant readInstant(ResultSet rs, String column) throws SQLException {
        Timestamp timestamp = rs.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String blankToDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
