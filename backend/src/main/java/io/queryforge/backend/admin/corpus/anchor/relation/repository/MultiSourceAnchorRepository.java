package io.queryforge.backend.admin.corpus.anchor.relation.repository;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.queryforge.backend.admin.corpus.anchor.relation.dto.MultiSourceAnchorDtos;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static io.queryforge.backend.admin.corpus.anchor.support.AnchorSchemaVersions.MAPPING_VERSION;
import static io.queryforge.backend.admin.corpus.anchor.support.AnchorSchemaVersions.NORMALIZATION_VERSION;
import static io.queryforge.backend.admin.corpus.anchor.support.AnchorSchemaVersions.RUNTIME_SCHEMA_VERSION;

@Repository
@RequiredArgsConstructor
public class MultiSourceAnchorRepository {

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public void createRun(
            UUID runId,
            String runName,
            String relationVersion,
            JsonNode relationTypeAllowlist,
            double minRelationScore,
            Integer maxRelationsPerAnchor,
            String createdBy
    ) {
        jdbcTemplate.update(
                """
                INSERT INTO canonical_anchor_relation_run (
                    run_id,
                    run_name,
                    relation_version,
                    mapping_version,
                    normalization_version,
                    canonical_anchor_runtime_schema_version,
                    status,
                    relation_type_allowlist,
                    min_relation_score,
                    max_relations_per_anchor,
                    created_by
                ) VALUES (
                    :runId,
                    :runName,
                    :relationVersion,
                    :mappingVersion,
                    :normalizationVersion,
                    :runtimeSchemaVersion,
                    'running',
                    CAST(:relationTypeAllowlist AS jsonb),
                    :minRelationScore,
                    :maxRelationsPerAnchor,
                    :createdBy
                )
                """,
                new MapSqlParameterSource()
                        .addValue("runId", runId)
                        .addValue("runName", runName)
                        .addValue("relationVersion", relationVersion)
                        .addValue("mappingVersion", MAPPING_VERSION)
                        .addValue("normalizationVersion", NORMALIZATION_VERSION)
                        .addValue("runtimeSchemaVersion", RUNTIME_SCHEMA_VERSION)
                        .addValue("relationTypeAllowlist", relationTypeAllowlist.toString())
                        .addValue("minRelationScore", minRelationScore)
                        .addValue("maxRelationsPerAnchor", maxRelationsPerAnchor)
                        .addValue("createdBy", createdBy)
        );
    }

