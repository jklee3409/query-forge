package io.queryforge.backend.rag.service;

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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AgenticRetrievalServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final UUID domainId = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private final UUID onlineQueryId = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");

    @Mock
    private RagRepository repository;
    @Mock
    private RagTracePersistenceService ragTracePersistenceService;
    @Mock
    private HashEmbeddingService embeddingService;
    @Mock
    private DenseEmbeddingService denseEmbeddingService;
    @Mock
    private CohereRerankService cohereRerankService;
    @Mock
    private RewriteCandidateService rewriteCandidateService;
    @Mock
    private AgenticQueryPlannerService plannerService;

    private AgenticRetrievalService service;

    @BeforeEach
    void setUp() {
        service = new AgenticRetrievalService(
                repository,
                ragTracePersistenceService,
                embeddingService,
                denseEmbeddingService,
                cohereRerankService,
                rewriteCandidateService,
                new QueryStrategyRouter(),
                plannerService,
                new SearchResultMerger(),
                objectMapper
        );
    }

    @Test
    void executeKeepsSubqueryRetrievalDomainScoped() {
        String rawQuery = "security filter order";
        ChatRuntimeDtos.ChatRuntimeConfigResponse config = config();
        ChatRuntimeDtos.ChatDomainReadinessResponse readiness = readiness();
        RagDtos.AgenticQueryPlan plan = new RagDtos.AgenticQueryPlan(
                rawQuery,
                domainId,
                "spring",
                "Spring",
                3,
                List.of(new RagDtos.AgenticSubquery(
                        1,
                        "FilterChainProxy order",
                        "filter order",
                        1.0d,
                        objectMapper.createObjectNode()
                )),
                "test-planner",
                false,
                null,
                objectMapper.createObjectNode()
        );
        List<RagRepository.RetrievalDoc> docs = List.of(new RagRepository.RetrievalDoc(
                "doc-1",
                "chunk-1",
                "FilterChainProxy orders Spring Security filters.",
                0.91d
        ));
        when(plannerService.plan(eq(rawQuery), eq(config), any(), eq(List.of()), eq(3))).thenReturn(plan);
        when(embeddingService.embed(anyString())).thenReturn(List.of(1.0d, 0.0d));
        when(embeddingService.toHalfvecLiteral(anyList())).thenReturn("[1.000000,0.000000]");
        when(repository.findTopChunksByEmbedding(anyString(), anyInt(), eq(domainId))).thenReturn(docs);
        when(cohereRerankService.rerank(anyString(), anyList(), anyInt())).thenAnswer(invocation -> invocation.getArgument(1));

        AgenticRetrievalService.AgenticExecutionResult result = service.execute(new AgenticRetrievalService.AgenticExecutionRequest(
                rawQuery,
                onlineQueryId,
                config,
                readiness,
                objectMapper.createObjectNode(),
                List.of(),
                "raw_only",
                "compact_anchor",
                "full_gating",
                3,
                3,
                5,
                2,
                0.05d,
                3,
                60,
                3
        ));

        assertThat(result.plan().domainId()).isEqualTo(domainId);
        assertThat(result.traces()).hasSize(1);
        assertThat(result.traces().getFirst().routeStrategy()).isEqualTo("RAW_ONLY");
        assertThat(result.mergedDocs()).extracting(RagRepository.RetrievalDoc::chunkId)
                .containsExactly("chunk-1");
        verify(repository).findTopChunksByEmbedding(anyString(), anyInt(), eq(domainId));
        ArgumentCaptor<RagTracePersistenceService.AgenticSubqueryRetrievalTracePersistenceRequest> traceCaptor =
                ArgumentCaptor.forClass(RagTracePersistenceService.AgenticSubqueryRetrievalTracePersistenceRequest.class);
        verify(ragTracePersistenceService).persistAgenticSubqueryRetrievalTrace(traceCaptor.capture());
        RagTracePersistenceService.AgenticSubqueryRetrievalTracePersistenceRequest traceRequest = traceCaptor.getValue();
        assertThat(traceRequest.persistPolicy()).isEqualTo(RagPersistPolicy.ONLINE_QUERY);
        assertThat(traceRequest.onlineQueryId()).isEqualTo(onlineQueryId);
        assertThat(traceRequest.executionKind()).isEqualTo(RagTracePersistenceService.AgenticRetrievalExecutionKind.AGENTIC_MULTI_QUERY);
        assertThat(traceRequest.writeScope()).isEqualTo(RagTracePersistenceService.AgenticSubqueryRetrievalTraceWriteScope.SUBQUERY_RAW_RETRIEVAL);
        assertThat(traceRequest.subqueryIndex()).isEqualTo(1);
        assertThat(traceRequest.subqueryText()).isEqualTo("FilterChainProxy order");
        assertThat(traceRequest.mode()).isEqualTo("raw_only");
        assertThat(traceRequest.rewriteCandidateId()).isNull();
        assertThat(traceRequest.retrievedDocs()).extracting(RagRepository.RetrievalDoc::chunkId)
                .containsExactly("chunk-1");
        assertThat(traceRequest.retrieverName()).isEqualTo("local:dense_only:hash-embedding-v1");
        assertThat(traceRequest.retrievalMetadata().path("agentic_phase").asText()).isEqualTo("raw");
        assertThat(traceRequest.retrievalMetadata().path("subquery_index").asInt()).isEqualTo(1);
        verify(repository, never()).insertRetrievalResults(any(), any(), anyString(), anyList(), anyString(), anyString(), any());
        verify(repository, never()).createRewriteCandidate(any(), anyInt(), anyString(), anyString(), any(), any(), anyDouble(), any());
        verify(repository, never()).markRewriteCandidateAdopted(any(), anyBoolean(), any());
        verify(ragTracePersistenceService, never()).createAgenticRewriteCandidateTrace(any());
        verify(ragTracePersistenceService, never()).markAgenticRewriteCandidateAdopted(any());
        verify(repository, never()).findMemoryTopN(anyString(), anyInt(), anyString(), any(), anyList(), anyList(), anyList());
        verify(rewriteCandidateService, never()).buildCandidates(anyString(), any(), anyList(), anyInt(), anyString(), anyBoolean(), any());
        verifyNoOnlineRootAnswerDecisionOrLogWrites();
    }

    @Test
    void executeDelegatesSubqueryCandidateRootAdoptionAndRetrievalTraceToPersistenceService() {
        String rawQuery = "security filter order";
        ChatRuntimeDtos.ChatRuntimeConfigResponse config = config("selective_rewrite", false);
        ChatRuntimeDtos.ChatDomainReadinessResponse readiness = readiness();
        RagDtos.AgenticQueryPlan plan = new RagDtos.AgenticQueryPlan(
                rawQuery,
                domainId,
                "spring",
                "Spring",
                3,
                List.of(new RagDtos.AgenticSubquery(
                        2,
                        "security filter order",
                        "filter order",
                        1.0d,
                        objectMapper.createObjectNode()
                )),
                "test-planner",
                false,
                null,
                objectMapper.createObjectNode()
        );
        List<RagRepository.RetrievalDoc> rawDocs = List.of(new RagRepository.RetrievalDoc(
                "doc-raw",
                "chunk-raw",
                "Spring Security filter overview.",
                0.10d
        ));
        List<RagRepository.RetrievalDoc> candidateDocs = List.of(new RagRepository.RetrievalDoc(
                "doc-candidate",
                "chunk-candidate",
                "FilterChainProxy orders SecurityFilterChain filters.",
                0.96d
        ));
        UUID rewriteCandidateId = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");
        when(plannerService.plan(eq(rawQuery), eq(config), any(), eq(List.of()), eq(3))).thenReturn(plan);
        when(embeddingService.embed(anyString())).thenReturn(List.of(1.0d, 0.0d));
        when(embeddingService.toHalfvecLiteral(anyList())).thenReturn("[1.000000,0.000000]");
        when(repository.findTopChunksByEmbedding(anyString(), anyInt(), eq(domainId)))
                .thenReturn(rawDocs, candidateDocs);
        when(repository.findMemoryTopN(anyString(), anyInt(), eq("full_gating"), eq(domainId), anyList(), anyList(), anyList()))
                .thenReturn(List.of(memoryCandidate()));
        when(cohereRerankService.rerank(anyString(), anyList(), anyInt())).thenAnswer(invocation -> invocation.getArgument(1));
        when(rewriteCandidateService.buildCandidates(anyString(), any(), anyList(), anyInt(), anyString(), anyBoolean(), any()))
                .thenReturn(List.of(new RewriteCandidateService.CandidateTemplate(
                        "candidate-1",
                        "FilterChainProxy SecurityFilterChain order"
                )));
        when(ragTracePersistenceService.createAgenticRewriteCandidateTrace(any()))
                .thenReturn(new RagTracePersistenceService.RewriteCandidatePersistenceResult(
                        RagPersistPolicy.ONLINE_QUERY,
                        onlineQueryId,
                        rewriteCandidateId,
                        true,
                        "persisted_agentic_multi_query_candidate_root"
                ));

        AgenticRetrievalService.AgenticExecutionResult result = service.execute(new AgenticRetrievalService.AgenticExecutionRequest(
                rawQuery,
                onlineQueryId,
                config,
                readiness,
                objectMapper.createObjectNode(),
                List.of(),
                "selective_rewrite",
                "compact_anchor",
                "full_gating",
                3,
                3,
                5,
                1,
                0.05d,
                3,
                60,
                3
        ));

        assertThat(result.persistedCandidates()).hasSize(1);
        assertThat(result.persistedCandidates().getFirst().rewriteCandidateId()).isEqualTo(rewriteCandidateId);
        assertThat(result.persistedCandidates().getFirst().retrieved())
                .extracting(RagRepository.RetrievalDoc::chunkId)
                .containsExactly("chunk-candidate");
        ArgumentCaptor<RagTracePersistenceService.AgenticSubqueryRetrievalTracePersistenceRequest> traceCaptor =
                ArgumentCaptor.forClass(RagTracePersistenceService.AgenticSubqueryRetrievalTracePersistenceRequest.class);
        verify(ragTracePersistenceService, times(2)).persistAgenticSubqueryRetrievalTrace(traceCaptor.capture());
        List<RagTracePersistenceService.AgenticSubqueryRetrievalTracePersistenceRequest> traceRequests = traceCaptor.getAllValues();
        assertThat(traceRequests)
                .extracting(RagTracePersistenceService.AgenticSubqueryRetrievalTracePersistenceRequest::writeScope)
                .containsExactly(
                        RagTracePersistenceService.AgenticSubqueryRetrievalTraceWriteScope.SUBQUERY_RAW_RETRIEVAL,
                        RagTracePersistenceService.AgenticSubqueryRetrievalTraceWriteScope.SUBQUERY_CANDIDATE_RETRIEVAL
                );
        assertThat(traceRequests.get(0).rewriteCandidateId()).isNull();
        assertThat(traceRequests.get(0).retrievedDocs()).extracting(RagRepository.RetrievalDoc::chunkId)
                .containsExactly("chunk-raw");
        assertThat(traceRequests.get(0).retrievalMetadata().path("agentic_phase").asText()).isEqualTo("raw");
        assertThat(traceRequests.get(1).rewriteCandidateId()).isEqualTo(rewriteCandidateId);
        assertThat(traceRequests.get(1).retrievedDocs()).extracting(RagRepository.RetrievalDoc::chunkId)
                .containsExactly("chunk-candidate");
        assertThat(traceRequests.get(1).retrievalMetadata().path("agentic_phase").asText()).isEqualTo("rewrite_candidate");
        assertThat(traceRequests.get(1).subqueryIndex()).isEqualTo(2);
        assertThat(traceRequests.get(1).subqueryText()).isEqualTo("security filter order");
        assertThat(traceRequests.get(1).mode()).isEqualTo("selective_rewrite");
        ArgumentCaptor<RagTracePersistenceService.AgenticRewriteCandidateTracePersistenceRequest> createCaptor =
                ArgumentCaptor.forClass(RagTracePersistenceService.AgenticRewriteCandidateTracePersistenceRequest.class);
        verify(ragTracePersistenceService).createAgenticRewriteCandidateTrace(createCaptor.capture());
        RagTracePersistenceService.AgenticRewriteCandidateTracePersistenceRequest createRequest = createCaptor.getValue();
        assertThat(createRequest.persistPolicy()).isEqualTo(RagPersistPolicy.ONLINE_QUERY);
        assertThat(createRequest.onlineQueryId()).isEqualTo(onlineQueryId);
        assertThat(createRequest.executionKind()).isEqualTo(RagTracePersistenceService.AgenticRetrievalExecutionKind.AGENTIC_MULTI_QUERY);
        assertThat(createRequest.subqueryIndex()).isEqualTo(2);
        assertThat(createRequest.subqueryText()).isEqualTo("security filter order");
        assertThat(createRequest.candidateIndex()).isEqualTo(21);
        assertThat(createRequest.candidateLabel()).isEqualTo("subquery_2_candidate-1");
        assertThat(createRequest.candidateQuery()).isEqualTo("FilterChainProxy SecurityFilterChain order");
        assertThat(createRequest.candidateMetadata().path("subquery_index").asInt()).isEqualTo(2);
        assertThat(createRequest.retrievalTopKDocs().toString()).contains("chunk-candidate");

        ArgumentCaptor<RagTracePersistenceService.AgenticRewriteCandidateAdoptionPersistenceRequest> adoptionCaptor =
                ArgumentCaptor.forClass(RagTracePersistenceService.AgenticRewriteCandidateAdoptionPersistenceRequest.class);
        verify(ragTracePersistenceService).markAgenticRewriteCandidateAdopted(adoptionCaptor.capture());
        RagTracePersistenceService.AgenticRewriteCandidateAdoptionPersistenceRequest adoptionRequest = adoptionCaptor.getValue();
        assertThat(adoptionRequest.persistPolicy()).isEqualTo(RagPersistPolicy.ONLINE_QUERY);
        assertThat(adoptionRequest.onlineQueryId()).isEqualTo(onlineQueryId);
        assertThat(adoptionRequest.rewriteCandidateId()).isEqualTo(rewriteCandidateId);
        assertThat(adoptionRequest.executionKind()).isEqualTo(RagTracePersistenceService.AgenticRetrievalExecutionKind.AGENTIC_MULTI_QUERY);
        assertThat(adoptionRequest.subqueryIndex()).isEqualTo(2);
        assertThat(adoptionRequest.adopted()).isTrue();
        assertThat(adoptionRequest.rejectedReason()).isNull();
        verify(repository, never()).createRewriteCandidate(any(), anyInt(), anyString(), anyString(), any(), any(), anyDouble(), any());
        verify(repository, never()).markRewriteCandidateAdopted(any(), anyBoolean(), any());
        verify(repository, never()).insertRetrievalResults(any(), any(), anyString(), anyList(), anyString(), anyString(), any());
        verifyNoOnlineRootAnswerDecisionOrLogWrites();
    }

    @Test
    void executeSuppressesRouterSelectedAgenticForSubqueries() {
        String rawQuery = "\uC2A4\uD504\uB9C1 \uC694\uCCAD \uD750\uB984\uC744 \uBE44\uAD50\uD558\uACE0 mvc \uB77C\uC6B0\uD305\uACFC \uCC98\uB9AC \uB2E8\uACC4\uB97C \uC124\uBA85";
        ChatRuntimeDtos.ChatRuntimeConfigResponse config = config("selective_rewrite", true, true);
        ChatRuntimeDtos.ChatDomainReadinessResponse readiness = readiness();
        RagDtos.AgenticQueryPlan plan = new RagDtos.AgenticQueryPlan(
                rawQuery,
                domainId,
                "spring",
                "Spring",
                3,
                List.of(new RagDtos.AgenticSubquery(
                        1,
                        "\uC2A4\uD504\uB9C1 \uC694\uCCAD \uD750\uB984\uC744 \uBE44\uAD50\uD558\uACE0 mvc \uB77C\uC6B0\uD305\uACFC \uCC98\uB9AC \uB2E8\uACC4\uB97C \uC124\uBA85",
                        "multi intent",
                        1.0d,
                        objectMapper.createObjectNode()
                )),
                "test-planner",
                false,
                null,
                objectMapper.createObjectNode()
        );
        List<RagRepository.RetrievalDoc> rawDocs = List.of(new RagRepository.RetrievalDoc(
                "doc-raw",
                "chunk-raw",
                "Spring Security filter overview.",
                0.10d
        ));
        List<RagRepository.RetrievalDoc> candidateDocs = List.of(new RagRepository.RetrievalDoc(
                "doc-candidate",
                "chunk-candidate",
                "FilterChainProxy and DispatcherServlet request flow.",
                0.94d
        ));
        UUID rewriteCandidateId = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccd");
        when(plannerService.plan(eq(rawQuery), eq(config), any(), eq(List.of()), eq(3))).thenReturn(plan);
        when(embeddingService.embed(anyString())).thenReturn(List.of(1.0d, 0.0d));
        when(embeddingService.toHalfvecLiteral(anyList())).thenReturn("[1.000000,0.000000]");
        when(repository.findTopChunksByEmbedding(anyString(), anyInt(), eq(domainId)))
                .thenReturn(rawDocs, candidateDocs);
        when(repository.findMemoryTopN(anyString(), anyInt(), eq("full_gating"), eq(domainId), anyList(), anyList(), anyList()))
                .thenReturn(List.of(memoryCandidate()));
        when(cohereRerankService.rerank(anyString(), anyList(), anyInt())).thenAnswer(invocation -> invocation.getArgument(1));
        when(rewriteCandidateService.buildCandidates(anyString(), any(), anyList(), anyInt(), anyString(), anyBoolean(), any()))
                .thenReturn(List.of(new RewriteCandidateService.CandidateTemplate(
                        "candidate-1",
                        "FilterChainProxy DispatcherServlet request flow"
                )));
        when(ragTracePersistenceService.createAgenticRewriteCandidateTrace(any()))
                .thenReturn(new RagTracePersistenceService.RewriteCandidatePersistenceResult(
                        RagPersistPolicy.ONLINE_QUERY,
                        onlineQueryId,
                        rewriteCandidateId,
                        true,
                        "persisted_agentic_multi_query_candidate_root"
                ));

        AgenticRetrievalService.AgenticExecutionResult result = service.execute(new AgenticRetrievalService.AgenticExecutionRequest(
                rawQuery,
                onlineQueryId,
                config,
                readiness,
                objectMapper.createObjectNode(),
                List.of(),
                "selective_rewrite",
                "compact_anchor",
                "full_gating",
                3,
                3,
                5,
                1,
                0.05d,
                3,
                60,
                3
        ));

        RagDtos.SubqueryRetrievalTrace trace = result.traces().getFirst();
        assertThat(trace.routeStrategy()).isEqualTo("SYNTHETIC_SELECTIVE_REWRITE");
        assertThat(trace.metadata().path("route_metadata").path("agenticSubquery").asBoolean()).isTrue();
        assertThat(trace.metadata().path("route_metadata").path("agenticCandidate").asBoolean()).isTrue();
        assertThat(trace.metadata().path("route_metadata").path("agenticCandidateSuppressed").asText())
                .isEqualTo("agentic_subquery_recursion_guard");
        assertThat(result.persistedCandidates()).hasSize(1);
        verify(rewriteCandidateService).buildCandidates(anyString(), any(), anyList(), anyInt(), eq("compact_anchor"), eq(false), any());
        verify(ragTracePersistenceService).createAgenticRewriteCandidateTrace(any());
        verifyNoOnlineRootAnswerDecisionOrLogWrites();
    }

    private void verifyNoOnlineRootAnswerDecisionOrLogWrites() {
        verify(repository, never()).createOnlineQuery(any(), any(), any(), any(), any(), anyDouble(), any());
        verify(repository, never()).insertRerankResults(any(), any(), anyList(), anyString());
        verify(repository, never()).insertAnswer(any(), any(), any(), any(), any(), any());
        verify(repository, never()).upsertOnlineQueryDecision(
                any(),
                any(),
                anyBoolean(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any()
        );
        verify(repository, never()).mergeOnlineQueryMetadata(any(), any());
        verify(repository, never()).createOnlineRewriteLog(
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                anyBoolean(),
                any(),
                anyBoolean(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any()
        );
        verify(repository, never()).insertMemoryRetrievalLog(any(), any(), anyInt(), any(), any());
        verify(repository, never()).insertRewriteCandidateLog(
                any(),
                any(),
                any(),
                anyInt(),
                any(),
                any(),
                any(),
                anyBoolean(),
                any(),
                any(),
                any(),
                any()
        );
    }

    private ChatRuntimeDtos.ChatRuntimeConfigResponse config() {
        return config("raw_only", true);
    }

    private ChatRuntimeDtos.ChatRuntimeConfigResponse config(String mode, boolean routerEnabled) {
        return config(mode, routerEnabled, false);
    }

    private ChatRuntimeDtos.ChatRuntimeConfigResponse config(String mode, boolean routerEnabled, boolean agenticEnabled) {
        ObjectNode metadata = objectMapper.createObjectNode();
        metadata.put("routerEnabled", routerEnabled);
        if (agenticEnabled) {
            metadata.put("agenticMultiQueryEnabled", true);
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
                null,
                null,
                List.of(),
                List.of(),
                "compact_anchor",
                false,
                false,
                "local",
                "intfloat/multilingual-e5-small",
                "dense_only",
                20,
                1.0d,
                0.0d,
                0.0d,
                3,
                3,
                5,
                2,
                0.05d,
                "skip_to_raw",
                routerEnabled,
                metadata,
                Instant.now(),
                true,
                "ready"
        );
    }

    private RagRepository.MemoryCandidate memoryCandidate() {
        return new RagRepository.MemoryCandidate(
                UUID.fromString("dddddddd-dddd-dddd-dddd-dddddddddddd"),
                "FilterChainProxy SecurityFilterChain order",
                "memory-doc",
                objectMapper.createArrayNode().add("memory-chunk"),
                objectMapper.createArrayNode().add("FilterChainProxy"),
                objectMapper.createObjectNode(),
                0.72d,
                "C",
                UUID.fromString("eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee"),
                domainId,
                "gated-query-1",
                "source-gate-run-1",
                "source-gating-batch-1"
        );
    }

    private ChatRuntimeDtos.ChatDomainReadinessResponse readiness() {
        return new ChatRuntimeDtos.ChatDomainReadinessResponse(
                domainId,
                "spring",
                "Spring",
                "en",
                true,
                true,
                "raw_only",
                false,
                List.of("C"),
                "full_gating",
                null,
                null,
                0L,
                0L,
                null,
                null,
                true,
                List.of(),
                Instant.now()
        );
    }
}
