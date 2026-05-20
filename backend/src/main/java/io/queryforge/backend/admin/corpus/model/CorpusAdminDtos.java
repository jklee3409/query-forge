package io.queryforge.backend.admin.corpus.model;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Builder;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class CorpusAdminDtos {

    private CorpusAdminDtos() {
    }

    public record SourceSummary(
            String sourceId,
            String sourceType,
            String productName,
            String sourceName,
            String baseUrl,
            JsonNode includePatterns,
            JsonNode excludePatterns,
            String defaultVersion,
            boolean enabled,
            long totalDocuments,
            long activeDocuments,
            JsonNode versionStats,
            Instant createdAt,
            Instant updatedAt
    ) {
    }

    public record RunSummary(
            UUID runId,
            UUID domainId,
            String runType,
            String runStatus,
            String triggerType,
            JsonNode sourceScope,
            JsonNode configSnapshot,
            Instant startedAt,
            Instant finishedAt,
            Long durationMs,
            JsonNode summaryJson,
            String errorMessage,
            String createdBy,
            Instant createdAt,
            Instant cancelRequestedAt,
            Instant updatedAt
    ) {
    }

    public record RunStep(
            UUID stepId,
            String stepName,
            int stepOrder,
            String stepStatus,
            String inputArtifactPath,
            String outputArtifactPath,
            String commandLine,
            JsonNode metricsJson,
            Instant startedAt,
            Instant finishedAt,
            String errorMessage,
            String stdoutLogPath,
            String stderrLogPath,
            String stdoutExcerpt,
            String stderrExcerpt,
            Instant updatedAt
        ) {
    }

    public record RunDetail(
            RunSummary run,
            List<RunStep> steps
    ) {
    }

    public record DocumentSummary(
            String documentId,
            String sourceId,
            String productName,
            String versionLabel,
            String canonicalUrl,
            String title,
            String sectionPathText,
            String languageCode,
            String contentType,
            boolean active,
            UUID importRunId,
            long sectionCount,
            long chunkCount,
            Instant collectedAt,
            Instant normalizedAt,
            Instant updatedAt
    ) {
    }

    public record DocumentDetail(
            String documentId,
            String sourceId,
            String productName,
            String versionLabel,
            String canonicalUrl,
            String title,
            String sectionPathText,
            JsonNode headingHierarchyJson,
            String rawChecksum,
            String cleanedChecksum,
            String rawText,
            String cleanedText,
            String languageCode,
            String contentType,
            Instant collectedAt,
            Instant normalizedAt,
            boolean active,
            String supersededByDocumentId,
            UUID importRunId,
            JsonNode metadataJson,
            Instant createdAt,
            Instant updatedAt
    ) {
    }

    public record SectionDto(
            String sectionId,
            String documentId,
            String parentSectionId,
            Integer headingLevel,
            String headingText,
            int sectionOrder,
            String sectionPathText,
            String contentText,
            int codeBlockCount,
            int tableCount,
            int listCount,
            UUID importRunId,
            JsonNode structuralBlocksJson,
            Instant createdAt,
            Instant updatedAt
    ) {
    }

    public record ChunkSummary(
            String chunkId,
            String documentId,
            String sectionId,
            int chunkIndexInDocument,
            int chunkIndexInSection,
            String sectionPathText,
            int charLen,
            int tokenLen,
            int overlapFromPrevChars,
            String previousChunkId,
            String nextChunkId,
            boolean codePresence,
            boolean tablePresence,
            boolean listPresence,
            String productName,
            String versionLabel,
            UUID importRunId,
            Instant createdAt,
            Instant updatedAt
    ) {
    }

    public record ChunkDetail(
            String chunkId,
            String documentId,
            String sectionId,
            int chunkIndexInDocument,
            int chunkIndexInSection,
            String sectionPathText,
            String chunkText,
            int charLen,
            int tokenLen,
            int overlapFromPrevChars,
            String previousChunkId,
            String nextChunkId,
            boolean codePresence,
            boolean tablePresence,
            boolean listPresence,
            String productName,
            String versionLabel,
            String contentChecksum,
            UUID importRunId,
            JsonNode metadataJson,
            Instant createdAt,
            Instant updatedAt
    ) {
    }

    public record ChunkNeighborDto(
            UUID relationId,
            String sourceChunkId,
            String targetChunkId,
            String relationType,
            Integer distanceInDoc,
            String targetDocumentId,
            Integer targetChunkIndexInDocument,
            String targetSectionPathText
    ) {
    }

    public record GlossaryAliasDto(
            UUID aliasId,
            UUID termId,
            String aliasText,
            String aliasLanguage,
            String aliasType,
            UUID importRunId,
            Instant createdAt
    ) {
    }

    public record GlossaryTermSummary(
            UUID termId,
            String canonicalForm,
            String normalizedForm,
            String termType,
            boolean keepInEnglish,
            String descriptionShort,
            double sourceConfidence,
            String firstSeenDocumentId,
            String firstSeenChunkId,
            int evidenceCount,
            boolean active,
            UUID importRunId,
            JsonNode metadataJson,
            Instant createdAt,
            Instant updatedAt
    ) {
    }

    public record GlossaryTermDetail(
            GlossaryTermSummary term,
            List<GlossaryAliasDto> aliases
    ) {
    }

    public record GlossaryEvidenceDto(
            UUID evidenceId,
            UUID termId,
            String documentId,
            String chunkId,
            String matchedText,
            JsonNode lineOrOffsetInfo,
            UUID importRunId,
            Instant createdAt
    ) {
    }

    public record AnchorSummary(
            UUID termId,
            String canonicalForm,
            String normalizedForm,
            String termType,
            boolean keepInEnglish,
            double sourceConfidence,
            int evidenceCount,
            int scopedEvidenceCount,
            String firstSeenDocumentId,
            String firstSeenChunkId,
            Instant updatedAt
    ) {
    }

    public record RawVsCleanedPreview(
            String documentId,
            String rawText,
            String cleanedText,
            String removedBoilerplateExcerpt,
            JsonNode headingHierarchy,
            JsonNode metadata
    ) {
    }

    public record ChunkBoundaryDto(
            String chunkId,
            int chunkIndexInDocument,
            String sectionPathText,
            int startChar,
            int endChar,
            int overlapFromPrevChars,
            int charLen,
            int tokenLen
    ) {
    }

    public record ChunkBoundaryPreview(
            String documentId,
            List<ChunkBoundaryDto> boundaries
    ) {
    }

    public record TopTermPreview(
            UUID termId,
            String canonicalForm,
            String termType,
            int evidenceCount,
            boolean keepInEnglish,
            List<String> provenanceSnippets
    ) {
    }

    @Builder
    public record AnchorExtractRequest(
            List<String> documentIds,
            List<String> chunkIds
    ) {
    }

    public record AnchorExtractResponse(
            int targetChunkCount,
            int deletedEvidenceCount,
            int insertedEvidenceCount,
            int updatedTermCount,
            int deactivatedTermCount,
            int remappedSyntheticQueryCount,
            int remappedLinkCount
    ) {
    }

    @Builder
    public record AnchorNormalizationRunCreateRequest(
            String runName,
            String documentId,
            String chunkId,
            String keyword,
            Boolean activeOnly,
            Integer limit,
            String createdBy
    ) {
    }

    @Builder
    public record AnchorNormalizationReviewRequest(
            String reviewedBy,
            String note
    ) {
    }

    @Builder
    public record AnchorNormalizationCandidateReviewRequest(
            String decision,
            String reviewedBy,
            String note
    ) {
    }

    @Builder
    public record AnchorNormalizationCandidateDecision(
            UUID candidateId,
            String decision,
            String note
    ) {
    }

    @Builder
    public record AnchorNormalizationCandidateReviewBatchRequest(
            List<AnchorNormalizationCandidateDecision> decisions,
            String reviewedBy,
            String note
    ) {
    }

    public record AnchorNormalizationRunSummary(
            UUID runId,
            String runName,
            String status,
            int candidateCount,
            int changedCount,
            int unchangedCount,
            int conflictCount,
            int invalidCount,
            int appliedUpdateCount,
            int reviewApprovedCount,
            int reviewSkippedCount,
            int reviewPendingCount,
            String createdBy,
            String reviewedBy,
            JsonNode sourceScopeJson,
            JsonNode summaryJson,
            Instant createdAt,
            Instant updatedAt,
            Instant reviewedAt,
            Instant appliedAt
    ) {
    }

    public record AnchorNormalizationCandidateDto(
            UUID candidateId,
            UUID termId,
            String termType,
            String currentCanonicalForm,
            String currentNormalizedForm,
            String proposedCanonicalForm,
            String proposedNormalizedForm,
            String resolutionStatus,
            boolean changeRequired,
            UUID conflictTermId,
            String reviewDecision,
            String reviewedBy,
            String reviewNote,
            JsonNode metadataJson,
            Instant reviewedAt,
            Instant appliedAt
    ) {
    }

    public record AnchorNormalizationRunDetail(
            AnchorNormalizationRunSummary run,
            List<AnchorNormalizationCandidateDto> candidates
    ) {
    }

    @Builder
    public record MultiSourceAnchorBuildRequest(
            String runName,
            String relationVersion,
            List<String> relationTypes,
            Double minRelationScore,
            Integer maxRelationsPerAnchor,
            String createdBy
    ) {
    }

    public record MultiSourceAnchorBuildRunSummary(
            UUID runId,
            String runName,
            String relationVersion,
            String mappingVersion,
            String normalizationVersion,
            String canonicalAnchorRuntimeSchemaVersion,
            String status,
            JsonNode relationTypeAllowlist,
            double minRelationScore,
            Integer maxRelationsPerAnchor,
            int candidateAnchorCount,
            int relationCount,
            int evidenceCount,
            JsonNode summaryJson,
            String createdBy,
            String errorMessage,
            Instant createdAt,
            Instant updatedAt,
            Instant finishedAt
    ) {
    }

    @Builder
    public record AnchorEvalRunCreateRequest(
            String runName,
            String productName,
            String sourceId,
            List<String> documentIds,
            List<String> chunkIds,
            Integer sampleSize,
            Integer candidateLimit,
            String createdBy
    ) {
    }

    public record AnchorEvalRunSummary(
            UUID runId,
            String runName,
            String status,
            String productName,
            String sourceId,
            int sampleSize,
            int candidateLimit,
            String createdBy,
            JsonNode summaryJson,
            Instant createdAt,
            Instant updatedAt
    ) {
    }

    public record AnchorEvalCandidateDto(
            UUID candidateId,
            UUID termId,
            String canonicalForm,
            String termType,
            double score,
            int rankIndex,
            String labelValue,
            Double labelConfidence,
            String labelNote
    ) {
    }

    public record AnchorEvalSampleDto(
            UUID sampleId,
            String documentId,
            String chunkId,
            String chunkText,
            List<AnchorEvalCandidateDto> candidates
    ) {
    }

    public record AnchorEvalRunDetail(
            AnchorEvalRunSummary run,
            List<AnchorEvalSampleDto> samples
    ) {
    }

    @Builder
    public record AnchorEvalLabelRequest(
            UUID candidateId,
            String labelValue,
            Double confidence,
            String note,
            String labeledBy
    ) {
    }

    @Builder
    public record SourceUpdateRequest(
            Boolean enabled
    ) {
    }

    @Builder
    public record SourceUpsertRequest(
            String sourceId,
            String productName,
            List<String> startUrls,
            List<String> allowPrefixes,
            List<String> denyUrlPatterns,
            Boolean enabled,
            Double requestDelaySeconds,
            Integer maxDepth,
            UUID domainId
    ) {
    }

    @Builder
    public record SourceAutoRegisterRequest(
            String url,
            String sourceId,
            String productName,
            Boolean enabled,
            Double requestDelaySeconds,
            Integer maxDepth,
            UUID domainId
    ) {
    }

    @Builder
    public record GlossaryTermPatchRequest(
            Boolean keepInEnglish,
            Boolean active,
            String descriptionShort
    ) {
    }

    @Builder
    public record GlossaryAliasCreateRequest(
            String aliasText,
            String aliasLanguage,
            String aliasType
    ) {
    }
}
