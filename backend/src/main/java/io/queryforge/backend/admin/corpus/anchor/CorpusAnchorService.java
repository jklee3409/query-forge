package io.queryforge.backend.admin.corpus.anchor;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CorpusAnchorService {

    private final CorpusAnchorRepository repository;

    private final AnchorExtractionService anchorExtractionService;

    public List<CorpusAnchorDtos.AnchorSummary> listAnchors(
            String documentId,
            String chunkId,
            String keyword,
            boolean activeOnly,
            Integer limit,
            Integer offset
    ) {
        return repository.findAnchors(documentId, chunkId, keyword, activeOnly, limit, offset);
    }

    @Transactional
    public CorpusAnchorDtos.AnchorExtractResponse extractAnchors(CorpusAnchorDtos.AnchorExtractRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("anchor extract request is required");
        }
        return anchorExtractionService.extractAnchors(request.documentIds(), request.chunkIds());
    }
}
