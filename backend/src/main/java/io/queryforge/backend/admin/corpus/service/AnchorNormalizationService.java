package io.queryforge.backend.admin.corpus.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.queryforge.backend.admin.corpus.model.CorpusAdminDtos;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.text.Normalizer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AnchorNormalizationService {

    private static final String MAPPING_VERSION = "anchor-map-v1";
    private static final String NORMALIZATION_VERSION = "anchor-normalize-v1";
    private static final String RUNTIME_SCHEMA_VERSION = "canonical-anchor-runtime-v1";
    private static final String REPORT_SCHEMA_VERSION = "canonical-anchor-normalization-review-v1";
    private static final String REVIEW_DECISION_PENDING = "pending";
    private static final String REVIEW_DECISION_APPROVE = "approve";
    private static final String REVIEW_DECISION_SKIP = "skip";
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final Pattern JAVA_IDENTIFIER = Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]*");
    private static final Pattern HANGUL_BETWEEN_SPACE = Pattern.compile("(?<=[\\uAC00-\\uD7A3])\\s+(?=[\\uAC00-\\uD7A3])");
    private static final String WRAPPER_PUNCTUATION = " \t\r\n\"'`“”‘’[]{}()<>";

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    @Transactional
    public CorpusAdminDtos.AnchorNormalizationRunSummary createDryRun(
            CorpusAdminDtos.AnchorNormalizationRunCreateRequest request
    ) {
        CorpusAdminDtos.AnchorNormalizationRunCreateRequest safeRequest = request == null
                ? CorpusAdminDtos.AnchorNormalizationRunCreateRequest.builder().build()
                : request;
        int limit = normalizeLimit(safeRequest.limit());
        List<TargetAnchor> targets = findTargets(
                safeRequest.documentId(),
                safeRequest.chunkId(),
                safeRequest.keyword(),
                safeRequest.activeOnly() == null || safeRequest.activeOnly(),
                limit
        );
        UUID runId = UUID.randomUUID();
        String runName = blankToDefault(safeRequest.runName(), "anchor-normalize-" + runId.toString().substring(0, 8));
        String createdBy = blankToDefault(safeRequest.createdBy(), "admin-ui");
        List<CandidateDraft> candidates = new ArrayList<>();
        for (TargetAnchor target : targets) {
            candidates.add(buildCandidate(runId, target));
        }
        JsonNode sourceScope = objectMapper.valueToTree(sourceScopePayload(safeRequest, limit));
        JsonNode summary = objectMapper.valueToTree(summaryPayload(candidates));
        JsonNode report = objectMapper.valueToTree(reportPayload(runId, runName, sourceScope, summary));
        insertRun(runId, runName, sourceScope, summary, report, createdBy);
        for (CandidateDraft candidate : candidates) {
            insertCandidate(candidate);
        }
        return getRun(runId);
    }

    public List<CorpusAdminDtos.AnchorNormalizationRunSummary> listRuns(Integer limit, Integer offset) {
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
                        .addValue("limit", normalizeLimit(limit))
                        .addValue("offset", normalizeOffset(offset)),
                runSummaryRowMapper()
        );
    }

    public CorpusAdminDtos.AnchorNormalizationRunDetail getRunDetail(UUID runId) {
        return new CorpusAdminDtos.AnchorNormalizationRunDetail(
                getRun(runId),
                findCandidates(runId)
        );
    }

    @Transactional
    public CorpusAdminDtos.AnchorNormalizationCandidateDto reviewCandidate(
            UUID runId,
            UUID candidateId,
            CorpusAdminDtos.AnchorNormalizationCandidateReviewRequest request
    ) {
        CorpusAdminDtos.AnchorNormalizationRunSummary run = getRun(runId);
        ensurePendingReview(run, runId);
        String decision = normalizeReviewDecision(request == null ? null : request.decision());
        String reviewedBy = blankToDefault(request == null ? null : request.reviewedBy(), "admin-ui");
        String note = trimToNull(request == null ? null : request.note());
        return updateCandidateReview(runId, candidateId, decision, reviewedBy, note);
    }

    @Transactional
    public CorpusAdminDtos.AnchorNormalizationRunDetail reviewCandidates(
            UUID runId,
            CorpusAdminDtos.AnchorNormalizationCandidateReviewBatchRequest request
    ) {
        CorpusAdminDtos.AnchorNormalizationRunSummary run = getRun(runId);
        ensurePendingReview(run, runId);
        List<CorpusAdminDtos.AnchorNormalizationCandidateDecision> decisions = request == null
                ? List.of()
                : request.decisions();
        if (decisions == null || decisions.isEmpty()) {
            return getRunDetail(runId);
        }
        String reviewedBy = blankToDefault(request.reviewedBy(), "admin-ui");
        String batchNote = trimToNull(request.note());
        for (CorpusAdminDtos.AnchorNormalizationCandidateDecision candidateDecision : decisions) {
            if (candidateDecision == null || candidateDecision.candidateId() == null) {
                continue;
            }
            String decision = normalizeReviewDecision(candidateDecision.decision());
            String note = trimToNull(candidateDecision.note());
            updateCandidateReview(
                    runId,
                    candidateDecision.candidateId(),
                    decision,
                    reviewedBy,
                    note == null ? batchNote : note
            );
        }
        return getRunDetail(runId);
    }

    @Transactional
    public CorpusAdminDtos.AnchorNormalizationRunSummary approve(
            UUID runId,
            CorpusAdminDtos.AnchorNormalizationReviewRequest request
    ) {
        CorpusAdminDtos.AnchorNormalizationRunSummary run = getRun(runId);
        ensurePendingReview(run, runId);
        List<CorpusAdminDtos.AnchorNormalizationCandidateDto> candidates = findCandidates(runId);
        validateCandidateReviewDecisions(runId, candidates);
        int applied = 0;
        for (CorpusAdminDtos.AnchorNormalizationCandidateDto candidate : candidates) {
            if (!"would_update".equals(candidate.resolutionStatus())
                    || !REVIEW_DECISION_APPROVE.equals(candidate.reviewDecision())) {
                continue;
            }
            int updated = updateCanonicalColumns(candidate);
            if (updated != 1) {
                throw new IllegalStateException("failed to apply anchor normalization candidate: " + candidate.candidateId());
            }
            markCandidateApplied(candidate.candidateId());
            applied += 1;
        }
        String reviewedBy = blankToDefault(request == null ? null : request.reviewedBy(), "admin-ui");
        String note = trimToNull(request == null ? null : request.note());
        updateRunReview(runId, "approved", reviewedBy, note, applied);
        return getRun(runId);
    }

    @Transactional
    public CorpusAdminDtos.AnchorNormalizationRunSummary reject(
            UUID runId,
            CorpusAdminDtos.AnchorNormalizationReviewRequest request
    ) {
        CorpusAdminDtos.AnchorNormalizationRunSummary run = getRun(runId);
        ensurePendingReview(run, runId);
        String reviewedBy = blankToDefault(request == null ? null : request.reviewedBy(), "admin-ui");
        String note = trimToNull(request == null ? null : request.note());
        updateRunReview(runId, "rejected", reviewedBy, note, 0);
        return getRun(runId);
    }

    private void ensurePendingReview(CorpusAdminDtos.AnchorNormalizationRunSummary run, UUID runId) {
        if (!"pending_review".equals(run.status())) {
            throw new IllegalArgumentException("anchor normalization run is not pending_review: " + runId);
        }
    }

    private void validateCandidateReviewDecisions(
            UUID runId,
            List<CorpusAdminDtos.AnchorNormalizationCandidateDto> candidates
    ) {
        int pending = 0;
        int invalidApprove = 0;
        for (CorpusAdminDtos.AnchorNormalizationCandidateDto candidate : candidates) {
            if (reviewRequired(candidate) && REVIEW_DECISION_PENDING.equals(candidate.reviewDecision())) {
                pending += 1;
            }
            if (REVIEW_DECISION_APPROVE.equals(candidate.reviewDecision())
                    && !"would_update".equals(candidate.resolutionStatus())) {
                invalidApprove += 1;
            }
        }
        if (pending > 0) {
            throw new IllegalArgumentException("anchor normalization run has pending candidate reviews: " + runId);
        }
        if (invalidApprove > 0) {
            throw new IllegalArgumentException("only would_update candidates can be approved: " + runId);
        }
    }

    private boolean reviewRequired(CorpusAdminDtos.AnchorNormalizationCandidateDto candidate) {
        return !"unchanged".equals(candidate.resolutionStatus());
    }

    private String normalizeReviewDecision(String decision) {
        String normalized = blankToDefault(decision, REVIEW_DECISION_PENDING).toLowerCase(Locale.ROOT).trim();
        if (!List.of(REVIEW_DECISION_PENDING, REVIEW_DECISION_APPROVE, REVIEW_DECISION_SKIP).contains(normalized)) {
            throw new IllegalArgumentException("unsupported anchor normalization candidate review decision: " + decision);
        }
        return normalized;
    }

    private CorpusAdminDtos.AnchorNormalizationCandidateDto updateCandidateReview(
            UUID runId,
            UUID candidateId,
            String decision,
            String reviewedBy,
            String note
    ) {
        CorpusAdminDtos.AnchorNormalizationCandidateDto candidate = findCandidate(runId, candidateId);
        if (REVIEW_DECISION_APPROVE.equals(decision) && !"would_update".equals(candidate.resolutionStatus())) {
            throw new IllegalArgumentException("only would_update candidates can be approved: " + candidateId);
        }
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
        return findCandidate(runId, candidateId);
    }

    private CandidateDraft buildCandidate(UUID runId, TargetAnchor target) {
        String proposedCanonical = normalizeDisplayCanonical(target.canonicalForm(), target.termType());
        String proposedNormalized = normalizeStoredCanonical(proposedCanonical);
        UUID conflictTermId = null;
        String status;
        boolean changeRequired = false;
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("normalization_version", NORMALIZATION_VERSION);
        metadata.put("dry_run_only", true);
        if (proposedCanonical.isBlank() || proposedNormalized.isBlank()) {
            status = "invalid";
            metadata.put("reason", "blank_after_normalization");
        } else {
            conflictTermId = findConflictTermId(target.termId(), target.termType(), proposedNormalized);
            if (conflictTermId != null) {
                status = "conflict";
                metadata.put("reason", "normalized_form_conflict");
            } else if (proposedCanonical.equals(target.canonicalForm()) && proposedNormalized.equals(target.normalizedForm())) {
                status = "unchanged";
            } else {
                status = "would_update";
                changeRequired = true;
            }
        }
        return new CandidateDraft(
                UUID.randomUUID(),
                runId,
                target.termId(),
                target.termType(),
                target.canonicalForm(),
                target.normalizedForm(),
                proposedCanonical,
                proposedNormalized,
                status,
                changeRequired,
                conflictTermId,
                objectMapper.valueToTree(metadata)
        );
    }

    private String normalizeDisplayCanonical(String value, String termType) {
        String normalized = Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFKC);
        normalized = WHITESPACE.matcher(normalized).replaceAll(" ").trim();
        normalized = stripWrapperPunctuation(normalized);
        if ("annotation".equalsIgnoreCase(termType) && JAVA_IDENTIFIER.matcher(normalized).matches()) {
            normalized = "@" + normalized;
        }
        return WHITESPACE.matcher(normalized).replaceAll(" ").trim();
    }

    private String normalizeStoredCanonical(String value) {
        String normalized = WHITESPACE.matcher(value == null ? "" : value).replaceAll(" ").trim();
        normalized = HANGUL_BETWEEN_SPACE.matcher(normalized).replaceAll("");
        return WHITESPACE.matcher(normalized.toLowerCase(Locale.ROOT)).replaceAll(" ").trim();
    }

    private String stripWrapperPunctuation(String value) {
        int start = 0;
        int end = value.length();
        while (start < end && WRAPPER_PUNCTUATION.indexOf(value.charAt(start)) >= 0) {
            start += 1;
        }
        while (end > start && WRAPPER_PUNCTUATION.indexOf(value.charAt(end - 1)) >= 0) {
            end -= 1;
        }
        return value.substring(start, end);
    }

    private List<TargetAnchor> findTargets(
            String documentId,
            String chunkId,
            String keyword,
            boolean activeOnly,
            int limit
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
                LIMIT :limit
                """);
        params.addValue("limit", limit);
        return jdbcTemplate.query(sql.toString(), params, (rs, rowNum) -> new TargetAnchor(
                readUuid(rs, "term_id"),
                rs.getString("canonical_form"),
                rs.getString("normalized_form"),
                rs.getString("term_type")
        ));
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

    private UUID findConflictTermId(UUID termId, String termType, String proposedNormalized) {
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

    private void insertRun(
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

    private void insertCandidate(CandidateDraft candidate) {
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

    private CorpusAdminDtos.AnchorNormalizationRunSummary getRun(UUID runId) {
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
        List<CorpusAdminDtos.AnchorNormalizationRunSummary> rows = jdbcTemplate.query(
                sql,
                new MapSqlParameterSource("runId", runId),
                runSummaryRowMapper()
        );
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("anchor normalization run not found: " + runId);
        }
        return rows.getFirst();
    }

    private CorpusAdminDtos.AnchorNormalizationCandidateDto findCandidate(UUID runId, UUID candidateId) {
        String sql = """
                SELECT candidate_id, run_id, term_id, term_type, current_canonical_form, current_normalized_form,
                       proposed_canonical_form, proposed_normalized_form, resolution_status, change_required,
                       conflict_term_id, review_decision, reviewed_by, review_note,
                       metadata_json::text AS metadata_json, reviewed_at, applied_at
                FROM anchor_normalization_candidate
                WHERE run_id = :runId
                  AND candidate_id = :candidateId
                """;
        List<CorpusAdminDtos.AnchorNormalizationCandidateDto> rows = jdbcTemplate.query(
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

    private List<CorpusAdminDtos.AnchorNormalizationCandidateDto> findCandidates(UUID runId) {
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

    private int updateCanonicalColumns(CorpusAdminDtos.AnchorNormalizationCandidateDto candidate) {
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

    private void markCandidateApplied(UUID candidateId) {
        String sql = """
                UPDATE anchor_normalization_candidate
                SET applied_at = NOW()
                WHERE candidate_id = :candidateId
                """;
        jdbcTemplate.update(sql, new MapSqlParameterSource("candidateId", candidateId));
    }

    private void updateRunReview(UUID runId, String status, String reviewedBy, String note, int applied) {
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

    private RowMapper<CorpusAdminDtos.AnchorNormalizationRunSummary> runSummaryRowMapper() {
        return (rs, rowNum) -> {
            JsonNode summary = readJson(rs, "summary_json");
            return new CorpusAdminDtos.AnchorNormalizationRunSummary(
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

    private RowMapper<CorpusAdminDtos.AnchorNormalizationCandidateDto> candidateRowMapper() {
        return (rs, rowNum) -> new CorpusAdminDtos.AnchorNormalizationCandidateDto(
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

    private Map<String, Object> sourceScopePayload(
            CorpusAdminDtos.AnchorNormalizationRunCreateRequest request,
            int limit
    ) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("document_id", trimToNull(request.documentId()));
        payload.put("chunk_id", trimToNull(request.chunkId()));
        payload.put("keyword", trimToNull(request.keyword()));
        payload.put("active_only", request.activeOnly() == null || request.activeOnly());
        payload.put("limit", limit);
        return payload;
    }

    private Map<String, Object> summaryPayload(List<CandidateDraft> candidates) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("candidate_count", candidates.size());
        payload.put("changed_count", countStatus(candidates, "would_update"));
        payload.put("unchanged_count", countStatus(candidates, "unchanged"));
        payload.put("conflict_count", countStatus(candidates, "conflict"));
        payload.put("invalid_count", countStatus(candidates, "invalid"));
        payload.put("anchor_mapping_version", MAPPING_VERSION);
        payload.put("anchor_normalization_version", NORMALIZATION_VERSION);
        payload.put("canonical_anchor_runtime_schema_version", RUNTIME_SCHEMA_VERSION);
        payload.put("approval_updates_only", List.of("corpus_glossary_terms.canonical_form", "corpus_glossary_terms.normalized_form"));
        return payload;
    }

    private Map<String, Object> reportPayload(UUID runId, String runName, JsonNode sourceScope, JsonNode summary) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("report_type", "anchor_normalization_dry_run");
        payload.put("report_schema_version", REPORT_SCHEMA_VERSION);
        payload.put("run_id", runId.toString());
        payload.put("run_name", runName);
        payload.put("source_scope", sourceScope);
        payload.put("summary", summary);
        payload.put("dry_run_only", true);
        payload.put("approval_requires_manual_review", true);
        return payload;
    }

    private int countStatus(List<CandidateDraft> candidates, String status) {
        int count = 0;
        for (CandidateDraft candidate : candidates) {
            if (status.equals(candidate.resolutionStatus())) {
                count += 1;
            }
        }
        return count;
    }

    private int normalizeLimit(Integer limit) {
        if (limit == null || limit <= 0) {
            return 500;
        }
        return Math.min(limit, 1000);
    }

    private int normalizeOffset(Integer offset) {
        return offset == null || offset < 0 ? 0 : offset;
    }

    private String blankToDefault(String value, String fallback) {
        String normalized = trimToNull(value);
        return normalized == null ? fallback : normalized;
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isBlank() ? null : normalized;
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

    private record TargetAnchor(
            UUID termId,
            String canonicalForm,
            String normalizedForm,
            String termType
    ) {
    }

    private record CandidateDraft(
            UUID candidateId,
            UUID runId,
            UUID termId,
            String termType,
            String currentCanonicalForm,
            String currentNormalizedForm,
            String proposedCanonicalForm,
            String proposedNormalizedForm,
            String resolutionStatus,
            boolean changeRequired,
            UUID conflictTermId,
            JsonNode metadataJson
    ) {
    }
}
