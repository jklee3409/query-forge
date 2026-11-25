package io.queryforge.backend.admin.corpus.document.dto;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class CorpusDocumentDtos {

    private CorpusDocumentDtos() {
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
}
