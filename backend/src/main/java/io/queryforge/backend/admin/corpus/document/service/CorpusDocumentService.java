package io.queryforge.backend.admin.corpus.document.service;

import io.queryforge.backend.admin.corpus.document.dto.CorpusDocumentDtos;
import io.queryforge.backend.admin.corpus.document.repository.CorpusDocumentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CorpusDocumentService {

    private static final String OVERLAP_LABEL = "Overlap context from previous chunk:";

    private final CorpusDocumentRepository repository;

    private String stripOverlapPrefix(String chunkText) {
        if (!chunkText.startsWith(OVERLAP_LABEL)) {
            return chunkText;
        }
        String[] parts = chunkText.split("\\n\\n", 2);
        if (parts.length < 2) {
            return chunkText;
        }
        return parts[1];
    }

    public List<CorpusDocumentDtos.DocumentSummary> listDocuments(
            String productName,
            String versionLabel,
            String sourceId,
            String documentId,
            String headingKeyword,
            String chunkKeyword,
            String search,
            UUID runId,
            UUID domainId,
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
                search,
                runId,
                domainId,
                activeOnly,
                limit,
                offset
        );
    }

    public CorpusDocumentDtos.DocumentDetail getDocument(String documentId) {
        return repository.findDocument(documentId);
    }

    public List<CorpusDocumentDtos.SectionDto> listSections(
            String documentId,
            String headingKeyword,
            UUID runId
    ) {
        return repository.findSections(documentId, headingKeyword, runId);
    }

    public List<CorpusDocumentDtos.ChunkSummary> listDocumentChunks(
            String documentId,
            String chunkKeyword,
            String search,
            UUID runId,
            Integer limit,
            Integer offset
    ) {
        return repository.findChunks(null, null, null, documentId, chunkKeyword, search, null, null, null, runId, false, limit, offset);
    }

    public List<CorpusDocumentDtos.ChunkSummary> listChunks(
            String productName,
            String versionLabel,
            String sourceId,
            String documentId,
            String chunkKeyword,
            String search,
            Boolean codePresence,
            Integer minTokenLen,
            Integer maxTokenLen,
            UUID runId,
            boolean activeOnly,
            Integer limit,
            Integer offset
    ) {
        return repository.findChunks(
                productName,
                versionLabel,
                sourceId,
                documentId,
                chunkKeyword,
                search,
                codePresence,
                minTokenLen,
                maxTokenLen,
                runId,
                activeOnly,
                limit,
                offset
        );
    }

    public CorpusDocumentDtos.ChunkDetail getChunk(String chunkId) {
        return repository.findChunk(chunkId);
    }

    public List<CorpusDocumentDtos.ChunkNeighborDto> listChunkNeighbors(String chunkId) {
        return repository.findChunkNeighbors(chunkId);
    }

    public CorpusDocumentDtos.RawVsCleanedPreview previewRawVsCleaned(String documentId) {
        CorpusDocumentDtos.DocumentDetail document = repository.findDocument(documentId);
        return new CorpusDocumentDtos.RawVsCleanedPreview(
                document.documentId(),
                document.rawText(),
                document.cleanedText(),
                extractRemovedExcerpt(document.rawText(), document.cleanedText()),
                document.headingHierarchyJson(),
                document.metadataJson()
        );
    }

    public CorpusDocumentDtos.ChunkBoundaryPreview previewChunkBoundaries(String documentId) {
        List<CorpusDocumentDtos.ChunkDetail> chunks = repository.findChunkDetailsByDocumentId(documentId, 500);
        int cursor = 0;
        List<CorpusDocumentDtos.ChunkBoundaryDto> boundaries = new ArrayList<>();
        for (CorpusDocumentDtos.ChunkDetail detail : chunks) {
            String baseChunkText = stripOverlapPrefix(detail.chunkText());
            int start = cursor;
            int end = cursor + baseChunkText.length();
            boundaries.add(
                    new CorpusDocumentDtos.ChunkBoundaryDto(
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
        return new CorpusDocumentDtos.ChunkBoundaryPreview(documentId, boundaries);
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
}
