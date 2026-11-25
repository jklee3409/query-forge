package io.queryforge.backend.admin.corpus.anchor.query.service;

import io.queryforge.backend.admin.corpus.anchor.query.dto.CorpusAnchorDtos;
import io.queryforge.backend.admin.corpus.anchor.query.repository.CorpusAnchorRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CorpusAnchorService {

    private final CorpusAnchorRepository repository;

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

}
