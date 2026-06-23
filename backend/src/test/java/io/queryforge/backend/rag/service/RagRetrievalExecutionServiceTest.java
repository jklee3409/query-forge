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
import static org.mockito.ArgumentMatchers.anyBoolean;
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
    @Mock
    private RewriteCandidateService rewriteCandidateService;

    private RagRetrievalExecutionService service;

    @BeforeEach
    void setUp() {
        DomainScopedRetrievalService domainScopedRetrievalService = new DomainScopedRetrievalService(
                repository,
                embeddingService,
                denseEmbeddingService
        );
        service = new RagRetrievalExecutionService(
                domainScopedRetrievalService,
                cohereRerankService,
                rewriteCandidateService,
                objectMapper
        );
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

    @Test
    void selectiveRewriteExecutionBuildsCandidatesAndRetrievesWithinDomain() {
        String query = "spring filter order";
        String rewrittenQuery = "FilterChainProxy SecurityFilterChain order";
        DomainScopedRetrievalService.RetrievalRuntime runtime = localDenseRuntime(20);
        List<RagRepository.MemoryCandidate> memories = List.of(memory());
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
                0.92d
        ));
        when(rewriteCandidateService.buildCandidates(
                eq(query),
                any(),
                eq(memories),
                eq(2),
                eq("compact_anchor"),
                eq(false),
                any()
        )).thenReturn(List.of(new RewriteCandidateService.CandidateTemplate("candidate-1", rewrittenQuery)));
        when(embeddingService.embed(rewrittenQuery)).thenReturn(List.of(1.0d, 0.0d));
        when(embeddingService.toHalfvecLiteral(List.of(1.0d, 0.0d))).thenReturn("embedding");
        when(repository.findTopChunksByEmbedding("embedding", 20, domainId)).thenReturn(localDocs);
        when(cohereRerankService.rerank(rewrittenQuery, localDocs, 3)).thenReturn(rerankedDocs);

        RagRetrievalExecutionService.SelectiveRewriteExecutionResult result = service.executeSelectiveRewrite(
                new RagRetrievalExecutionService.SelectiveRewriteExecutionRequest(
                        query,
                        objectMapper.createObjectNode(),
                        memories,
                        2,
                        "compact_anchor",
                        objectMapper.createObjectNode(),
                        3,
                        3,
                        domainId,
                        runtime,
                        0.72d
                )
        );

        assertThat(result.candidates()).hasSize(1);
        RagRetrievalExecutionService.ExecutedRewriteCandidate candidate = result.candidates().getFirst();
        assertThat(candidate.label()).isEqualTo("candidate-1");
        assertThat(candidate.query()).isEqualTo(rewrittenQuery);
        assertThat(candidate.localRetrievedDocs()).isEqualTo(localDocs);
        assertThat(candidate.rerankedDocs()).isEqualTo(rerankedDocs);
        assertThat(candidate.confidence()).isGreaterThan(0.0d);
        assertThat(result.latencyMs()).isGreaterThanOrEqualTo(0L);

        verify(rewriteCandidateService).buildCandidates(
                eq(query),
                any(),
                eq(memories),
                eq(2),
                eq("compact_anchor"),
                eq(false),
                any()
        );
        verify(repository).findTopChunksByEmbedding("embedding", 20, domainId);
        verifyNoRepositoryWrites();
    }

    @Test
    void anchorAwareRewriteExecutionBuildsCandidatesWithAnchorInjectionAndRetrievesWithinDomain() {
        String query = "FilterChainProxy SecurityFilterChain order";
        String rewrittenQuery = "FilterChainProxy SecurityFilterChain order in Spring Security";
        DomainScopedRetrievalService.RetrievalRuntime runtime = localDenseRuntime(20);
        List<RagRepository.MemoryCandidate> memories = List.of(memory());
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
                0.94d
        ));
        when(rewriteCandidateService.buildCandidates(
                eq(query),
                any(),
                eq(memories),
                eq(2),
                eq("compact_anchor"),
                eq(true),
                any()
        )).thenReturn(List.of(new RewriteCandidateService.CandidateTemplate("anchor-aware", rewrittenQuery)));
        when(embeddingService.embed(rewrittenQuery)).thenReturn(List.of(1.0d, 0.0d));
        when(embeddingService.toHalfvecLiteral(List.of(1.0d, 0.0d))).thenReturn("embedding");
        when(repository.findTopChunksByEmbedding("embedding", 20, domainId)).thenReturn(localDocs);
        when(cohereRerankService.rerank(rewrittenQuery, localDocs, 3)).thenReturn(rerankedDocs);

        RagRetrievalExecutionService.AnchorAwareRewriteExecutionResult result = service.executeAnchorAwareRewrite(
                new RagRetrievalExecutionService.AnchorAwareRewriteExecutionRequest(
                        query,
                        objectMapper.createObjectNode(),
                        memories,
                        2,
                        "compact_anchor",
                        objectMapper.createObjectNode(),
                        3,
                        3,
                        domainId,
                        runtime,
                        0.72d
                )
        );

        assertThat(result.anchorInjectionApplied()).isTrue();
        assertThat(result.candidates()).hasSize(1);
        RagRetrievalExecutionService.ExecutedRewriteCandidate candidate = result.candidates().getFirst();
        assertThat(candidate.label()).isEqualTo("anchor-aware");
        assertThat(candidate.query()).isEqualTo(rewrittenQuery);
        assertThat(candidate.localRetrievedDocs()).isEqualTo(localDocs);
        assertThat(candidate.rerankedDocs()).isEqualTo(rerankedDocs);
        assertThat(candidate.confidence()).isGreaterThan(0.0d);
        assertThat(result.latencyMs()).isGreaterThanOrEqualTo(0L);

        verify(rewriteCandidateService).buildCandidates(
                eq(query),
                any(),
                eq(memories),
                eq(2),
                eq("compact_anchor"),
                eq(true),
                any()
        );
        verify(repository).findTopChunksByEmbedding("embedding", 20, domainId);
        verifyNoRepositoryWrites();
    }

    @Test
    void anchorAwareRewriteExecutionReturnsEmptyCandidatesWithoutRetrieval() {
        String query = "FilterChainProxy SecurityFilterChain order";
        DomainScopedRetrievalService.RetrievalRuntime runtime = localDenseRuntime(20);
        List<RagRepository.MemoryCandidate> memories = List.of(memory());
        when(rewriteCandidateService.buildCandidates(
                eq(query),
                any(),
                eq(memories),
                eq(2),
                eq("compact_anchor"),
                eq(true),
                any()
        )).thenReturn(List.of());

        RagRetrievalExecutionService.AnchorAwareRewriteExecutionResult result = service.executeAnchorAwareRewrite(
                new RagRetrievalExecutionService.AnchorAwareRewriteExecutionRequest(
                        query,
                        objectMapper.createObjectNode(),
                        memories,
                        2,
                        "compact_anchor",
                        objectMapper.createObjectNode(),
                        3,
                        3,
                        domainId,
                        runtime,
                        0.72d
                )
        );

        assertThat(result.anchorInjectionApplied()).isTrue();
        assertThat(result.candidates()).isEmpty();
        verify(repository, never()).findTopChunksByEmbedding(anyString(), anyInt(), any());
        verifyNoRepositoryWrites();
    }

    @Test
    void selectiveRewriteExecutionReturnsEmptyCandidatesWithoutRetrieval() {
        String query = "spring filter order";
        DomainScopedRetrievalService.RetrievalRuntime runtime = localDenseRuntime(20);
        List<RagRepository.MemoryCandidate> memories = List.of(memory());
        when(rewriteCandidateService.buildCandidates(
                eq(query),
                any(),
                eq(memories),
                eq(2),
                eq("compact_anchor"),
                eq(false),
                any()
        )).thenReturn(List.of());

        RagRetrievalExecutionService.SelectiveRewriteExecutionResult result = service.executeSelectiveRewrite(
                new RagRetrievalExecutionService.SelectiveRewriteExecutionRequest(
                        query,
                        objectMapper.createObjectNode(),
                        memories,
                        2,
                        "compact_anchor",
                        objectMapper.createObjectNode(),
                        3,
                        3,
                        domainId,
                        runtime,
                        0.72d
                )
        );

        assertThat(result.candidates()).isEmpty();
        verify(repository, never()).findTopChunksByEmbedding(anyString(), anyInt(), any());
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

    private RagRepository.MemoryCandidate memory() {
        return new RagRepository.MemoryCandidate(
                UUID.fromString("22222222-2222-2222-2222-222222222222"),
                "FilterChainProxy SecurityFilterChain order",
                "doc-1",
                objectMapper.createArrayNode().add("chunk-1"),
                objectMapper.createArrayNode().add("FilterChainProxy"),
                objectMapper.createObjectNode(),
                0.72d,
                "C",
                UUID.fromString("33333333-3333-3333-3333-333333333333"),
                domainId,
                "gated-query-1",
                "44444444-4444-4444-4444-444444444444",
                "55555555-5555-5555-5555-555555555555"
        );
    }

    private void verifyNoRepositoryWrites() {
        verify(repository, never()).createOnlineQuery(any(), any(), anyString(), any(), anyString(), anyDouble(), any());
        verify(repository, never()).createRewriteCandidate(any(), anyInt(), anyString(), anyString(), any(), any(), anyDouble(), any());
        verify(repository, never()).markRewriteCandidateAdopted(any(), anyBoolean(), any());
        verify(repository, never()).insertRetrievalResults(any(), any(), anyString(), anyList(), anyString(), anyString(), any());
        verify(repository, never()).insertRerankResults(any(), any(), anyList(), anyString());
        verify(repository, never()).insertAnswer(any(), anyString(), any(), any(), anyString(), any());
        verify(repository, never()).upsertOnlineQueryDecision(any(), anyString(), anyBoolean(), any(), any(), any(), anyString(), any(), any());
        verify(repository, never()).mergeOnlineQueryMetadata(any(), any());
        verify(repository, never()).createOnlineRewriteLog(any(), any(), anyString(), anyString(), anyString(), any(), any(), anyBoolean(), anyString(), anyBoolean(), any(), any(), any(), any(), any(), any(), any(), any());
        verify(repository, never()).insertMemoryRetrievalLog(any(), any(), anyInt(), any(), any());
        verify(repository, never()).insertRewriteCandidateLog(any(), any(), any(), anyInt(), anyString(), anyString(), any(), anyBoolean(), any(), any(), any(), any());
    }
}
