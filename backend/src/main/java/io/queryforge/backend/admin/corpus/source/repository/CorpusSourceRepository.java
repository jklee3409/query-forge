package io.queryforge.backend.admin.corpus.source.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.queryforge.backend.admin.corpus.source.dto.CorpusSourceDtos;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

import static io.queryforge.backend.admin.corpus.persistence.support.CorpusJdbc.readInstant;
import static io.queryforge.backend.admin.corpus.persistence.support.CorpusJdbc.readJson;

@Repository
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CorpusSourceRepository {

    private final ObjectMapper objectMapper;

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public List<CorpusSourceDtos.SourceSummary> findSources() {
        return findSources(null);
    }

    public List<CorpusSourceDtos.SourceSummary> findSources(UUID domainId) {
        String sql = """
                SELECT cs.source_id,
                       cs.source_type,
                       cs.product_name,
                       cs.source_name,
                       cs.base_url,
                       cs.include_patterns::text AS include_patterns,
                       cs.exclude_patterns::text AS exclude_patterns,
                       cs.default_version,
                       cs.enabled,
                       cs.created_at,
                       cs.updated_at,
                       COALESCE(doc_stats.total_documents, 0) AS total_documents,
                       COALESCE(doc_stats.active_documents, 0) AS active_documents,
                       COALESCE(version_stats.version_stats, '[]'::jsonb)::text AS version_stats
                FROM corpus_sources cs
                LEFT JOIN LATERAL (
                    SELECT COUNT(*) AS total_documents,
                           COUNT(*) FILTER (WHERE is_active) AS active_documents
                    FROM corpus_documents cd
                    WHERE cd.source_id = cs.source_id
                ) doc_stats ON TRUE
                LEFT JOIN LATERAL (
                    SELECT jsonb_agg(
                               jsonb_build_object(
                                   'version_label', version_counts.version_label,
                                   'document_count', version_counts.document_count,
                                   'active_count', version_counts.active_count
                               )
                               ORDER BY version_counts.version_label
                           ) AS version_stats
                    FROM (
                        SELECT cd.version_label,
                               COUNT(*) AS document_count,
                               COUNT(*) FILTER (WHERE cd.is_active) AS active_count
                        FROM corpus_documents cd
                        WHERE cd.source_id = cs.source_id
                        GROUP BY cd.version_label
                    ) version_counts
                ) version_stats ON TRUE
                WHERE (
                    CAST(:domainId AS uuid) IS NULL
                    OR EXISTS (
                        SELECT 1
                        FROM tech_doc_domain_source ds
                        WHERE ds.domain_id = :domainId
                          AND ds.source_id = cs.source_id
                          AND ds.active IS TRUE
                    )
                )
                ORDER BY cs.product_name, cs.source_id
                """;
        return jdbcTemplate.query(sql, new MapSqlParameterSource("domainId", domainId), sourceRowMapper());
    }

    public CorpusSourceDtos.SourceSummary findSourceById(String sourceId) {
        String sql = """
                SELECT cs.source_id,
                       cs.source_type,
                       cs.product_name,
                       cs.source_name,
                       cs.base_url,
                       cs.include_patterns::text AS include_patterns,
                       cs.exclude_patterns::text AS exclude_patterns,
                       cs.default_version,
                       cs.enabled,
                       cs.created_at,
                       cs.updated_at,
                       COALESCE(doc_stats.total_documents, 0) AS total_documents,
                       COALESCE(doc_stats.active_documents, 0) AS active_documents,
                       COALESCE(version_stats.version_stats, '[]'::jsonb)::text AS version_stats
                FROM corpus_sources cs
                LEFT JOIN LATERAL (
                    SELECT COUNT(*) AS total_documents,
                           COUNT(*) FILTER (WHERE is_active) AS active_documents
                    FROM corpus_documents cd
                    WHERE cd.source_id = cs.source_id
                ) doc_stats ON TRUE
                LEFT JOIN LATERAL (
                    SELECT jsonb_agg(
                               jsonb_build_object(
                                   'version_label', version_counts.version_label,
                                   'document_count', version_counts.document_count,
                                   'active_count', version_counts.active_count
                               )
                               ORDER BY version_counts.version_label
                           ) AS version_stats
                    FROM (
                        SELECT cd.version_label,
                               COUNT(*) AS document_count,
                               COUNT(*) FILTER (WHERE cd.is_active) AS active_count
                        FROM corpus_documents cd
                        WHERE cd.source_id = cs.source_id
                        GROUP BY cd.version_label
                    ) version_counts
                ) version_stats ON TRUE
                WHERE cs.source_id = :sourceId
                """;
        return jdbcTemplate.queryForObject(
                sql,
                new MapSqlParameterSource("sourceId", sourceId),
                sourceRowMapper()
        );
    }

    @Transactional
    public void updateSourceEnabled(String sourceId, boolean enabled) {
        String sql = """
                UPDATE corpus_sources
                SET enabled = :enabled,
                    updated_at = NOW()
                WHERE source_id = :sourceId
                """;
        jdbcTemplate.update(
                sql,
                new MapSqlParameterSource()
                        .addValue("sourceId", sourceId)
                        .addValue("enabled", enabled)
        );
    }

    private RowMapper<CorpusSourceDtos.SourceSummary> sourceRowMapper() {
        return (rs, rowNum) -> new CorpusSourceDtos.SourceSummary(
                rs.getString("source_id"),
                rs.getString("source_type"),
                rs.getString("product_name"),
                rs.getString("source_name"),
                rs.getString("base_url"),
                readJson(objectMapper, rs, "include_patterns"),
                readJson(objectMapper, rs, "exclude_patterns"),
                rs.getString("default_version"),
                rs.getBoolean("enabled"),
                rs.getLong("total_documents"),
                rs.getLong("active_documents"),
                readJson(objectMapper, rs, "version_stats"),
                readInstant(rs, "created_at"),
                readInstant(rs, "updated_at")
        );
    }
}
