package io.queryforge.backend.rag.embedding.reindex.controller;

import io.queryforge.backend.rag.embedding.reindex.dto.RagReindexDtos;
import io.queryforge.backend.rag.embedding.reindex.service.RagReindexService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class RagReindexController {

    private final RagReindexService reindexService;

    @PostMapping("/admin/reindex")
    public RagReindexDtos.ReindexResponse reindex(@RequestBody(required = false) RagReindexDtos.ReindexRequest request) {
        return reindexService.reindex(request);
    }
}
