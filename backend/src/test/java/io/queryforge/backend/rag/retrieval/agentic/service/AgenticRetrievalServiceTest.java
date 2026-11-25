package io.queryforge.backend.rag.retrieval.agentic.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.queryforge.backend.rag.embedding.service.DenseEmbeddingService;
import io.queryforge.backend.rag.embedding.service.HashEmbeddingService;
import io.queryforge.backend.rag.retrieval.agentic.dto.AgenticExecutionDtos;
import io.queryforge.backend.rag.retrieval.agentic.dto.AgenticRetrievalDtos;
import io.queryforge.backend.rag.retrieval.agentic.fusion.service.SearchResultMerger;
import io.queryforge.backend.rag.retrieval.agentic.planning.dto.AgenticPlanningDtos;
import io.queryforge.backend.rag.retrieval.agentic.planning.service.AgenticQueryPlannerService;
import io.queryforge.backend.rag.retrieval.candidate.model.RetrievalDoc;
import io.queryforge.backend.rag.retrieval.candidate.repository.ChunkRetrievalRepository;
import io.queryforge.backend.rag.retrieval.candidate.service.DomainScopedRetrievalService;
import io.queryforge.backend.rag.retrieval.execution.model.RagPersistPolicy;
import io.queryforge.backend.rag.retrieval.memory.model.MemoryCandidate;
import io.queryforge.backend.rag.retrieval.memory.repository.RagMemoryRepository;
import io.queryforge.backend.rag.retrieval.rerank.service.CohereRerankService;
import io.queryforge.backend.rag.retrieval.rewrite.dto.RewriteDtos;
import io.queryforge.backend.rag.retrieval.rewrite.service.RewriteCandidateService;
import io.queryforge.backend.rag.retrieval.routing.service.QueryStrategyRouter;
import io.queryforge.backend.rag.runtime.configuration.dto.ChatConfigDtos;
import io.queryforge.backend.rag.runtime.configuration.model.RagExecutionSettings;
import io.queryforge.backend.rag.runtime.readiness.dto.ChatReadinessDtos;
import io.queryforge.backend.rag.trace.write.agentic.dto.AgenticTraceWriteDtos;
import io.queryforge.backend.rag.trace.write.agentic.service.AgenticTracePersistenceService;
import io.queryforge.backend.rag.trace.write.dto.TraceWriteResults;
import io.queryforge.backend.rag.trace.write.repository.RagTraceRepository;
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
    private ChunkRetrievalRepository chunkRepository;
    @Mock
    private RagMemoryRepository memoryRepository;
    @Mock
    private AgenticTracePersistenceService agenticTracePersistenceService;
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
                new DomainScopedRetrievalService(chunkRepository, memoryRepository, embeddingService, denseEmbeddingService),
                agenticTracePersistenceService,
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
        ChatConfigDtos.ChatRuntimeConfigResponse config = config();
        ChatReadinessDtos.ChatDomainReadinessResponse readiness = readiness();
        AgenticPlanningDtos.AgenticQueryPlan plan = new AgenticPlanningDtos.AgenticQueryPlan(
                rawQuery,
                domainId,
                "spring",
                "Spring",
                3,
                List.of(new AgenticPlanningDtos.AgenticSubquery(
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
        List<RetrievalDoc> docs = List.of(new RetrievalDoc(
                "doc-1",
                "chunk-1",
                "FilterChainProxy orders Spring Security filters.",
                0.91d
        ));
        when(plannerService.plan(eq(rawQuery), eq(config), any(), eq(List.of()), eq(3))).thenReturn(plan);
        when(embeddingService.embed(anyString())).thenReturn(List.of(1.0d, 0.0d));
        when(embeddingService.toHalfvecLiteral(anyList())).thenReturn("[1.000000,0.000000]");
        when(chunkRepository.findTopChunksByEmbedding(anyString(), anyInt(), eq(domainId))).thenReturn(docs);
        when(cohereRerankService.rerank(anyString(), anyList(), anyInt())).thenAnswer(invocation -> invocation.getArgument(1));

        AgenticExecutionDtos.AgenticExecutionResult result = service.execute(new AgenticExecutionDtos.AgenticExecutionRequest(
                rawQuery,
                onlineQueryId,
                config,
                readiness,
                objectMapper.createObjectNode(),
                List.of(),
                "raw_only",
                "compact_anchor",
                "full_gating",
                new RagExecutionSettings(3, 3, 5, 2, 0.05d),
                3,
                60,
                3,
                RagPersistPolicy.ONLINE_QUERY
        ));

        assertThat(result.plan().domainId()).isEqualTo(domainId);
        assertThat(result.traces()).hasSize(1);
        assertThat(result.traces().getFirst().routeStrategy()).isEqualTo("RAW_ONLY");
        assertThat(result.mergedDocs()).extracting(RetrievalDoc::chunkId)
                .containsExactly("chunk-1");
        verify(chunkRepository).findTopChunksByEmbedding(anyString(), anyInt(), eq(domainId));
        ArgumentCaptor<AgenticTraceWriteDtos.AgenticSubqueryRetrievalTracePersistenceRequest> traceCaptor =
                ArgumentCaptor.forClass(AgenticTraceWriteDtos.AgenticSubqueryRetrievalTracePersistenceRequest.class);
        verify(agenticTracePersistenceService).persistAgenticSubqueryRetrievalTrace(traceCaptor.capture());
        AgenticTraceWriteDtos.AgenticSubqueryRetrievalTracePersistenceRequest traceRequest = traceCaptor.getValue();
        assertThat(traceRequest.persistPolicy()).isEqualTo(RagPersistPolicy.ONLINE_QUERY);
        assertThat(traceRequest.onlineQueryId()).isEqualTo(onlineQueryId);
        assertThat(traceRequest.executionKind()).isEqualTo(AgenticTraceWriteDtos.AgenticRetrievalExecutionKind.AGENTIC_MULTI_QUERY);
        assertThat(traceRequest.writeScope()).isEqualTo(AgenticTraceWriteDtos.AgenticSubqueryRetrievalTraceWriteScope.SUBQUERY_RAW_RETRIEVAL);
        assertThat(traceRequest.subqueryIndex()).isEqualTo(1);
        assertThat(traceRequest.subqueryText()).isEqualTo("FilterChainProxy order");
        assertThat(traceRequest.mode()).isEqualTo("raw_only");
        assertThat(traceRequest.rewriteCandidateId()).isNull();
        assertThat(traceRequest.retrievedDocs()).extracting(RetrievalDoc::chunkId)
                .containsExactly("chunk-1");
        assertThat(traceRequest.retrieverName()).isEqualTo("local:dense_only:hash-embedding-v1");
        assertThat(traceRequest.retrievalMetadata().path("agentic_phase").asText()).isEqualTo("raw");
        assertThat(traceRequest.retrievalMetadata().path("subquery_index").asInt()).isEqualTo(1);
        verify(agenticTracePersistenceService, never()).createAgenticRewriteCandidateTrace(any());
        verify(agenticTracePersistenceService, never()).markAgenticRewriteCandidateAdopted(any());
        verify(memoryRepository, never()).findMemoryTopN(anyString(), anyInt(), anyString(), any(), anyList(), anyList(), anyList());
        verify(rewriteCandidateService, never()).buildCandidates(anyString(), any(), anyList(), anyInt(), anyString(), anyBoolean(), any());
        verifyNoOnlineRootAnswerDecisionOrLogWrites();
    }

    @Test
    void executeDelegatesSubqueryCandidateRootAdoptionAndRetrievalTraceToPersistenceService() {
        String rawQuery = "security filter order";
        ChatConfigDtos.ChatRuntimeConfigResponse config = config("selective_rewrite", false);
        ChatReadinessDtos.ChatDomainReadinessResponse readiness = readiness();
        AgenticPlanningDtos.AgenticQueryPlan plan = new AgenticPlanningDtos.AgenticQueryPlan(
                rawQuery,
                domainId,
                "spring",
                "Spring",
                3,
                List.of(new AgenticPlanningDtos.AgenticSubquery(
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
        List<RetrievalDoc> rawDocs = List.of(new RetrievalDoc(
                "doc-raw",
                "chunk-raw",
                "Spring Security filter overview.",
                0.10d
        ));
        List<RetrievalDoc> candidateDocs = List.of(new RetrievalDoc(
                "doc-candidate",
                "chunk-candidate",
                "FilterChainProxy orders SecurityFilterChain filters.",
                0.96d
        ));
        UUID rewriteCandidateId = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");
        when(plannerService.plan(eq(rawQuery), eq(config), any(), eq(List.of()), eq(3))).thenReturn(plan);
        when(embeddingService.embed(anyString())).thenReturn(List.of(1.0d, 0.0d));
        when(embeddingService.toHalfvecLiteral(anyList())).thenReturn("[1.000000,0.000000]");
        when(chunkRepository.findTopChunksByEmbedding(anyString(), anyInt(), eq(domainId)))
                .thenReturn(rawDocs, candidateDocs);
        when(memoryRepository.findMemoryTopN(anyString(), anyInt(), eq("full_gating"), eq(domainId), anyList(), anyList(), anyList()))
                .thenReturn(List.of(memoryCandidate()));
        when(cohereRerankService.rerank(anyString(), anyList(), anyInt())).thenAnswer(invocation -> invocation.getArgument(1));
        when(rewriteCandidateService.buildCandidates(anyString(), any(), anyList(), anyInt(), anyString(), anyBoolean(), any()))
                .thenReturn(List.of(new RewriteDtos.CandidateTemplate(
                        "candidate-1",
                        "FilterChainProxy SecurityFilterChain order"
                )));
        when(agenticTracePersistenceService.createAgenticRewriteCandidateTrace(any()))
                .thenReturn(new TraceWriteResults.RewriteCandidatePersistenceResult(
                        RagPersistPolicy.ONLINE_QUERY,
                        onlineQueryId,
                        rewriteCandidateId,
                        true,
                        "persisted_agentic_multi_query_candidate_root"
                ));

        AgenticExecutionDtos.AgenticExecutionResult result = service.execute(new AgenticExecutionDtos.AgenticExecutionRequest(
                rawQuery,
                onlineQueryId,
                config,
                readiness,
                objectMapper.createObjectNode(),
                List.of(),
                "selective_rewrite",
                "compact_anchor",
                "full_gating",
                new RagExecutionSettings(3, 3, 5, 1, 0.05d),
                3,
                60,
                3,
                RagPersistPolicy.ONLINE_QUERY
        ));

        assertThat(result.persistedCandidates()).hasSize(1);
        assertThat(result.persistedCandidates().getFirst().rewriteCandidateId()).isEqualTo(rewriteCandidateId);
        assertThat(result.persistedCandidates().getFirst().retrieved())
                .extracting(RetrievalDoc::chunkId)
                .containsExactly("chunk-candidate");
        ArgumentCaptor<AgenticTraceWriteDtos.AgenticSubqueryRetrievalTracePersistenceRequest> traceCaptor =
                ArgumentCaptor.forClass(AgenticTraceWriteDtos.AgenticSubqueryRetrievalTracePersistenceRequest.class);
        verify(agenticTracePersistenceService, times(2)).persistAgenticSubqueryRetrievalTrace(traceCaptor.capture());
        List<AgenticTraceWriteDtos.AgenticSubqueryRetrievalTracePersistenceRequest> traceRequests = traceCaptor.getAllValues();
        assertThat(traceRequests)
                .extracting(AgenticTraceWriteDtos.AgenticSubqueryRetrievalTracePersistenceRequest::writeScope)
                .containsExactly(
                        AgenticTraceWriteDtos.AgenticSubqueryRetrievalTraceWriteScope.SUBQUERY_RAW_RETRIEVAL,
                        AgenticTraceWriteDtos.AgenticSubqueryRetrievalTraceWriteScope.SUBQUERY_CANDIDATE_RETRIEVAL
                );
        assertThat(traceRequests.get(0).rewriteCandidateId()).isNull();
        assertThat(traceRequests.get(0).retrievedDocs()).extracting(RetrievalDoc::chunkId)
                .containsExactly("chunk-raw");
        assertThat(traceRequests.get(0).retrievalMetadata().path("agentic_phase").asText()).isEqualTo("raw");
        assertThat(traceRequests.get(1).rewriteCandidateId()).isEqualTo(rewriteCandidateId);
        assertThat(traceRequests.get(1).retrievedDocs()).extracting(RetrievalDoc::chunkId)
                .containsExactly("chunk-candidate");
        assertThat(traceRequests.get(1).retrievalMetadata().path("agentic_phase").asText()).isEqualTo("rewrite_candidate");
        assertThat(traceRequests.get(1).subqueryIndex()).isEqualTo(2);
        assertThat(traceRequests.get(1).subqueryText()).isEqualTo("security filter order");
        assertThat(traceRequests.get(1).mode()).isEqualTo("selective_rewrite");
        ArgumentCaptor<AgenticTraceWriteDtos.AgenticRewriteCandidateTracePersistenceRequest> createCaptor =
                ArgumentCaptor.forClass(AgenticTraceWriteDtos.AgenticRewriteCandidateTracePersistenceRequest.class);
        verify(agenticTracePersistenceService).createAgenticRewriteCandidateTrace(createCaptor.capture());
        AgenticTraceWriteDtos.AgenticRewriteCandidateTracePersistenceRequest createRequest = createCaptor.getValue();
        assertThat(createRequest.persistPolicy()).isEqualTo(RagPersistPolicy.ONLINE_QUERY);
        assertThat(createRequest.onlineQueryId()).isEqualTo(onlineQueryId);
        assertThat(createRequest.executionKind()).isEqualTo(AgenticTraceWriteDtos.AgenticRetrievalExecutionKind.AGENTIC_MULTI_QUERY);
        assertThat(createRequest.subqueryIndex()).isEqualTo(2);
        assertThat(createRequest.subqueryText()).isEqualTo("security filter order");
        assertThat(createRequest.candidateIndex()).isEqualTo(21);
        assertThat(createRequest.candidateLabel()).isEqualTo("subquery_2_candidate-1");
        assertThat(createRequest.candidateQuery()).isEqualTo("FilterChainProxy SecurityFilterChain order");
        assertThat(createRequest.candidateMetadata().path("subquery_index").asInt()).isEqualTo(2);
        assertThat(createRequest.retrievalTopKDocs().toString()).contains("chunk-candidate");

        ArgumentCaptor<AgenticTraceWriteDtos.AgenticRewriteCandidateAdoptionPersistenceRequest> adoptionCaptor =
                ArgumentCaptor.forClass(AgenticTraceWriteDtos.AgenticRewriteCandidateAdoptionPersistenceRequest.class);
        verify(agenticTracePersistenceService).markAgenticRewriteCandidateAdopted(adoptionCaptor.capture());
        AgenticTraceWriteDtos.AgenticRewriteCandidateAdoptionPersistenceRequest adoptionRequest = adoptionCaptor.getValue();
        assertThat(adoptionRequest.persistPolicy()).isEqualTo(RagPersistPolicy.ONLINE_QUERY);
        assertThat(adoptionRequest.onlineQueryId()).isEqualTo(onlineQueryId);
        assertThat(adoptionRequest.rewriteCandidateId()).isEqualTo(rewriteCandidateId);
        assertThat(adoptionRequest.executionKind()).isEqualTo(AgenticTraceWriteDtos.AgenticRetrievalExecutionKind.AGENTIC_MULTI_QUERY);
        assertThat(adoptionRequest.subqueryIndex()).isEqualTo(2);
        assertThat(adoptionRequest.adopted()).isTrue();
        assertThat(adoptionRequest.rejectedReason()).isNull();
        verifyNoOnlineRootAnswerDecisionOrLogWrites();
    }

    @Test
    void executeSuppressesRouterSelectedAgenticForSubqueries() {
        String rawQuery = "\uC2A4\uD504\uB9C1 \uC694\uCCAD \uD750\uB984\uC744 \uBE44\uAD50\uD558\uACE0 mvc \uB77C\uC6B0\uD305\uACFC \uCC98\uB9AC \uB2E8\uACC4\uB97C \uC124\uBA85";
        ChatConfigDtos.ChatRuntimeConfigResponse config = config("selective_rewrite", true, true);
        ChatReadinessDtos.ChatDomainReadinessResponse readiness = readiness();
        AgenticPlanningDtos.AgenticQueryPlan plan = new AgenticPlanningDtos.AgenticQueryPlan(
                rawQuery,
                domainId,
                "spring",
                "Spring",
                3,
                List.of(new AgenticPlanningDtos.AgenticSubquery(
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
        List<RetrievalDoc> rawDocs = List.of(new RetrievalDoc(
                "doc-raw",
                "chunk-raw",
                "Spring Security filter overview.",
                0.10d
        ));
        List<RetrievalDoc> candidateDocs = List.of(new RetrievalDoc(
                "doc-candidate",
                "chunk-candidate",
                "FilterChainProxy and DispatcherServlet request flow.",
                0.94d
        ));
        UUID rewriteCandidateId = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccd");
        when(plannerService.plan(eq(rawQuery), eq(config), any(), eq(List.of()), eq(3))).thenReturn(plan);
        when(embeddingService.embed(anyString())).thenReturn(List.of(1.0d, 0.0d));
        when(embeddingService.toHalfvecLiteral(anyList())).thenReturn("[1.000000,0.000000]");
        when(chunkRepository.findTopChunksByEmbedding(anyString(), anyInt(), eq(domainId)))
                .thenReturn(rawDocs, candidateDocs);
        when(memoryRepository.findMemoryTopN(anyString(), anyInt(), eq("full_gating"), eq(domainId), anyList(), anyList(), anyList()))
                .thenReturn(List.of(memoryCandidate()));
        when(cohereRerankService.rerank(anyString(), anyList(), anyInt())).thenAnswer(invocation -> invocation.getArgument(1));
        when(rewriteCandidateService.buildCandidates(anyString(), any(), anyList(), anyInt(), anyString(), anyBoolean(), any()))
                .thenReturn(List.of(new RewriteDtos.CandidateTemplate(
                        "candidate-1",
                        "FilterChainProxy DispatcherServlet request flow"
                )));
        when(agenticTracePersistenceService.createAgenticRewriteCandidateTrace(any()))
                .thenReturn(new TraceWriteResults.RewriteCandidatePersistenceResult(
                        RagPersistPolicy.ONLINE_QUERY,
                        onlineQueryId,
                        rewriteCandidateId,
                        true,
                        "persisted_agentic_multi_query_candidate_root"
                ));

        AgenticExecutionDtos.AgenticExecutionResult result = service.execute(new AgenticExecutionDtos.AgenticExecutionRequest(
                rawQuery,
                onlineQueryId,
                config,
                readiness,
                objectMapper.createObjectNode(),
                List.of(),
                "selective_rewrite",
                "compact_anchor",
                "full_gating",
                new RagExecutionSettings(3, 3, 5, 1, 0.05d),
                3,
                60,
                3,
                RagPersistPolicy.ONLINE_QUERY
        ));

        AgenticRetrievalDtos.SubqueryRetrievalTrace trace = result.traces().getFirst();
        assertThat(trace.routeStrategy()).isEqualTo("SYNTHETIC_SELECTIVE_REWRITE");
        assertThat(trace.metadata().path("route_metadata").path("agenticSubquery").asBoolean()).isTrue();
        assertThat(trace.metadata().path("route_metadata").path("agenticCandidate").asBoolean()).isTrue();
        assertThat(trace.metadata().path("route_metadata").path("agenticCandidateSuppressed").asText())
                .isEqualTo("agentic_subquery_recursion_guard");
        assertThat(result.persistedCandidates()).hasSize(1);
        verify(rewriteCandidateService).buildCandidates(anyString(), any(), anyList(), anyInt(), eq("compact_anchor"), eq(false), any());
        verify(agenticTracePersistenceService).createAgenticRewriteCandidateTrace(any());
        verifyNoOnlineRootAnswerDecisionOrLogWrites();
    }

    private void verifyNoOnlineRootAnswerDecisionOrLogWrites() {
        assertThat(java.util.Arrays.stream(AgenticRetrievalService.class.getDeclaredFields())
                .map(java.lang.reflect.Field::getType).toList())
                .doesNotContain(RagTraceRepository.class);
        verify(agenticTracePersistenceService, never()).persistAgenticFinalRerankTrace(any());
        verify(agenticTracePersistenceService, never()).persistAgenticOnlineQueryDecision(any());
        verify(agenticTracePersistenceService, never()).mergeAgenticOnlineQueryMetadata(any());
        verify(agenticTracePersistenceService, never()).createAgenticOnlineRewriteLogTrace(any());
        verify(agenticTracePersistenceService, never()).insertAgenticMemoryRetrievalTrace(any());
        verify(agenticTracePersistenceService, never()).insertAgenticRewriteCandidateTrace(any());
    }

    private ChatConfigDtos.ChatRuntimeConfigResponse config() {
        return config("raw_only", true);
    }

    private ChatConfigDtos.ChatRuntimeConfigResponse config(String mode, boolean routerEnabled) {
        return config(mode, routerEnabled, false);
    }

    private ChatConfigDtos.ChatRuntimeConfigResponse config(String mode, boolean routerEnabled, boolean agenticEnabled) {
        ObjectNode metadata = objectMapper.createObjectNode();
        metadata.put("routerEnabled", routerEnabled);
        if (agenticEnabled) {
            metadata.put("agenticMultiQueryEnabled", true);
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

    private MemoryCandidate memoryCandidate() {
        return new MemoryCandidate(
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

    private ChatReadinessDtos.ChatDomainReadinessResponse readiness() {
        return new ChatReadinessDtos.ChatDomainReadinessResponse(
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
