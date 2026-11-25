package io.queryforge.backend.admin.console.embedding.controller;

import io.queryforge.backend.admin.console.embedding.dto.ChunkEmbeddingAdminDtos;
import io.queryforge.backend.admin.console.embedding.service.ChunkEmbeddingAdminService;
import io.queryforge.backend.admin.console.jobs.dto.LlmJobDtos;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/console")
@RequiredArgsConstructor
public class ChunkEmbeddingAdminController {

    private final ChunkEmbeddingAdminService chunkEmbeddingService;

    @GetMapping("/rag/chunk-embeddings/status")
    public ChunkEmbeddingAdminDtos.ChunkEmbeddingMaterializationStatusResponse chunkEmbeddingStatus(
            @RequestParam(name = "embedding_model", required = false) String embeddingModel
    ) {
        return chunkEmbeddingService.getChunkEmbeddingMaterializationStatus(embeddingModel);
    }

    @PostMapping("/rag/chunk-embeddings/materialize")
    public LlmJobDtos.LlmJobRow materializeChunkEmbeddings(
            @RequestBody ChunkEmbeddingAdminDtos.ChunkEmbeddingMaterializationRequest request
    ) {
        return chunkEmbeddingService.runChunkEmbeddingMaterialization(request);
    }
}
