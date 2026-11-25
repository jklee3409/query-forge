package io.queryforge.backend.admin.corpus.anchor.normalization.repository;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.queryforge.backend.admin.corpus.anchor.normalization.dto.AnchorNormalizationDtos.CandidateDraft;
import io.queryforge.backend.admin.corpus.anchor.normalization.dto.AnchorNormalizationDtos.TargetAnchor;
import io.queryforge.backend.admin.corpus.anchor.normalization.dto.AnchorNormalizationDtos;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static io.queryforge.backend.admin.corpus.anchor.support.AnchorSchemaVersions.MAPPING_VERSION;
import static io.queryforge.backend.admin.corpus.anchor.support.AnchorSchemaVersions.NORMALIZATION_VERSION;
import static io.queryforge.backend.admin.corpus.anchor.support.AnchorSchemaVersions.RUNTIME_SCHEMA_VERSION;

@Repository
@RequiredArgsConstructor
public class AnchorNormalizationRepository {

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public List<AnchorNormalizationDtos.AnchorNormalizationRunSummary> listRuns(int limit, int offset) {
        String sql = """
                SELECT r.run_id, r.run_name, r.status, r.summary_json::text AS summary_json, r.source_scope_json::text AS source_scope_json,
                       r.applied_update_count, r.created_by, r.reviewed_by, r.created_at, r.updated_at, r.reviewed_at, r.applied_at,
                       COALESCE(review_stats.review_approved_count, 0) AS review_approved_count,
                       COALESCE(review_stats.review_skipped_count, 0) AS review_skipped_count,
                       COALESCE(review_stats.review_pending_count, 0) AS review_pending_count
                FROM anchor_normalization_run r
                LEFT JOIN LATERAL (
                    SELECT COUNT(*) FILTER (WHERE c.review_decision = 'approve') AS review_approved_count,
                           COUNT(*) FILTER (WHERE c.review_decision = 'skip') AS review_skipped_count,
                           COUNT(*) FILTER (
                               WHERE c.resolution_status <> 'unchanged'
                                 AND c.review_decision = 'pending'
                           ) AS review_pending_count
                    FROM anchor_normalization_candidate c
                    WHERE c.run_id = r.run_id
                ) review_stats ON TRUE
                ORDER BY r.created_at DESC
                LIMIT :limit OFFSET :offset
                """;
        return jdbcTemplate.query(
                sql,
                new MapSqlParameterSource()
                        .addValue("limit", limit)
                        .addValue("offset", offset),
                runSummaryRowMapper()
        );
    }

    public int deleteRun(UUID runId) {
        MapSqlParameterSource params = new MapSqlParameterSource("runId", runId);
        jdbcTemplate.update(
                "DELETE FROM anchor_normalization_candidate WHERE run_id = :runId",
                params
        );
        int deleted = jdbcTemplate.update(
                "DELETE FROM anchor_normalization_run WHERE run_id = :runId",
                params
        );
        return deleted;
    }

    public void updateCandidateReview(UUID runId, UUID candidateId, String decision, String reviewedBy, String note) {
        String sql = """
                UPDATE anchor_normalization_candidate
                SET review_decision = :decision,
                    reviewed_by = CASE WHEN :decision = 'pending' THEN NULL ELSE :reviewedBy END,
                    review_note = CASE WHEN :decision = 'pending' THEN NULL ELSE :note END,
                    reviewed_at = CASE WHEN :decision = 'pending' THEN NULL ELSE NOW() END
                WHERE run_id = :runId
                  AND candidate_id = :candidateId
                """;
        jdbcTemplate.update(sql, new MapSqlParameterSource()
                .addValue("runId", runId)
                .addValue("candidateId", candidateId)
                .addValue("decision", decision)
                .addValue("reviewedBy", reviewedBy)
                .addValue("note", note));
        jdbcTemplate.update(
                "UPDATE anchor_normalization_run SET updated_at = NOW() WHERE run_id = :runId",
                new MapSqlParameterSource("runId", runId)
        );
    }

