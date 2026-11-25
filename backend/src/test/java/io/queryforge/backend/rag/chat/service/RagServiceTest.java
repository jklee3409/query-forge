package io.queryforge.backend.rag.chat.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.queryforge.backend.rag.chat.answer.dto.ChatAnswerDtos;
import io.queryforge.backend.rag.chat.answer.service.ChatAnswerService;
import io.queryforge.backend.rag.chat.dto.ChatDtos;
import io.queryforge.backend.rag.embedding.service.DenseEmbeddingService;
import io.queryforge.backend.rag.embedding.service.HashEmbeddingService;
import io.queryforge.backend.rag.retrieval.agentic.dto.AgenticExecutionDtos;
import io.queryforge.backend.rag.retrieval.agentic.dto.AgenticRetrievalDtos;
import io.queryforge.backend.rag.retrieval.agentic.planning.dto.AgenticPlanningDtos;
import io.queryforge.backend.rag.retrieval.agentic.service.AgenticRetrievalService;
import io.queryforge.backend.rag.retrieval.candidate.dto.RetrievalDtos;
import io.queryforge.backend.rag.retrieval.candidate.model.RetrievalDoc;
import io.queryforge.backend.rag.retrieval.candidate.repository.ChunkRetrievalRepository;
import io.queryforge.backend.rag.retrieval.candidate.service.DomainScopedRetrievalService;
import io.queryforge.backend.rag.retrieval.execution.dto.RetrievalExecutionDtos;
import io.queryforge.backend.rag.retrieval.execution.model.RagPersistPolicy;
import io.queryforge.backend.rag.retrieval.execution.service.RagRetrievalExecutionService;
import io.queryforge.backend.rag.retrieval.memory.model.MemoryCandidate;
import io.queryforge.backend.rag.retrieval.memory.repository.RagMemoryRepository;
import io.queryforge.backend.rag.retrieval.rerank.service.CohereRerankService;
import io.queryforge.backend.rag.retrieval.rewrite.dto.RewriteDtos;
import io.queryforge.backend.rag.retrieval.rewrite.service.RewriteCandidateService;
import io.queryforge.backend.rag.retrieval.routing.service.QueryStrategyRouter;
import io.queryforge.backend.rag.runtime.configuration.dto.ChatConfigDtos;
import io.queryforge.backend.rag.runtime.configuration.service.ChatRuntimeConfigService;
import io.queryforge.backend.rag.runtime.readiness.dto.ChatReadinessDtos;
import io.queryforge.backend.rag.runtime.readiness.service.ChatReadinessService;
import io.queryforge.backend.rag.trace.write.agentic.dto.AgenticTraceWriteDtos;
import io.queryforge.backend.rag.trace.write.agentic.service.AgenticTracePersistenceService;
import io.queryforge.backend.rag.trace.write.dto.RagTraceWriteDtos;
import io.queryforge.backend.rag.trace.write.repository.RagTraceRepository;
import io.queryforge.backend.rag.trace.write.service.RagTracePersistenceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RagServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final UUID domainId = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private final UUID sourceGatingBatchId = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private final UUID sourceGatingRunId = UUID.fromString("33333333-3333-3333-3333-333333333333");

    @Mock
    private ChunkRetrievalRepository chunkRepository;
    @Mock
    private RagMemoryRepository memoryRepository;
    @Mock
    private RagTraceRepository traceRepository;
    @Mock
    private HashEmbeddingService embeddingService;
    @Mock
    private DenseEmbeddingService denseEmbeddingService;
    @Mock
    private CohereRerankService cohereRerankService;
    @Mock
    private RewriteCandidateService rewriteCandidateService;
    @Mock
    private ChatAnswerService chatAnswerService;
    @Mock
    private ChatRuntimeConfigService chatRuntimeConfigService;
    @Mock
    private ChatReadinessService chatReadinessService;
    @Mock
    private AgenticRetrievalService agenticRetrievalService;

    private RagService ragService;
    private RagRetrievalExecutionService ragRetrievalExecutionService;
    private RagTracePersistenceService ragTracePersistenceService;
    private AgenticTracePersistenceService agenticTracePersistenceService;

    @BeforeEach
    void setUp() {
        DomainScopedRetrievalService domainScopedRetrievalService = new DomainScopedRetrievalService(
                chunkRepository,
                memoryRepository,
                embeddingService,
                denseEmbeddingService
        );
        ragRetrievalExecutionService = spy(new RagRetrievalExecutionService(
                domainScopedRetrievalService,
                cohereRerankService,
                rewriteCandidateService,
                objectMapper
        ));
        ragTracePersistenceService = spy(new RagTracePersistenceService(traceRepository, objectMapper));
        agenticTracePersistenceService = spy(new AgenticTracePersistenceService(traceRepository));
        ChatResponseAssembler responses = new ChatResponseAssembler(chatAnswerService, objectMapper);
        ragService = new RagService(
                responses,
                new AgenticChatService(responses, traceRepository, agenticTracePersistenceService, agenticRetrievalService, objectMapper),
                traceRepository,
                domainScopedRetrievalService,
                ragRetrievalExecutionService,
                ragTracePersistenceService,
                cohereRerankService,
                chatRuntimeConfigService,
                chatReadinessService,
                new QueryStrategyRouter(),
                objectMapper
        );
    }

    @Test
    void routerReadinessFallbackUsesRawOnlyAndSkipsRewriteWork() {
        UUID onlineQueryId = UUID.randomUUID();
        ChatConfigDtos.ChatRuntimeConfigResponse config = config("selective_rewrite", true, false);
        when(chatRuntimeConfigService.getConfig(domainId)).thenReturn(config);
        when(chatReadinessService.getReadiness(domainId)).thenReturn(readiness(false, "selected snapshot has no built synthetic memory"));
        when(traceRepository.createOnlineQuery(eq(domainId), any(), anyString(), any(), anyString(), anyDouble(), any()))
                .thenReturn(onlineQueryId);
        when(embeddingService.embed(anyString())).thenReturn(List.of(1.0d, 0.0d));
        when(embeddingService.toHalfvecLiteral(anyList())).thenReturn("[1.000000,0.000000]");
        List<RetrievalDoc> docs = List.of(new RetrievalDoc(
                "doc-1",
                "chunk-1",
                "Spring Security filter chain reference",
                0.8d
        ));
        when(chunkRepository.findTopChunksByEmbedding(anyString(), anyInt(), eq(domainId))).thenReturn(docs);
        when(cohereRerankService.rerank(anyString(), anyList(), anyInt())).thenAnswer(invocation -> invocation.getArgument(1));
        when(cohereRerankService.modelName()).thenReturn("local-rerank-fallback");
        when(chatAnswerService.generateAnswer(anyString(), anyString(), anyString(), anyList()))
                .thenReturn(new ChatAnswerDtos.GeneratedAnswer(
                        "answer",
                        List.of("doc-1"),
                        List.of("chunk-1"),
                        "test-answer-model"
                ));

        ChatDtos.AskResponse response = ragService.ask(new ChatDtos.AskRequest(
                "스프링 시큐리티 필터 순서",
                domainId,
                "session-1",
                objectMapper.createObjectNode(),
                null
        ));

        assertThat(response.rewriteApplied()).isFalse();
        assertThat(response.finalQueryUsed()).isEqualTo("스프링 시큐리티 필터 순서");
        assertThat(response.rewriteCandidates()).isEmpty();
        assertThat(response.memoryTopN().size()).isZero();
        verify(memoryRepository, never()).findMemoryTopN(anyString(), anyInt(), anyString(), eq(domainId), anyList(), anyList(), anyList());
        verify(rewriteCandidateService, never()).buildCandidates(anyString(), any(), anyList(), anyInt(), anyString(), anyBoolean(), any());
        verify(traceRepository, never()).createRewriteCandidate(any(), anyInt(), anyString(), anyString(), any(), any(), anyDouble(), any());
        verify(ragTracePersistenceService, never()).createRewriteCandidateTrace(any());
        verify(ragTracePersistenceService, never()).markRewriteCandidateAdopted(any());
        verify(agenticRetrievalService, never()).execute(any());
        verify(ragTracePersistenceService, never()).persistRawOnlyTrace(any());

        JsonNode router = verifyOnlineQueryMetadataMerge(
                onlineQueryId,
                RetrievalExecutionDtos.NonAgenticExecutionKind.RAW_ONLY
        ).path("router");
        assertThat(router.path("enabled").asBoolean()).isTrue();
        assertThat(router.path("strategy").asText()).isEqualTo("RAW_ONLY");
        assertThat(router.path("fallbackApplied").asBoolean()).isTrue();
        assertThat(router.path("fallbackReason").asText()).contains("synthetic memory");
    }

    @Test
    void routerDisabledKeepsExistingStrictReadinessFailure() {
        ChatConfigDtos.ChatRuntimeConfigResponse config = config("selective_rewrite", false, false);
        when(chatRuntimeConfigService.getConfig(domainId)).thenReturn(config);
        when(chatReadinessService.getReadiness(domainId)).thenReturn(readiness(false, "selected snapshot has no built synthetic memory"));

        assertThatThrownBy(() -> ragService.ask(new ChatDtos.AskRequest(
                "스프링 시큐리티 필터 순서",
                domainId,
                "session-1",
                objectMapper.createObjectNode(),
                null
        ))).hasMessageContaining("synthetic memory");

        verify(traceRepository, never()).createOnlineQuery(any(), any(), anyString(), any(), anyString(), anyDouble(), any());
        verify(rewriteCandidateService, never()).buildCandidates(anyString(), any(), anyList(), anyInt(), anyString(), anyBoolean(), any());
    }

    @Test
    void rawOnlyAskKeepsOriginalQueryAndPersistsAnswerTrace() {
        UUID onlineQueryId = UUID.fromString("44444444-4444-4444-4444-444444444444");
        String query = "FilterChainProxy order";
        ChatConfigDtos.ChatRuntimeConfigResponse config = config("raw_only", true, false);
        List<RetrievalDoc> rawDocs = docs("raw-doc", "raw-chunk", 0.82d);
        stubCommonAskDependencies(config, onlineQueryId);
        when(chunkRepository.findTopChunksByEmbedding(anyString(), anyInt(), eq(domainId))).thenReturn(rawDocs);
        when(cohereRerankService.rerank(anyString(), anyList(), anyInt())).thenAnswer(invocation -> invocation.getArgument(1));
        when(cohereRerankService.modelName()).thenReturn("local-rerank-fallback");
        stubRewriteLog(UUID.fromString("55555555-5555-5555-5555-555555555555"));
        when(chatAnswerService.generateAnswer(anyString(), anyString(), anyString(), anyList()))
                .thenReturn(generatedAnswer("raw answer"));

        ChatDtos.AskResponse response = ragService.ask(request(query));

        assertThat(response.finalQueryUsed()).isEqualTo(query);
        assertThat(response.rawQuery()).isEqualTo(query);
        assertThat(response.rewriteApplied()).isFalse();
        assertThat(response.answer()).isEqualTo("raw answer");
        assertThat(response.answerModel()).isEqualTo("test-answer-model");

        verify(traceRepository).createOnlineQuery(eq(domainId), eq("session-1"), eq(query), any(), eq("raw_only"), eq(0.05d), any());
        verify(memoryRepository, never()).findMemoryTopN(anyString(), anyInt(), anyString(), any(), anyList(), anyList(), anyList());
        verify(rewriteCandidateService, never()).buildCandidates(anyString(), any(), anyList(), anyInt(), anyString(), anyBoolean(), any());
        verify(agenticRetrievalService, never()).execute(any());
        verify(chunkRepository).findTopChunksByEmbedding(anyString(), anyInt(), eq(domainId));
        verify(chatAnswerService).generateAnswer(eq(query), eq(query), eq("Spring"), anyList());
        ArgumentCaptor<RagTraceWriteDtos.RawOnlyTracePersistenceRequest> traceCaptor =
                ArgumentCaptor.forClass(RagTraceWriteDtos.RawOnlyTracePersistenceRequest.class);
        verify(ragTracePersistenceService, times(2)).persistRawOnlyTrace(traceCaptor.capture());
        assertThat(traceCaptor.getAllValues())
                .extracting(RagTraceWriteDtos.RawOnlyTracePersistenceRequest::writeScope)
                .containsExactly(
                        RagTraceWriteDtos.RawOnlyTraceWriteScope.RETRIEVAL,
                        RagTraceWriteDtos.RawOnlyTraceWriteScope.RERANK
                );
        assertThat(traceCaptor.getAllValues())
                .allSatisfy(trace -> {
                    assertThat(trace.onlineQueryId()).isEqualTo(onlineQueryId);
                    assertThat(trace.mode()).isEqualTo("raw_only");
                    assertThat(trace.rawQuery()).isEqualTo(query);
                    assertThat(trace.finalQuery()).isEqualTo(query);
                });
        verify(traceRepository).insertRetrievalResults(eq(onlineQueryId), isNull(), eq("raw"), anyList(), eq("raw_only"), eq("local:dense_only:hash-embedding-v1"), any());
        verify(traceRepository).insertRerankResults(eq(onlineQueryId), isNull(), anyList(), eq("local-rerank-fallback"));
        verify(ragTracePersistenceService, never()).persistRewriteCandidateTrace(any());
        verify(ragTracePersistenceService, never()).createRewriteCandidateTrace(any());
        verify(ragTracePersistenceService, never()).markRewriteCandidateAdopted(any());
        verify(ragTracePersistenceService).persistOnlineRewriteLogs(any(), eq(List.of()), eq(List.of()), eq(Map.of()), isNull());
        verify(traceRepository).insertAnswer(eq(onlineQueryId), eq("raw answer"), any(), any(), eq("test-answer-model"), any());
        verify(traceRepository).upsertOnlineQueryDecision(eq(onlineQueryId), eq(query), eq(false), any(), anyDouble(), isNull(), eq("mode_raw_only"), eq("query_router_strategy=raw_only"), any());
        verify(traceRepository).mergeOnlineQueryMetadata(eq(onlineQueryId), any());
        verifyOnlineQueryDecisionPersistence(
                onlineQueryId,
                RetrievalExecutionDtos.NonAgenticExecutionKind.RAW_ONLY,
                query,
                query,
                false,
                null,
                "mode_raw_only",
                "query_router_strategy=raw_only"
        );
        verifyOnlineQueryMetadataMerge(
                onlineQueryId,
                RetrievalExecutionDtos.NonAgenticExecutionKind.RAW_ONLY
        );
        verify(traceRepository).createOnlineRewriteLog(eq(onlineQueryId), isNull(), eq(query), eq(query), eq("raw_only"), any(), any(), eq(false), eq("full_gating"), eq(false), eq(false), eq(false), anyDouble(), anyDouble(), anyDouble(), eq("mode_raw_only"), eq("query_router_strategy=raw_only"), any());
        verify(traceRepository, never()).insertMemoryRetrievalLog(any(), any(), anyInt(), any(), any());
        verify(traceRepository, never()).insertRewriteCandidateLog(any(), any(), any(), anyInt(), anyString(), anyString(), any(), anyBoolean(), any(), any(), any(), any());
    }

    @Test
    void answerFailureKeepsEarlierRetrievalTraceAndSkipsCompletionLogs() {
        UUID onlineQueryId = UUID.randomUUID();
        String query = "spring filter order";
        List<RetrievalDoc> rawDocs = docs("raw-doc", "raw-chunk", 0.8d);
        stubCommonAskDependencies(config("raw_only", true, false), onlineQueryId);
        when(chunkRepository.findTopChunksByEmbedding(anyString(), anyInt(), eq(domainId))).thenReturn(rawDocs);
        when(cohereRerankService.rerank(eq(query), anyList(), eq(3))).thenAnswer(call -> call.getArgument(1));
        when(cohereRerankService.modelName()).thenReturn("local-rerank-fallback");
        when(chatAnswerService.generateAnswer(eq(query), eq(query), eq("Spring"), anyList()))
                .thenThrow(new IllegalStateException("answer failed"));

        assertThatThrownBy(() -> ragService.ask(request(query)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("answer failed");

        InOrder writes = inOrder(traceRepository, chatAnswerService);
        writes.verify(traceRepository).insertRetrievalResults(eq(onlineQueryId), isNull(), eq("raw"), anyList(),
                eq("raw_only"), anyString(), any());
        writes.verify(traceRepository).insertRerankResults(eq(onlineQueryId), isNull(), anyList(), eq("local-rerank-fallback"));
        writes.verify(chatAnswerService).generateAnswer(eq(query), eq(query), eq("Spring"), anyList());
        verify(traceRepository, never()).insertAnswer(any(), anyString(), any(), any(), any(), any());
        verify(ragTracePersistenceService, never()).persistOnlineRewriteLogs(any(), anyList(), anyList(), any(), any());
    }

    @Test
    void selectiveRewriteAskAdoptsCandidateAndPersistsRewriteTrace() {
        UUID onlineQueryId = UUID.fromString("44444444-4444-4444-4444-444444444445");
        UUID rewriteCandidateId = UUID.fromString("66666666-6666-6666-6666-666666666666");
        UUID rewriteLogId = UUID.fromString("77777777-7777-7777-7777-777777777777");
        String query = "spring filter order";
        String rewrittenQuery = "FilterChainProxy SecurityFilterChain order";
        ChatConfigDtos.ChatRuntimeConfigResponse config = config("selective_rewrite", false, false);
        List<MemoryCandidate> memories = memories();
        List<RetrievalDoc> rawDocs = docs("raw-doc", "raw-chunk", 0.10d);
        List<RetrievalDoc> rewriteDocs = docs("rewrite-doc", "rewrite-chunk", 0.95d);
        stubCommonAskDependencies(config, onlineQueryId);
        when(memoryRepository.findMemoryTopN(anyString(), anyInt(), eq("full_gating"), eq(domainId), eq(List.of("C")), eq(List.of(sourceGatingRunId)), eq(List.of(sourceGatingBatchId))))
                .thenReturn(memories);
        when(chunkRepository.findTopChunksByEmbedding(anyString(), anyInt(), eq(domainId))).thenReturn(rawDocs, rewriteDocs);
        when(cohereRerankService.rerank(anyString(), anyList(), anyInt())).thenAnswer(invocation -> invocation.getArgument(1));
        when(cohereRerankService.modelName()).thenReturn("local-rerank-fallback");
        when(rewriteCandidateService.buildCandidates(eq(query), any(), eq(memories), eq(2), eq("compact_anchor"), eq(false), any()))
                .thenReturn(List.of(new RewriteDtos.CandidateTemplate("candidate-1", rewrittenQuery)));
        when(traceRepository.createRewriteCandidate(eq(onlineQueryId), eq(1), eq("candidate-1"), eq(rewrittenQuery), any(), any(), anyDouble(), any()))
                .thenReturn(rewriteCandidateId);
        stubRewriteLog(rewriteLogId);
        when(chatAnswerService.generateAnswer(anyString(), anyString(), anyString(), anyList()))
                .thenReturn(generatedAnswer("rewrite answer"));

        ChatDtos.AskResponse response = ragService.ask(request(query));

        assertThat(response.finalQueryUsed()).isEqualTo(rewrittenQuery);
        assertThat(response.rewriteApplied()).isTrue();
        assertThat(response.answer()).isEqualTo("rewrite answer");
        assertThat(response.answerModel()).isEqualTo("test-answer-model");
        assertThat(response.rewriteCandidates()).hasSize(1);
        assertThat(response.rewriteCandidates().getFirst().adopted()).isTrue();

        verify(memoryRepository).findMemoryTopN(anyString(), anyInt(), eq("full_gating"), eq(domainId), eq(List.of("C")), eq(List.of(sourceGatingRunId)), eq(List.of(sourceGatingBatchId)));
        verify(chunkRepository, times(2)).findTopChunksByEmbedding(anyString(), anyInt(), eq(domainId));
        ArgumentCaptor<RetrievalExecutionDtos.RewriteExecutionRequest> executionRequestCaptor =
                ArgumentCaptor.forClass(RetrievalExecutionDtos.RewriteExecutionRequest.class);
        verify(ragRetrievalExecutionService).executeRewrite(executionRequestCaptor.capture());
        assertThat(executionRequestCaptor.getValue().domainId()).isEqualTo(domainId);
        assertThat(executionRequestCaptor.getValue().memoryCandidates()).isEqualTo(memories);
        assertThat(executionRequestCaptor.getValue().rewriteQueryProfile()).isEqualTo("compact_anchor");
        assertThat(executionRequestCaptor.getValue().anchorInjectionEnabled()).isFalse();
        verify(rewriteCandidateService).buildCandidates(eq(query), any(), eq(memories), eq(2), eq("compact_anchor"), eq(false), any());
        verify(traceRepository).createRewriteCandidate(eq(onlineQueryId), eq(1), eq("candidate-1"), eq(rewrittenQuery), any(), any(), anyDouble(), any());
        verify(traceRepository).markRewriteCandidateAdopted(eq(rewriteCandidateId), eq(true), isNull());
        verifyCreateRewriteCandidatePersistence(
                onlineQueryId,
                RetrievalExecutionDtos.NonAgenticExecutionKind.SELECTIVE_REWRITE,
                "candidate-1",
                rewrittenQuery
        );
        verifyRewriteCandidateAdoptionPersistence(
                onlineQueryId,
                rewriteCandidateId,
                RetrievalExecutionDtos.NonAgenticExecutionKind.SELECTIVE_REWRITE,
                true,
                null
        );
        verify(traceRepository).insertRetrievalResults(eq(onlineQueryId), isNull(), eq("raw"), anyList(), eq("selective_rewrite"), eq("local:dense_only:hash-embedding-v1"), any());
        verify(traceRepository).insertRetrievalResults(eq(onlineQueryId), eq(rewriteCandidateId), eq("rewrite_candidate"), anyList(), eq("selective_rewrite"), eq("local:dense_only:hash-embedding-v1"), any());
        verify(traceRepository).insertRerankResults(eq(onlineQueryId), eq(rewriteCandidateId), anyList(), eq("local-rerank-fallback"));
        verifyRewriteCandidateTracePersistence(
                onlineQueryId,
                rewriteCandidateId,
                RetrievalExecutionDtos.NonAgenticExecutionKind.SELECTIVE_REWRITE
        );
        verify(ragTracePersistenceService, never()).persistRawOnlyTrace(any());
        verify(chatAnswerService).generateAnswer(eq(query), eq(rewrittenQuery), eq("Spring"), anyList());
        verify(traceRepository).insertAnswer(eq(onlineQueryId), eq("rewrite answer"), any(), any(), eq("test-answer-model"), any());
        verify(traceRepository).createOnlineRewriteLog(eq(onlineQueryId), isNull(), eq(query), eq(rewrittenQuery), eq("selective_rewrite"), any(), any(), eq(true), eq("full_gating"), eq(true), eq(true), eq(false), anyDouble(), anyDouble(), anyDouble(), eq("delta_above_threshold"), isNull(), any());
        verify(traceRepository).insertMemoryRetrievalLog(eq(rewriteLogId), eq(onlineQueryId), eq(1), eq(memories.getFirst()), any());
        verify(traceRepository).insertRewriteCandidateLog(eq(rewriteLogId), eq(onlineQueryId), eq(rewriteCandidateId), eq(1), eq("candidate-1"), eq(rewrittenQuery), anyDouble(), eq(true), isNull(), any(), any(), any());
        verifyOnlineRewriteLogPersistence(
                onlineQueryId,
                RetrievalExecutionDtos.NonAgenticExecutionKind.SELECTIVE_REWRITE,
                query,
                rewrittenQuery
        );
        verifyOnlineQueryDecisionPersistence(
                onlineQueryId,
                RetrievalExecutionDtos.NonAgenticExecutionKind.SELECTIVE_REWRITE,
                query,
                rewrittenQuery,
                true,
                rewriteCandidateId,
                "delta_above_threshold",
                null
        );
        verifyOnlineQueryMetadataMerge(
                onlineQueryId,
                RetrievalExecutionDtos.NonAgenticExecutionKind.SELECTIVE_REWRITE
        );
        verify(agenticRetrievalService, never()).execute(any());
    }

    @Test
    void rewriteAlwaysPersistsFirstCandidateBeforeSecondCandidateFails() {
        UUID onlineQueryId = UUID.randomUUID();
        UUID firstCandidateId = UUID.randomUUID();
        String query = "spring filter order";
        List<MemoryCandidate> memories = memories();
        List<RetrievalDoc> firstDocs = docs("first-doc", "first-chunk", 0.90d);
        stubCommonAskDependencies(config("rewrite_always", false, false), onlineQueryId);
        when(memoryRepository.findMemoryTopN(anyString(), anyInt(), eq("full_gating"), eq(domainId),
                eq(List.of("C")), eq(List.of(sourceGatingRunId)), eq(List.of(sourceGatingBatchId))))
                .thenReturn(memories);
        when(rewriteCandidateService.buildCandidates(eq(query), any(), eq(memories), eq(2),
                eq("compact_anchor"), eq(false), any())).thenReturn(List.of(
                new RewriteDtos.CandidateTemplate("first", "first rewrite"),
                new RewriteDtos.CandidateTemplate("second", "second rewrite")
        ));
        when(chunkRepository.findTopChunksByEmbedding(anyString(), anyInt(), eq(domainId)))
                .thenReturn(docs("raw-doc", "raw-chunk", 0.10d), firstDocs)
                .thenThrow(new IllegalStateException("second candidate search failed"));
        when(cohereRerankService.rerank(anyString(), anyList(), anyInt())).thenAnswer(invocation -> invocation.getArgument(1));
        when(traceRepository.createRewriteCandidate(eq(onlineQueryId), eq(1), eq("first"), eq("first rewrite"),
                any(), any(), anyDouble(), any())).thenReturn(firstCandidateId);

        assertThatThrownBy(() -> ragService.ask(request(query)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("second candidate search failed");

        InOrder writes = inOrder(traceRepository, chunkRepository);
        writes.verify(traceRepository).createRewriteCandidate(eq(onlineQueryId), eq(1), eq("first"), eq("first rewrite"),
                any(), any(), anyDouble(), any());
        writes.verify(traceRepository).insertRetrievalResults(eq(onlineQueryId), eq(firstCandidateId),
                eq("rewrite_candidate"),
                argThat(docs -> docs.size() == 1 && "first-chunk".equals(docs.getFirst().chunkId())),
                eq("rewrite_always"), anyString(), any());
        writes.verify(chunkRepository).findTopChunksByEmbedding(anyString(), anyInt(), eq(domainId));
        verify(traceRepository, never()).createRewriteCandidate(eq(onlineQueryId), eq(2), anyString(), anyString(),
                any(), any(), anyDouble(), any());
        verify(traceRepository, never()).markRewriteCandidateAdopted(any(), anyBoolean(), any());
        verify(chatAnswerService, never()).generateAnswer(anyString(), anyString(), anyString(), anyList());
    }

    @ParameterizedTest
    @CsvSource({"0.95, true", "0.18, false"})
    void selectiveRewriteUsesCandidateOrderToLinkAdoptionToStoredIds(double secondScore, boolean adopted) {
        UUID onlineQueryId = UUID.randomUUID();
        UUID firstCandidateId = UUID.randomUUID();
        UUID secondCandidateId = UUID.randomUUID();
        String query = "spring filter order";
        List<MemoryCandidate> memories = memories();
        stubCommonAskDependencies(config("selective_rewrite", false, false), onlineQueryId);
        when(memoryRepository.findMemoryTopN(anyString(), anyInt(), eq("full_gating"), eq(domainId),
                eq(List.of("C")), eq(List.of(sourceGatingRunId)), eq(List.of(sourceGatingBatchId))))
                .thenReturn(memories);
        when(rewriteCandidateService.buildCandidates(eq(query), any(), eq(memories), eq(2),
                eq("compact_anchor"), eq(false), any())).thenReturn(List.of(
                new RewriteDtos.CandidateTemplate("first", "first rewrite"),
                new RewriteDtos.CandidateTemplate("second", "second rewrite")
        ));
        when(chunkRepository.findTopChunksByEmbedding(anyString(), anyInt(), eq(domainId))).thenReturn(
                docs("raw-doc", "raw-chunk", 0.10d),
                docs("first-doc", "first-chunk", 0.12d),
                docs("second-doc", "second-chunk", secondScore)
        );
        when(cohereRerankService.rerank(anyString(), anyList(), anyInt())).thenAnswer(invocation -> invocation.getArgument(1));
        when(traceRepository.createRewriteCandidate(eq(onlineQueryId), eq(1), eq("first"), eq("first rewrite"),
                any(), any(), anyDouble(), any())).thenReturn(firstCandidateId);
        when(traceRepository.createRewriteCandidate(eq(onlineQueryId), eq(2), eq("second"), eq("second rewrite"),
                any(), any(), anyDouble(), any())).thenReturn(secondCandidateId);
        stubRewriteLog(UUID.randomUUID());
        when(chatAnswerService.generateAnswer(anyString(), anyString(), anyString(), anyList()))
                .thenReturn(generatedAnswer("answer"));

        ChatDtos.AskResponse response = ragService.ask(request(query));

        assertThat(response.finalQueryUsed()).isEqualTo(adopted ? "second rewrite" : query);
        assertThat(response.rewriteApplied()).isEqualTo(adopted);
        assertThat(response.rewriteCandidates()).extracting(RewriteDtos.RewriteCandidateDto::rewriteCandidateId)
                .containsExactly(firstCandidateId, secondCandidateId);
        assertThat(response.rewriteCandidates()).extracting(RewriteDtos.RewriteCandidateDto::adopted)
                .containsExactly(false, adopted);
        String rejectedReason = adopted ? null : "best-candidate delta below threshold";
        verify(traceRepository).markRewriteCandidateAdopted(firstCandidateId, false, rejectedReason);
        verify(traceRepository).markRewriteCandidateAdopted(secondCandidateId, adopted, rejectedReason);
        verify(traceRepository).insertRerankResults(eq(onlineQueryId), eq(adopted ? secondCandidateId : null),
                anyList(), any());
    }

    @Test
    void routerSelectedSelectiveRewriteUsesExecutionServiceAndKeepsPersistenceWrites() {
        UUID onlineQueryId = UUID.fromString("44444444-4444-4444-4444-444444444448");
        UUID rewriteCandidateId = UUID.fromString("66666666-6666-6666-6666-666666666668");
        UUID rewriteLogId = UUID.fromString("77777777-7777-7777-7777-777777777780");
        String query = "spring";
        String rewrittenQuery = "FilterChainProxy SecurityFilterChain order";
        ChatConfigDtos.ChatRuntimeConfigResponse config = config("selective_rewrite", true, false);
        List<MemoryCandidate> memories = memories();
        List<RetrievalDoc> rawDocs = docs("raw-doc", "raw-chunk", 0.10d);
        List<RetrievalDoc> rewriteDocs = docs("rewrite-doc", "rewrite-chunk", 0.95d);
        stubCommonAskDependencies(config, onlineQueryId);
        when(memoryRepository.findMemoryTopN(anyString(), anyInt(), eq("full_gating"), eq(domainId), eq(List.of("C")), eq(List.of(sourceGatingRunId)), eq(List.of(sourceGatingBatchId))))
                .thenReturn(memories);
        when(chunkRepository.findTopChunksByEmbedding(anyString(), anyInt(), eq(domainId))).thenReturn(rawDocs, rewriteDocs);
        when(cohereRerankService.rerank(anyString(), anyList(), anyInt())).thenAnswer(invocation -> invocation.getArgument(1));
        when(cohereRerankService.modelName()).thenReturn("local-rerank-fallback");
        when(rewriteCandidateService.buildCandidates(eq(query), any(), eq(memories), eq(2), eq("compact_anchor"), eq(false), any()))
                .thenReturn(List.of(new RewriteDtos.CandidateTemplate("candidate-1", rewrittenQuery)));
        when(traceRepository.createRewriteCandidate(eq(onlineQueryId), eq(1), eq("candidate-1"), eq(rewrittenQuery), any(), any(), anyDouble(), any()))
                .thenReturn(rewriteCandidateId);
        stubRewriteLog(rewriteLogId);
        when(chatAnswerService.generateAnswer(anyString(), anyString(), anyString(), anyList()))
                .thenReturn(generatedAnswer("router selective answer"));

        ChatDtos.AskResponse response = ragService.ask(request(query));

        assertThat(response.finalQueryUsed()).isEqualTo(rewrittenQuery);
        assertThat(response.rewriteApplied()).isTrue();
        assertThat(response.answer()).isEqualTo("router selective answer");
        assertThat(response.answerModel()).isEqualTo("test-answer-model");
        assertThat(response.rewriteCandidates()).hasSize(1);
        assertThat(response.rewriteCandidates().getFirst().adopted()).isTrue();

        ArgumentCaptor<RetrievalExecutionDtos.RewriteExecutionRequest> executionRequestCaptor =
                ArgumentCaptor.forClass(RetrievalExecutionDtos.RewriteExecutionRequest.class);
        verify(ragRetrievalExecutionService).executeRewrite(executionRequestCaptor.capture());
        assertThat(executionRequestCaptor.getValue().domainId()).isEqualTo(domainId);
        assertThat(executionRequestCaptor.getValue().memoryCandidates()).isEqualTo(memories);
        assertThat(executionRequestCaptor.getValue().rewriteQueryProfile()).isEqualTo("compact_anchor");
        assertThat(executionRequestCaptor.getValue().anchorInjectionEnabled()).isFalse();
        verify(rewriteCandidateService).buildCandidates(eq(query), any(), eq(memories), eq(2), eq("compact_anchor"), eq(false), any());
        verify(traceRepository).createRewriteCandidate(eq(onlineQueryId), eq(1), eq("candidate-1"), eq(rewrittenQuery), any(), any(), anyDouble(), any());
        verify(traceRepository).markRewriteCandidateAdopted(eq(rewriteCandidateId), eq(true), isNull());
        verifyCreateRewriteCandidatePersistence(
                onlineQueryId,
                RetrievalExecutionDtos.NonAgenticExecutionKind.SELECTIVE_REWRITE,
                "candidate-1",
                rewrittenQuery
        );
        verifyRewriteCandidateAdoptionPersistence(
                onlineQueryId,
                rewriteCandidateId,
                RetrievalExecutionDtos.NonAgenticExecutionKind.SELECTIVE_REWRITE,
                true,
                null
        );
        verify(traceRepository).insertRetrievalResults(eq(onlineQueryId), isNull(), eq("raw"), anyList(), eq("selective_rewrite"), eq("local:dense_only:hash-embedding-v1"), any());
        verify(traceRepository).insertRetrievalResults(eq(onlineQueryId), eq(rewriteCandidateId), eq("rewrite_candidate"), anyList(), eq("selective_rewrite"), eq("local:dense_only:hash-embedding-v1"), any());
        verify(traceRepository).insertRerankResults(eq(onlineQueryId), eq(rewriteCandidateId), anyList(), eq("local-rerank-fallback"));
        verifyRewriteCandidateTracePersistence(
                onlineQueryId,
                rewriteCandidateId,
                RetrievalExecutionDtos.NonAgenticExecutionKind.SELECTIVE_REWRITE
        );
        verify(chatAnswerService).generateAnswer(eq(query), eq(rewrittenQuery), eq("Spring"), anyList());
        verify(traceRepository).insertAnswer(eq(onlineQueryId), eq("router selective answer"), any(), any(), eq("test-answer-model"), any());
        verify(traceRepository).createOnlineRewriteLog(eq(onlineQueryId), isNull(), eq(query), eq(rewrittenQuery), eq("selective_rewrite"), any(), any(), eq(true), eq("full_gating"), eq(true), eq(true), eq(false), anyDouble(), anyDouble(), anyDouble(), eq("delta_above_threshold"), isNull(), any());
        verify(traceRepository).insertMemoryRetrievalLog(eq(rewriteLogId), eq(onlineQueryId), eq(1), eq(memories.getFirst()), any());
        verify(traceRepository).insertRewriteCandidateLog(eq(rewriteLogId), eq(onlineQueryId), eq(rewriteCandidateId), eq(1), eq("candidate-1"), eq(rewrittenQuery), anyDouble(), eq(true), isNull(), any(), any(), any());
        verifyOnlineRewriteLogPersistence(
                onlineQueryId,
                RetrievalExecutionDtos.NonAgenticExecutionKind.SELECTIVE_REWRITE,
                query,
                rewrittenQuery
        );
        verify(agenticRetrievalService, never()).execute(any());

        verifyOnlineQueryDecisionPersistence(
                onlineQueryId,
                RetrievalExecutionDtos.NonAgenticExecutionKind.SELECTIVE_REWRITE,
                query,
                rewrittenQuery,
                true,
                rewriteCandidateId,
                "delta_above_threshold",
                null
        );
        JsonNode router = verifyOnlineQueryMetadataMerge(
                onlineQueryId,
                RetrievalExecutionDtos.NonAgenticExecutionKind.SELECTIVE_REWRITE
        ).path("router");
        assertThat(router.path("enabled").asBoolean()).isTrue();
        assertThat(router.path("strategy").asText()).isEqualTo("SYNTHETIC_SELECTIVE_REWRITE");
        assertThat(router.path("anchorInjectionEnabled").asBoolean()).isFalse();
    }

    @Test
    void anchorAwareRewritePassesAnchorInjectionAndRecordsRouteMetadata() {
        UUID onlineQueryId = UUID.fromString("44444444-4444-4444-4444-444444444446");
        UUID rewriteCandidateId = UUID.fromString("66666666-6666-6666-6666-666666666667");
        UUID rewriteLogId = UUID.fromString("77777777-7777-7777-7777-777777777778");
        String query = "FilterChainProxy SecurityFilterChain order";
        String rewrittenQuery = "FilterChainProxy SecurityFilterChain order in Spring Security";
        ChatConfigDtos.ChatRuntimeConfigResponse config = config("selective_rewrite", true, true);
        List<MemoryCandidate> memories = memories();
        List<RetrievalDoc> rawDocs = docs("raw-doc", "raw-chunk", 0.25d);
        List<RetrievalDoc> rewriteDocs = docs("rewrite-doc", "rewrite-chunk", 0.90d);
        stubCommonAskDependencies(config, onlineQueryId);
        when(memoryRepository.findMemoryTopN(anyString(), anyInt(), eq("full_gating"), eq(domainId), eq(List.of("C")), eq(List.of(sourceGatingRunId)), eq(List.of(sourceGatingBatchId))))
                .thenReturn(memories);
        when(chunkRepository.findTopChunksByEmbedding(anyString(), anyInt(), eq(domainId))).thenReturn(rawDocs, rewriteDocs);
        when(cohereRerankService.rerank(anyString(), anyList(), anyInt())).thenAnswer(invocation -> invocation.getArgument(1));
        when(cohereRerankService.modelName()).thenReturn("local-rerank-fallback");
        when(rewriteCandidateService.buildCandidates(eq(query), any(), eq(memories), eq(2), eq("compact_anchor"), eq(true), any()))
                .thenReturn(List.of(new RewriteDtos.CandidateTemplate("anchor-aware", rewrittenQuery)));
        when(traceRepository.createRewriteCandidate(eq(onlineQueryId), eq(1), eq("anchor-aware"), eq(rewrittenQuery), any(), any(), anyDouble(), any()))
                .thenReturn(rewriteCandidateId);
        stubRewriteLog(rewriteLogId);
        when(chatAnswerService.generateAnswer(anyString(), anyString(), anyString(), anyList()))
                .thenReturn(generatedAnswer("anchor answer"));

        ChatDtos.AskResponse response = ragService.ask(request(query));

        assertThat(response.answer()).isEqualTo("anchor answer");
        assertThat(response.answerModel()).isEqualTo("test-answer-model");
        ArgumentCaptor<RetrievalExecutionDtos.RewriteExecutionRequest> executionRequestCaptor =
                ArgumentCaptor.forClass(RetrievalExecutionDtos.RewriteExecutionRequest.class);
        verify(ragRetrievalExecutionService).executeRewrite(executionRequestCaptor.capture());
        assertThat(executionRequestCaptor.getValue().domainId()).isEqualTo(domainId);
        assertThat(executionRequestCaptor.getValue().memoryCandidates()).isEqualTo(memories);
        assertThat(executionRequestCaptor.getValue().rewriteQueryProfile()).isEqualTo("compact_anchor");
        assertThat(executionRequestCaptor.getValue().anchorInjectionEnabled()).isTrue();
        verify(rewriteCandidateService).buildCandidates(eq(query), any(), eq(memories), eq(2), eq("compact_anchor"), eq(true), any());
        verify(traceRepository).createRewriteCandidate(eq(onlineQueryId), eq(1), eq("anchor-aware"), eq(rewrittenQuery), any(), any(), anyDouble(), any());
        verify(traceRepository).markRewriteCandidateAdopted(eq(rewriteCandidateId), eq(true), isNull());
        verifyCreateRewriteCandidatePersistence(
                onlineQueryId,
                RetrievalExecutionDtos.NonAgenticExecutionKind.ANCHOR_AWARE_REWRITE,
                "anchor-aware",
                rewrittenQuery
        );
        verifyRewriteCandidateAdoptionPersistence(
                onlineQueryId,
                rewriteCandidateId,
                RetrievalExecutionDtos.NonAgenticExecutionKind.ANCHOR_AWARE_REWRITE,
                true,
                null
        );
        verify(traceRepository).insertRetrievalResults(eq(onlineQueryId), isNull(), eq("raw"), anyList(), eq("selective_rewrite"), eq("local:dense_only:hash-embedding-v1"), any());
        verify(traceRepository).insertRetrievalResults(eq(onlineQueryId), eq(rewriteCandidateId), eq("rewrite_candidate"), anyList(), eq("selective_rewrite"), eq("local:dense_only:hash-embedding-v1"), any());
        verify(chunkRepository, times(2)).findTopChunksByEmbedding(anyString(), anyInt(), eq(domainId));
        verify(traceRepository).insertRerankResults(eq(onlineQueryId), eq(rewriteCandidateId), anyList(), eq("local-rerank-fallback"));
        verifyRewriteCandidateTracePersistence(
                onlineQueryId,
                rewriteCandidateId,
                RetrievalExecutionDtos.NonAgenticExecutionKind.ANCHOR_AWARE_REWRITE
        );
        verify(ragTracePersistenceService, never()).persistRawOnlyTrace(any());
        verify(chatAnswerService).generateAnswer(eq(query), eq(rewrittenQuery), eq("Spring"), anyList());
        verify(traceRepository).insertAnswer(eq(onlineQueryId), eq("anchor answer"), any(), any(), eq("test-answer-model"), any());
        verify(traceRepository).createOnlineRewriteLog(eq(onlineQueryId), isNull(), eq(query), eq(rewrittenQuery), eq("selective_rewrite"), any(), any(), eq(true), eq("full_gating"), eq(true), eq(true), eq(false), anyDouble(), anyDouble(), anyDouble(), eq("delta_above_threshold"), isNull(), any());
        verify(traceRepository).insertMemoryRetrievalLog(eq(rewriteLogId), eq(onlineQueryId), eq(1), eq(memories.getFirst()), any());
        verify(traceRepository).insertRewriteCandidateLog(eq(rewriteLogId), eq(onlineQueryId), eq(rewriteCandidateId), eq(1), eq("anchor-aware"), eq(rewrittenQuery), anyDouble(), eq(true), isNull(), any(), any(), any());
        verifyOnlineRewriteLogPersistence(
                onlineQueryId,
                RetrievalExecutionDtos.NonAgenticExecutionKind.ANCHOR_AWARE_REWRITE,
                query,
                rewrittenQuery
        );

        verifyOnlineQueryDecisionPersistence(
                onlineQueryId,
                RetrievalExecutionDtos.NonAgenticExecutionKind.ANCHOR_AWARE_REWRITE,
                query,
                rewrittenQuery,
                true,
                rewriteCandidateId,
                "delta_above_threshold",
                null
        );
        JsonNode router = verifyOnlineQueryMetadataMerge(
                onlineQueryId,
                RetrievalExecutionDtos.NonAgenticExecutionKind.ANCHOR_AWARE_REWRITE
        ).path("router");
        assertThat(router.path("strategy").asText()).isEqualTo("ANCHOR_AWARE_REWRITE");
        assertThat(router.path("reason").asText()).isEqualTo("anchor_injection_enabled_and_technical_anchor_detected");
        assertThat(router.path("anchorInjectionEnabled").asBoolean()).isTrue();
        assertThat(router.path("metadata").path("containsTechnicalAnchor").asBoolean()).isTrue();
    }

    @Test
    void forcedAgenticModeBranchesToAgenticExecutionAndReturnsMetadata() {
        UUID onlineQueryId = UUID.fromString("44444444-4444-4444-4444-444444444447");
        String query = "spring security and mvc filter order";
        ChatConfigDtos.ChatRuntimeConfigResponse config = config("agentic_multi_query", false, false, true);
        List<MemoryCandidate> memories = memories();
        List<RetrievalDoc> mergedDocs = docs("agentic-doc", "agentic-chunk", 0.88d);
        UUID agenticCandidateId = UUID.fromString("99999999-9999-9999-9999-999999999997");
        ObjectNode agenticCandidateScoreBreakdown = objectMapper.createObjectNode().put("rrf_score", 0.88d);
        AgenticPlanningDtos.AgenticQueryPlan plan = new AgenticPlanningDtos.AgenticQueryPlan(
                query,
                domainId,
                "spring",
                "Spring",
                3,
                List.of(new AgenticPlanningDtos.AgenticSubquery(1, "FilterChainProxy order", "filter order", 1.0d, objectMapper.createObjectNode())),
                "test-planner",
                false,
                null,
                objectMapper.createObjectNode()
        );
        AgenticRetrievalDtos.SubqueryRetrievalTrace trace = new AgenticRetrievalDtos.SubqueryRetrievalTrace(
                1,
                "FilterChainProxy order",
                "FilterChainProxy order",
                false,
                "RAW_ONLY",
                "raw_only",
                "mode=raw_only",
                List.of(new RetrievalDtos.ScoredDocumentDto("agentic-doc", "agentic-chunk", "agentic text", 0.88d)),
                List.of(),
                objectMapper.createArrayNode(),
                3L,
                objectMapper.createObjectNode()
        );
        when(agenticRetrievalService.execute(any()))
                .thenReturn(new AgenticExecutionDtos.AgenticExecutionResult(
                        plan,
                        List.of(trace),
                        List.of(new AgenticExecutionDtos.PersistedRewriteCandidate(
                                agenticCandidateId,
                                "agentic-candidate-1",
                                "FilterChainProxy order",
                                mergedDocs,
                                0.88d,
                                false,
                                "agentic_not_selected",
                                agenticCandidateScoreBreakdown
                        )),
                        mergedDocs,
                        false,
                        "agentic_multi_query_rrf",
                        null,
                        5L,
                        8L,
                        objectMapper.createObjectNode().put("subquery_count", 1)
                ));
        stubCommonAskDependencies(config, onlineQueryId);
        when(memoryRepository.findMemoryTopN(anyString(), anyInt(), eq("full_gating"), eq(domainId), eq(List.of("C")), eq(List.of(sourceGatingRunId)), eq(List.of(sourceGatingBatchId))))
                .thenReturn(memories);
        when(chatAnswerService.generateAnswer(anyString(), anyString(), anyString(), anyList()))
                .thenReturn(generatedAnswer("agentic answer"));
        UUID rewriteLogId = UUID.fromString("77777777-7777-7777-7777-777777777779");
        stubRewriteLog(rewriteLogId);

        ChatDtos.AskResponse response = ragService.ask(request(query));

        assertThat(response.answer()).isEqualTo("agentic answer");
        assertThat(response.answerModel()).isEqualTo("test-answer-model");
        assertThat(response.agenticMetadata()).isNotNull();
        assertThat(response.agenticMetadata().plan().domainId()).isEqualTo(domainId);
        assertThat(response.agenticMetadata().mergedDocs()).extracting(RetrievalDtos.ScoredDocumentDto::chunkId)
                .containsExactly("agentic-chunk");

        ArgumentCaptor<AgenticExecutionDtos.AgenticExecutionRequest> requestCaptor =
                ArgumentCaptor.forClass(AgenticExecutionDtos.AgenticExecutionRequest.class);
        verify(agenticRetrievalService).execute(requestCaptor.capture());
        AgenticExecutionDtos.AgenticExecutionRequest executionRequest = requestCaptor.getValue();
        assertThat(executionRequest.onlineQueryId()).isEqualTo(onlineQueryId);
        assertThat(executionRequest.config().domainId()).isEqualTo(domainId);
        assertThat(executionRequest.plannerMemoryHints()).isEqualTo(memories);
        assertThat(executionRequest.memoryPreset()).isEqualTo("full_gating");

        verify(traceRepository).createOnlineQuery(eq(domainId), eq("session-1"), eq(query), any(), eq("agentic_multi_query"), eq(0.05d), any());
        verify(memoryRepository).findMemoryTopN(anyString(), anyInt(), eq("full_gating"), eq(domainId), eq(List.of("C")), eq(List.of(sourceGatingRunId)), eq(List.of(sourceGatingBatchId)));
        verify(chunkRepository, never()).findTopChunksByEmbedding(anyString(), anyInt(), any());
        verify(rewriteCandidateService, never()).buildCandidates(anyString(), any(), anyList(), anyInt(), anyString(), anyBoolean(), any());
        verify(ragTracePersistenceService, never()).persistRawOnlyTrace(any());
        verify(ragTracePersistenceService, never()).persistRewriteCandidateTrace(any());
        verify(ragTracePersistenceService, never()).createRewriteCandidateTrace(any());
        verify(ragTracePersistenceService, never()).markRewriteCandidateAdopted(any());
        verify(ragTracePersistenceService, never()).persistOnlineRewriteLogs(any(), anyList(), anyList(), any(), any());
        ArgumentCaptor<AgenticTraceWriteDtos.AgenticOnlineRewriteLogPersistenceRequest> rewriteLogCaptor =
                ArgumentCaptor.forClass(AgenticTraceWriteDtos.AgenticOnlineRewriteLogPersistenceRequest.class);
        verify(agenticTracePersistenceService).createAgenticOnlineRewriteLogTrace(rewriteLogCaptor.capture());
        AgenticTraceWriteDtos.AgenticOnlineRewriteLogPersistenceRequest rewriteLogRequest =
                rewriteLogCaptor.getValue();
        assertThat(rewriteLogRequest.persistPolicy()).isEqualTo(RagPersistPolicy.ONLINE_QUERY);
        assertThat(rewriteLogRequest.onlineQueryId()).isEqualTo(onlineQueryId);
        assertThat(rewriteLogRequest.executionKind()).isEqualTo(
                AgenticTraceWriteDtos.AgenticRetrievalExecutionKind.AGENTIC_MULTI_QUERY
        );
        assertThat(rewriteLogRequest.rawQuery()).isEqualTo(query);
        assertThat(rewriteLogRequest.finalQuery()).isEqualTo(query);
        assertThat(rewriteLogRequest.rewriteStrategy()).isEqualTo("agentic_multi_query");
        assertThat(rewriteLogRequest.rewriteApplied()).isFalse();
        assertThat(rewriteLogRequest.decisionReason()).isEqualTo("agentic_multi_query_rrf");
        assertThat(rewriteLogRequest.metadata().path("agentic_retrieval").isObject()).isTrue();

        ArgumentCaptor<AgenticTraceWriteDtos.AgenticMemoryRetrievalLogPersistenceRequest> memoryLogCaptor =
                ArgumentCaptor.forClass(AgenticTraceWriteDtos.AgenticMemoryRetrievalLogPersistenceRequest.class);
        verify(agenticTracePersistenceService).insertAgenticMemoryRetrievalTrace(memoryLogCaptor.capture());
        AgenticTraceWriteDtos.AgenticMemoryRetrievalLogPersistenceRequest memoryLogRequest =
                memoryLogCaptor.getValue();
        assertThat(memoryLogRequest.persistPolicy()).isEqualTo(RagPersistPolicy.ONLINE_QUERY);
        assertThat(memoryLogRequest.onlineQueryId()).isEqualTo(onlineQueryId);
        assertThat(memoryLogRequest.rewriteLogId()).isEqualTo(rewriteLogId);
        assertThat(memoryLogRequest.executionKind()).isEqualTo(
                AgenticTraceWriteDtos.AgenticRetrievalExecutionKind.AGENTIC_MULTI_QUERY
        );
        assertThat(memoryLogRequest.retrievalRank()).isEqualTo(1);
        assertThat(memoryLogRequest.candidate()).isEqualTo(memories.getFirst());
        assertThat(memoryLogRequest.metadata().path("agentic_role").asText()).isEqualTo("planner_memory_hint");

        ArgumentCaptor<AgenticTraceWriteDtos.AgenticRewriteCandidateLogPersistenceRequest> candidateLogCaptor =
                ArgumentCaptor.forClass(AgenticTraceWriteDtos.AgenticRewriteCandidateLogPersistenceRequest.class);
        verify(agenticTracePersistenceService).insertAgenticRewriteCandidateTrace(candidateLogCaptor.capture());
        AgenticTraceWriteDtos.AgenticRewriteCandidateLogPersistenceRequest candidateLogRequest =
                candidateLogCaptor.getValue();
        assertThat(candidateLogRequest.persistPolicy()).isEqualTo(RagPersistPolicy.ONLINE_QUERY);
        assertThat(candidateLogRequest.onlineQueryId()).isEqualTo(onlineQueryId);
        assertThat(candidateLogRequest.rewriteLogId()).isEqualTo(rewriteLogId);
        assertThat(candidateLogRequest.rewriteCandidateId()).isEqualTo(agenticCandidateId);
        assertThat(candidateLogRequest.executionKind()).isEqualTo(
                AgenticTraceWriteDtos.AgenticRetrievalExecutionKind.AGENTIC_MULTI_QUERY
        );
        assertThat(candidateLogRequest.candidateRank()).isEqualTo(1);
        assertThat(candidateLogRequest.candidateLabel()).isEqualTo("agentic-candidate-1");
        assertThat(candidateLogRequest.candidateQuery()).isEqualTo("FilterChainProxy order");
        assertThat(candidateLogRequest.selected()).isFalse();
        assertThat(candidateLogRequest.rejectionReason()).isEqualTo("agentic_not_selected");
        assertThat(candidateLogRequest.scoreBreakdown()).isEqualTo(agenticCandidateScoreBreakdown);
        assertThat(candidateLogRequest.metadata().path("agentic_multi_query").asBoolean()).isTrue();
        ArgumentCaptor<AgenticTraceWriteDtos.AgenticFinalRerankTracePersistenceRequest> finalRerankCaptor =
                ArgumentCaptor.forClass(AgenticTraceWriteDtos.AgenticFinalRerankTracePersistenceRequest.class);
        verify(agenticTracePersistenceService).persistAgenticFinalRerankTrace(finalRerankCaptor.capture());
        AgenticTraceWriteDtos.AgenticFinalRerankTracePersistenceRequest finalRerankRequest =
                finalRerankCaptor.getValue();
        assertThat(finalRerankRequest.persistPolicy()).isEqualTo(RagPersistPolicy.ONLINE_QUERY);
        assertThat(finalRerankRequest.onlineQueryId()).isEqualTo(onlineQueryId);
        assertThat(finalRerankRequest.executionKind()).isEqualTo(
                AgenticTraceWriteDtos.AgenticRetrievalExecutionKind.AGENTIC_MULTI_QUERY
        );
        assertThat(finalRerankRequest.finalMergedDocs()).isEqualTo(mergedDocs);
        assertThat(finalRerankRequest.resultScope()).isEqualTo("agentic-rrf");
        assertThat(finalRerankRequest.rerankerModel()).isEqualTo("agentic-rrf");
        ArgumentCaptor<AgenticTraceWriteDtos.AgenticOnlineQueryDecisionPersistenceRequest> agenticDecisionCaptor =
                ArgumentCaptor.forClass(AgenticTraceWriteDtos.AgenticOnlineQueryDecisionPersistenceRequest.class);
        verify(agenticTracePersistenceService).persistAgenticOnlineQueryDecision(agenticDecisionCaptor.capture());
        AgenticTraceWriteDtos.AgenticOnlineQueryDecisionPersistenceRequest agenticDecisionRequest =
                agenticDecisionCaptor.getValue();
        assertThat(agenticDecisionRequest.persistPolicy()).isEqualTo(RagPersistPolicy.ONLINE_QUERY);
        assertThat(agenticDecisionRequest.onlineQueryId()).isEqualTo(onlineQueryId);
        assertThat(agenticDecisionRequest.executionKind()).isEqualTo(
                AgenticTraceWriteDtos.AgenticRetrievalExecutionKind.AGENTIC_MULTI_QUERY
        );
        assertThat(agenticDecisionRequest.rawQuery()).isEqualTo(query);
        assertThat(agenticDecisionRequest.finalQueryUsed()).isEqualTo(query);
        assertThat(agenticDecisionRequest.rewriteApplied()).isFalse();
        assertThat(agenticDecisionRequest.selectedRewriteCandidateId()).isNull();
        assertThat(agenticDecisionRequest.finalRetrievedDocsCount()).isEqualTo(mergedDocs.size());
        assertThat(agenticDecisionRequest.selectedReason()).isEqualTo("agentic_multi_query_rrf");
        assertThat(agenticDecisionRequest.rejectedReason()).isNull();
        assertThat(agenticDecisionRequest.memoryTopN()).isEqualTo(objectMapper.valueToTree(memories));
        assertThat(agenticDecisionRequest.latencyBreakdown().path("totalMs").isNumber()).isTrue();
        ArgumentCaptor<AgenticTraceWriteDtos.AgenticOnlineQueryMetadataMergePersistenceRequest> agenticMetadataCaptor =
                ArgumentCaptor.forClass(AgenticTraceWriteDtos.AgenticOnlineQueryMetadataMergePersistenceRequest.class);
        verify(agenticTracePersistenceService).mergeAgenticOnlineQueryMetadata(agenticMetadataCaptor.capture());
        AgenticTraceWriteDtos.AgenticOnlineQueryMetadataMergePersistenceRequest agenticMetadataRequest =
                agenticMetadataCaptor.getValue();
        assertThat(agenticMetadataRequest.persistPolicy()).isEqualTo(RagPersistPolicy.ONLINE_QUERY);
        assertThat(agenticMetadataRequest.onlineQueryId()).isEqualTo(onlineQueryId);
        assertThat(agenticMetadataRequest.executionKind()).isEqualTo(
                AgenticTraceWriteDtos.AgenticRetrievalExecutionKind.AGENTIC_MULTI_QUERY
        );
        assertThat(agenticMetadataRequest.sourceMarker()).isEqualTo("agentic_retrieval");
        assertThat(agenticMetadataRequest.metadata().path("agentic_plan").isObject()).isTrue();
        assertThat(agenticMetadataRequest.metadata().path("router").isObject()).isTrue();
        verify(traceRepository).insertRerankResults(eq(onlineQueryId), isNull(), eq(mergedDocs), eq("agentic-rrf"));
        verify(chatAnswerService).generateAnswer(eq(query), eq(query), eq("Spring"), eq(mergedDocs));
        verify(traceRepository).insertAnswer(eq(onlineQueryId), eq("agentic answer"), any(), any(), eq("test-answer-model"), any());
        verify(traceRepository).upsertOnlineQueryDecision(eq(onlineQueryId), eq(query), eq(false), any(), anyDouble(), isNull(), eq("agentic_multi_query_rrf"), isNull(), any());
        verify(traceRepository).mergeOnlineQueryMetadata(eq(onlineQueryId), any());
        verify(traceRepository).createOnlineRewriteLog(eq(onlineQueryId), isNull(), eq(query), eq(query), eq("agentic_multi_query"), any(), any(), eq(true), eq("full_gating"), eq(false), eq(false), eq(false), anyDouble(), anyDouble(), eq(0.0d), eq("agentic_multi_query_rrf"), isNull(), any());
        verify(traceRepository).insertMemoryRetrievalLog(eq(rewriteLogId), eq(onlineQueryId), eq(1), eq(memories.getFirst()), any());
        verify(traceRepository).insertRewriteCandidateLog(eq(rewriteLogId), eq(onlineQueryId), eq(agenticCandidateId), eq(1), eq("agentic-candidate-1"), eq("FilterChainProxy order"), eq(0.88d), eq(false), eq("agentic_not_selected"), any(), eq(agenticCandidateScoreBreakdown), any());
        verify(ragTracePersistenceService, never()).persistOnlineQueryDecision(any());
        verify(ragTracePersistenceService, never()).mergeOnlineQueryMetadata(any());
    }

    @Test
    void routerSelectedAgenticBranchesToAgenticExecutionAndRecordsRouteMetadata() {
        UUID onlineQueryId = UUID.fromString("44444444-4444-4444-4444-444444444449");
        String query = "\uC2A4\uD504\uB9C1 \uC694\uCCAD \uD750\uB984\uC744 \uBE44\uAD50\uD558\uACE0 mvc \uB77C\uC6B0\uD305\uACFC \uCC98\uB9AC \uB2E8\uACC4\uB97C \uC124\uBA85";
        ChatConfigDtos.ChatRuntimeConfigResponse config = config("selective_rewrite", true, false, true);
        List<MemoryCandidate> memories = memories();
        List<RetrievalDoc> mergedDocs = docs("agentic-doc", "agentic-router-chunk", 0.91d);
        AgenticPlanningDtos.AgenticQueryPlan plan = new AgenticPlanningDtos.AgenticQueryPlan(
                query,
                domainId,
                "spring",
                "Spring",
                3,
                List.of(new AgenticPlanningDtos.AgenticSubquery(1, "spring filter order", "filter order", 1.0d, objectMapper.createObjectNode())),
                "test-planner",
                false,
                null,
                objectMapper.createObjectNode()
        );
        AgenticRetrievalDtos.SubqueryRetrievalTrace trace = new AgenticRetrievalDtos.SubqueryRetrievalTrace(
                1,
                "spring filter order",
                "spring filter order",
                false,
                "SYNTHETIC_SELECTIVE_REWRITE",
                "agentic_multi_query_rrf",
                null,
                List.of(new RetrievalDtos.ScoredDocumentDto("agentic-doc", "agentic-router-chunk", "agentic text", 0.91d)),
                List.of(),
                objectMapper.createArrayNode(),
                4L,
                objectMapper.createObjectNode()
        );
        when(agenticRetrievalService.execute(any()))
                .thenReturn(new AgenticExecutionDtos.AgenticExecutionResult(
                        plan,
                        List.of(trace),
                        List.of(),
                        mergedDocs,
                        false,
                        "agentic_multi_query_rrf",
                        null,
                        5L,
                        9L,
                        objectMapper.createObjectNode().put("subquery_count", 1)
                ));
        stubCommonAskDependencies(config, onlineQueryId);
        when(memoryRepository.findMemoryTopN(anyString(), anyInt(), eq("full_gating"), eq(domainId), eq(List.of("C")), eq(List.of(sourceGatingRunId)), eq(List.of(sourceGatingBatchId))))
                .thenReturn(memories);
        when(chatAnswerService.generateAnswer(anyString(), anyString(), anyString(), anyList()))
                .thenReturn(generatedAnswer("router agentic answer"));
        stubRewriteLog(UUID.fromString("77777777-7777-7777-7777-777777777781"));

        ChatDtos.AskResponse response = ragService.ask(request(query));

        assertThat(response.answer()).isEqualTo("router agentic answer");
        assertThat(response.agenticMetadata()).isNotNull();
        assertThat(response.agenticMetadata().mergedDocs()).extracting(RetrievalDtos.ScoredDocumentDto::chunkId)
                .containsExactly("agentic-router-chunk");

        ArgumentCaptor<AgenticExecutionDtos.AgenticExecutionRequest> requestCaptor =
                ArgumentCaptor.forClass(AgenticExecutionDtos.AgenticExecutionRequest.class);
        verify(agenticRetrievalService).execute(requestCaptor.capture());
        assertThat(requestCaptor.getValue().plannerMemoryHints()).isEqualTo(memories);
        assertThat(requestCaptor.getValue().mode()).isEqualTo("selective_rewrite");

        ArgumentCaptor<AgenticTraceWriteDtos.AgenticOnlineQueryMetadataMergePersistenceRequest> metadataCaptor =
                ArgumentCaptor.forClass(AgenticTraceWriteDtos.AgenticOnlineQueryMetadataMergePersistenceRequest.class);
        verify(agenticTracePersistenceService).mergeAgenticOnlineQueryMetadata(metadataCaptor.capture());
        JsonNode router = metadataCaptor.getValue().metadata().path("router");
        assertThat(router.path("enabled").asBoolean()).isTrue();
        assertThat(router.path("strategy").asText()).isEqualTo("AGENTIC_MULTI_QUERY");
        assertThat(router.path("reason").asText()).isEqualTo("agentic_multi_query_candidate");
        assertThat(router.path("metadata").path("agenticMultiQueryEnabled").asBoolean()).isTrue();
        assertThat(router.path("metadata").path("agenticSubquery").asBoolean()).isFalse();

        verify(chunkRepository, never()).findTopChunksByEmbedding(anyString(), anyInt(), any());
        verify(rewriteCandidateService, never()).buildCandidates(anyString(), any(), anyList(), anyInt(), anyString(), anyBoolean(), any());
        verify(ragTracePersistenceService, never()).persistOnlineQueryDecision(any());
        verify(ragTracePersistenceService, never()).mergeOnlineQueryMetadata(any());
    }

    private ChatConfigDtos.ChatRuntimeConfigResponse config(String mode, boolean routerEnabled, boolean anchorInjectionEnabled) {
        return config(mode, routerEnabled, anchorInjectionEnabled, false);
    }

    private ChatConfigDtos.ChatRuntimeConfigResponse config(String mode, boolean routerEnabled, boolean anchorInjectionEnabled, boolean agenticEnabled) {
        ObjectNode metadata = objectMapper.createObjectNode();
        if (routerEnabled) {
            metadata.put("routerEnabled", true);
        }
        if (agenticEnabled) {
            metadata.put("agenticMultiQueryEnabled", true);
            metadata.put("maxSubqueries", 3);
            metadata.put("rrfK", 60);
        }
        return new ChatConfigDtos.ChatRuntimeConfigResponse(
                domainId,
                "spring",
                "Spring",
                "en",
                true,
                mode,
                List.of("C"),
                "full_gating",
                sourceGatingBatchId,
                sourceGatingRunId,
                List.of(sourceGatingBatchId),
                List.of(sourceGatingRunId),
                "compact_anchor",
                anchorInjectionEnabled,
                false,
                "local",
                "intfloat/multilingual-e5-small",
                "dense_only",
                20,
                1.0,
                0.0,
                0.0,
                3,
                3,
                5,
                2,
                0.05,
                "skip_to_raw",
                routerEnabled,
                metadata,
                Instant.now(),
                true,
                "ready"
        );
    }

    private ChatReadinessDtos.ChatDomainReadinessResponse readiness(boolean ready, String... reasons) {
        return new ChatReadinessDtos.ChatDomainReadinessResponse(
                domainId,
                "spring",
                "Spring",
                "en",
                true,
                true,
                "selective_rewrite",
                true,
                List.of("KO_TECHNICAL"),
                "full_gating",
                null,
                null,
                ready ? 3L : 0L,
                ready ? 3L : 0L,
                null,
                null,
                ready,
                List.of(reasons),
                Instant.now()
        );
    }

    private void verifyRewriteCandidateTracePersistence(
            UUID onlineQueryId,
            UUID rewriteCandidateId,
            RetrievalExecutionDtos.NonAgenticExecutionKind executionKind
    ) {
        ArgumentCaptor<RagTraceWriteDtos.RewriteCandidateTracePersistenceRequest> traceCaptor =
                ArgumentCaptor.forClass(RagTraceWriteDtos.RewriteCandidateTracePersistenceRequest.class);
        verify(ragTracePersistenceService, times(2)).persistRewriteCandidateTrace(traceCaptor.capture());
        assertThat(traceCaptor.getAllValues())
                .extracting(RagTraceWriteDtos.RewriteCandidateTracePersistenceRequest::writeScope)
                .containsExactly(
                        RagTraceWriteDtos.RewriteCandidateTraceWriteScope.CANDIDATE_RETRIEVAL,
                        RagTraceWriteDtos.RewriteCandidateTraceWriteScope.CANDIDATE_RERANK
                );
        assertThat(traceCaptor.getAllValues())
                .allSatisfy(trace -> {
                    assertThat(trace.onlineQueryId()).isEqualTo(onlineQueryId);
                    assertThat(trace.rewriteCandidateId()).isEqualTo(rewriteCandidateId);
                    assertThat(trace.executionKind()).isEqualTo(executionKind);
                    assertThat(trace.persistPolicy()).isEqualTo(io.queryforge.backend.rag.retrieval.execution.model.RagPersistPolicy.ONLINE_QUERY);
                    assertThat(trace.mode()).isEqualTo("selective_rewrite");
                });
        assertThat(traceCaptor.getAllValues().get(0).retrievedDocs()).isNotEmpty();
        assertThat(traceCaptor.getAllValues().get(1).rerankedDocs()).isNotEmpty();
    }

    private void verifyCreateRewriteCandidatePersistence(
            UUID onlineQueryId,
            RetrievalExecutionDtos.NonAgenticExecutionKind executionKind,
            String candidateLabel,
            String candidateQuery
    ) {
        ArgumentCaptor<RagTraceWriteDtos.CreateRewriteCandidateTracePersistenceRequest> createCaptor =
                ArgumentCaptor.forClass(RagTraceWriteDtos.CreateRewriteCandidateTracePersistenceRequest.class);
        verify(ragTracePersistenceService).createRewriteCandidateTrace(createCaptor.capture());
        RagTraceWriteDtos.CreateRewriteCandidateTracePersistenceRequest request = createCaptor.getValue();
        assertThat(request.persistPolicy()).isEqualTo(RagPersistPolicy.ONLINE_QUERY);
        assertThat(request.onlineQueryId()).isEqualTo(onlineQueryId);
        assertThat(request.executionKind()).isEqualTo(executionKind);
        assertThat(request.candidateIndex()).isEqualTo(1);
        assertThat(request.candidateLabel()).isEqualTo(candidateLabel);
        assertThat(request.candidateQuery()).isEqualTo(candidateQuery);
        assertThat(request.candidateMetadata().path("candidate_index").asInt()).isEqualTo(1);
        assertThat(request.memorySourceIds().isArray()).isTrue();
        assertThat(request.retrievalTopKDocs().isArray()).isTrue();
        assertThat(request.scoreBreakdown().isObject()).isTrue();
    }

    private void verifyRewriteCandidateAdoptionPersistence(
            UUID onlineQueryId,
            UUID rewriteCandidateId,
            RetrievalExecutionDtos.NonAgenticExecutionKind executionKind,
            boolean adopted,
            String rejectedReason
    ) {
        ArgumentCaptor<RagTraceWriteDtos.RewriteCandidateAdoptionPersistenceRequest> adoptionCaptor =
                ArgumentCaptor.forClass(RagTraceWriteDtos.RewriteCandidateAdoptionPersistenceRequest.class);
        verify(ragTracePersistenceService).markRewriteCandidateAdopted(adoptionCaptor.capture());
        RagTraceWriteDtos.RewriteCandidateAdoptionPersistenceRequest request = adoptionCaptor.getValue();
        assertThat(request.persistPolicy()).isEqualTo(RagPersistPolicy.ONLINE_QUERY);
        assertThat(request.onlineQueryId()).isEqualTo(onlineQueryId);
        assertThat(request.rewriteCandidateId()).isEqualTo(rewriteCandidateId);
        assertThat(request.executionKind()).isEqualTo(executionKind);
        assertThat(request.adopted()).isEqualTo(adopted);
        assertThat(request.rejectedReason()).isEqualTo(rejectedReason);
    }

    private void verifyOnlineRewriteLogPersistence(
            UUID onlineQueryId,
            RetrievalExecutionDtos.NonAgenticExecutionKind executionKind,
            String rawQuery,
            String finalQuery
    ) {
        ArgumentCaptor<RagTraceWriteDtos.OnlineRewriteLogPersistenceRequest> rewriteLogCaptor =
                ArgumentCaptor.forClass(RagTraceWriteDtos.OnlineRewriteLogPersistenceRequest.class);
        verify(ragTracePersistenceService).persistOnlineRewriteLogs(rewriteLogCaptor.capture(), anyList(), anyList(), any(), eq(1));
        RagTraceWriteDtos.OnlineRewriteLogPersistenceRequest request = rewriteLogCaptor.getValue();
        assertThat(request.persistPolicy()).isEqualTo(RagPersistPolicy.ONLINE_QUERY);
        assertThat(request.onlineQueryId()).isEqualTo(onlineQueryId);
        assertThat(request.executionKind()).isEqualTo(executionKind);
        assertThat(request.rawQuery()).isEqualTo(rawQuery);
        assertThat(request.finalQuery()).isEqualTo(finalQuery);
        assertThat(request.rewriteStrategy()).isEqualTo("selective_rewrite");
        assertThat(request.gatingApplied()).isTrue();
        assertThat(request.gatingPreset()).isEqualTo("full_gating");
        assertThat(request.rewriteApplied()).isTrue();
        assertThat(request.selectiveRewrite()).isTrue();
        assertThat(request.useSessionContext()).isFalse();
        assertThat(request.decisionReason()).isEqualTo("delta_above_threshold");
        assertThat(request.rejectionReason()).isNull();
        assertThat(request.metadata().path("router").isObject()).isTrue();
    }

    private void verifyOnlineQueryDecisionPersistence(
            UUID onlineQueryId,
            RetrievalExecutionDtos.NonAgenticExecutionKind executionKind,
            String rawQuery,
            String finalQuery,
            boolean rewriteApplied,
            UUID selectedCandidateId,
            String selectedReason,
            String rejectedReason
    ) {
        ArgumentCaptor<RagTraceWriteDtos.OnlineQueryDecisionPersistenceRequest> decisionCaptor =
                ArgumentCaptor.forClass(RagTraceWriteDtos.OnlineQueryDecisionPersistenceRequest.class);
        verify(ragTracePersistenceService).persistOnlineQueryDecision(decisionCaptor.capture());
        RagTraceWriteDtos.OnlineQueryDecisionPersistenceRequest request = decisionCaptor.getValue();
        assertThat(request.persistPolicy()).isEqualTo(RagPersistPolicy.ONLINE_QUERY);
        assertThat(request.onlineQueryId()).isEqualTo(onlineQueryId);
        assertThat(request.executionKind()).isEqualTo(executionKind);
        assertThat(request.rawQuery()).isEqualTo(rawQuery);
        assertThat(request.finalQueryUsed()).isEqualTo(finalQuery);
        assertThat(request.rewriteApplied()).isEqualTo(rewriteApplied);
        assertThat(request.selectedRewriteCandidateId()).isEqualTo(selectedCandidateId);
        assertThat(request.selectedReason()).isEqualTo(selectedReason);
        assertThat(request.rejectedReason()).isEqualTo(rejectedReason);
        assertThat(request.memoryTopN().isMissingNode()).isFalse();
        assertThat(request.latencyBreakdown().path("totalMs").isNumber()).isTrue();
        assertThat(request.finalRetrievedDocsCount()).isPositive();
    }

    private JsonNode verifyOnlineQueryMetadataMerge(
            UUID onlineQueryId,
            RetrievalExecutionDtos.NonAgenticExecutionKind executionKind
    ) {
        ArgumentCaptor<RagTraceWriteDtos.OnlineQueryMetadataMergePersistenceRequest> metadataCaptor =
                ArgumentCaptor.forClass(RagTraceWriteDtos.OnlineQueryMetadataMergePersistenceRequest.class);
        verify(ragTracePersistenceService).mergeOnlineQueryMetadata(metadataCaptor.capture());
        RagTraceWriteDtos.OnlineQueryMetadataMergePersistenceRequest request = metadataCaptor.getValue();
        assertThat(request.persistPolicy()).isEqualTo(RagPersistPolicy.ONLINE_QUERY);
        assertThat(request.onlineQueryId()).isEqualTo(onlineQueryId);
        assertThat(request.executionKind()).isEqualTo(executionKind);
        assertThat(request.sourceMarker()).isEqualTo("router");
        assertThat(request.metadata().path("router").isObject()).isTrue();
        return request.metadata();
    }

    private void stubCommonAskDependencies(ChatConfigDtos.ChatRuntimeConfigResponse config, UUID onlineQueryId) {
        when(chatRuntimeConfigService.getConfig(domainId)).thenReturn(config);
        when(chatReadinessService.getReadiness(domainId)).thenReturn(readiness(true));
        when(traceRepository.createOnlineQuery(eq(domainId), any(), anyString(), any(), anyString(), anyDouble(), any()))
                .thenReturn(onlineQueryId);
        when(embeddingService.embed(anyString())).thenReturn(List.of(1.0d, 0.0d));
        when(embeddingService.toHalfvecLiteral(anyList())).thenReturn("[1.000000,0.000000]");
    }

    private void stubRewriteLog(UUID rewriteLogId) {
        when(traceRepository.createOnlineRewriteLog(
                any(),
                any(),
                anyString(),
                anyString(),
                anyString(),
                any(),
                any(),
                anyBoolean(),
                anyString(),
                anyBoolean(),
                any(),
                any(),
                anyDouble(),
                anyDouble(),
                anyDouble(),
                nullable(String.class),
                nullable(String.class),
                any()
        )).thenReturn(rewriteLogId);
    }

    private ChatDtos.AskRequest request(String query) {
        return new ChatDtos.AskRequest(
                query,
                domainId,
                "session-1",
                objectMapper.createObjectNode(),
                null
        );
    }

    private ChatAnswerDtos.GeneratedAnswer generatedAnswer(String answer) {
        return new ChatAnswerDtos.GeneratedAnswer(
                answer,
                List.of("doc-1"),
                List.of("chunk-1"),
                "test-answer-model"
        );
    }

    private List<RetrievalDoc> docs(String documentId, String chunkId, double score) {
        return List.of(new RetrievalDoc(
                documentId,
                chunkId,
                "Spring Security FilterChainProxy documentation",
                score
        ));
    }

    private List<MemoryCandidate> memories() {
        return List.of(new MemoryCandidate(
                UUID.fromString("88888888-8888-8888-8888-888888888888"),
                "FilterChainProxy SecurityFilterChain order",
                "memory-doc",
                objectMapper.createArrayNode().add("memory-chunk"),
                objectMapper.createArrayNode().add("FilterChainProxy"),
                objectMapper.createObjectNode(),
                0.72d,
                "C",
                UUID.fromString("99999999-9999-9999-9999-999999999999"),
                domainId,
                "gated-query-1",
                sourceGatingRunId.toString(),
                sourceGatingBatchId.toString()
        ));
    }
}
