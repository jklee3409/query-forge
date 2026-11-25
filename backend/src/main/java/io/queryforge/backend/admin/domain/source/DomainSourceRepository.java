package io.queryforge.backend.admin.domain.source;

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
import java.util.UUID;

@Repository
@RequiredArgsConstructor
@Transactional(readOnly = true)
class DomainSourceRepository {

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public List<DomainSourceDtos.DomainSource> findSources(UUID domainId) {
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

    @Transactional
    public void attachSource(UUID domainId, DomainSourceDtos.DomainSourceAttachRequest request) {
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

    private RowMapper<DomainSourceDtos.DomainSource> sourceRowMapper() {
        return (rs, rowNum) -> new DomainSourceDtos.DomainSource(
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

    private Instant readInstant(ResultSet rs, String column) throws SQLException {
        Timestamp timestamp = rs.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }

    private String blankToDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
