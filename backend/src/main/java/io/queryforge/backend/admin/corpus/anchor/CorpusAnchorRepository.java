package io.queryforge.backend.admin.corpus.anchor;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static io.queryforge.backend.admin.corpus.CorpusJdbc.like;
import static io.queryforge.backend.admin.corpus.CorpusJdbc.normalizeLimit;
import static io.queryforge.backend.admin.corpus.CorpusJdbc.normalizeOffset;
import static io.queryforge.backend.admin.corpus.CorpusJdbc.readInstant;
import static io.queryforge.backend.admin.corpus.CorpusJdbc.readUuid;

@Repository
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CorpusAnchorRepository {

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public List<CorpusAnchorDtos.AnchorSummary> findAnchors(
            String documentId,
            String chunkId,
            String keyword,
            boolean activeOnly,
            Integer limit,
            Integer offset
    ) {
        StringBuilder sql = new StringBuilder("""
                SELECT gt.term_id,
                       gt.canonical_form,
                       gt.normalized_form,
                       gt.term_type,
                       gt.keep_in_english,
                       gt.source_confidence,
                       gt.evidence_count,
                       COALESCE(scope_stats.scoped_evidence_count, 0) AS scoped_evidence_count,
                       gt.first_seen_document_id,
                       gt.first_seen_chunk_id,
                       gt.updated_at
                FROM corpus_glossary_terms gt
                LEFT JOIN LATERAL (
                    SELECT COUNT(*) AS scoped_evidence_count
                    FROM corpus_glossary_evidence e
                    WHERE e.term_id = gt.term_id
                """);
        MapSqlParameterSource params = new MapSqlParameterSource();
        appendAnchorEvidenceScopeFilters(sql, params, "e", documentId, chunkId);
        sql.append("""
                ) scope_stats ON TRUE
                WHERE 1=1
                """);

        if (activeOnly) {
            sql.append(" AND gt.is_active = TRUE");
        }
        if (keyword != null && !keyword.isBlank()) {
            sql.append(" AND gt.canonical_form ILIKE :keyword");
            params.addValue("keyword", like(keyword));
        }
        if ((documentId != null && !documentId.isBlank()) || (chunkId != null && !chunkId.isBlank())) {
            sql.append("""
                     AND EXISTS (
                         SELECT 1
                         FROM corpus_glossary_evidence e2
                         WHERE e2.term_id = gt.term_id
                    """);
            appendAnchorEvidenceScopeFilters(sql, params, "e2", documentId, chunkId);
            sql.append(")");
        }

        sql.append("""

                ORDER BY COALESCE(scope_stats.scoped_evidence_count, 0) DESC,
                         gt.evidence_count DESC,
                         gt.canonical_form
                LIMIT :limit OFFSET :offset
                """);
        params.addValue("limit", normalizeLimit(limit));
        params.addValue("offset", normalizeOffset(offset));
        return jdbcTemplate.query(sql.toString(), params, anchorSummaryRowMapper());
    }

    private void appendAnchorEvidenceScopeFilters(
            StringBuilder sql,
            MapSqlParameterSource params,
            String evidenceAlias,
            String documentId,
            String chunkId
    ) {
        if (documentId != null && !documentId.isBlank()) {
            sql.append(" AND ").append(evidenceAlias).append(".document_id = :anchorDocumentId");
            params.addValue("anchorDocumentId", documentId);
        }
        if (chunkId != null && !chunkId.isBlank()) {
            sql.append(" AND ").append(evidenceAlias).append(".chunk_id = :anchorChunkId");
            params.addValue("anchorChunkId", chunkId);
        }
    }

    private RowMapper<CorpusAnchorDtos.AnchorSummary> anchorSummaryRowMapper() {
        return (rs, rowNum) -> new CorpusAnchorDtos.AnchorSummary(
                readUuid(rs, "term_id"),
                rs.getString("canonical_form"),
                rs.getString("normalized_form"),
                rs.getString("term_type"),
                rs.getBoolean("keep_in_english"),
                rs.getDouble("source_confidence"),
                rs.getInt("evidence_count"),
                rs.getInt("scoped_evidence_count"),
                rs.getString("first_seen_document_id"),
                rs.getString("first_seen_chunk_id"),
                readInstant(rs, "updated_at")
        );
    }
}
