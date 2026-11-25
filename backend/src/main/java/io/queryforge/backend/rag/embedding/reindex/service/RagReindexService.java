package io.queryforge.backend.rag.embedding.reindex.service;

import io.queryforge.backend.rag.embedding.reindex.dto.RagReindexDtos;
import io.queryforge.backend.rag.embedding.reindex.repository.RagEmbeddingRepository;
import io.queryforge.backend.rag.embedding.service.HashEmbeddingService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RagReindexService {

    private final RagEmbeddingRepository embeddingRepository;
    private final HashEmbeddingService embeddingService;

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public RagReindexDtos.ReindexResponse reindex(RagReindexDtos.ReindexRequest request) {
        boolean reindexChunks = request == null || request.reindexChunks() == null || request.reindexChunks();
        boolean reindexMemory = request == null || request.reindexMemory() == null || request.reindexMemory();
        int chunkUpdates = 0;
        int memoryUpdates = 0;
        String model = "hash-embedding-v1";

        if (reindexChunks) {
            for (RagReindexDtos.ChunkSource chunk : embeddingRepository.findAllChunksForEmbedding()) {
                String literal = embeddingService.toHalfvecLiteral(embeddingService.embed(chunk.chunkText()));
                embeddingRepository.upsertChunkEmbedding(chunk.chunkId(), literal, model);
                chunkUpdates++;
            }
        }
        if (reindexMemory) {
            for (RagReindexDtos.MemorySource memory : embeddingRepository.findAllMemorySources()) {
                String literal = embeddingService.toHalfvecLiteral(embeddingService.embed(memory.queryText()));
                embeddingRepository.updateMemoryEmbedding(memory.memoryId(), literal, model);
                memoryUpdates++;
            }
        }
        return new RagReindexDtos.ReindexResponse(chunkUpdates, memoryUpdates, model);
    }
}
