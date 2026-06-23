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
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RagRetrievalExecutionServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final UUID domainId = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Mock
    private RagRepository repository;
    @Mock
    private HashEmbeddingService embeddingService;
    @Mock
    private DenseEmbeddingService denseEmbeddingService;
    @Mock
    private CohereRerankService cohereRerankService;

    private RagRetrievalExecutionService service;

    @BeforeEach
    void setUp() {
        DomainScopedRetrievalService domainScopedRetrievalService = new DomainScopedRetrievalService(
                repository,
                embeddingService,
                denseEmbeddingService
        );
        service = new RagRetrievalExecutionService(domainScopedRetrievalService, cohereRerankService, objectMapper);
    }

    @Test
    void rawOnlyExecutionRetrievesWithinDomainAndKeepsOriginalQuery() {
        String query = "FilterChainProxy order";
        DomainScopedRetrievalService.RetrievalRuntime runtime = localDenseRuntime(20);
        List<RagRepository.RetrievalDoc> localDocs = List.of(new RagRepository.RetrievalDoc(
                "doc-1",
                "chunk-1",
                "Spring Security FilterChainProxy documentation",
                0.8d
        ));
        List<RagRepository.RetrievalDoc> rerankedDocs = List.of(new RagRepository.RetrievalDoc(
                "doc-1",
                "chunk-1",
                "Spring Security FilterChainProxy documentation",
                0.9d
        ));
        when(repository.findTopChunksByEmbedding("embedding", 20, domainId)).thenReturn(localDocs);
        when(cohereRerankService.rerank(query, localDocs, 3)).thenReturn(rerankedDocs);

        RagRetrievalExecutionService.RawOnlyExecutionResult result = service.executeRawOnly(
                new RagRetrievalExecutionService.RawOnlyExecutionRequest(
                        query,
                        "embedding",
                        3,
                        3,
                        domainId,
                        runtime
                )
        );

        assertThat(result.originalQuery()).isEqualTo(query);
        assertThat(result.finalQuery()).isEqualTo(query);
        assertThat(result.localRetrievedDocs()).extracting(RagRepository.RetrievalDoc::chunkId)
                .containsExactly("chunk-1");
        assertThat(result.rerankedDocs()).isEqualTo(rerankedDocs);
        assertThat(result.rawRetrievalConfidence()).isGreaterThan(0.0d);
        assertThat(result.retrieverName()).isEqualTo("local:dense_only:hash-embedding-v1");
        assertThat(result.retrievalMetadata().path("retriever_mode").asText()).isEqualTo("dense_only");
        assertThat(result.latencyMs()).isGreaterThanOrEqualTo(0L);

        verify(repository).findTopChunksByEmbedding("embedding", 20, domainId);
        verify(repository, never()).findMemoryTopN(anyString(), anyInt(), anyString(), any(), anyList(), anyList(), anyList());
        verifyNoRepositoryWrites();
    }

    @Test
    void emptyRawOnlyExecutionReturnsEmptyDocsAndZeroConfidence() {
        String query = "FilterChainProxy order";
        DomainScopedRetrievalService.RetrievalRuntime runtime = localDenseRuntime(20);
        when(repository.findTopChunksByEmbedding("embedding", 20, domainId)).thenReturn(List.of());
        when(cohereRerankService.rerank(query, List.of(), 3)).thenReturn(List.of());

        RagRetrievalExecutionService.RawOnlyExecutionResult result = service.executeRawOnly(
                new RagRetrievalExecutionService.RawOnlyExecutionRequest(
                        query,
                        "embedding",
                        3,
                        3,
                        domainId,
                        runtime
                )
        );

        assertThat(result.originalQuery()).isEqualTo(query);
        assertThat(result.finalQuery()).isEqualTo(query);
        assertThat(result.localRetrievedDocs()).isEmpty();
        assertThat(result.rerankedDocs()).isEmpty();
        assertThat(result.rawRetrievalConfidence()).isZero();
        verify(repository).findTopChunksByEmbedding("embedding", 20, domainId);
        verifyNoRepositoryWrites();
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

    private void verifyNoRepositoryWrites() {
        verify(repository, never()).createOnlineQuery(any(), any(), anyString(), any(), anyString(), anyDouble(), any());
        verify(repository, never()).insertRetrievalResults(any(), any(), anyString(), anyList(), anyString(), anyString(), any());
        verify(repository, never()).insertRerankResults(any(), isNull(), anyList(), anyString());
        verify(repository, never()).insertAnswer(any(), anyString(), any(), any(), anyString(), any());
        verify(repository, never()).mergeOnlineQueryMetadata(any(), any());
    }
}