    public int countActiveAnchors() {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM corpus_glossary_terms WHERE is_active = TRUE",
                new MapSqlParameterSource(),
                Integer.class
        );
        return count == null ? 0 : count;
    }

    public void markExistingRelationsSuperseded(String relationVersion, List<String> relationTypes) {
        jdbcTemplate.update(
                """
                UPDATE canonical_anchor_relation
                SET status = 'superseded',
                    updated_at = NOW()
                WHERE relation_version = :relationVersion
                  AND status = 'active'
                  AND relation_type IN (:relationTypes)
                """,
                new MapSqlParameterSource()
                        .addValue("relationVersion", relationVersion)
                        .addValue("relationTypes", relationTypes)
        );
    }

    public int insertCanonicalAliasRelations(
            UUID runId,
            String relationVersion,
            double minRelationScore,
            Integer maxRelationsPerAnchor
    ) {
        return jdbcTemplate.update(
                relationInsertSql(
                        """
                        WITH mapping_pairs AS (
                            SELECT DISTINCT
                                   m.canonical_term_id AS left_anchor_id,
                                   m.alias_term_id AS right_anchor_id,
                                   COALESCE(m.confidence, 1.0) AS raw_score,
                                   m.mapping_id::text AS evidence_key
                            FROM canonical_anchor_mapping m
                            JOIN corpus_glossary_terms left_term
                              ON left_term.term_id = m.canonical_term_id
                             AND left_term.is_active = TRUE
                            JOIN corpus_glossary_terms right_term
                              ON right_term.term_id = m.alias_term_id
                             AND right_term.is_active = TRUE
                            WHERE m.mapping_version = :mappingVersion
                              AND m.normalization_version = :normalizationVersion
                              AND m.review_status = 'approved'
                              AND m.mapping_status = 'active'
                              AND m.alias_term_id IS NOT NULL
                              AND m.alias_term_id <> m.canonical_term_id
                        ),
                        directed AS (
                            SELECT left_anchor_id AS canonical_anchor_id,
                                   right_anchor_id AS related_anchor_id,
                                   GREATEST(0.0, LEAST(1.0, MAX(raw_score))) AS relation_score,
                                   COUNT(DISTINCT evidence_key) AS evidence_count,
                                   NULL::text AS source_query_id,
                                   NULL::text AS source_chunk_id,
                                   NULL::text AS source_section_id,
                                   NULL::text AS method_code,
                                   jsonb_build_object('mapping_count', COUNT(DISTINCT evidence_key)) AS metadata_json
                            FROM mapping_pairs
                            GROUP BY left_anchor_id, right_anchor_id
                            UNION ALL
                            SELECT right_anchor_id AS canonical_anchor_id,
                                   left_anchor_id AS related_anchor_id,
                                   GREATEST(0.0, LEAST(1.0, MAX(raw_score))) AS relation_score,
                                   COUNT(DISTINCT evidence_key) AS evidence_count,
                                   NULL::text AS source_query_id,
                                   NULL::text AS source_chunk_id,
                                   NULL::text AS source_section_id,
                                   NULL::text AS method_code,
                                   jsonb_build_object('mapping_count', COUNT(DISTINCT evidence_key)) AS metadata_json
                            FROM mapping_pairs
                            GROUP BY right_anchor_id, left_anchor_id
                        ),
                        scored AS (
                            SELECT *
                            FROM directed
                            WHERE relation_score >= :minRelationScore
                        )
                        """,
                        "canonical_alias",
                        "canonical_anchor_mapping"
                ),
                baseParams(runId, relationVersion, minRelationScore, maxRelationsPerAnchor, "canonical_alias", "canonical_anchor_mapping")
        );
    }

    public int insertSyntheticQueryCooccurrenceRelations(
            UUID runId,
            String relationVersion,
            double minRelationScore,
            Integer maxRelationsPerAnchor
    ) {
        return jdbcTemplate.update(
                relationInsertSql(
                        """
                        WITH pair_counts AS (
                            SELECT l1.term_id AS canonical_anchor_id,
                                   l2.term_id AS related_anchor_id,
                                   COUNT(DISTINCT l1.synthetic_query_id) AS evidence_count,
                                   MIN(l1.synthetic_query_id) AS source_query_id,
                                   STRING_AGG(DISTINCT reg.generation_strategy, ',' ORDER BY reg.generation_strategy) AS method_codes
                            FROM synthetic_query_anchor_link l1
                            JOIN synthetic_query_anchor_link l2
                              ON l2.synthetic_query_id = l1.synthetic_query_id
                             AND l2.term_id <> l1.term_id
                             AND l2.is_active = TRUE
                            JOIN corpus_glossary_terms left_term
                              ON left_term.term_id = l1.term_id
                             AND left_term.is_active = TRUE
                            JOIN corpus_glossary_terms right_term
                              ON right_term.term_id = l2.term_id
                             AND right_term.is_active = TRUE
                            JOIN synthetic_query_registry reg
                              ON reg.synthetic_query_id = l1.synthetic_query_id
                            WHERE l1.is_active = TRUE
                              AND NOT (left_term.term_type = 'concept' AND right_term.term_type = 'concept')
                            GROUP BY l1.term_id, l2.term_id
                        ),
                        scored AS (
                            SELECT canonical_anchor_id,
                                   related_anchor_id,
                                   LEAST(0.95, 0.62 + LN(evidence_count + 1) * 0.07) AS relation_score,
                                   evidence_count::integer AS evidence_count,
                                   source_query_id,
                                   NULL::text AS source_chunk_id,
                                   NULL::text AS source_section_id,
                                   CASE
                                       WHEN method_codes IS NULL OR method_codes = '' THEN NULL
                                       WHEN POSITION(',' IN method_codes) = 0 THEN method_codes
                                       ELSE 'mixed'
                                   END AS method_code,
                                   jsonb_build_object(
                                       'method_codes',
                                       CASE
                                           WHEN method_codes IS NULL OR method_codes = '' THEN ARRAY[]::text[]
                                           ELSE string_to_array(method_codes, ',')
                                       END
                                   ) AS metadata_json
                            FROM pair_counts
                            WHERE LEAST(0.95, 0.62 + LN(evidence_count + 1) * 0.07) >= :minRelationScore
                        )
                        """,
                        "synthetic_query_cooccurrence",
                        "synthetic_query_anchor_link"
                ),
                baseParams(runId, relationVersion, minRelationScore, maxRelationsPerAnchor, "synthetic_query_cooccurrence", "synthetic_query_anchor_link")
        );
    }

    public int insertChunkCooccurrenceRelations(
            UUID runId,
            String relationVersion,
            double minRelationScore,
            Integer maxRelationsPerAnchor
    ) {
        return jdbcTemplate.update(
                relationInsertSql(
                        """
                        WITH pair_counts AS (
                            SELECT e1.term_id AS canonical_anchor_id,
                                   e2.term_id AS related_anchor_id,
                                   COUNT(DISTINCT e1.chunk_id) AS evidence_count,
                                   MIN(e1.chunk_id) AS source_chunk_id,
                                   MIN(c.section_id) AS source_section_id,
                                   COUNT(DISTINCT e1.document_id) AS document_count
                            FROM corpus_glossary_evidence e1
                            JOIN corpus_glossary_evidence e2
                              ON e2.chunk_id = e1.chunk_id
                             AND e2.term_id <> e1.term_id
                            JOIN corpus_chunks c
                              ON c.chunk_id = e1.chunk_id
                            JOIN corpus_glossary_terms left_term
                              ON left_term.term_id = e1.term_id
                             AND left_term.is_active = TRUE
                            JOIN corpus_glossary_terms right_term
                              ON right_term.term_id = e2.term_id
                             AND right_term.is_active = TRUE
                            WHERE NOT (left_term.term_type = 'concept' AND right_term.term_type = 'concept')
                            GROUP BY e1.term_id, e2.term_id
                        ),
                        scored AS (
                            SELECT canonical_anchor_id,
                                   related_anchor_id,
                                   LEAST(0.90, 0.55 + LN(evidence_count + 1) * 0.06) AS relation_score,
                                   evidence_count::integer AS evidence_count,
                                   NULL::text AS source_query_id,
                                   source_chunk_id,
                                   source_section_id,
                                   NULL::text AS method_code,
                                   jsonb_build_object('document_count', document_count) AS metadata_json
                            FROM pair_counts
                            WHERE LEAST(0.90, 0.55 + LN(evidence_count + 1) * 0.06) >= :minRelationScore
                        )
                        """,
                        "chunk_cooccurrence",
                        "corpus_glossary_evidence"
                ),
                baseParams(runId, relationVersion, minRelationScore, maxRelationsPerAnchor, "chunk_cooccurrence", "corpus_glossary_evidence")
        );
    }

    public int countActiveRunRelations(UUID runId) {
        Integer count = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM canonical_anchor_relation
                WHERE run_id = :runId
                  AND status = 'active'
                """,
                new MapSqlParameterSource("runId", runId),
                Integer.class
        );
        return count == null ? 0 : count;
    }

    public int sumActiveRunEvidence(UUID runId) {
        Integer count = jdbcTemplate.queryForObject(
                """
                SELECT COALESCE(SUM(evidence_count), 0)::integer
                FROM canonical_anchor_relation
                WHERE run_id = :runId
                  AND status = 'active'
                """,
                new MapSqlParameterSource("runId", runId),
                Integer.class
        );
        return count == null ? 0 : count;
    }

    public void completeRun(
            UUID runId,
            int candidateAnchorCount,
            int relationCount,
            int evidenceCount,
            JsonNode summary
    ) {
        jdbcTemplate.update(
                """
                UPDATE canonical_anchor_relation_run
                SET status = 'completed',
                    candidate_anchor_count = :candidateAnchorCount,
                    relation_count = :relationCount,
                    evidence_count = :evidenceCount,
                    summary_json = CAST(:summaryJson AS jsonb),
                    updated_at = NOW(),
                    finished_at = NOW()
                WHERE run_id = :runId
                """,
                new MapSqlParameterSource()
                        .addValue("runId", runId)
                        .addValue("candidateAnchorCount", candidateAnchorCount)
                        .addValue("relationCount", relationCount)
                        .addValue("evidenceCount", evidenceCount)
                        .addValue("summaryJson", summary.toString())
        );
    }

    public List<MultiSourceAnchorDtos.MultiSourceAnchorBuildRunSummary> listRuns(Integer limit, Integer offset) {
        int safeLimit = limit == null ? 20 : Math.max(1, Math.min(100, limit));
        int safeOffset = offset == null ? 0 : Math.max(0, offset);
        return jdbcTemplate.query(
                """
                SELECT run_id,
                       run_name,
                       relation_version,
                       mapping_version,
                       normalization_version,
                       canonical_anchor_runtime_schema_version,
                       status,
                       relation_type_allowlist::text AS relation_type_allowlist,
                       min_relation_score,
                       max_relations_per_anchor,
                       candidate_anchor_count,
                       relation_count,
                       evidence_count,
                       summary_json::text AS summary_json,
                       created_by,
                       error_message,
                       created_at,
                       updated_at,
                       finished_at
                FROM canonical_anchor_relation_run
                ORDER BY created_at DESC
                LIMIT :limit OFFSET :offset
                """,
                new MapSqlParameterSource()
                        .addValue("limit", safeLimit)
                        .addValue("offset", safeOffset),
                runRowMapper()
        );
    }

    public MultiSourceAnchorDtos.MultiSourceAnchorBuildRunSummary getRun(UUID runId) {
        List<MultiSourceAnchorDtos.MultiSourceAnchorBuildRunSummary> rows = jdbcTemplate.query(
                """
                SELECT run_id,
                       run_name,
                       relation_version,
                       mapping_version,
                       normalization_version,
                       canonical_anchor_runtime_schema_version,
                       status,
                       relation_type_allowlist::text AS relation_type_allowlist,
                       min_relation_score,
                       max_relations_per_anchor,
                       candidate_anchor_count,
                       relation_count,
                       evidence_count,
                       summary_json::text AS summary_json,
                       created_by,
                       error_message,
                       created_at,
                       updated_at,
                       finished_at
                FROM canonical_anchor_relation_run
                WHERE run_id = :runId
                """,
                new MapSqlParameterSource("runId", runId),
                runRowMapper()
        );
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("multi-source anchor build run not found: " + runId);
        }
        return rows.getFirst();
    }

    private String relationInsertSql(String sourceCte, String relationType, String relationSource) {
        return sourceCte + """
                ,
                ranked AS (
                    SELECT scored.*,
                           ROW_NUMBER() OVER (
                               PARTITION BY canonical_anchor_id
                               ORDER BY relation_score DESC, evidence_count DESC, related_anchor_id
                           ) AS relation_rank
                    FROM scored
                )
                INSERT INTO canonical_anchor_relation (
                    run_id,
                    relation_version,
                    mapping_version,
                    normalization_version,
                    canonical_anchor_runtime_schema_version,
                    canonical_anchor_id,
                    related_anchor_id,
                    relation_type,
                    relation_score,
                    relation_source,
                    evidence_count,
                    source_query_id,
                    source_chunk_id,
                    source_section_id,
                    method_code,
                    status,
                    metadata_json,
                    updated_at
                )
                SELECT :runId,
                       :relationVersion,
                       :mappingVersion,
                       :normalizationVersion,
                       :runtimeSchemaVersion,
                       canonical_anchor_id,
                       related_anchor_id,
                       :relationType,
                       relation_score,
                       :relationSource,
                       evidence_count,
                       source_query_id,
                       source_chunk_id,
                       source_section_id,
                       method_code,
                       'active',
                       metadata_json,
                       NOW()
                FROM ranked
                WHERE (:maxRelationsPerAnchor IS NULL OR relation_rank <= :maxRelationsPerAnchor)
                ON CONFLICT (
                    relation_version,
                    canonical_anchor_id,
                    related_anchor_id,
                    relation_type,
                    relation_source
                ) DO UPDATE
                SET run_id = EXCLUDED.run_id,
                    mapping_version = EXCLUDED.mapping_version,
                    normalization_version = EXCLUDED.normalization_version,
                    canonical_anchor_runtime_schema_version = EXCLUDED.canonical_anchor_runtime_schema_version,
                    relation_score = EXCLUDED.relation_score,
                    evidence_count = EXCLUDED.evidence_count,
                    source_query_id = EXCLUDED.source_query_id,
                    source_chunk_id = EXCLUDED.source_chunk_id,
                    source_section_id = EXCLUDED.source_section_id,
                    method_code = EXCLUDED.method_code,
                    status = 'active',
                    metadata_json = EXCLUDED.metadata_json,
                    updated_at = NOW()
                """;
    }

    private MapSqlParameterSource baseParams(
            UUID runId,
            String relationVersion,
            double minRelationScore,
            Integer maxRelationsPerAnchor,
            String relationType,
            String relationSource
    ) {
        return new MapSqlParameterSource()
                .addValue("runId", runId)
                .addValue("relationVersion", relationVersion)
                .addValue("mappingVersion", MAPPING_VERSION)
                .addValue("normalizationVersion", NORMALIZATION_VERSION)
                .addValue("runtimeSchemaVersion", RUNTIME_SCHEMA_VERSION)
                .addValue("minRelationScore", minRelationScore)
                .addValue("maxRelationsPerAnchor", maxRelationsPerAnchor)
                .addValue("relationType", relationType)
                .addValue("relationSource", relationSource);
    }

    private RowMapper<MultiSourceAnchorDtos.MultiSourceAnchorBuildRunSummary> runRowMapper() {
        return (rs, rowNum) -> new MultiSourceAnchorDtos.MultiSourceAnchorBuildRunSummary(
                readUuid(rs, "run_id"),
                rs.getString("run_name"),
                rs.getString("relation_version"),
                rs.getString("mapping_version"),
                rs.getString("normalization_version"),
                rs.getString("canonical_anchor_runtime_schema_version"),
                rs.getString("status"),
                readJson(rs.getString("relation_type_allowlist")),
                rs.getDouble("min_relation_score"),
                rs.getObject("max_relations_per_anchor", Integer.class),
                rs.getInt("candidate_anchor_count"),
                rs.getInt("relation_count"),
                rs.getInt("evidence_count"),
                readJson(rs.getString("summary_json")),
                rs.getString("created_by"),
                rs.getString("error_message"),
                readInstant(rs, "created_at"),
                readInstant(rs, "updated_at"),
                readInstant(rs, "finished_at")
        );
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

    private Instant readInstant(ResultSet rs, String column) throws SQLException {
        java.sql.Timestamp timestamp = rs.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }

    private JsonNode readJson(String raw) {
        if (raw == null || raw.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            return objectMapper.readTree(raw);
        } catch (Exception exception) {
            return objectMapper.createObjectNode();
        }
    }
}
