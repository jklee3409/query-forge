package io.queryforge.backend.admin.domain.dashboard;

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
import java.util.Optional;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DomainDashboardRepository {

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public Optional<DomainDashboardDtos.DomainDashboardSummary> findDashboardSummary(String domainRef) {
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

    private RowMapper<DomainDashboardDtos.DomainDashboardSummary> dashboardRowMapper() {
        return (rs, rowNum) -> new DomainDashboardDtos.DomainDashboardSummary(
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

    private Instant readInstant(ResultSet rs, String column) throws SQLException {
        Timestamp timestamp = rs.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }
}
