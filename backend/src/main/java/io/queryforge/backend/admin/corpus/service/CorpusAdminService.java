package io.queryforge.backend.admin.corpus.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.queryforge.backend.admin.corpus.model.CorpusAdminDtos;
import io.queryforge.backend.admin.corpus.repository.CorpusAdminRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;

@Service
public class CorpusAdminService {

    private final CorpusAdminRepository repository;
    private final ObjectMapper objectMapper;

    public CorpusAdminService(
            CorpusAdminRepository repository,
            ObjectMapper objectMapper
    ) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    public List<CorpusAdminDtos.SourceSummary> listSources() {
        return repository.findSources();
    }

    public List<CorpusAdminDtos.RunSummary> listRuns(
            UUID runId,
            String runStatus,
            String runType,
            Integer limit,
            Integer offset
    ) {
        return repository.findRuns(runId, runStatus, runType, limit, offset);
    }

    public CorpusAdminDtos.RunDetail getRunDetail(UUID runId) {
        return new CorpusAdminDtos.RunDetail(
                repository.findRun(runId),
                repository.findRunSteps(runId)
        );
    }

    public List<CorpusAdminDtos.DocumentSummary> listDocuments(
            String productName,
            String versionLabel,
            String sourceId,
            String documentId,
            String headingKeyword,
            String chunkKeyword,
            UUID runId,
            boolean activeOnly,
            Integer limit,
            Integer offset
    ) {
        return repository.findDocuments(
                productName,
                versionLabel,
                sourceId,
                documentId,
                headingKeyword,
                chunkKeyword,
                runId,
                activeOnly,
                limit,
                offset
        );
    }

    public CorpusAdminDtos.DocumentDetail getDocument(String documentId) {
        return repository.findDocument(documentId);
    }

    public List<CorpusAdminDtos.SectionDto> listSections(
            String documentId,
            String headingKeyword,
            UUID runId
    ) {
        return repository.findSections(documentId, headingKeyword, runId);
    }

    public List<CorpusAdminDtos.ChunkSummary> listDocumentChunks(
            String documentId,
            String chunkKeyword,
            UUID runId,
            Integer limit,
            Integer offset
    ) {
        return repository.findChunks(null, null, null, documentId, chunkKeyword, runId, false, limit, offset);
    }

    public List<CorpusAdminDtos.ChunkSummary> listChunks(
            String productName,
            String versionLabel,
            String sourceId,
            String documentId,
            String chunkKeyword,
            UUID runId,
            boolean activeOnly,
            Integer limit,
            Integer offset
    ) {
        return repository.findChunks(productName, versionLabel, sourceId, documentId, chunkKeyword, runId, activeOnly, limit, offset);
    }

    public CorpusAdminDtos.ChunkDetail getChunk(String chunkId) {
        return repository.findChunk(chunkId);
    }

    public List<CorpusAdminDtos.ChunkNeighborDto> listChunkNeighbors(String chunkId) {
        return repository.findChunkNeighbors(chunkId);
    }

    public List<CorpusAdminDtos.GlossaryTermSummary> listGlossary(
            String productName,
            String versionLabel,
            String sourceId,
            String termType,
            Boolean keepInEnglish,
            UUID runId,
            boolean activeOnly,
            String keyword,
            Integer limit,
            Integer offset
    ) {
        return repository.findGlossaryTerms(
                productName,
                versionLabel,
                sourceId,
                termType,
                keepInEnglish,
                runId,
                activeOnly,
                keyword,
                limit,
                offset
        );
    }

    public CorpusAdminDtos.GlossaryTermDetail getGlossaryTerm(UUID termId) {
        return new CorpusAdminDtos.GlossaryTermDetail(
                repository.findGlossaryTerm(termId),
                repository.findGlossaryAliases(termId)
        );
    }

    public List<CorpusAdminDtos.GlossaryEvidenceDto> listGlossaryEvidence(UUID termId) {
        return repository.findGlossaryEvidence(termId);
    }

    public CorpusAdminDtos.RawVsCleanedPreview previewRawVsCleaned(String documentId) {
        CorpusAdminDtos.DocumentDetail document = repository.findDocument(documentId);
        return new CorpusAdminDtos.RawVsCleanedPreview(
                document.documentId(),
                document.rawText(),
                document.cleanedText(),
                extractRemovedExcerpt(document.rawText(), document.cleanedText()),
                document.headingHierarchyJson(),
                document.metadataJson()
        );
    }

    public CorpusAdminDtos.ChunkBoundaryPreview previewChunkBoundaries(String documentId) {
        List<CorpusAdminDtos.ChunkSummary> chunks = repository.findChunks(
                null,
                null,
                null,
                documentId,
                null,
                null,
                false,
                500,
                0
        );
        int cursor = 0;
        List<CorpusAdminDtos.ChunkBoundaryDto> boundaries = new ArrayList<>();
        for (CorpusAdminDtos.ChunkSummary chunk : chunks) {
            CorpusAdminDtos.ChunkDetail detail = repository.findChunk(chunk.chunkId());
            String baseChunkText = repository.stripOverlapPrefix(detail.chunkText());
            int start = cursor;
            int end = cursor + baseChunkText.length();
            boundaries.add(
                    new CorpusAdminDtos.ChunkBoundaryDto(
                            detail.chunkId(),
                            detail.chunkIndexInDocument(),
                            detail.sectionPathText(),
                            start,
                            end,
                            detail.overlapFromPrevChars(),
                            detail.charLen(),
                            detail.tokenLen()
                    )
            );
            cursor = end;
        }
        return new CorpusAdminDtos.ChunkBoundaryPreview(documentId, boundaries);
    }

    public List<CorpusAdminDtos.TopTermPreview> previewTopTerms(
            Integer limit,
            String productName,
            String termType,
            Boolean keepInEnglish
    ) {
        return repository.findTopTermsPreview(limit, productName, termType, keepInEnglish);
    }

    private String extractRemovedExcerpt(String rawText, String cleanedText) {
        LinkedHashSet<String> removed = new LinkedHashSet<>();
        for (String segment : rawText.split("\\n\\n")) {
            String trimmed = segment.trim();
            if (trimmed.isBlank()) {
                continue;
            }
            if (!cleanedText.contains(trimmed)) {
                removed.add(trimmed);
            }
        }
        if (removed.isEmpty()) {
            return "";
        }
        return removed.stream().limit(5).reduce((left, right) -> left + "\n\n" + right).orElse("");
    }

    public JsonNode toJson(Object value) {
        return objectMapper.valueToTree(value);
    }
}
