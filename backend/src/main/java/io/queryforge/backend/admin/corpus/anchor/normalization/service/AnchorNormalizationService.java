package io.queryforge.backend.admin.corpus.anchor.normalization.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.queryforge.backend.admin.corpus.anchor.normalization.dto.AnchorNormalizationDtos.CandidateDraft;
import io.queryforge.backend.admin.corpus.anchor.normalization.dto.AnchorNormalizationDtos.TargetAnchor;
import io.queryforge.backend.admin.corpus.anchor.normalization.dto.AnchorNormalizationDtos;
import io.queryforge.backend.admin.corpus.anchor.normalization.repository.AnchorNormalizationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import static io.queryforge.backend.admin.corpus.anchor.support.AnchorSchemaVersions.MAPPING_VERSION;
import static io.queryforge.backend.admin.corpus.anchor.support.AnchorSchemaVersions.NORMALIZATION_VERSION;
import static io.queryforge.backend.admin.corpus.anchor.support.AnchorSchemaVersions.RUNTIME_SCHEMA_VERSION;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AnchorNormalizationService {

    private static final String REPORT_SCHEMA_VERSION = "canonical-anchor-normalization-review-v1";
    private static final String REVIEW_DECISION_PENDING = "pending";
    private static final String REVIEW_DECISION_APPROVE = "approve";
    private static final String REVIEW_DECISION_SKIP = "skip";
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final Pattern JAVA_IDENTIFIER = Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]*");
    private static final Pattern HANGUL_BETWEEN_SPACE = Pattern.compile("(?<=[\\uAC00-\\uD7A3])\\s+(?=[\\uAC00-\\uD7A3])");
    private static final String WRAPPER_PUNCTUATION = " \t\r\n\"'`“”‘’[]{}()<>";

    private final AnchorNormalizationRepository repository;
    private final ObjectMapper objectMapper;

    @Transactional
    public AnchorNormalizationDtos.AnchorNormalizationRunSummary createDryRun(
            AnchorNormalizationDtos.AnchorNormalizationRunCreateRequest request
    ) {
        AnchorNormalizationDtos.AnchorNormalizationRunCreateRequest safeRequest = request == null
                ? AnchorNormalizationDtos.AnchorNormalizationRunCreateRequest.builder().build()
                : request;
        Integer targetLimit = normalizeTargetLimit(safeRequest.limit());
        List<TargetAnchor> targets = repository.findTargets(
                safeRequest.documentId(),
                safeRequest.chunkId(),
                safeRequest.keyword(),
                safeRequest.activeOnly() == null || safeRequest.activeOnly(),
                targetLimit
        );
        UUID runId = UUID.randomUUID();
        String runName = blankToDefault(safeRequest.runName(), "anchor-normalize-" + runId.toString().substring(0, 8));
        String createdBy = blankToDefault(safeRequest.createdBy(), "admin-ui");
        List<CandidateDraft> candidates = new ArrayList<>();
        for (TargetAnchor target : targets) {
            candidates.add(buildCandidate(runId, target));
        }
        JsonNode sourceScope = objectMapper.valueToTree(sourceScopePayload(safeRequest, targetLimit));
        JsonNode summary = objectMapper.valueToTree(summaryPayload(candidates));
        JsonNode report = objectMapper.valueToTree(reportPayload(runId, runName, sourceScope, summary));
        repository.insertRun(runId, runName, sourceScope, summary, report, createdBy);
        for (CandidateDraft candidate : candidates) {
            repository.insertCandidate(candidate);
        }
        return repository.getRun(runId);
    }

    public List<AnchorNormalizationDtos.AnchorNormalizationRunSummary> listRuns(Integer limit, Integer offset) {
        return repository.listRuns(normalizePageLimit(limit), normalizeOffset(offset));
    }

    public AnchorNormalizationDtos.AnchorNormalizationRunDetail getRunDetail(UUID runId) {
        return new AnchorNormalizationDtos.AnchorNormalizationRunDetail(
                repository.getRun(runId),
                repository.findCandidates(runId)
        );
    }

    @Transactional
    public void deleteRun(UUID runId) {
        repository.getRun(runId);
        int deleted = repository.deleteRun(runId);
        if (deleted != 1) {
            throw new IllegalStateException("failed to delete anchor normalization run: " + runId);
        }
    }

    @Transactional
    public AnchorNormalizationDtos.AnchorNormalizationCandidateDto reviewCandidate(
            UUID runId,
            UUID candidateId,
            AnchorNormalizationDtos.AnchorNormalizationCandidateReviewRequest request
    ) {
        AnchorNormalizationDtos.AnchorNormalizationRunSummary run = repository.getRun(runId);
        ensurePendingReview(run, runId);
        String decision = normalizeReviewDecision(request == null ? null : request.decision());
        String reviewedBy = blankToDefault(request == null ? null : request.reviewedBy(), "admin-ui");
        String note = trimToNull(request == null ? null : request.note());
        return updateCandidateReview(runId, candidateId, decision, reviewedBy, note);
    }

    @Transactional
    public AnchorNormalizationDtos.AnchorNormalizationRunDetail reviewCandidates(
            UUID runId,
            AnchorNormalizationDtos.AnchorNormalizationCandidateReviewBatchRequest request
    ) {
        AnchorNormalizationDtos.AnchorNormalizationRunSummary run = repository.getRun(runId);
        ensurePendingReview(run, runId);
        List<AnchorNormalizationDtos.AnchorNormalizationCandidateDecision> decisions = request == null
                ? List.of()
                : request.decisions();
        if (decisions == null || decisions.isEmpty()) {
            return getRunDetail(runId);
        }
        String reviewedBy = blankToDefault(request.reviewedBy(), "admin-ui");
        String batchNote = trimToNull(request.note());
        for (AnchorNormalizationDtos.AnchorNormalizationCandidateDecision candidateDecision : decisions) {
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
    public AnchorNormalizationDtos.AnchorNormalizationRunSummary approve(
            UUID runId,
            AnchorNormalizationDtos.AnchorNormalizationReviewRequest request
    ) {
        AnchorNormalizationDtos.AnchorNormalizationRunSummary run = repository.getRun(runId);
        ensurePendingReview(run, runId);
        List<AnchorNormalizationDtos.AnchorNormalizationCandidateDto> candidates = repository.findCandidates(runId);
        validateCandidateReviewDecisions(runId, candidates);
        int applied = 0;
        for (AnchorNormalizationDtos.AnchorNormalizationCandidateDto candidate : candidates) {
            if (!"would_update".equals(candidate.resolutionStatus())
                    || !REVIEW_DECISION_APPROVE.equals(candidate.reviewDecision())) {
                continue;
            }
            int updated = repository.updateCanonicalColumns(candidate);
            if (updated != 1) {
                throw new IllegalStateException("failed to apply anchor normalization candidate: " + candidate.candidateId());
            }
            repository.markCandidateApplied(candidate.candidateId());
            applied += 1;
        }
        String reviewedBy = blankToDefault(request == null ? null : request.reviewedBy(), "admin-ui");
        String note = trimToNull(request == null ? null : request.note());
        repository.updateRunReview(runId, "approved", reviewedBy, note, applied);
        return repository.getRun(runId);
    }

    @Transactional
    public AnchorNormalizationDtos.AnchorNormalizationRunSummary reject(
            UUID runId,
            AnchorNormalizationDtos.AnchorNormalizationReviewRequest request
    ) {
        AnchorNormalizationDtos.AnchorNormalizationRunSummary run = repository.getRun(runId);
        ensurePendingReview(run, runId);
        String reviewedBy = blankToDefault(request == null ? null : request.reviewedBy(), "admin-ui");
        String note = trimToNull(request == null ? null : request.note());
        repository.updateRunReview(runId, "rejected", reviewedBy, note, 0);
        return repository.getRun(runId);
    }

    private void ensurePendingReview(AnchorNormalizationDtos.AnchorNormalizationRunSummary run, UUID runId) {
        if (!"pending_review".equals(run.status())) {
            throw new IllegalArgumentException("anchor normalization run is not pending_review: " + runId);
        }
    }

    private void validateCandidateReviewDecisions(
            UUID runId,
            List<AnchorNormalizationDtos.AnchorNormalizationCandidateDto> candidates
    ) {
        int pending = 0;
        int invalidApprove = 0;
        for (AnchorNormalizationDtos.AnchorNormalizationCandidateDto candidate : candidates) {
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

    private boolean reviewRequired(AnchorNormalizationDtos.AnchorNormalizationCandidateDto candidate) {
        return !"unchanged".equals(candidate.resolutionStatus());
    }

    private String normalizeReviewDecision(String decision) {
        String normalized = blankToDefault(decision, REVIEW_DECISION_PENDING).toLowerCase(Locale.ROOT).trim();
        if (!List.of(REVIEW_DECISION_PENDING, REVIEW_DECISION_APPROVE, REVIEW_DECISION_SKIP).contains(normalized)) {
            throw new IllegalArgumentException("unsupported anchor normalization candidate review decision: " + decision);
        }
        return normalized;
    }

    private AnchorNormalizationDtos.AnchorNormalizationCandidateDto updateCandidateReview(
            UUID runId,
            UUID candidateId,
            String decision,
            String reviewedBy,
            String note
    ) {
        AnchorNormalizationDtos.AnchorNormalizationCandidateDto candidate = repository.findCandidate(runId, candidateId);
        if (REVIEW_DECISION_APPROVE.equals(decision) && !"would_update".equals(candidate.resolutionStatus())) {
            throw new IllegalArgumentException("only would_update candidates can be approved: " + candidateId);
        }
        repository.updateCandidateReview(runId, candidateId, decision, reviewedBy, note);
        return repository.findCandidate(runId, candidateId);
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
            conflictTermId = repository.findConflictTermId(target.termId(), target.termType(), proposedNormalized);
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

    private Map<String, Object> sourceScopePayload(
            AnchorNormalizationDtos.AnchorNormalizationRunCreateRequest request,
            Integer limit
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

    private int normalizePageLimit(Integer limit) {
        if (limit == null || limit <= 0) {
            return 500;
        }
        return Math.min(limit, 1000);
    }

    private Integer normalizeTargetLimit(Integer limit) {
        if (limit == null || limit <= 0) {
            return null;
        }
        return Math.min(limit, 100_000);
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

}
