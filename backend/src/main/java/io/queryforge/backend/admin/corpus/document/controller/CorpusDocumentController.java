package io.queryforge.backend.admin.corpus.document.controller;

import io.queryforge.backend.admin.corpus.document.dto.CorpusDocumentDtos;
import io.queryforge.backend.admin.corpus.document.service.CorpusDocumentService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/admin/corpus")
@RequiredArgsConstructor
public class CorpusDocumentController {

    private final CorpusDocumentService service;

    @GetMapping("/documents")
    public List<CorpusDocumentDtos.DocumentSummary> listDocuments(
            @RequestParam(name = "product_name", required = false) String productName,
            @RequestParam(name = "version_label", required = false) String versionLabel,
            @RequestParam(name = "source_id", required = false) String sourceId,
            @RequestParam(name = "document_id", required = false) String documentId,
            @RequestParam(name = "section_heading_keyword", required = false) String headingKeyword,
            @RequestParam(name = "chunk_keyword", required = false) String chunkKeyword,
            @RequestParam(name = "search", required = false) String search,
            @RequestParam(name = "run_id", required = false) UUID runId,
            @RequestParam(name = "domain_id", required = false) UUID domainId,
            @RequestParam(name = "active_only", defaultValue = "true") boolean activeOnly,
            @RequestParam(name = "limit", required = false) Integer limit,
            @RequestParam(name = "offset", required = false) Integer offset
    ) {
        return service.listDocuments(
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

    @GetMapping("/documents/{documentId}")
    public CorpusDocumentDtos.DocumentDetail getDocument(@PathVariable String documentId) {
        return service.getDocument(documentId);
    }

    @GetMapping("/documents/{documentId}/sections")
    public List<CorpusDocumentDtos.SectionDto> getDocumentSections(
            @PathVariable String documentId,
            @RequestParam(name = "section_heading_keyword", required = false) String headingKeyword,
            @RequestParam(name = "run_id", required = false) UUID runId
    ) {
        return service.listSections(documentId, headingKeyword, runId);
    }

    @GetMapping("/documents/{documentId}/chunks")
    public List<CorpusDocumentDtos.ChunkSummary> getDocumentChunks(
            @PathVariable String documentId,
            @RequestParam(name = "chunk_keyword", required = false) String chunkKeyword,
            @RequestParam(name = "search", required = false) String search,
            @RequestParam(name = "run_id", required = false) UUID runId,
            @RequestParam(name = "limit", required = false) Integer limit,
            @RequestParam(name = "offset", required = false) Integer offset
    ) {
        return service.listDocumentChunks(documentId, chunkKeyword, search, runId, limit, offset);
    }

    @GetMapping("/chunks")
    public List<CorpusDocumentDtos.ChunkSummary> listChunks(
            @RequestParam(name = "product_name", required = false) String productName,
            @RequestParam(name = "version_label", required = false) String versionLabel,
            @RequestParam(name = "source_id", required = false) String sourceId,
            @RequestParam(name = "document_id", required = false) String documentId,
            @RequestParam(name = "chunk_keyword", required = false) String chunkKeyword,
            @RequestParam(name = "search", required = false) String search,
            @RequestParam(name = "code_presence", required = false) Boolean codePresence,
            @RequestParam(name = "min_token_len", required = false) Integer minTokenLen,
            @RequestParam(name = "max_token_len", required = false) Integer maxTokenLen,
            @RequestParam(name = "run_id", required = false) UUID runId,
            @RequestParam(name = "active_only", defaultValue = "true") boolean activeOnly,
            @RequestParam(name = "limit", required = false) Integer limit,
            @RequestParam(name = "offset", required = false) Integer offset
    ) {
        return service.listChunks(
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

    @GetMapping("/chunks/{chunkId}")
    public CorpusDocumentDtos.ChunkDetail getChunk(@PathVariable String chunkId) {
        return service.getChunk(chunkId);
    }

    @GetMapping("/chunks/{chunkId}/neighbors")
    public List<CorpusDocumentDtos.ChunkNeighborDto> getChunkNeighbors(@PathVariable String chunkId) {
        return service.listChunkNeighbors(chunkId);
    }

    @GetMapping("/documents/{documentId}/preview/raw-vs-cleaned")
    public CorpusDocumentDtos.RawVsCleanedPreview previewRawVsCleaned(@PathVariable String documentId) {
        return service.previewRawVsCleaned(documentId);
    }

    @GetMapping("/documents/{documentId}/preview/chunk-boundaries")
    public CorpusDocumentDtos.ChunkBoundaryPreview previewChunkBoundaries(@PathVariable String documentId) {
        return service.previewChunkBoundaries(documentId);
    }
}