    public List<TargetAnchor> findTargets(
            String documentId,
            String chunkId,
            String keyword,
            boolean activeOnly,
            Integer limit
    ) {
        StringBuilder sql = new StringBuilder("""
                SELECT gt.term_id,
                       gt.canonical_form,
                       gt.normalized_form,
                       gt.term_type,
                       COALESCE(scope_stats.scoped_evidence_count, 0) AS scoped_evidence_count,
                       gt.evidence_count
                FROM corpus_glossary_terms gt
                LEFT JOIN LATERAL (
                    SELECT COUNT(*) AS scoped_evidence_count
                    FROM corpus_glossary_evidence e
                    WHERE e.term_id = gt.term_id
                """);
        MapSqlParameterSource params = new MapSqlParameterSource();
        appendEvidenceScopeFilters(sql, params, "e", documentId, chunkId);
        sql.append("""
                ) scope_stats ON TRUE
                WHERE 1=1
                """);
        if (activeOnly) {
            sql.append(" AND gt.is_active = TRUE\n");
        }
        if (keyword != null && !keyword.isBlank()) {
            sql.append(" AND gt.canonical_form ILIKE :keyword\n");
            params.addValue("keyword", "%" + keyword.trim() + "%");
        }
        if ((documentId != null && !documentId.isBlank()) || (chunkId != null && !chunkId.isBlank())) {
            sql.append("""
                     AND EXISTS (
                         SELECT 1
                         FROM corpus_glossary_evidence e2
                         WHERE e2.term_id = gt.term_id
                    """);
            appendEvidenceScopeFilters(sql, params, "e2", documentId, chunkId);
            sql.append(")");
        }
        sql.append("""
                ORDER BY COALESCE(scope_stats.scoped_evidence_count, 0) DESC,
                         gt.evidence_count DESC,
                         gt.canonical_form
                """);
        if (limit != null) {
            sql.append(" LIMIT :limit\n");
            params.addValue("limit", limit);
        }
        return jdbcTemplate.query(sql.toString(), params, (rs, rowNum) -> new TargetAnchor(
                readUuid(rs, "term_id"),
                rs.getString("canonical_form"),
                rs.getString("normalized_form"),
                rs.getString("term_type")
        ));
    }

    public UUID findConflictTermId(UUID termId, String termType, String proposedNormalized) {
        String sql = """
                SELECT term_id
                FROM corpus_glossary_terms
                WHERE term_type = :termType
                  AND normalized_form = :normalizedForm
                  AND term_id <> :termId
                ORDER BY term_id
                LIMIT 1
                """;
        List<UUID> rows = jdbcTemplate.query(
                sql,
                new MapSqlParameterSource()
                        .addValue("termId", termId)
                        .addValue("termType", termType)
                        .addValue("normalizedForm", proposedNormalized),
                (rs, rowNum) -> readUuid(rs, "term_id")
        );
        return rows.isEmpty() ? null : rows.getFirst();
    }

    public void insertRun(
            UUID runId,
            String runName,
            JsonNode sourceScope,
            JsonNode summary,
            JsonNode report,
            String createdBy
    ) {
        String sql = """
                INSERT INTO anchor_normalization_run (
                    run_id, run_name, status, source_scope_json, report_json, summary_json,
                    anchor_mapping_version, anchor_normalization_version, canonical_anchor_runtime_schema_version,
                    created_by
                ) VALUES (
                    :runId, :runName, 'pending_review', CAST(:sourceScope AS jsonb), CAST(:report AS jsonb), CAST(:summary AS jsonb),
                    :mappingVersion, :normalizationVersion, :runtimeSchemaVersion, :createdBy
                )
                """;
        jdbcTemplate.update(sql, new MapSqlParameterSource()
                .addValue("runId", runId)
                .addValue("runName", runName)
                .addValue("sourceScope", sourceScope.toString())
                .addValue("report", report.toString())
                .addValue("summary", summary.toString())
                .addValue("mappingVersion", MAPPING_VERSION)
                .addValue("normalizationVersion", NORMALIZATION_VERSION)
                .addValue("runtimeSchemaVersion", RUNTIME_SCHEMA_VERSION)
                .addValue("createdBy", createdBy));
    }

    public void insertCandidate(CandidateDraft candidate) {
        String sql = """
                INSERT INTO anchor_normalization_candidate (
                    candidate_id, run_id, term_id, term_type,
                    current_canonical_form, current_normalized_form,
                    proposed_canonical_form, proposed_normalized_form,
                    resolution_status, change_required, conflict_term_id, metadata_json
                ) VALUES (
                    :candidateId, :runId, :termId, :termType,
                    :currentCanonicalForm, :currentNormalizedForm,
                    :proposedCanonicalForm, :proposedNormalizedForm,
                    :resolutionStatus, :changeRequired, :conflictTermId, CAST(:metadataJson AS jsonb)
                )
                """;
        jdbcTemplate.update(sql, new MapSqlParameterSource()
                .addValue("candidateId", candidate.candidateId())
                .addValue("runId", candidate.runId())
                .addValue("termId", candidate.termId())
                .addValue("termType", candidate.termType())
                .addValue("currentCanonicalForm", candidate.currentCanonicalForm())
                .addValue("currentNormalizedForm", candidate.currentNormalizedForm())
                .addValue("proposedCanonicalForm", candidate.proposedCanonicalForm())
                .addValue("proposedNormalizedForm", candidate.proposedNormalizedForm())
                .addValue("resolutionStatus", candidate.resolutionStatus())
                .addValue("changeRequired", candidate.changeRequired())
                .addValue("conflictTermId", candidate.conflictTermId())
                .addValue("metadataJson", candidate.metadataJson().toString()));
    }

