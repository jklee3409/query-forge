package io.queryforge.backend.admin.corpus.glossary.service;

import io.queryforge.backend.admin.corpus.glossary.dto.CorpusGlossaryDtos;
import io.queryforge.backend.admin.corpus.glossary.repository.CorpusGlossaryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CorpusGlossaryService {

    private final CorpusGlossaryRepository repository;

    public List<CorpusGlossaryDtos.GlossaryTermSummary> listGlossary(
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

    public CorpusGlossaryDtos.GlossaryTermDetail getGlossaryTerm(UUID termId) {
        return new CorpusGlossaryDtos.GlossaryTermDetail(
                repository.findGlossaryTerm(termId),
                repository.findGlossaryAliases(termId)
        );
    }

    public List<CorpusGlossaryDtos.GlossaryEvidenceDto> listGlossaryEvidence(UUID termId) {
        return repository.findGlossaryEvidence(termId);
    }

    public List<CorpusGlossaryDtos.TopTermPreview> previewTopTerms(
            Integer limit,
            String productName,
            String termType,
            Boolean keepInEnglish
    ) {
        return repository.findTopTermsPreview(limit, productName, termType, keepInEnglish);
    }

    @Transactional
    public List<CorpusGlossaryDtos.GlossaryAliasDto> updateGlossaryTerm(
            UUID termId,
            CorpusGlossaryDtos.GlossaryTermPatchRequest request
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
    public CorpusGlossaryDtos.GlossaryTermDetail createGlossaryAlias(
            UUID termId,
            CorpusGlossaryDtos.GlossaryAliasCreateRequest request
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
}
