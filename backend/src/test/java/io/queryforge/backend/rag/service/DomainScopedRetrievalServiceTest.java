package io.queryforge.backend.rag.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.queryforge.backend.rag.repository.RagRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DomainScopedRetrievalServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final UUID domainId = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private final UUID sourceGatingRunId = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private final UUID sourceGatingBatchId = UUID.fromString("33333333-3333-3333-3333-333333333333");

    @Mock
    private RagRepository repository;
    @Mock
    private HashEmbeddingService embeddingService;
    @Mock
    private DenseEmbeddingService denseEmbeddingService;

    private DomainScopedRetrievalService service;

    @BeforeEach
    void setUp() {
        service = new DomainScopedRetrievalService(repository, embeddingService, denseEmbeddingService);
    }

    @Test
    void localChunkRetrievalPassesDomainAndUsesCandidatePool() {
        DomainScopedRetrievalService.RetrievalRuntime runtime = localDenseRuntime(20);
        List<RagRepository.RetrievalDoc> docs = List.of(new RagRepository.RetrievalDoc(
                "doc-1",
                "chunk-1",
                "Spring Security FilterChainProxy documentation",
                0.8d
        ));
        when(repository.findTopChunksByEmbedding("embedding", 20, domainId)).thenReturn(docs);

        List<RagRepository.RetrievalDoc> result = service.retrieveChunks(
                "FilterChainProxy order",
                "embedding",
                3,
                domainId,
                runtime
        );

        assertThat(result).extracting(RagRepository.RetrievalDoc::chunkId).containsExactly("chunk-1");
        assertThat(result.getFirst().score()).isEqualTo(0.8d);
        verify(repository).findTopChunksByEmbedding("embedding", 20, domainId);
        verify(repository, never()).findChunkTextPool(anyList(), anyInt(), any());
    }

    @Test
    void localMemoryRetrievalPreservesDomainSnapshotAndStrategyFilters() {
        DomainScopedRetrievalService.RetrievalRuntime runtime = localDenseRuntime(50);
        List<RagRepository.MemoryCandidate> memories = List.of(memory());
        when(repository.findMemoryTopN(
                eq("embedding"),
                eq(5),
                eq("full_gating"),
                eq(domainId),
                eq(List.of("C")),
                eq(List.of(sourceGatingRunId)),
                eq(List.of(sourceGatingBatchId))
        )).thenReturn(memories);

        List<RagRepository.MemoryCandidate> result = service.findMemoryCandidates(
                "FilterChainProxy order",
                "embedding",
                5,
                "full_gating",
                domainId,
                List.of("C"),
                List.of(sourceGatingRunId),
                List.of(sourceGatingBatchId),
                runtime
        );

        assertThat(result).isEqualTo(memories);
        verify(repository).findMemoryTopN(
                "embedding",
                5,
                "full_gating",
                domainId,
                List.of("C"),
                List.of(sourceGatingRunId),
                List.of(sourceGatingBatchId)
        );
        verify(repository, never()).findMemoryDensePool(anyString(), anyString(), anyInt(), anyString(), any(), anyList(), anyList(), anyList());
        verify(repository, never()).findMemoryTextPool(anyString(), anyString(), anyList(), anyInt(), anyString(), any(), anyList(), anyList(), anyList());
    }

    @Test
    void emptyChunkRetrievalReturnsEmptyList() {
        DomainScopedRetrievalService.RetrievalRuntime runtime = localDenseRuntime(20);
        when(repository.findTopChunksByEmbedding("embedding", 20, domainId)).thenReturn(List.of());

        List<RagRepository.RetrievalDoc> result = service.retrieveChunks(
                "FilterChainProxy order",
                "embedding",
                3,
                domainId,
                runtime
        );

        assertThat(result).isEmpty();
        verify(repository).findTopChunksByEmbedding("embedding", 20, domainId);
    }

    private DomainScopedRetrievalService.RetrievalRuntime localDenseRuntime(int candidatePoolK) {
        return new DomainScopedRetrievalService.RetrievalRuntime(
                "local",
                "intfloat/multilingual-e5-small",
                "dense_only",
                candidatePoolK,
                1.0d,
                0.0d,
                0.0d
        );
    }

    private RagRepository.MemoryCandidate memory() {
        return new RagRepository.MemoryCandidate(
                UUID.fromString("44444444-4444-4444-4444-444444444444"),
                "FilterChainProxy SecurityFilterChain order",
                "doc-1",
                objectMapper.createArrayNode().add("chunk-1"),
                objectMapper.createArrayNode().add("FilterChainProxy"),
                objectMapper.createObjectNode(),
                0.72d,
                "C",
                UUID.fromString("55555555-5555-5555-5555-555555555555"),
                domainId,
                "gated-query-1",
                sourceGatingRunId.toString(),
                sourceGatingBatchId.toString()
        );
    }
}