    public AnchorNormalizationDtos.AnchorNormalizationRunSummary getRun(UUID runId) {
        String sql = """
                SELECT r.run_id, r.run_name, r.status, r.summary_json::text AS summary_json, r.source_scope_json::text AS source_scope_json,
                       r.applied_update_count, r.created_by, r.reviewed_by, r.created_at, r.updated_at, r.reviewed_at, r.applied_at,
                       COALESCE(review_stats.review_approved_count, 0) AS review_approved_count,
                       COALESCE(review_stats.review_skipped_count, 0) AS review_skipped_count,
                       COALESCE(review_stats.review_pending_count, 0) AS review_pending_count
                FROM anchor_normalization_run r
                LEFT JOIN LATERAL (
                    SELECT COUNT(*) FILTER (WHERE c.review_decision = 'approve') AS review_approved_count,
                           COUNT(*) FILTER (WHERE c.review_decision = 'skip') AS review_skipped_count,
                           COUNT(*) FILTER (
                               WHERE c.resolution_status <> 'unchanged'
                                 AND c.review_decision = 'pending'
                           ) AS review_pending_count
                    FROM anchor_normalization_candidate c
                    WHERE c.run_id = r.run_id
                ) review_stats ON TRUE
                WHERE r.run_id = :runId
                """;
        List<AnchorNormalizationDtos.AnchorNormalizationRunSummary> rows = jdbcTemplate.query(
                sql,
                new MapSqlParameterSource("runId", runId),
                runSummaryRowMapper()
        );
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("anchor normalization run not found: " + runId);
        }
        return rows.getFirst();
    }

    public AnchorNormalizationDtos.AnchorNormalizationCandidateDto findCandidate(UUID runId, UUID candidateId) {
        String sql = """
                SELECT candidate_id, run_id, term_id, term_type, current_canonical_form, current_normalized_form,
                       proposed_canonical_form, proposed_normalized_form, resolution_status, change_required,
                       conflict_term_id, review_decision, reviewed_by, review_note,
                       metadata_json::text AS metadata_json, reviewed_at, applied_at
                FROM anchor_normalization_candidate
                WHERE run_id = :runId
                  AND candidate_id = :candidateId
                """;
        List<AnchorNormalizationDtos.AnchorNormalizationCandidateDto> rows = jdbcTemplate.query(
                sql,
                new MapSqlParameterSource()
                        .addValue("runId", runId)
                        .addValue("candidateId", candidateId),
                candidateRowMapper()
        );
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("anchor normalization candidate not found: " + candidateId);
        }
        return rows.getFirst();
    }

    public List<AnchorNormalizationDtos.AnchorNormalizationCandidateDto> findCandidates(UUID runId) {
        String sql = """
                SELECT candidate_id, run_id, term_id, term_type, current_canonical_form, current_normalized_form,
                       proposed_canonical_form, proposed_normalized_form, resolution_status, change_required,
                       conflict_term_id, review_decision, reviewed_by, review_note,
                       metadata_json::text AS metadata_json, reviewed_at, applied_at
                FROM anchor_normalization_candidate
                WHERE run_id = :runId
                ORDER BY
                    CASE resolution_status
                        WHEN 'conflict' THEN 1
                        WHEN 'invalid' THEN 2
                        WHEN 'would_update' THEN 3
                        ELSE 4
                    END,
                    current_canonical_form
                """;
        return jdbcTemplate.query(
                sql,
                new MapSqlParameterSource("runId", runId),
                candidateRowMapper()
        );
    }

    public int updateCanonicalColumns(AnchorNormalizationDtos.AnchorNormalizationCandidateDto candidate) {
        String sql = """
                UPDATE corpus_glossary_terms t
                SET canonical_form = :proposedCanonicalForm,
                    normalized_form = :proposedNormalizedForm
                WHERE t.term_id = :termId
                  AND NOT EXISTS (
                      SELECT 1
                      FROM corpus_glossary_terms other
                      WHERE other.term_type = t.term_type
                        AND other.normalized_form = :proposedNormalizedForm
                        AND other.term_id <> t.term_id
                  )
                """;
        return jdbcTemplate.update(sql, new MapSqlParameterSource()
                .addValue("termId", candidate.termId())
                .addValue("proposedCanonicalForm", candidate.proposedCanonicalForm())
                .addValue("proposedNormalizedForm", candidate.proposedNormalizedForm()));
    }

    public void markCandidateApplied(UUID candidateId) {
        String sql = """
                UPDATE anchor_normalization_candidate
                SET applied_at = NOW()
                WHERE candidate_id = :candidateId
                """;
        jdbcTemplate.update(sql, new MapSqlParameterSource("candidateId", candidateId));
    }

    public void updateRunReview(UUID runId, String status, String reviewedBy, String note, int applied) {
        String sql = """
                UPDATE anchor_normalization_run
                SET status = :status,
                    reviewed_by = :reviewedBy,
                    review_note = :note,
                    applied_update_count = :applied,
                    reviewed_at = NOW(),
                    applied_at = CASE WHEN :status = 'approved' THEN NOW() ELSE applied_at END,
                    updated_at = NOW()
                WHERE run_id = :runId
                """;
        jdbcTemplate.update(sql, new MapSqlParameterSource()
                .addValue("runId", runId)
                .addValue("status", status)
                .addValue("reviewedBy", reviewedBy)
                .addValue("note", note)
                .addValue("applied", applied));
    }

    private void appendEvidenceScopeFilters(
            StringBuilder sql,
            MapSqlParameterSource params,
            String alias,
            String documentId,
            String chunkId
    ) {
        if (documentId != null && !documentId.isBlank()) {
            sql.append(" AND ").append(alias).append(".document_id = :anchorNormDocumentId");
            params.addValue("anchorNormDocumentId", documentId.trim());
        }
        if (chunkId != null && !chunkId.isBlank()) {
            sql.append(" AND ").append(alias).append(".chunk_id = :anchorNormChunkId");
            params.addValue("anchorNormChunkId", chunkId.trim());
        }
    }

    private RowMapper<AnchorNormalizationDtos.AnchorNormalizationRunSummary> runSummaryRowMapper() {
        return (rs, rowNum) -> {
            JsonNode summary = readJson(rs, "summary_json");
            return new AnchorNormalizationDtos.AnchorNormalizationRunSummary(
                    readUuid(rs, "run_id"),
                    rs.getString("run_name"),
                    rs.getString("status"),
                    summary.path("candidate_count").asInt(0),
                    summary.path("changed_count").asInt(0),
                    summary.path("unchanged_count").asInt(0),
                    summary.path("conflict_count").asInt(0),
                    summary.path("invalid_count").asInt(0),
                    rs.getInt("applied_update_count"),
                    rs.getInt("review_approved_count"),
                    rs.getInt("review_skipped_count"),
                    rs.getInt("review_pending_count"),
                    rs.getString("created_by"),
                    rs.getString("reviewed_by"),
                    readJson(rs, "source_scope_json"),
                    summary,
                    readInstant(rs, "created_at"),
                    readInstant(rs, "updated_at"),
                    readInstant(rs, "reviewed_at"),
                    readInstant(rs, "applied_at")
            );
        };
    }

    private RowMapper<AnchorNormalizationDtos.AnchorNormalizationCandidateDto> candidateRowMapper() {
        return (rs, rowNum) -> new AnchorNormalizationDtos.AnchorNormalizationCandidateDto(
                readUuid(rs, "candidate_id"),
                readUuid(rs, "term_id"),
                rs.getString("term_type"),
                rs.getString("current_canonical_form"),
                rs.getString("current_normalized_form"),
                rs.getString("proposed_canonical_form"),
                rs.getString("proposed_normalized_form"),
                rs.getString("resolution_status"),
                rs.getBoolean("change_required"),
                readUuid(rs, "conflict_term_id"),
                rs.getString("review_decision"),
                rs.getString("reviewed_by"),
                rs.getString("review_note"),
                readJson(rs, "metadata_json"),
                readInstant(rs, "reviewed_at"),
                readInstant(rs, "applied_at")
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
            throw new SQLException("Failed to parse JSON column " + column, exception);
        }
    }

    private UUID readUuid(ResultSet rs, String column) throws SQLException {
        Object value = rs.getObject(column);
        if (value == null) {
            return null;
        }
        if (value instanceof UUID uuid) {
            return uuid;
        }
        return UUID.fromString(value.toString());
    }

    private Instant readInstant(ResultSet rs, String column) throws SQLException {
        Timestamp timestamp = rs.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }
}
