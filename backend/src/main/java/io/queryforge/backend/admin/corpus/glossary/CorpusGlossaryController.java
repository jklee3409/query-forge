package io.queryforge.backend.admin.corpus.glossary;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/admin/corpus")
@RequiredArgsConstructor
public class CorpusGlossaryController {

    private final CorpusGlossaryService service;

    @GetMapping("/glossary")
    public List<CorpusGlossaryDtos.GlossaryTermSummary> listGlossary(
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
    public List<CorpusGlossaryDtos.TopTermPreview> previewTopTerms(
            @RequestParam(name = "limit", required = false) Integer limit,
            @RequestParam(name = "product_name", required = false) String productName,
            @RequestParam(name = "term_type", required = false) String termType,
            @RequestParam(name = "keep_in_english", required = false) Boolean keepInEnglish
    ) {
        return service.previewTopTerms(limit, productName, termType, keepInEnglish);
    }

    @GetMapping("/glossary/{termId}")
    public CorpusGlossaryDtos.GlossaryTermDetail getGlossaryTerm(@PathVariable UUID termId) {
        return service.getGlossaryTerm(termId);
    }

    @GetMapping("/glossary/{termId}/evidence")
    public List<CorpusGlossaryDtos.GlossaryEvidenceDto> getGlossaryEvidence(@PathVariable UUID termId) {
        return service.listGlossaryEvidence(termId);
    }

    @PatchMapping("/glossary/{termId}")
    public CorpusGlossaryDtos.GlossaryTermDetail patchGlossaryTerm(
            @PathVariable UUID termId,
            @RequestBody CorpusGlossaryDtos.GlossaryTermPatchRequest request
    ) {
        service.updateGlossaryTerm(termId, request);
        return service.getGlossaryTerm(termId);
    }

    @PostMapping("/glossary/{termId}/aliases")
    public CorpusGlossaryDtos.GlossaryTermDetail createGlossaryAlias(
            @PathVariable UUID termId,
            @RequestBody CorpusGlossaryDtos.GlossaryAliasCreateRequest request
    ) {
        return service.createGlossaryAlias(termId, request);
    }

    @DeleteMapping("/glossary/aliases/{aliasId}")
    public void deleteGlossaryAlias(@PathVariable UUID aliasId) {
        service.deleteGlossaryAlias(aliasId);
    }
}
