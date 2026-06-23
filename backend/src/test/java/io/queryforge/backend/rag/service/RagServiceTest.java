package io.queryforge.backend.rag.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.queryforge.backend.rag.model.ChatRuntimeDtos;
import io.queryforge.backend.rag.model.RagDtos;
import io.queryforge.backend.rag.model.RagPersistPolicy;
import io.queryforge.backend.rag.repository.RagRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.nullable;
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
    private RagRepository repository;
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
    private AgenticRetrievalService agenticRetrievalService;

    private RagService ragService;
    private RagRetrievalExecutionService ragRetrievalExecutionService;
    private RagTracePersistenceService ragTracePersistenceService;

    @BeforeEach
    void setUp() {
        DomainScopedRetrievalService domainScopedRetrievalService = new DomainScopedRetrievalService(
                repository,
                embeddingService,
                denseEmbeddingService
        );
        ragRetrievalExecutionService = spy(new RagRetrievalExecutionService(
                domainScopedRetrievalService,
                cohereRerankService,
                rewriteCandidateService,
                objectMapper
        ));
        ragTracePersistenceService = spy(new RagTracePersistenceService(repository));
        ragService = new RagService(
                repository,
                domainScopedRetrievalService,
                ragRetrievalExecutionService,
                ragTracePersistenceService,
                embeddingService,
                cohereRerankService,
                rewriteCandidateService,
                chatAnswerService,
                chatRuntimeConfigService,
                new QueryStrategyRouter(),
                agenticRetrievalService,
                objectMapper
        );
    }

    @Test
    void routerReadinessFallbackUsesRawOnlyAndSkipsRewriteWork() {
        UUID onlineQueryId = UUID.randomUUID();
        ChatRuntimeDtos.ChatRuntimeConfigResponse config = config("selective_rewrite", true, false);
        when(chatRuntimeConfigService.getConfig(domainId)).thenReturn(config);
        when(chatRuntimeConfigService.getReadiness(domainId)).thenReturn(readiness(false, "selected snapshot has no built synthetic memory"));
        when(repository.createOnlineQuery(eq(domainId), any(), anyString(), any(), anyString(), anyDouble(), any()))
                .thenReturn(onlineQueryId);
        when(embeddingService.embed(anyString())).thenReturn(List.of(1.0d, 0.0d));
        when(embeddingService.toHalfvecLiteral(anyList())).thenReturn("[1.000000,0.000000]");
        List<RagRepository.RetrievalDoc> docs = List.of(new RagRepository.RetrievalDoc(
                "doc-1",
                "chunk-1",
                "Spring Security filter chain reference",
                0.8d
        ));
        when(repository.findTopChunksByEmbedding(anyString(), anyInt(), eq(domainId))).thenReturn(docs);
        when(cohereRerankService.rerank(anyString(), anyList(), anyInt())).thenAnswer(invocation -> invocation.getArgument(1));
        when(cohereRerankService.modelName()).thenReturn("local-rerank-fallback");
        when(chatAnswerService.generateAnswer(anyString(), anyString(), anyString(), anyList()))
                .thenReturn(new ChatAnswerService.GeneratedAnswer(
                        "answer",
                        List.of("doc-1"),
                        List.of("chunk-1"),
                        "test-answer-model"
                ));

        RagDtos.AskResponse response = ragService.ask(new RagDtos.AskRequest(
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
        verify(repository, never()).findMemoryTopN(anyString(), anyInt(), anyString(), eq(domainId), anyList(), anyList(), anyList());
        verify(rewriteCandidateService, never()).buildCandidates(anyString(), any(), anyList(), anyInt(), anyString(), anyBoolean(), any());
        verify(repository, never()).createRewriteCandidate(any(), anyInt(), anyString(), anyString(), any(), any(), anyDouble(), any());
        verify(ragTracePersistenceService, never()).createRewriteCandidateTrace(any());
        verify(ragTracePersistenceService, never()).markRewriteCandidateAdopted(any());
        verify(agenticRetrievalService, never()).execute(any());
        verify(ragTracePersistenceService, never()).persistRawOnlyTrace(any());

        ArgumentCaptor<JsonNode> metadataCaptor = ArgumentCaptor.forClass(JsonNode.class);
        verify(repository).mergeOnlineQueryMetadata(eq(onlineQueryId), metadataCaptor.capture());
        JsonNode router = metadataCaptor.getValue().path("router");
        assertThat(router.path("enabled").asBoolean()).isTrue();
        assertThat(router.path("strategy").asText()).isEqualTo("RAW_ONLY");
        assertThat(router.path("fallbackApplied").asBoolean()).isTrue();
        assertThat(router.path("fallbackReason").asText()).contains("synthetic memory");
    }

    @Test
    void routerDisabledKeepsExistingStrictReadinessFailure() {
        ChatRuntimeDtos.ChatRuntimeConfigResponse config = config("selective_rewrite", false, false);
        when(chatRuntimeConfigService.getConfig(domainId)).thenReturn(config);
        when(chatRuntimeConfigService.getReadiness(domainId)).thenReturn(readiness(false, "selected snapshot has no built synthetic memory"));

        assertThatThrownBy(() -> ragService.ask(new RagDtos.AskRequest(
                "스프링 시큐리티 필터 순서",
                domainId,
                "session-1",
                objectMapper.createObjectNode(),
                null
        ))).hasMessageContaining("synthetic memory");

        verify(repository, never()).createOnlineQuery(any(), any(), anyString(), any(), anyString(), anyDouble(), any());
        verify(rewriteCandidateService, never()).buildCandidates(anyString(), any(), anyList(), anyInt(), anyString(), anyBoolean(), any());
    }

    @Test
    void rawOnlyAskKeepsOriginalQueryAndPersistsAnswerTrace() {
        UUID onlineQueryId = UUID.fromString("44444444-4444-4444-4444-444444444444");
        String query = "FilterChainProxy order";
        ChatRuntimeDtos.ChatRuntimeConfigResponse config = config("raw_only", true, false);
        List<RagRepository.RetrievalDoc> rawDocs = docs("raw-doc", "raw-chunk", 0.82d);
        stubCommonAskDependencies(config, onlineQueryId);
        when(repository.findTopChunksByEmbedding(anyString(), anyInt(), eq(domainId))).thenReturn(rawDocs);
        when(cohereRerankService.rerank(anyString(), anyList(), anyInt())).thenAnswer(invocation -> invocation.getArgument(1));
        when(cohereRerankService.modelName()).thenReturn("local-rerank-fallback");
        stubRewriteLog(UUID.fromString("55555555-5555-5555-5555-555555555555"));
        when(chatAnswerService.generateAnswer(anyString(), anyString(), anyString(), anyList()))
                .thenReturn(generatedAnswer("raw answer"));

        RagDtos.AskResponse response = ragService.ask(request(query));

        assertThat(response.finalQueryUsed()).isEqualTo(query);
        assertThat(response.rawQuery()).isEqualTo(query);
        assertThat(response.rewriteApplied()).isFalse();
        assertThat(response.answer()).isEqualTo("raw answer");
        assertThat(response.answerModel()).isEqualTo("test-answer-model");

        verify(repository).createOnlineQuery(eq(domainId), eq("session-1"), eq(query), any(), eq("raw_only"), eq(0.05d), any());
        verify(repository, never()).findMemoryTopN(anyString(), anyInt(), anyString(), any(), anyList(), anyList(), anyList());
        verify(rewriteCandidateService, never()).buildCandidates(anyString(), any(), anyList(), anyInt(), anyString(), anyBoolean(), any());
        verify(agenticRetrievalService, never()).execute(any());
        verify(repository).findTopChunksByEmbedding(anyString(), anyInt(), eq(domainId));
        verify(chatAnswerService).generateAnswer(eq(query), eq(query), eq("Spring"), anyList());
        ArgumentCaptor<RagTracePersistenceService.RawOnlyTracePersistenceRequest> traceCaptor =
                ArgumentCaptor.forClass(RagTracePersistenceService.RawOnlyTracePersistenceRequest.class);
        verify(ragTracePersistenceService, times(2)).persistRawOnlyTrace(traceCaptor.capture());
        assertThat(traceCaptor.getAllValues())
                .extracting(RagTracePersistenceService.RawOnlyTracePersistenceRequest::writeScope)
                .containsExactly(
                        RagTracePersistenceService.RawOnlyTraceWriteScope.RETRIEVAL,
                        RagTracePersistenceService.RawOnlyTraceWriteScope.RERANK
                );
        assertThat(traceCaptor.getAllValues())
                .allSatisfy(trace -> {
                    assertThat(trace.onlineQueryId()).isEqualTo(onlineQueryId);
                    assertThat(trace.mode()).isEqualTo("raw_only");
                    assertThat(trace.rawQuery()).isEqualTo(query);
                    assertThat(trace.finalQuery()).isEqualTo(query);
                });
        verify(repository).insertRetrievalResults(eq(onlineQueryId), isNull(), eq("raw"), anyList(), eq("raw_only"), eq("local:dense_only:hash-embedding-v1"), any());
        verify(repository).insertRerankResults(eq(onlineQueryId), isNull(), anyList(), eq("local-rerank-fallback"));
        verify(ragTracePersistenceService, never()).persistRewriteCandidateTrace(any());
        verify(ragTracePersistenceService, never()).createRewriteCandidateTrace(any());
        verify(ragTracePersistenceService, never()).markRewriteCandidateAdopted(any());
        verify(repository).insertAnswer(eq(onlineQueryId), eq("raw answer"), any(), any(), eq("test-answer-model"), any());
        verify(repository).upsertOnlineQueryDecision(eq(onlineQueryId), eq(query), eq(false), any(), anyDouble(), isNull(), eq("mode_raw_only"), eq("query_router_strategy=raw_only"), any());
        verify(repository).mergeOnlineQueryMetadata(eq(onlineQueryId), any());
        verify(repository).createOnlineRewriteLog(eq(onlineQueryId), isNull(), eq(query), eq(query), eq("raw_only"), any(), any(), eq(false), eq("full_gating"), eq(false), eq(false), eq(false), anyDouble(), anyDouble(), anyDouble(), eq("mode_raw_only"), eq("query_router_strategy=raw_only"), any());
        verify(repository, never()).insertMemoryRetrievalLog(any(), any(), anyInt(), any(), any());
        verify(repository, never()).insertRewriteCandidateLog(any(), any(), any(), anyInt(), anyString(), anyString(), any(), anyBoolean(), any(), any(), any(), any());
    }

    @Test
    void selectiveRewriteAskAdoptsCandidateAndPersistsRewriteTrace() {
        UUID onlineQueryId = UUID.fromString("44444444-4444-4444-4444-444444444445");
        UUID rewriteCandidateId = UUID.fromString("66666666-6666-6666-6666-666666666666");
        UUID rewriteLogId = UUID.fromString("77777777-7777-7777-7777-777777777777");
        String query = "spring filter order";
        String rewrittenQuery = "FilterChainProxy SecurityFilterChain order";
        ChatRuntimeDtos.ChatRuntimeConfigResponse config = config("selective_rewrite", false, false);
        List<RagRepository.MemoryCandidate> memories = memories();
        List<RagRepository.RetrievalDoc> rawDocs = docs("raw-doc", "raw-chunk", 0.10d);
        List<RagRepository.RetrievalDoc> rewriteDocs = docs("rewrite-doc", "rewrite-chunk", 0.95d);
        stubCommonAskDependencies(config, onlineQueryId);
        when(repository.findMemoryTopN(anyString(), anyInt(), eq("full_gating"), eq(domainId), eq(List.of("C")), eq(List.of(sourceGatingRunId)), eq(List.of(sourceGatingBatchId))))
                .thenReturn(memories);
        when(repository.findTopChunksByEmbedding(anyString(), anyInt(), eq(domainId))).thenReturn(rawDocs, rewriteDocs);
        when(cohereRerankService.rerank(anyString(), anyList(), anyInt())).thenAnswer(invocation -> invocation.getArgument(1));
        when(cohereRerankService.modelName()).thenReturn("local-rerank-fallback");
        when(rewriteCandidateService.buildCandidates(eq(query), any(), eq(memories), eq(2), eq("compact_anchor"), eq(false), any()))
                .thenReturn(List.of(new RewriteCandidateService.CandidateTemplate("candidate-1", rewrittenQuery)));
        when(repository.createRewriteCandidate(eq(onlineQueryId), eq(1), eq("candidate-1"), eq(rewrittenQuery), any(), any(), anyDouble(), any()))
                .thenReturn(rewriteCandidateId);
        stubRewriteLog(rewriteLogId);
        when(chatAnswerService.generateAnswer(anyString(), anyString(), anyString(), anyList()))
                .thenReturn(generatedAnswer("rewrite answer"));

        RagDtos.AskResponse response = ragService.ask(request(query));

        assertThat(response.finalQueryUsed()).isEqualTo(rewrittenQuery);
        assertThat(response.rewriteApplied()).isTrue();
        assertThat(response.answer()).isEqualTo("rewrite answer");
        assertThat(response.answerModel()).isEqualTo("test-answer-model");
        assertThat(response.rewriteCandidates()).hasSize(1);
        assertThat(response.rewriteCandidates().getFirst().adopted()).isTrue();

        verify(repository).findMemoryTopN(anyString(), anyInt(), eq("full_gating"), eq(domainId), eq(List.of("C")), eq(List.of(sourceGatingRunId)), eq(List.of(sourceGatingBatchId)));
        verify(repository, times(2)).findTopChunksByEmbedding(anyString(), anyInt(), eq(domainId));
        ArgumentCaptor<RagRetrievalExecutionService.SelectiveRewriteExecutionRequest> executionRequestCaptor =
                ArgumentCaptor.forClass(RagRetrievalExecutionService.SelectiveRewriteExecutionRequest.class);
        verify(ragRetrievalExecutionService).executeSelectiveRewrite(executionRequestCaptor.capture());
        assertThat(executionRequestCaptor.getValue().domainId()).isEqualTo(domainId);
        assertThat(executionRequestCaptor.getValue().memoryCandidates()).isEqualTo(memories);
        assertThat(executionRequestCaptor.getValue().rewriteQueryProfile()).isEqualTo("compact_anchor");
        verify(ragRetrievalExecutionService, never()).executeAnchorAwareRewrite(any());
        verify(rewriteCandidateService).buildCandidates(eq(query), any(), eq(memories), eq(2), eq("compact_anchor"), eq(false), any());
        verify(repository).createRewriteCandidate(eq(onlineQueryId), eq(1), eq("candidate-1"), eq(rewrittenQuery), any(), any(), anyDouble(), any());
        verify(repository).markRewriteCandidateAdopted(eq(rewriteCandidateId), eq(true), isNull());
        verifyCreateRewriteCandidatePersistence(
                onlineQueryId,
                RagRetrievalExecutionService.NonAgenticExecutionKind.SELECTIVE_REWRITE,
                "candidate-1",
                rewrittenQuery
        );
        verifyRewriteCandidateAdoptionPersistence(
                onlineQueryId,
                rewriteCandidateId,
                RagRetrievalExecutionService.NonAgenticExecutionKind.SELECTIVE_REWRITE,
                true,
                null
        );
        verify(repository).insertRetrievalResults(eq(onlineQueryId), isNull(), eq("raw"), anyList(), eq("selective_rewrite"), eq("local:dense_only:hash-embedding-v1"), any());
        verify(repository).insertRetrievalResults(eq(onlineQueryId), eq(rewriteCandidateId), eq("rewrite_candidate"), anyList(), eq("selective_rewrite"), eq("local:dense_only:hash-embedding-v1"), any());
        verify(repository).insertRerankResults(eq(onlineQueryId), eq(rewriteCandidateId), anyList(), eq("local-rerank-fallback"));
        verifyRewriteCandidateTracePersistence(
                onlineQueryId,
                rewriteCandidateId,
                RagRetrievalExecutionService.NonAgenticExecutionKind.SELECTIVE_REWRITE
        );
        verify(ragTracePersistenceService, never()).persistRawOnlyTrace(any());
        verify(chatAnswerService).generateAnswer(eq(query), eq(rewrittenQuery), eq("Spring"), anyList());
        verify(repository).insertAnswer(eq(onlineQueryId), eq("rewrite answer"), any(), any(), eq("test-answer-model"), any());
        verify(repository).createOnlineRewriteLog(eq(onlineQueryId), isNull(), eq(query), eq(rewrittenQuery), eq("selective_rewrite"), any(), any(), eq(true), eq("full_gating"), eq(true), eq(true), eq(false), anyDouble(), anyDouble(), anyDouble(), eq("delta_above_threshold"), isNull(), any());
        verify(repository).insertMemoryRetrievalLog(eq(rewriteLogId), eq(onlineQueryId), eq(1), eq(memories.getFirst()), any());
        verify(repository).insertRewriteCandidateLog(eq(rewriteLogId), eq(onlineQueryId), eq(rewriteCandidateId), eq(1), eq("candidate-1"), eq(rewrittenQuery), anyDouble(), eq(true), isNull(), any(), any(), any());
        verify(agenticRetrievalService, never()).execute(any());
    }

    @Test
    void routerSelectedSelectiveRewriteUsesExecutionServiceAndKeepsPersistenceWrites() {
        UUID onlineQueryId = UUID.fromString("44444444-4444-4444-4444-444444444448");
        UUID rewriteCandidateId = UUID.fromString("66666666-6666-6666-6666-666666666668");
        UUID rewriteLogId = UUID.fromString("77777777-7777-7777-7777-777777777780");
        String query = "spring";
        String rewrittenQuery = "FilterChainProxy SecurityFilterChain order";
        ChatRuntimeDtos.ChatRuntimeConfigResponse config = config("selective_rewrite", true, false);
        List<RagRepository.MemoryCandidate> memories = memories();
        List<RagRepository.RetrievalDoc> rawDocs = docs("raw-doc", "raw-chunk", 0.10d);
        List<RagRepository.RetrievalDoc> rewriteDocs = docs("rewrite-doc", "rewrite-chunk", 0.95d);
        stubCommonAskDependencies(config, onlineQueryId);
        when(repository.findMemoryTopN(anyString(), anyInt(), eq("full_gating"), eq(domainId), eq(List.of("C")), eq(List.of(sourceGatingRunId)), eq(List.of(sourceGatingBatchId))))
                .thenReturn(memories);
        when(repository.findTopChunksByEmbedding(anyString(), anyInt(), eq(domainId))).thenReturn(rawDocs, rewriteDocs);
        when(cohereRerankService.rerank(anyString(), anyList(), anyInt())).thenAnswer(invocation -> invocation.getArgument(1));
        when(cohereRerankService.modelName()).thenReturn("local-rerank-fallback");
        when(rewriteCandidateService.buildCandidates(eq(query), any(), eq(memories), eq(2), eq("compact_anchor"), eq(false), any()))
                .thenReturn(List.of(new RewriteCandidateService.CandidateTemplate("candidate-1", rewrittenQuery)));
        when(repository.createRewriteCandidate(eq(onlineQueryId), eq(1), eq("candidate-1"), eq(rewrittenQuery), any(), any(), anyDouble(), any()))
                .thenReturn(rewriteCandidateId);
        stubRewriteLog(rewriteLogId);
        when(chatAnswerService.generateAnswer(anyString(), anyString(), anyString(), anyList()))
                .thenReturn(generatedAnswer("router selective answer"));

        RagDtos.AskResponse response = ragService.ask(request(query));

        assertThat(response.finalQueryUsed()).isEqualTo(rewrittenQuery);
        assertThat(response.rewriteApplied()).isTrue();
        assertThat(response.answer()).isEqualTo("router selective answer");
        assertThat(response.answerModel()).isEqualTo("test-answer-model");
        assertThat(response.rewriteCandidates()).hasSize(1);
        assertThat(response.rewriteCandidates().getFirst().adopted()).isTrue();

        ArgumentCaptor<RagRetrievalExecutionService.SelectiveRewriteExecutionRequest> executionRequestCaptor =
                ArgumentCaptor.forClass(RagRetrievalExecutionService.SelectiveRewriteExecutionRequest.class);
        verify(ragRetrievalExecutionService).executeSelectiveRewrite(executionRequestCaptor.capture());
        assertThat(executionRequestCaptor.getValue().domainId()).isEqualTo(domainId);
        assertThat(executionRequestCaptor.getValue().memoryCandidates()).isEqualTo(memories);
        assertThat(executionRequestCaptor.getValue().rewriteQueryProfile()).isEqualTo("compact_anchor");
        verify(ragRetrievalExecutionService, never()).executeAnchorAwareRewrite(any());
        verify(rewriteCandidateService).buildCandidates(eq(query), any(), eq(memories), eq(2), eq("compact_anchor"), eq(false), any());
        verify(repository).createRewriteCandidate(eq(onlineQueryId), eq(1), eq("candidate-1"), eq(rewrittenQuery), any(), any(), anyDouble(), any());
        verify(repository).markRewriteCandidateAdopted(eq(rewriteCandidateId), eq(true), isNull());
        verifyCreateRewriteCandidatePersistence(
                onlineQueryId,
                RagRetrievalExecutionService.NonAgenticExecutionKind.SELECTIVE_REWRITE,
                "candidate-1",
                rewrittenQuery
        );
        verifyRewriteCandidateAdoptionPersistence(
                onlineQueryId,
                rewriteCandidateId,
                RagRetrievalExecutionService.NonAgenticExecutionKind.SELECTIVE_REWRITE,
                true,
                null
        );
        verify(repository).insertRetrievalResults(eq(onlineQueryId), isNull(), eq("raw"), anyList(), eq("selective_rewrite"), eq("local:dense_only:hash-embedding-v1"), any());
        verify(repository).insertRetrievalResults(eq(onlineQueryId), eq(rewriteCandidateId), eq("rewrite_candidate"), anyList(), eq("selective_rewrite"), eq("local:dense_only:hash-embedding-v1"), any());
        verify(repository).insertRerankResults(eq(onlineQueryId), eq(rewriteCandidateId), anyList(), eq("local-rerank-fallback"));
        verifyRewriteCandidateTracePersistence(
                onlineQueryId,
                rewriteCandidateId,
                RagRetrievalExecutionService.NonAgenticExecutionKind.SELECTIVE_REWRITE
        );
        verify(chatAnswerService).generateAnswer(eq(query), eq(rewrittenQuery), eq("Spring"), anyList());
        verify(repository).insertAnswer(eq(onlineQueryId), eq("router selective answer"), any(), any(), eq("test-answer-model"), any());
        verify(repository).createOnlineRewriteLog(eq(onlineQueryId), isNull(), eq(query), eq(rewrittenQuery), eq("selective_rewrite"), any(), any(), eq(true), eq("full_gating"), eq(true), eq(true), eq(false), anyDouble(), anyDouble(), anyDouble(), eq("delta_above_threshold"), isNull(), any());
        verify(repository).insertMemoryRetrievalLog(eq(rewriteLogId), eq(onlineQueryId), eq(1), eq(memories.getFirst()), any());
        verify(repository).insertRewriteCandidateLog(eq(rewriteLogId), eq(onlineQueryId), eq(rewriteCandidateId), eq(1), eq("candidate-1"), eq(rewrittenQuery), anyDouble(), eq(true), isNull(), any(), any(), any());
        verify(agenticRetrievalService, never()).execute(any());

        ArgumentCaptor<JsonNode> metadataCaptor = ArgumentCaptor.forClass(JsonNode.class);
        verify(repository).mergeOnlineQueryMetadata(eq(onlineQueryId), metadataCaptor.capture());
        JsonNode router = metadataCaptor.getValue().path("router");
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
        ChatRuntimeDtos.ChatRuntimeConfigResponse config = config("selective_rewrite", true, true);
        List<RagRepository.MemoryCandidate> memories = memories();
        List<RagRepository.RetrievalDoc> rawDocs = docs("raw-doc", "raw-chunk", 0.25d);
        List<RagRepository.RetrievalDoc> rewriteDocs = docs("rewrite-doc", "rewrite-chunk", 0.90d);
        stubCommonAskDependencies(config, onlineQueryId);
        when(repository.findMemoryTopN(anyString(), anyInt(), eq("full_gating"), eq(domainId), eq(List.of("C")), eq(List.of(sourceGatingRunId)), eq(List.of(sourceGatingBatchId))))
                .thenReturn(memories);
        when(repository.findTopChunksByEmbedding(anyString(), anyInt(), eq(domainId))).thenReturn(rawDocs, rewriteDocs);
        when(cohereRerankService.rerank(anyString(), anyList(), anyInt())).thenAnswer(invocation -> invocation.getArgument(1));
        when(cohereRerankService.modelName()).thenReturn("local-rerank-fallback");
        when(rewriteCandidateService.buildCandidates(eq(query), any(), eq(memories), eq(2), eq("compact_anchor"), eq(true), any()))
                .thenReturn(List.of(new RewriteCandidateService.CandidateTemplate("anchor-aware", rewrittenQuery)));
        when(repository.createRewriteCandidate(eq(onlineQueryId), eq(1), eq("anchor-aware"), eq(rewrittenQuery), any(), any(), anyDouble(), any()))
                .thenReturn(rewriteCandidateId);
        stubRewriteLog(rewriteLogId);
        when(chatAnswerService.generateAnswer(anyString(), anyString(), anyString(), anyList()))
                .thenReturn(generatedAnswer("anchor answer"));

        RagDtos.AskResponse response = ragService.ask(request(query));

        assertThat(response.answer()).isEqualTo("anchor answer");
        assertThat(response.answerModel()).isEqualTo("test-answer-model");
        verify(ragRetrievalExecutionService, never()).executeSelectiveRewrite(any());
        ArgumentCaptor<RagRetrievalExecutionService.AnchorAwareRewriteExecutionRequest> executionRequestCaptor =
                ArgumentCaptor.forClass(RagRetrievalExecutionService.AnchorAwareRewriteExecutionRequest.class);
        verify(ragRetrievalExecutionService).executeAnchorAwareRewrite(executionRequestCaptor.capture());
        assertThat(executionRequestCaptor.getValue().domainId()).isEqualTo(domainId);
        assertThat(executionRequestCaptor.getValue().memoryCandidates()).isEqualTo(memories);
        assertThat(executionRequestCaptor.getValue().rewriteQueryProfile()).isEqualTo("compact_anchor");
        verify(rewriteCandidateService).buildCandidates(eq(query), any(), eq(memories), eq(2), eq("compact_anchor"), eq(true), any());
        verify(repository).createRewriteCandidate(eq(onlineQueryId), eq(1), eq("anchor-aware"), eq(rewrittenQuery), any(), any(), anyDouble(), any());
        verify(repository).markRewriteCandidateAdopted(eq(rewriteCandidateId), eq(true), isNull());
        verifyCreateRewriteCandidatePersistence(
                onlineQueryId,
                RagRetrievalExecutionService.NonAgenticExecutionKind.ANCHOR_AWARE_REWRITE,
                "anchor-aware",
                rewrittenQuery
        );
        verifyRewriteCandidateAdoptionPersistence(
                onlineQueryId,
                rewriteCandidateId,
                RagRetrievalExecutionService.NonAgenticExecutionKind.ANCHOR_AWARE_REWRITE,
                true,
                null
        );
        verify(repository).insertRetrievalResults(eq(onlineQueryId), isNull(), eq("raw"), anyList(), eq("selective_rewrite"), eq("local:dense_only:hash-embedding-v1"), any());
        verify(repository).insertRetrievalResults(eq(onlineQueryId), eq(rewriteCandidateId), eq("rewrite_candidate"), anyList(), eq("selective_rewrite"), eq("local:dense_only:hash-embedding-v1"), any());
        verify(repository, times(2)).findTopChunksByEmbedding(anyString(), anyInt(), eq(domainId));
        verify(repository).insertRerankResults(eq(onlineQueryId), eq(rewriteCandidateId), anyList(), eq("local-rerank-fallback"));
        verifyRewriteCandidateTracePersistence(
                onlineQueryId,
                rewriteCandidateId,
                RagRetrievalExecutionService.NonAgenticExecutionKind.ANCHOR_AWARE_REWRITE
        );
        verify(ragTracePersistenceService, never()).persistRawOnlyTrace(any());
        verify(chatAnswerService).generateAnswer(eq(query), eq(rewrittenQuery), eq("Spring"), anyList());
        verify(repository).insertAnswer(eq(onlineQueryId), eq("anchor answer"), any(), any(), eq("test-answer-model"), any());
        verify(repository).createOnlineRewriteLog(eq(onlineQueryId), isNull(), eq(query), eq(rewrittenQuery), eq("selective_rewrite"), any(), any(), eq(true), eq("full_gating"), eq(true), eq(true), eq(false), anyDouble(), anyDouble(), anyDouble(), eq("delta_above_threshold"), isNull(), any());
        verify(repository).insertMemoryRetrievalLog(eq(rewriteLogId), eq(onlineQueryId), eq(1), eq(memories.getFirst()), any());
        verify(repository).insertRewriteCandidateLog(eq(rewriteLogId), eq(onlineQueryId), eq(rewriteCandidateId), eq(1), eq("anchor-aware"), eq(rewrittenQuery), anyDouble(), eq(true), isNull(), any(), any(), any());

        ArgumentCaptor<JsonNode> metadataCaptor = ArgumentCaptor.forClass(JsonNode.class);
        verify(repository).mergeOnlineQueryMetadata(eq(onlineQueryId), metadataCaptor.capture());
        JsonNode router = metadataCaptor.getValue().path("router");
        assertThat(router.path("strategy").asText()).isEqualTo("ANCHOR_AWARE_REWRITE");
        assertThat(router.path("reason").asText()).isEqualTo("anchor_injection_enabled_and_technical_anchor_detected");
        assertThat(router.path("anchorInjectionEnabled").asBoolean()).isTrue();
        assertThat(router.path("metadata").path("containsTechnicalAnchor").asBoolean()).isTrue();
    }

    @Test
    void agenticEnabledBranchesToAgenticExecutionAndReturnsMetadata() {
        UUID onlineQueryId = UUID.fromString("44444444-4444-4444-4444-444444444447");
        String query = "spring security and mvc filter order";
        ChatRuntimeDtos.ChatRuntimeConfigResponse config = config("selective_rewrite", false, false, true);
        List<RagRepository.MemoryCandidate> memories = memories();
        List<RagRepository.RetrievalDoc> mergedDocs = docs("agentic-doc", "agentic-chunk", 0.88d);
        RagDtos.AgenticQueryPlan plan = new RagDtos.AgenticQueryPlan(
                query,
                domainId,
                "spring",
                "Spring",
                3,
                List.of(new RagDtos.AgenticSubquery(1, "FilterChainProxy order", "filter order", 1.0d, objectMapper.createObjectNode())),
                "test-planner",
                false,
                null,
                objectMapper.createObjectNode()
        );
        RagDtos.SubqueryRetrievalTrace trace = new RagDtos.SubqueryRetrievalTrace(
                1,
                "FilterChainProxy order",
                "FilterChainProxy order",
                false,
                "RAW_ONLY",
                "raw_only",
                "mode=raw_only",
                List.of(new RagDtos.ScoredDocumentDto("agentic-doc", "agentic-chunk", "agentic text", 0.88d)),
                List.of(),
                objectMapper.createArrayNode(),
                3L,
                objectMapper.createObjectNode()
        );
        when(agenticRetrievalService.execute(any()))
                .thenReturn(new AgenticRetrievalService.AgenticExecutionResult(
                        plan,
                        List.of(trace),
                        List.of(),
                        mergedDocs,
                        false,
                        "agentic_multi_query_rrf",
                        null,
                        5L,
                        8L,
                        objectMapper.createObjectNode().put("subquery_count", 1)
                ));
        stubCommonAskDependencies(config, onlineQueryId);
        when(repository.findMemoryTopN(anyString(), anyInt(), eq("full_gating"), eq(domainId), eq(List.of("C")), eq(List.of(sourceGatingRunId)), eq(List.of(sourceGatingBatchId))))
                .thenReturn(memories);
        when(chatAnswerService.generateAnswer(anyString(), anyString(), anyString(), anyList()))
                .thenReturn(generatedAnswer("agentic answer"));
        stubRewriteLog(UUID.fromString("77777777-7777-7777-7777-777777777779"));

        RagDtos.AskResponse response = ragService.ask(request(query));

        assertThat(response.answer()).isEqualTo("agentic answer");
        assertThat(response.answerModel()).isEqualTo("test-answer-model");
        assertThat(response.agenticMetadata()).isNotNull();
        assertThat(response.agenticMetadata().plan().domainId()).isEqualTo(domainId);
        assertThat(response.agenticMetadata().mergedDocs()).extracting(RagDtos.ScoredDocumentDto::chunkId)
                .containsExactly("agentic-chunk");

        ArgumentCaptor<AgenticRetrievalService.AgenticExecutionRequest> requestCaptor =
                ArgumentCaptor.forClass(AgenticRetrievalService.AgenticExecutionRequest.class);
        verify(agenticRetrievalService).execute(requestCaptor.capture());
        AgenticRetrievalService.AgenticExecutionRequest executionRequest = requestCaptor.getValue();
        assertThat(executionRequest.onlineQueryId()).isEqualTo(onlineQueryId);
        assertThat(executionRequest.config().domainId()).isEqualTo(domainId);
        assertThat(executionRequest.plannerMemoryHints()).isEqualTo(memories);
        assertThat(executionRequest.memoryPreset()).isEqualTo("full_gating");

        verify(repository).findMemoryTopN(anyString(), anyInt(), eq("full_gating"), eq(domainId), eq(List.of("C")), eq(List.of(sourceGatingRunId)), eq(List.of(sourceGatingBatchId)));
        verify(repository, never()).findTopChunksByEmbedding(anyString(), anyInt(), any());
        verify(rewriteCandidateService, never()).buildCandidates(anyString(), any(), anyList(), anyInt(), anyString(), anyBoolean(), any());
        verify(ragTracePersistenceService, never()).persistRewriteCandidateTrace(any());
        verify(ragTracePersistenceService, never()).createRewriteCandidateTrace(any());
        verify(ragTracePersistenceService, never()).markRewriteCandidateAdopted(any());
        verify(repository).insertRerankResults(eq(onlineQueryId), isNull(), eq(mergedDocs), eq("agentic-rrf"));
        verify(chatAnswerService).generateAnswer(eq(query), eq(query), eq("Spring"), eq(mergedDocs));
        verify(repository).insertAnswer(eq(onlineQueryId), eq("agentic answer"), any(), any(), eq("test-answer-model"), any());
        verify(repository).mergeOnlineQueryMetadata(eq(onlineQueryId), any());
    }

    private ChatRuntimeDtos.ChatRuntimeConfigResponse config(String mode, boolean routerEnabled, boolean anchorInjectionEnabled) {
        return config(mode, routerEnabled, anchorInjectionEnabled, false);
    }

    private ChatRuntimeDtos.ChatRuntimeConfigResponse config(String mode, boolean routerEnabled, boolean anchorInjectionEnabled, boolean agenticEnabled) {
        ObjectNode metadata = objectMapper.createObjectNode();
        if (routerEnabled) {
            metadata.put("routerEnabled", true);
        }
        if (agenticEnabled) {
            metadata.put("agenticMultiQueryEnabled", true);
            metadata.put("maxSubqueries", 3);
            metadata.put("rrfK", 60);
        }
        return new ChatRuntimeDtos.ChatRuntimeConfigResponse(
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

    private ChatRuntimeDtos.ChatDomainReadinessResponse readiness(boolean ready, String... reasons) {
        return new ChatRuntimeDtos.ChatDomainReadinessResponse(
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
            RagRetrievalExecutionService.NonAgenticExecutionKind executionKind
    ) {
        ArgumentCaptor<RagTracePersistenceService.RewriteCandidateTracePersistenceRequest> traceCaptor =
                ArgumentCaptor.forClass(RagTracePersistenceService.RewriteCandidateTracePersistenceRequest.class);
        verify(ragTracePersistenceService, times(2)).persistRewriteCandidateTrace(traceCaptor.capture());
        assertThat(traceCaptor.getAllValues())
                .extracting(RagTracePersistenceService.RewriteCandidateTracePersistenceRequest::writeScope)
                .containsExactly(
                        RagTracePersistenceService.RewriteCandidateTraceWriteScope.CANDIDATE_RETRIEVAL,
                        RagTracePersistenceService.RewriteCandidateTraceWriteScope.CANDIDATE_RERANK
                );
        assertThat(traceCaptor.getAllValues())
                .allSatisfy(trace -> {
                    assertThat(trace.onlineQueryId()).isEqualTo(onlineQueryId);
                    assertThat(trace.rewriteCandidateId()).isEqualTo(rewriteCandidateId);
                    assertThat(trace.executionKind()).isEqualTo(executionKind);
                    assertThat(trace.persistPolicy()).isEqualTo(io.queryforge.backend.rag.model.RagPersistPolicy.ONLINE_QUERY);
                    assertThat(trace.mode()).isEqualTo("selective_rewrite");
                });
        assertThat(traceCaptor.getAllValues().get(0).retrievedDocs()).isNotEmpty();
        assertThat(traceCaptor.getAllValues().get(1).rerankedDocs()).isNotEmpty();
    }

    private void verifyCreateRewriteCandidatePersistence(
            UUID onlineQueryId,
            RagRetrievalExecutionService.NonAgenticExecutionKind executionKind,
            String candidateLabel,
            String candidateQuery
    ) {
        ArgumentCaptor<RagTracePersistenceService.CreateRewriteCandidateTracePersistenceRequest> createCaptor =
                ArgumentCaptor.forClass(RagTracePersistenceService.CreateRewriteCandidateTracePersistenceRequest.class);
        verify(ragTracePersistenceService).createRewriteCandidateTrace(createCaptor.capture());
        RagTracePersistenceService.CreateRewriteCandidateTracePersistenceRequest request = createCaptor.getValue();
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
            RagRetrievalExecutionService.NonAgenticExecutionKind executionKind,
            boolean adopted,
            String rejectedReason
    ) {
        ArgumentCaptor<RagTracePersistenceService.RewriteCandidateAdoptionPersistenceRequest> adoptionCaptor =
                ArgumentCaptor.forClass(RagTracePersistenceService.RewriteCandidateAdoptionPersistenceRequest.class);
        verify(ragTracePersistenceService).markRewriteCandidateAdopted(adoptionCaptor.capture());
        RagTracePersistenceService.RewriteCandidateAdoptionPersistenceRequest request = adoptionCaptor.getValue();
        assertThat(request.persistPolicy()).isEqualTo(RagPersistPolicy.ONLINE_QUERY);
        assertThat(request.onlineQueryId()).isEqualTo(onlineQueryId);
        assertThat(request.rewriteCandidateId()).isEqualTo(rewriteCandidateId);
        assertThat(request.executionKind()).isEqualTo(executionKind);
        assertThat(request.adopted()).isEqualTo(adopted);
        assertThat(request.rejectedReason()).isEqualTo(rejectedReason);
    }

    private void stubCommonAskDependencies(ChatRuntimeDtos.ChatRuntimeConfigResponse config, UUID onlineQueryId) {
        when(chatRuntimeConfigService.getConfig(domainId)).thenReturn(config);
        when(chatRuntimeConfigService.getReadiness(domainId)).thenReturn(readiness(true));
        when(repository.createOnlineQuery(eq(domainId), any(), anyString(), any(), anyString(), anyDouble(), any()))
                .thenReturn(onlineQueryId);
        when(embeddingService.embed(anyString())).thenReturn(List.of(1.0d, 0.0d));
        when(embeddingService.toHalfvecLiteral(anyList())).thenReturn("[1.000000,0.000000]");
    }

    private void stubRewriteLog(UUID rewriteLogId) {
        when(repository.createOnlineRewriteLog(
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

    private RagDtos.AskRequest request(String query) {
        return new RagDtos.AskRequest(
                query,
                domainId,
                "session-1",
                objectMapper.createObjectNode(),
                null
        );
    }

    private ChatAnswerService.GeneratedAnswer generatedAnswer(String answer) {
        return new ChatAnswerService.GeneratedAnswer(
                answer,
                List.of("doc-1"),
                List.of("chunk-1"),
                "test-answer-model"
        );
    }

    private List<RagRepository.RetrievalDoc> docs(String documentId, String chunkId, double score) {
        return List.of(new RagRepository.RetrievalDoc(
                documentId,
                chunkId,
                "Spring Security FilterChainProxy documentation",
                score
        ));
    }

    private List<RagRepository.MemoryCandidate> memories() {
        return List.of(new RagRepository.MemoryCandidate(
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
