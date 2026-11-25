package io.queryforge.backend.admin.corpus.anchor.query.controller;

import io.queryforge.backend.admin.corpus.anchor.query.dto.CorpusAnchorDtos;
import io.queryforge.backend.admin.corpus.anchor.query.service.CorpusAnchorService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/admin/corpus")
@RequiredArgsConstructor
public class CorpusAnchorController {

    private final CorpusAnchorService service;

    @GetMapping("/anchors")
    public List<CorpusAnchorDtos.AnchorSummary> listAnchors(
            @RequestParam(name = "document_id", required = false) String documentId,
            @RequestParam(name = "chunk_id", required = false) String chunkId,
            @RequestParam(name = "keyword", required = false) String keyword,
            @RequestParam(name = "active_only", defaultValue = "true") boolean activeOnly,
            @RequestParam(name = "limit", required = false) Integer limit,
            @RequestParam(name = "offset", required = false) Integer offset
    ) {
        return service.listAnchors(documentId, chunkId, keyword, activeOnly, limit, offset);
    }
}
