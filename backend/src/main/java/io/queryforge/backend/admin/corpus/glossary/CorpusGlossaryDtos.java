package io.queryforge.backend.admin.corpus.glossary;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Builder;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class CorpusGlossaryDtos {

    private CorpusGlossaryDtos() {
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
