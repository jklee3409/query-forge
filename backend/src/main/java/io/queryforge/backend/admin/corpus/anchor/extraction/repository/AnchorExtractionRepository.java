package io.queryforge.backend.admin.corpus.anchor.extraction.repository;

import io.queryforge.backend.admin.corpus.anchor.extraction.dto.AnchorExtractionDtos.AnchorCandidate;
import io.queryforge.backend.admin.corpus.anchor.extraction.dto.AnchorExtractionDtos.AnchorChunkRow;
import io.queryforge.backend.admin.corpus.anchor.extraction.dto.AnchorExtractionDtos.AnchorRemapSummary;
import io.queryforge.backend.admin.corpus.anchor.extraction.dto.AnchorExtractionDtos.TermRefreshSummary;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class AnchorExtractionRepository {

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public List<AnchorChunkRow> findTargetChunks(List<String> documentIds, List<String> chunkIds) {
        StringBuilder sql = new StringBuilder("""
                SELECT c.chunk_id, c.document_id, c.chunk_text, c.product_name
                FROM corpus_chunks c
                WHERE 1=1
                """);
        MapSqlParameterSource params = new MapSqlParameterSource();
        if (!documentIds.isEmpty()) {
            sql.append(" AND c.document_id = ANY(:documentIds)");
            params.addValue("documentIds", documentIds.toArray(String[]::new));
        } else if (!chunkIds.isEmpty()) {
            sql.append(" AND c.chunk_id = ANY(:chunkIds)");
            params.addValue("chunkIds", chunkIds.toArray(String[]::new));
        }
        sql.append(" ORDER BY c.document_id, c.chunk_index_in_document");
        return jdbcTemplate.query(
                sql.toString(),
                params,
                (rs, rowNum) -> new AnchorChunkRow(
                        rs.getString("chunk_id"),
                        rs.getString("document_id"),
                        rs.getString("chunk_text"),
                        rs.getString("product_name")
                )
        );
    }

    public Set<UUID> findTermIdsByChunkIds(List<String> chunkIds) {
        if (chunkIds.isEmpty()) {
            return Set.of();
        }
        String sql = """
                SELECT DISTINCT term_id
                FROM corpus_glossary_evidence
                WHERE chunk_id = ANY(:chunkIds)
                """;
        MapSqlParameterSource params = new MapSqlParameterSource("chunkIds", chunkIds.toArray(String[]::new));
        return new LinkedHashSet<>(jdbcTemplate.query(sql, params, (rs, rowNum) -> rs.getObject("term_id", UUID.class)));
    }

    public int deleteEvidenceByChunkIds(List<String> chunkIds) {
        if (chunkIds.isEmpty()) {
            return 0;
        }
        String sql = "DELETE FROM corpus_glossary_evidence WHERE chunk_id = ANY(:chunkIds)";
        return jdbcTemplate.update(sql, new MapSqlParameterSource("chunkIds", chunkIds.toArray(String[]::new)));
    }

    public UUID upsertGlossaryTerm(AnchorCandidate candidate) {
        String sql = """
                INSERT INTO corpus_glossary_terms (
                    canonical_form,
                    normalized_form,
                    term_type,
                    keep_in_english,
                    description_short,
                    source_confidence,
                    first_seen_document_id,
                    first_seen_chunk_id,
                    evidence_count,
                    is_active,
                    metadata_json,
                    created_at,
                    updated_at
                ) VALUES (
                    :canonicalForm,
                    :normalizedForm,
                    :termType,
                    TRUE,
                    :descriptionShort,
                    0.85,
                    :documentId,
                    :chunkId,
                    0,
                    TRUE,
                    CAST(:metadataJson AS jsonb),
                    NOW(),
                    NOW()
                )
                ON CONFLICT (term_type, normalized_form) DO UPDATE
                SET canonical_form = EXCLUDED.canonical_form,
                    keep_in_english = TRUE,
                    description_short = EXCLUDED.description_short,
                    is_active = TRUE,
                    updated_at = NOW()
                RETURNING term_id
                """;
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("canonicalForm", candidate.canonicalForm())
                .addValue("normalizedForm", candidate.normalizedForm())
                .addValue("termType", mapTermType(candidate.termType()))
                .addValue("descriptionShort", "Re-extracted anchor term from selected chunks.")
                .addValue("documentId", candidate.documentId())
                .addValue("chunkId", candidate.chunkId())
                .addValue("metadataJson", "{\"reextracted\":true}");
        return jdbcTemplate.queryForObject(sql, params, (rs, rowNum) -> rs.getObject("term_id", UUID.class));
    }

    public int insertEvidence(UUID termId, AnchorCandidate candidate) {
        String sql = """
                INSERT INTO corpus_glossary_evidence (
                    evidence_id,
                    term_id,
                    document_id,
                    chunk_id,
                    matched_text,
                    line_or_offset_info,
                    created_at
                ) VALUES (
                    :evidenceId,
                    :termId,
                    :documentId,
                    :chunkId,
                    :matchedText,
                    CAST(:offsetInfo AS jsonb),
                    NOW()
                )
                """;
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("evidenceId", UUID.randomUUID())
                .addValue("termId", termId)
                .addValue("documentId", candidate.documentId())
                .addValue("chunkId", candidate.chunkId())
                .addValue("matchedText", candidate.matchedText())
                .addValue("offsetInfo", "{\"reextracted\":true}");
        return jdbcTemplate.update(sql, params);
    }

    public TermRefreshSummary refreshTouchedTerms(Set<UUID> termIds) {
        if (termIds.isEmpty()) {
            return new TermRefreshSummary(0, 0);
        }
        int updated = 0;
        int deactivated = 0;
        for (UUID termId : termIds) {
            String statSql = """
                    SELECT COUNT(*) AS evidence_count,
                           MIN(document_id) AS first_document_id,
                           MIN(chunk_id) AS first_chunk_id
                    FROM corpus_glossary_evidence
                    WHERE term_id = :termId
                    """;
            MapSqlParameterSource params = new MapSqlParameterSource("termId", termId);
            TermStatRow stat = jdbcTemplate.queryForObject(
                    statSql,
                    params,
                    (rs, rowNum) -> new TermStatRow(
                            rs.getInt("evidence_count"),
                            rs.getString("first_document_id"),
                            rs.getString("first_chunk_id")
                    )
            );
            if (stat == null) {
                continue;
            }
            String updateSql = """
                    UPDATE corpus_glossary_terms
                    SET evidence_count = :evidenceCount,
                        first_seen_document_id = :firstDocumentId,
                        first_seen_chunk_id = :firstChunkId,
                        is_active = :active,
                        updated_at = NOW()
                    WHERE term_id = :termId
                    """;
            boolean active = stat.evidenceCount() > 0;
            updated += jdbcTemplate.update(
                    updateSql,
                    new MapSqlParameterSource()
                            .addValue("termId", termId)
                            .addValue("evidenceCount", stat.evidenceCount())
                            .addValue("firstDocumentId", stat.firstDocumentId())
                            .addValue("firstChunkId", stat.firstChunkId())
                            .addValue("active", active)
            );
            if (!active) {
                deactivated += 1;
            }
        }
        return new TermRefreshSummary(updated, deactivated);
    }

    public AnchorRemapSummary remapSyntheticQueryAnchors(List<String> chunkIds) {
        if (chunkIds.isEmpty()) {
            return new AnchorRemapSummary(0, 0);
        }
        List<String> affectedQueryIds = findAffectedSyntheticQueryIds(chunkIds);
        if (affectedQueryIds.isEmpty()) {
            return new AnchorRemapSummary(0, 0);
        }
        jdbcTemplate.update(
                "DELETE FROM synthetic_query_anchor_link WHERE synthetic_query_id = ANY(:queryIds)",
                new MapSqlParameterSource("queryIds", affectedQueryIds.toArray(String[]::new))
        );

        String insertSql = """
                WITH affected_queries AS (
                    SELECT unnest(CAST(:queryIds AS text[])) AS synthetic_query_id
                ),
                query_chunks AS (
                    SELECT r.synthetic_query_id, r.chunk_id_source AS chunk_id
                    FROM synthetic_queries_raw_all r
                    JOIN affected_queries aq ON aq.synthetic_query_id = r.synthetic_query_id
                    WHERE r.chunk_id_source IS NOT NULL
                    UNION
                    SELECT r.synthetic_query_id, source_chunks.value AS chunk_id
                    FROM synthetic_queries_raw_all r
                    JOIN affected_queries aq ON aq.synthetic_query_id = r.synthetic_query_id
                    JOIN LATERAL jsonb_array_elements_text(COALESCE(r.source_chunk_ids, '[]'::jsonb)) source_chunks ON TRUE
                ),
                candidates AS (
                    SELECT DISTINCT qc.synthetic_query_id, e.term_id, qc.chunk_id
                    FROM query_chunks qc
                    JOIN corpus_glossary_evidence e ON e.chunk_id = qc.chunk_id
                    JOIN corpus_glossary_terms t ON t.term_id = e.term_id
                    WHERE t.is_active = TRUE
                )
                INSERT INTO synthetic_query_anchor_link (
                    synthetic_query_id,
                    term_id,
                    source_chunk_id,
                    is_active,
                    created_at,
                    updated_at
                )
                SELECT c.synthetic_query_id,
                       c.term_id,
                       c.chunk_id,
                       TRUE,
                       NOW(),
                       NOW()
                FROM candidates c
                ON CONFLICT (synthetic_query_id, term_id, source_chunk_id) DO UPDATE
                SET is_active = TRUE,
                    updated_at = NOW()
                """;
        int inserted = jdbcTemplate.update(
                insertSql,
                new MapSqlParameterSource("queryIds", affectedQueryIds.toArray(String[]::new))
        );
        return new AnchorRemapSummary(affectedQueryIds.size(), inserted);
    }

    private List<String> findAffectedSyntheticQueryIds(List<String> chunkIds) {
        String sql = """
                SELECT DISTINCT query_id
                FROM (
                    SELECT r.synthetic_query_id AS query_id
                    FROM synthetic_queries_raw_all r
                    WHERE r.chunk_id_source = ANY(:chunkIds)
                    UNION
                    SELECT r.synthetic_query_id AS query_id
                    FROM synthetic_queries_raw_all r
                    JOIN LATERAL jsonb_array_elements_text(COALESCE(r.source_chunk_ids, '[]'::jsonb)) source_chunks ON TRUE
                    WHERE source_chunks.value = ANY(:chunkIds)
                ) q
                """;
        return jdbcTemplate.query(
                sql,
                new MapSqlParameterSource("chunkIds", chunkIds.toArray(String[]::new)),
                (rs, rowNum) -> rs.getString("query_id")
        );
    }

    private String mapTermType(String rawType) {
        return switch (rawType) {
            case "class_interface" -> "class";
            case "dependency_artifact" -> "artifact";
            case "config_key" -> "config_key";
            case "annotation" -> "annotation";
            case "spring_product" -> "product";
            case "cli_command" -> "cli";
            default -> "concept";
        };
    }

    private record TermStatRow(
            int evidenceCount,
            String firstDocumentId,
            String firstChunkId
    ) {
    }
}
