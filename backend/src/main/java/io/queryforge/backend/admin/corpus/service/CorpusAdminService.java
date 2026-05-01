package io.queryforge.backend.admin.corpus.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.queryforge.backend.admin.corpus.model.CorpusAdminDtos;
import io.queryforge.backend.admin.corpus.repository.CorpusAdminRepository;
import io.queryforge.backend.admin.pipeline.service.SourceCatalogService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;
import java.net.URI;
import java.util.Locale;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CorpusAdminService {

    private final CorpusAdminRepository repository;
    private final ObjectMapper objectMapper;
    private final SourceCatalogService sourceCatalogService;
    private final AnchorExtractionService anchorExtractionService;

    @Transactional
    public List<CorpusAdminDtos.SourceSummary> listSources() {
        sourceCatalogService.syncSourcesFromConfig();
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
            String search,
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
                search,
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
            String search,
            UUID runId,
            Integer limit,
            Integer offset
    ) {
        return repository.findChunks(null, null, null, documentId, chunkKeyword, search, null, null, null, runId, false, limit, offset);
    }

    public List<CorpusAdminDtos.ChunkSummary> listChunks(
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
        List<CorpusAdminDtos.ChunkDetail> chunks = repository.findChunkDetailsByDocumentId(documentId, 500);
        int cursor = 0;
        List<CorpusAdminDtos.ChunkBoundaryDto> boundaries = new ArrayList<>();
        for (CorpusAdminDtos.ChunkDetail detail : chunks) {
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

    @Transactional
    public List<CorpusAdminDtos.GlossaryAliasDto> updateGlossaryTerm(
            UUID termId,
            CorpusAdminDtos.GlossaryTermPatchRequest request
    ) {
        repository.updateGlossaryTerm(
                termId,
                request.keepInEnglish(),
                request.active(),
                request.descriptionShort()
        );
        return repository.findGlossaryAliases(termId);
    }

    @Transactional
    public CorpusAdminDtos.GlossaryTermDetail createGlossaryAlias(
            UUID termId,
            CorpusAdminDtos.GlossaryAliasCreateRequest request
    ) {
        repository.insertGlossaryAlias(
                termId,
                request.aliasText(),
                request.aliasLanguage() == null || request.aliasLanguage().isBlank() ? "en" : request.aliasLanguage(),
                request.aliasType() == null || request.aliasType().isBlank() ? "same_case" : request.aliasType()
        );
        return getGlossaryTerm(termId);
    }

    @Transactional
    public void deleteGlossaryAlias(UUID aliasId) {
        repository.deleteGlossaryAlias(aliasId);
    }

    @Transactional
    public CorpusAdminDtos.AnchorExtractResponse extractAnchors(CorpusAdminDtos.AnchorExtractRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("anchor extract request is required");
        }
        return anchorExtractionService.extractAnchors(request.documentIds(), request.chunkIds());
    }

    @Transactional
    public CorpusAdminDtos.SourceSummary updateSourceEnabled(String sourceId, boolean enabled) {
        repository.updateSourceEnabled(sourceId, enabled);
        return repository.findSources().stream()
                .filter(source -> source.sourceId().equals(sourceId))
                .findFirst()
                .orElseThrow();
    }

    @Transactional
    public CorpusAdminDtos.SourceSummary upsertSource(CorpusAdminDtos.SourceUpsertRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("source upsert request is required");
        }
        String sourceId = requireTrimmed(request.sourceId(), "sourceId");
        String productName = requireTrimmed(request.productName(), "productName");
        List<String> startUrls = normalizeDistinctNonBlank(request.startUrls());
        List<String> allowPrefixes = normalizeDistinctNonBlank(request.allowPrefixes());
        List<String> denyPatterns = normalizeDistinctNonBlank(request.denyUrlPatterns());
        boolean enabled = request.enabled() == null || request.enabled();
        double requestDelaySeconds = request.requestDelaySeconds() == null
                ? 0.75
                : Math.max(0.0, Math.min(15.0, request.requestDelaySeconds()));
        int maxDepth = request.maxDepth() == null ? 4 : Math.max(1, Math.min(20, request.maxDepth()));

        sourceCatalogService.upsertSourceAndPersistConfig(
                sourceId,
                productName,
                startUrls,
                allowPrefixes,
                denyPatterns,
                enabled,
                requestDelaySeconds,
                maxDepth
        );
        return repository.findSourceById(sourceId);
    }

    @Transactional
    public CorpusAdminDtos.AnchorEvalRunSummary createAnchorEvalRun(CorpusAdminDtos.AnchorEvalRunCreateRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("anchor eval run request is required");
        }
        int sampleSize = request.sampleSize() == null ? 20 : Math.max(1, Math.min(200, request.sampleSize()));
        int candidateLimit = request.candidateLimit() == null ? 10 : Math.max(1, Math.min(50, request.candidateLimit()));
        UUID runId = UUID.randomUUID();
        String runName = request.runName() == null || request.runName().isBlank() ? "anchor-eval-" + runId.toString().substring(0, 8) : request.runName().trim();
        String createdBy = request.createdBy() == null || request.createdBy().isBlank() ? "admin-ui" : request.createdBy().trim();
        repository.createAnchorEvalRun(
                runId,
                runName,
                request.productName(),
                request.sourceId(),
                sampleSize,
                candidateLimit,
                createdBy
        );
        List<String> documentIds = normalizeDistinctNonBlank(request.documentIds());
        List<String> chunkIds = normalizeDistinctNonBlank(request.chunkIds());
        List<CorpusAdminDtos.ChunkDetail> chunks = repository.findAnchorEvalTargetChunks(
                request.productName(),
                request.sourceId(),
                documentIds,
                chunkIds,
                sampleSize
        );
        for (CorpusAdminDtos.ChunkDetail chunk : chunks) {
            UUID sampleId = UUID.randomUUID();
            repository.insertAnchorEvalSample(sampleId, runId, chunk.documentId(), chunk.chunkId(), chunk.chunkText());
            List<CorpusAdminDtos.AnchorEvalCandidateDto> candidates = repository.findAnchorEvalChunkCandidates(chunk.chunkId(), candidateLimit);
            for (CorpusAdminDtos.AnchorEvalCandidateDto candidate : candidates) {
                repository.insertAnchorEvalCandidate(sampleId, candidate);
            }
        }
        repository.completeAnchorEvalRun(runId, repository.computeAnchorEvalSummary(runId));
        return repository.findAnchorEvalRun(runId);
    }

    public List<CorpusAdminDtos.AnchorEvalRunSummary> listAnchorEvalRuns(Integer limit, Integer offset) {
        return repository.findAnchorEvalRuns(limit, offset);
    }

    public CorpusAdminDtos.AnchorEvalRunDetail getAnchorEvalRun(UUID runId) {
        return new CorpusAdminDtos.AnchorEvalRunDetail(
                repository.findAnchorEvalRun(runId),
                repository.findAnchorEvalSamples(runId)
        );
    }

    @Transactional
    public CorpusAdminDtos.AnchorEvalRunSummary upsertAnchorEvalLabel(UUID runId, CorpusAdminDtos.AnchorEvalLabelRequest request) {
        if (request == null || request.candidateId() == null || request.labelValue() == null || request.labelValue().isBlank()) {
            throw new IllegalArgumentException("candidateId and labelValue are required");
        }
        String labeledBy = request.labeledBy() == null || request.labeledBy().isBlank() ? "admin-ui" : request.labeledBy().trim();
        repository.upsertAnchorEvalLabel(
                runId,
                request.candidateId(),
                request.labelValue().trim(),
                request.confidence(),
                request.note(),
                labeledBy
        );
        repository.completeAnchorEvalRun(runId, repository.computeAnchorEvalSummary(runId));
        return repository.findAnchorEvalRun(runId);
    }

    @Transactional
    public CorpusAdminDtos.AnchorEvalRunSummary recomputeAnchorEvalSummary(UUID runId) {
        repository.completeAnchorEvalRun(runId, repository.computeAnchorEvalSummary(runId));
        return repository.findAnchorEvalRun(runId);
    }

    @Transactional
    public CorpusAdminDtos.SourceSummary autoRegisterSource(CorpusAdminDtos.SourceAutoRegisterRequest request) {
        if (request == null || request.url() == null || request.url().isBlank()) {
            throw new IllegalArgumentException("url is required");
        }
        URI uri;
        try {
            uri = URI.create(request.url().trim());
        } catch (Exception exception) {
            throw new IllegalArgumentException("Invalid URL format");
        }
        if (uri.getScheme() == null || uri.getHost() == null) {
            throw new IllegalArgumentException("URL must include scheme and host");
        }

        String normalizedUrl = uri.normalize().toString();
        String sourceId = request.sourceId() == null || request.sourceId().isBlank()
                ? inferSourceId(uri)
                : request.sourceId().trim();
        String productName = request.productName() == null || request.productName().isBlank()
                ? inferProductName(uri)
                : request.productName().trim();
        String allowPrefix = inferAllowPrefix(uri);

        sourceCatalogService.upsertSourceAndPersistConfig(
                sourceId,
                productName,
                List.of(normalizedUrl),
                List.of(allowPrefix),
                defaultDenyPatterns(),
                request.enabled() == null || request.enabled(),
                request.requestDelaySeconds() == null ? 0.75 : Math.max(0.0, Math.min(15.0, request.requestDelaySeconds())),
                request.maxDepth() == null ? 4 : Math.max(1, Math.min(20, request.maxDepth()))
        );
        return repository.findSourceById(sourceId);
    }

    private String inferSourceId(URI uri) {
        String hostPart = uri.getHost().toLowerCase(Locale.ROOT).replace(".", "-");
        String path = uri.getPath() == null ? "" : uri.getPath().toLowerCase(Locale.ROOT);
        String[] segments = path.split("/");
        List<String> picks = new ArrayList<>();
        for (String segment : segments) {
            if (segment == null || segment.isBlank()) {
                continue;
            }
            if (segment.endsWith(".html")) {
                continue;
            }
            picks.add(segment.replaceAll("[^a-z0-9-]", "-"));
            if (picks.size() == 2) {
                break;
            }
        }
        String tail = picks.isEmpty() ? "docs" : String.join("-", picks);
        return (hostPart + "-" + tail).replaceAll("-{2,}", "-");
    }

    private String inferProductName(URI uri) {
        String path = uri.getPath() == null ? "" : uri.getPath();
        String[] segments = path.split("/");
        for (String segment : segments) {
            if (segment == null || segment.isBlank()) {
                continue;
            }
            if ("reference".equalsIgnoreCase(segment) || segment.endsWith(".html")) {
                continue;
            }
            return segment;
        }
        return uri.getHost();
    }

    private String inferAllowPrefix(URI uri) {
        String path = uri.getPath() == null ? "/" : uri.getPath();
        int referenceIndex = path.indexOf("/reference/");
        String normalizedPath;
        if (referenceIndex >= 0) {
            normalizedPath = path.substring(0, referenceIndex + "/reference/".length());
        } else {
            int lastSlash = path.lastIndexOf('/');
            normalizedPath = lastSlash <= 0 ? "/" : path.substring(0, lastSlash + 1);
        }
        return uri.getScheme() + "://" + uri.getHost() + normalizedPath;
    }

    private List<String> defaultDenyPatterns() {
        return List.of(
                ".*#.*",
                ".*/search.*",
                ".*/login.*",
                ".*\\?.*"
        );
    }

    private String requireTrimmed(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }

    private List<String> normalizeDistinctNonBlank(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> dedup = new LinkedHashSet<>();
        for (String value : values) {
            if (value == null) {
                continue;
            }
            String trimmed = value.trim();
            if (!trimmed.isBlank()) {
                dedup.add(trimmed);
            }
        }
        return List.copyOf(dedup);
    }
}
