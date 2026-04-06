package io.queryforge.backend.admin.corpus.controller;

import io.queryforge.backend.admin.corpus.model.CorpusAdminDtos;
import io.queryforge.backend.admin.corpus.service.CorpusAdminService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/admin/corpus")
public class CorpusAdminController {

    private final CorpusAdminService service;

    public CorpusAdminController(CorpusAdminService service) {
        this.service = service;
    }

    @GetMapping("/sources")
    public List<CorpusAdminDtos.SourceSummary> listSources() {
        return service.listSources();
    }

    @GetMapping("/runs")
    public List<CorpusAdminDtos.RunSummary> listRuns(
            @RequestParam(name = "run_id", required = false) UUID runId,
            @RequestParam(name = "run_status", required = false) String runStatus,
            @RequestParam(name = "run_type", required = false) String runType,
            @RequestParam(name = "limit", required = false) Integer limit,
            @RequestParam(name = "offset", required = false) Integer offset
    ) {
        return service.listRuns(runId, runStatus, runType, limit, offset);
    }

    @GetMapping("/runs/{runId}")
    public CorpusAdminDtos.RunDetail getRun(@PathVariable UUID runId) {
        return service.getRunDetail(runId);
    }

    @GetMapping("/documents")
    public List<CorpusAdminDtos.DocumentSummary> listDocuments(
            @RequestParam(name = "product_name", required = false) String productName,
            @RequestParam(name = "version_label", required = false) String versionLabel,
            @RequestParam(name = "source_id", required = false) String sourceId,
            @RequestParam(name = "document_id", required = false) String documentId,
            @RequestParam(name = "section_heading_keyword", required = false) String headingKeyword,
            @RequestParam(name = "chunk_keyword", required = false) String chunkKeyword,
            @RequestParam(name = "run_id", required = false) UUID runId,
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
                runId,
                activeOnly,
                limit,
                offset
        );
    }

    @GetMapping("/documents/{documentId}")
    public CorpusAdminDtos.DocumentDetail getDocument(@PathVariable String documentId) {
        return service.getDocument(documentId);
    }

    @GetMapping("/documents/{documentId}/sections")
    public List<CorpusAdminDtos.SectionDto> getDocumentSections(
            @PathVariable String documentId,
            @RequestParam(name = "section_heading_keyword", required = false) String headingKeyword,
            @RequestParam(name = "run_id", required = false) UUID runId
    ) {
        return service.listSections(documentId, headingKeyword, runId);
    }

    @GetMapping("/documents/{documentId}/chunks")
    public List<CorpusAdminDtos.ChunkSummary> getDocumentChunks(
            @PathVariable String documentId,
            @RequestParam(name = "chunk_keyword", required = false) String chunkKeyword,
            @RequestParam(name = "run_id", required = false) UUID runId,
            @RequestParam(name = "limit", required = false) Integer limit,
            @RequestParam(name = "offset", required = false) Integer offset
    ) {
        return service.listDocumentChunks(documentId, chunkKeyword, runId, limit, offset);
    }

    @GetMapping("/chunks")
    public List<CorpusAdminDtos.ChunkSummary> listChunks(
            @RequestParam(name = "product_name", required = false) String productName,
            @RequestParam(name = "version_label", required = false) String versionLabel,
            @RequestParam(name = "source_id", required = false) String sourceId,
            @RequestParam(name = "document_id", required = false) String documentId,
            @RequestParam(name = "chunk_keyword", required = false) String chunkKeyword,
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
    public CorpusAdminDtos.ChunkDetail getChunk(@PathVariable String chunkId) {
        return service.getChunk(chunkId);
    }

    @GetMapping("/chunks/{chunkId}/neighbors")
    public List<CorpusAdminDtos.ChunkNeighborDto> getChunkNeighbors(@PathVariable String chunkId) {
        return service.listChunkNeighbors(chunkId);
    }

    @GetMapping("/glossary")
    public List<CorpusAdminDtos.GlossaryTermSummary> listGlossary(
            @RequestParam(name = "product_name", required = false) String productName,
            @RequestParam(name = "version_label", required = false) String versionLabel,
            @RequestParam(name = "source_id", required = false) String sourceId,
            @RequestParam(name = "term_type", required = false) String termType,
            @RequestParam(name = "keep_in_english", required = false) Boolean keepInEnglish,
            @RequestParam(name = "run_id", required = false) UUID runId,
            @RequestParam(name = "active_only", defaultValue = "true") boolean activeOnly,
            @RequestParam(name = "keyword", required = false) String keyword,
            @RequestParam(name = "limit", required = false) Integer limit,
            @RequestParam(name = "offset", required = false) Integer offset
    ) {
        return service.listGlossary(productName, versionLabel, sourceId, termType, keepInEnglish, runId, activeOnly, keyword, limit, offset);
    }

    @GetMapping("/glossary/preview/top-terms")
    public List<CorpusAdminDtos.TopTermPreview> previewTopTerms(
            @RequestParam(name = "limit", required = false) Integer limit,
            @RequestParam(name = "product_name", required = false) String productName,
            @RequestParam(name = "term_type", required = false) String termType,
            @RequestParam(name = "keep_in_english", required = false) Boolean keepInEnglish
    ) {
        return service.previewTopTerms(limit, productName, termType, keepInEnglish);
    }

    @GetMapping("/glossary/{termId}")
    public CorpusAdminDtos.GlossaryTermDetail getGlossaryTerm(@PathVariable UUID termId) {
        return service.getGlossaryTerm(termId);
    }

    @PatchMapping("/sources/{sourceId}")
    public CorpusAdminDtos.SourceSummary patchSource(
            @PathVariable String sourceId,
            @RequestBody CorpusAdminDtos.SourceUpdateRequest request
    ) {
        return service.updateSourceEnabled(sourceId, Boolean.TRUE.equals(request.enabled()));
    }

    @GetMapping("/glossary/{termId}/evidence")
    public List<CorpusAdminDtos.GlossaryEvidenceDto> getGlossaryEvidence(@PathVariable UUID termId) {
        return service.listGlossaryEvidence(termId);
    }

    @PatchMapping("/glossary/{termId}")
    public CorpusAdminDtos.GlossaryTermDetail patchGlossaryTerm(
            @PathVariable UUID termId,
            @RequestBody CorpusAdminDtos.GlossaryTermPatchRequest request
    ) {
        service.updateGlossaryTerm(termId, request);
        return service.getGlossaryTerm(termId);
    }

    @PostMapping("/glossary/{termId}/aliases")
    public CorpusAdminDtos.GlossaryTermDetail createGlossaryAlias(
            @PathVariable UUID termId,
            @RequestBody CorpusAdminDtos.GlossaryAliasCreateRequest request
    ) {
        return service.createGlossaryAlias(termId, request);
    }

    @DeleteMapping("/glossary/aliases/{aliasId}")
    public void deleteGlossaryAlias(@PathVariable UUID aliasId) {
        service.deleteGlossaryAlias(aliasId);
    }

    @GetMapping("/documents/{documentId}/preview/raw-vs-cleaned")
    public CorpusAdminDtos.RawVsCleanedPreview previewRawVsCleaned(@PathVariable String documentId) {
        return service.previewRawVsCleaned(documentId);
    }

    @GetMapping("/documents/{documentId}/preview/chunk-boundaries")
    public CorpusAdminDtos.ChunkBoundaryPreview previewChunkBoundaries(@PathVariable String documentId) {
        return service.previewChunkBoundaries(documentId);
    }
}
