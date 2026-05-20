package io.queryforge.backend.admin.domain.repository;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.queryforge.backend.admin.domain.model.DomainAdminDtos;
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

    public List<DomainAdminDtos.DomainSummary> findDomains() {
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

    public Optional<DomainAdminDtos.DomainSummary> findDomain(String domainRef) {
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

    public List<DomainAdminDtos.DomainSource> findSources(UUID domainId) {
        String sql = """
                SELECT cs.source_id,
                       cs.source_type,
                       cs.product_name,
                       cs.source_name,
                       cs.enabled,
                       ds.source_role,
                       ds.active,
                       ds.created_at,
                       COALESCE(doc_stats.active_document_count, 0) AS active_document_count,
                       COALESCE(doc_stats.active_chunk_count, 0) AS active_chunk_count
                FROM tech_doc_domain_source ds
                JOIN corpus_sources cs ON cs.source_id = ds.source_id
                LEFT JOIN LATERAL (
                    SELECT COUNT(DISTINCT d.document_id) FILTER (WHERE d.is_active) AS active_document_count,
                           COUNT(DISTINCT c.chunk_id) AS active_chunk_count
                    FROM corpus_documents d
                    LEFT JOIN corpus_chunks c ON c.document_id = d.document_id
                    WHERE d.source_id = cs.source_id
                ) doc_stats ON TRUE
                WHERE ds.domain_id = :domainId
                ORDER BY ds.active DESC, cs.product_name, cs.source_id
                """;
        return jdbcTemplate.query(sql, new MapSqlParameterSource("domainId", domainId), sourceRowMapper());
    }

    public List<DomainAdminDtos.DomainMethodPolicy> findMethodPolicies(UUID domainId) {
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
        return jdbcTemplate.query(sql, new MapSqlParameterSource("domainId", domainId), methodPolicyRowMapper());
    }

    public Optional<DomainAdminDtos.DomainDashboardSummary> findDashboardSummary(String domainRef) {
        String sql = """
                SELECT d.domain_id,
                       d.domain_key,
                       d.display_name,
                       COALESCE(source_stats.source_count, 0) AS source_count,
                       COALESCE(corpus_stats.active_document_count, 0) AS active_document_count,
                       COALESCE(corpus_stats.active_chunk_count, 0) AS active_chunk_count,
                       COALESCE(glossary_stats.glossary_term_count, 0) AS glossary_term_count,
                       COALESCE(raw_stats.synthetic_raw_count, 0) AS synthetic_raw_count,
                       COALESCE(gated_stats.gated_query_count, 0) AS gated_query_count,
                       COALESCE(memory_stats.memory_entry_count, 0) AS memory_entry_count,
                       COALESCE(dataset_stats.eval_dataset_count, 0) AS eval_dataset_count,
                       COALESCE(rag_stats.rag_test_run_count, 0) AS rag_test_run_count,
                       latest_rag.status AS latest_rag_status,
                       latest_rag.created_at AS latest_rag_created_at
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
                    SELECT COUNT(*) AS glossary_term_count
                    FROM corpus_glossary_terms t
                    WHERE t.domain_id = d.domain_id
                      AND t.is_active IS TRUE
                ) glossary_stats ON TRUE
                LEFT JOIN LATERAL (
                    SELECT COUNT(*) AS synthetic_raw_count
                    FROM synthetic_query_registry r
                    WHERE r.domain_id = d.domain_id
                ) raw_stats ON TRUE
                LEFT JOIN LATERAL (
                    SELECT COUNT(*) AS gated_query_count
                    FROM synthetic_queries_gated g
                    WHERE g.domain_id = d.domain_id
                ) gated_stats ON TRUE
                LEFT JOIN LATERAL (
                    SELECT COUNT(*) AS memory_entry_count
                    FROM memory_entries m
                    WHERE m.domain_id = d.domain_id
                ) memory_stats ON TRUE
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
                LEFT JOIN LATERAL (
                    SELECT r.status, r.created_at
                    FROM rag_test_run r
                    WHERE r.domain_id = d.domain_id
                    ORDER BY r.created_at DESC
                    LIMIT 1
                ) latest_rag ON TRUE
                WHERE d.domain_key = :domainRef
                   OR d.domain_id::text = :domainRef
                """;
        return jdbcTemplate.query(sql, new MapSqlParameterSource("domainRef", domainRef), dashboardRowMapper())
                .stream()
                .findFirst();
    }

    @Transactional
    public UUID createDomain(DomainAdminDtos.DomainCreateRequest request) {
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
    public void updateDomain(UUID domainId, DomainAdminDtos.DomainUpdateRequest request) {
        String sql = """
                UPDATE tech_doc_domain
                SET display_name = COALESCE(:displayName, display_name),
                    description = COALESCE(:description, description),
                    primary_language = COALESCE(:primaryLanguage, primary_language),
                    source_language = COALESCE(:sourceLanguage, source_language),
                    status = COALESCE(:status, status),
                    metadata_json = CASE
                        WHEN :metadata IS NULL THEN metadata_json
                        ELSE CAST(:metadata AS jsonb)
                    END,
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

    @Transactional
    public void attachSource(UUID domainId, DomainAdminDtos.DomainSourceAttachRequest request) {
        String sql = """
                INSERT INTO tech_doc_domain_source (domain_id, source_id, source_role, active)
                VALUES (:domainId, :sourceId, :sourceRole, :active)
                ON CONFLICT (source_id) DO UPDATE
                SET domain_id = EXCLUDED.domain_id,
                    source_role = EXCLUDED.source_role,
                    active = EXCLUDED.active
                """;
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("domainId", domainId)
                .addValue("sourceId", request.sourceId())
                .addValue("sourceRole", blankToDefault(request.sourceRole(), "primary"))
                .addValue("active", request.active() == null || request.active());
        jdbcTemplate.update(sql, params);
        jdbcTemplate.update(
                "UPDATE corpus_sources SET domain_id = :domainId, updated_at = NOW() WHERE source_id = :sourceId",
                params
        );
    }

    @Transactional
    public void detachSource(UUID domainId, String sourceId) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("domainId", domainId)
                .addValue("sourceId", sourceId);
        jdbcTemplate.update(
                "DELETE FROM tech_doc_domain_source WHERE domain_id = :domainId AND source_id = :sourceId",
                params
        );
        jdbcTemplate.update(
                "UPDATE corpus_sources SET domain_id = NULL, updated_at = NOW() WHERE source_id = :sourceId AND domain_id = :domainId",
                params
        );
    }

    private RowMapper<DomainAdminDtos.DomainSummary> summaryRowMapper() {
        return (rs, rowNum) -> new DomainAdminDtos.DomainSummary(
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

    private RowMapper<DomainAdminDtos.DomainSource> sourceRowMapper() {
        return (rs, rowNum) -> new DomainAdminDtos.DomainSource(
                rs.getString("source_id"),
                rs.getString("source_type"),
                rs.getString("product_name"),
                rs.getString("source_name"),
                rs.getBoolean("enabled"),
                rs.getString("source_role"),
                rs.getBoolean("active"),
                rs.getLong("active_document_count"),
                rs.getLong("active_chunk_count"),
                readInstant(rs, "created_at")
        );
    }

    private RowMapper<DomainAdminDtos.DomainMethodPolicy> methodPolicyRowMapper() {
        return (rs, rowNum) -> new DomainAdminDtos.DomainMethodPolicy(
                rs.getString("method_code"),
                rs.getString("method_name"),
                rs.getBoolean("method_active"),
                rs.getBoolean("enabled"),
                rs.getString("default_query_language"),
                readJson(rs, "metadata_json"),
                readInstant(rs, "created_at")
        );
    }

    private RowMapper<DomainAdminDtos.DomainDashboardSummary> dashboardRowMapper() {
        return (rs, rowNum) -> new DomainAdminDtos.DomainDashboardSummary(
                rs.getObject("domain_id", UUID.class),
                rs.getString("domain_key"),
                rs.getString("display_name"),
                rs.getLong("source_count"),
                rs.getLong("active_document_count"),
                rs.getLong("active_chunk_count"),
                rs.getLong("glossary_term_count"),
                rs.getLong("synthetic_raw_count"),
                rs.getLong("gated_query_count"),
                rs.getLong("memory_entry_count"),
                rs.getLong("eval_dataset_count"),
                rs.getLong("rag_test_run_count"),
                rs.getString("latest_rag_status"),
                readInstant(rs, "latest_rag_created_at")
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
