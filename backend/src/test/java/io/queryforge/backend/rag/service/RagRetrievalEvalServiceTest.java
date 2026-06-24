package io.queryforge.backend.rag.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.queryforge.backend.rag.model.ChatRuntimeDtos;
import io.queryforge.backend.rag.model.QueryRouteDecision;
import io.queryforge.backend.rag.model.QueryStrategy;
import io.queryforge.backend.rag.model.RagDtos;
import io.queryforge.backend.rag.model.RagPersistPolicy;
import io.queryforge.backend.rag.model.RagRetrievalEvalDtos;
import io.queryforge.backend.rag.repository.RagRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RagRetrievalEvalServiceTest {

    private static final UUID DOMAIN_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final String QUERY = "FilterChainProxy order";
    private static final String DOMAIN_ID_REQUIRED_MESSAGE = "domainId_required: domainId is required";
    private static final String QUERY_REQUIRED_MESSAGE = "query_required: query must not be blank";
    private static final String UNSUPPORTED_PERSIST_POLICY_MESSAGE =
            "unsupported_persist_policy: retrieval eval supports only persistPolicy=NONE";
    private static final String UNSUPPORTED_ANSWER_GENERATION_MESSAGE =
            "unsupported_answer_generation: answerGeneration=true is unsupported for retrieval eval";
    private static final String UNSUPPORTED_FORCED_MODE_MESSAGE =
            "unsupported_forced_mode: forcedMode is not supported for retrieval eval: unknown_mode";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final DomainScopedRetrievalService.RetrievalRuntime retrievalRuntime =
            new DomainScopedRetrievalService.RetrievalRuntime(
                    "local",
                    "intfloat/multilingual-e5-small",
                    "dense_only",
                    20,
                    1.0d,
                    0.0d,
                    0.0d
            );

    @Mock
    private ChatRuntimeConfigService chatRuntimeConfigService;
    @Mock
    private DomainScopedRetrievalService domainScopedRetrievalService;
    @Mock
    private QueryStrategyRouter queryStrategyRouter;
    @Mock
    private RagRetrievalExecutionService ragRetrievalExecutionService;
    @Mock
    private AgenticRetrievalService agenticRetrievalService;

    private RagRetrievalEvalService service;

    @BeforeEach
    void setUp() {
        service = new RagRetrievalEvalService(
                chatRuntimeConfigService,
                domainScopedRetrievalService,
                queryStrategyRouter,
                ragRetrievalExecutionService,
                agenticRetrievalService,
                objectMapper
        );
    }

    @Test
    void requestDefaultsPersistPolicyToNone() {
        RagRetrievalEvalDtos.RagRetrievalEvalRequest request = request(null, null, null);

        assertThat(request.persistPolicy()).isEqualTo(RagPersistPolicy.NONE);
        assertThat(request.forcedMode()).isEqualTo("strategy_router");
    }

    @Test
    void requestDefaultsAnswerGenerationToFalse() {
        RagRetrievalEvalDtos.RagRetrievalEvalRequest request = request(null, null, null);

        assertThat(request.answerGeneration()).isFalse();
    }

    @Test
    void answerGenerationTrueRejectsRequest() {
        RagRetrievalEvalDtos.RagRetrievalEvalRequest request = request("raw_only", RagPersistPolicy.NONE, true);

        assertEvalError(
                request,
                "unsupported_answer_generation",
                UNSUPPORTED_ANSWER_GENERATION_MESSAGE
        );
    }

    @Test
    void onlineQueryPersistPolicyRejectsRequest() {
        RagRetrievalEvalDtos.RagRetrievalEvalRequest request = request("raw_only", RagPersistPolicy.ONLINE_QUERY, false);

        assertEvalError(
                request,
                "unsupported_persist_policy",
                UNSUPPORTED_PERSIST_POLICY_MESSAGE
        );
    }

    @Test
    void traceOnlyPersistPolicyRejectsRequest() {
        RagRetrievalEvalDtos.RagRetrievalEvalRequest request = request("raw_only", RagPersistPolicy.TRACE_ONLY, false);

        assertEvalError(
                request,
                "unsupported_persist_policy",
                UNSUPPORTED_PERSIST_POLICY_MESSAGE
        );
    }

    @Test
    void missingDomainIdRejectsRequest() {
        RagRetrievalEvalDtos.RagRetrievalEvalRequest request = new RagRetrievalEvalDtos.RagRetrievalEvalRequest(
                null,
                QUERY,
                "raw_only",
                null,
                RagPersistPolicy.NONE,
                false,
                false,
                true,
                false
        );

        assertEvalError(request, "domainId_required", DOMAIN_ID_REQUIRED_MESSAGE);
    }

    @Test
    void blankQueryRejectsRequest() {
        RagRetrievalEvalDtos.RagRetrievalEvalRequest request = new RagRetrievalEvalDtos.RagRetrievalEvalRequest(
                DOMAIN_ID,
                "  ",
                "raw_only",
                null,
                RagPersistPolicy.NONE,
                false,
                false,
                true,
                false
        );

        assertEvalError(request, "query_required", QUERY_REQUIRED_MESSAGE);
    }

    @Test
    void unknownForcedModeRejectsRequestWithFixedErrorCode() {
        RagRetrievalEvalDtos.RagRetrievalEvalRequest request = request("unknown_mode", null, null);

        assertEvalError(
                request,
                "unsupported_forced_mode",
                UNSUPPORTED_FORCED_MODE_MESSAGE
        );
    }

    @Test
    void rawOnlyCallsExecutionServiceAndReturnsRetrievedChunkIds() {
        givenRuntime();
        List<RagRepository.RetrievalDoc> docs = List.of(doc("chunk-1", 0.91d), doc("chunk-2", 0.82d));
        when(domainScopedRetrievalService.embeddingLiteral(QUERY, retrievalRuntime)).thenReturn("embedding");
        when(ragRetrievalExecutionService.executeRawOnly(any()))
                .thenReturn(rawResult(QUERY, docs));

        RagRetrievalEvalDtos.RagRetrievalEvalResponse response = service.execute(request("raw_only", null, null));

        ArgumentCaptor<RagRetrievalExecutionService.RawOnlyExecutionRequest> captor =
                ArgumentCaptor.forClass(RagRetrievalExecutionService.RawOnlyExecutionRequest.class);
        verify(ragRetrievalExecutionService).executeRawOnly(captor.capture());
        assertThat(captor.getValue().originalQuery()).isEqualTo(QUERY);
        assertThat(captor.getValue().queryEmbeddingLiteral()).isEqualTo("embedding");
        assertThat(captor.getValue().domainId()).isEqualTo(DOMAIN_ID);
        assertThat(response.selectedMode()).isEqualTo("raw_only");
        assertThat(response.retrievedChunkIds()).containsExactly("chunk-1", "chunk-2");
        assertThat(response.retrievedDocs()).extracting(RagRetrievalEvalDtos.RagRetrievalEvalDoc::rank)
                .containsExactly(1, 2);
        assertThat(response.warnings()).isEmpty();
        assertThat(response.persisted()).isFalse();
        assertThat(response.persistPolicy()).isEqualTo(RagPersistPolicy.NONE);
        verify(ragRetrievalExecutionService, never()).executeSelectiveRewrite(any());
        verify(ragRetrievalExecutionService, never()).executeAnchorAwareRewrite(any());
    }

    @Test
    void retrievedChunkIdsPreserveResultOrderAndDuplicates() {
        givenRuntime();
        List<RagRepository.RetrievalDoc> docs = List.of(
                doc("chunk-b", 0.91d),
                doc("chunk-a", 0.82d),
                doc("chunk-b", 0.71d)
        );
        when(domainScopedRetrievalService.embeddingLiteral(QUERY, retrievalRuntime)).thenReturn("embedding");
        when(ragRetrievalExecutionService.executeRawOnly(any()))
                .thenReturn(rawResult(QUERY, docs));

        RagRetrievalEvalDtos.RagRetrievalEvalResponse response = service.execute(request("raw_only", null, null));

        assertThat(response.retrievedChunkIds()).containsExactly("chunk-b", "chunk-a", "chunk-b");
    }

    @Test
    void retrievedDocsRankIsOneBased() {
        givenRuntime();
        List<RagRepository.RetrievalDoc> docs = List.of(
                doc("chunk-1", 0.91d),
                doc("chunk-2", 0.82d),
                doc("chunk-3", 0.73d)
        );
        when(domainScopedRetrievalService.embeddingLiteral(QUERY, retrievalRuntime)).thenReturn("embedding");
        when(ragRetrievalExecutionService.executeRawOnly(any()))
                .thenReturn(rawResult(QUERY, docs));

        RagRetrievalEvalDtos.RagRetrievalEvalResponse response = service.execute(request("raw_only", null, null));

        assertThat(response.retrievedDocs())
                .extracting(RagRetrievalEvalDtos.RagRetrievalEvalDoc::rank)
                .containsExactly(1, 2, 3);
    }

    @Test
    void contentPreviewIsTruncatedAndDoesNotExposeFullContent() {
        givenRuntime();
        String fullContent = "x".repeat(280);
        when(domainScopedRetrievalService.embeddingLiteral(QUERY, retrievalRuntime)).thenReturn("embedding");
        when(ragRetrievalExecutionService.executeRawOnly(any()))
                .thenReturn(rawResult(QUERY, List.of(doc("chunk-long", 0.91d, fullContent))));

        RagRetrievalEvalDtos.RagRetrievalEvalResponse response = service.execute(request("raw_only", null, null));

        String preview = response.retrievedDocs().getFirst().contentPreview();
        assertThat(preview).hasSize(240);
        assertThat(preview).endsWith("...");
        assertThat(preview).isNotEqualTo(fullContent);
    }

    @Test
    void finalQueryFallsBackToOriginalQueryWhenExecutionFinalQueryIsNull() {
        givenRuntime();
        when(domainScopedRetrievalService.embeddingLiteral(QUERY, retrievalRuntime)).thenReturn("embedding");
        when(ragRetrievalExecutionService.executeRawOnly(any()))
                .thenReturn(rawResult(QUERY, null, List.of(doc("chunk-1", 0.91d))));

        RagRetrievalEvalDtos.RagRetrievalEvalResponse response = service.execute(request("raw_only", null, null));

        assertThat(response.finalQuery()).isEqualTo(QUERY);
    }

    @Test
    void includeScoresFalseNullsDocumentScores() {
        givenRuntime();
        when(domainScopedRetrievalService.embeddingLiteral(QUERY, retrievalRuntime)).thenReturn("embedding");
        when(ragRetrievalExecutionService.executeRawOnly(any()))
                .thenReturn(rawResult(QUERY, List.of(doc("chunk-1", 0.91d), doc("chunk-2", 0.82d))));

        RagRetrievalEvalDtos.RagRetrievalEvalResponse response = service.execute(request(
                "raw_only",
                RagPersistPolicy.NONE,
                false,
                false,
                false,
                false
        ));

        assertThat(response.retrievedDocs())
                .extracting(RagRetrievalEvalDtos.RagRetrievalEvalDoc::score)
                .containsOnlyNulls();
    }

    @Test
    void includeTraceTrueReturnsMinimalTrace() {
        givenRuntime();
        when(domainScopedRetrievalService.embeddingLiteral(QUERY, retrievalRuntime)).thenReturn("embedding");
        when(ragRetrievalExecutionService.executeRawOnly(any()))
                .thenReturn(rawResult(QUERY, List.of(doc("chunk-1", 0.91d))));

        RagRetrievalEvalDtos.RagRetrievalEvalResponse response = service.execute(request(
                "raw_only",
                RagPersistPolicy.NONE,
                false,
                true,
                true,
                false
        ));

        assertThat(response.trace()).isNotNull();
        assertThat(response.trace().routeDecision()).isNull();
        assertThat(response.trace().retrievalTrace().path("selectedMode").asText()).isEqualTo("raw_only");
        assertThat(response.trace().retrievalTrace().path("retrievedChunkIds").get(0).asText()).isEqualTo("chunk-1");
    }

    @Test
    void includeMetadataTrueAddsReservedWarning() {
        givenRuntime();
        when(domainScopedRetrievalService.embeddingLiteral(QUERY, retrievalRuntime)).thenReturn("embedding");
        when(ragRetrievalExecutionService.executeRawOnly(any()))
                .thenReturn(rawResult(QUERY, List.of(doc("chunk-1", 0.91d))));

        RagRetrievalEvalDtos.RagRetrievalEvalResponse response = service.execute(request(
                "raw_only",
                RagPersistPolicy.NONE,
                false,
                false,
                true,
                true
        ));

        assertThat(response.warnings())
                .containsExactly("includeMetadata is accepted but detailed document metadata is not exposed in Phase 7C");
    }

    @Test
    void selectiveRewriteCallsExecutionService() {
        givenRuntime();
        List<RagRepository.MemoryCandidate> memories = List.of(memory());
        List<RagRepository.RetrievalDoc> docs = List.of(doc("chunk-selective", 0.93d));
        givenMemoryCandidates(memories);
        when(ragRetrievalExecutionService.executeSelectiveRewrite(any()))
                .thenReturn(selectiveResult("rewritten query", docs));

        RagRetrievalEvalDtos.RagRetrievalEvalResponse response = service.execute(request("selective_rewrite", null, null));

        ArgumentCaptor<RagRetrievalExecutionService.SelectiveRewriteExecutionRequest> captor =
                ArgumentCaptor.forClass(RagRetrievalExecutionService.SelectiveRewriteExecutionRequest.class);
        verify(ragRetrievalExecutionService).executeSelectiveRewrite(captor.capture());
        assertThat(captor.getValue().rawQuery()).isEqualTo(QUERY);
        assertThat(captor.getValue().memoryCandidates()).isEqualTo(memories);
        assertThat(captor.getValue().domainId()).isEqualTo(DOMAIN_ID);
        assertThat(response.selectedMode()).isEqualTo("selective_rewrite");
        assertThat(response.finalQuery()).isEqualTo("rewritten query");
        assertThat(response.retrievedChunkIds()).containsExactly("chunk-selective");
        verify(ragRetrievalExecutionService, never()).executeRawOnly(any());
        verify(ragRetrievalExecutionService, never()).executeAnchorAwareRewrite(any());
    }

    @Test
    void anchorAwareRewriteCallsExecutionService() {
        givenRuntime();
        List<RagRepository.MemoryCandidate> memories = List.of(memory());
        List<RagRepository.RetrievalDoc> docs = List.of(doc("chunk-anchor", 0.95d));
        givenMemoryCandidates(memories);
        when(ragRetrievalExecutionService.executeAnchorAwareRewrite(any()))
                .thenReturn(anchorResult("anchored query", docs));

        RagRetrievalEvalDtos.RagRetrievalEvalResponse response = service.execute(request("anchor_aware_rewrite", null, null));

        ArgumentCaptor<RagRetrievalExecutionService.AnchorAwareRewriteExecutionRequest> captor =
                ArgumentCaptor.forClass(RagRetrievalExecutionService.AnchorAwareRewriteExecutionRequest.class);
        verify(ragRetrievalExecutionService).executeAnchorAwareRewrite(captor.capture());
        assertThat(captor.getValue().rawQuery()).isEqualTo(QUERY);
        assertThat(captor.getValue().memoryCandidates()).isEqualTo(memories);
        assertThat(captor.getValue().domainId()).isEqualTo(DOMAIN_ID);
        assertThat(response.selectedMode()).isEqualTo("anchor_aware_rewrite");
        assertThat(response.finalQuery()).isEqualTo("anchored query");
        assertThat(response.retrievedChunkIds()).containsExactly("chunk-anchor");
        verify(ragRetrievalExecutionService, never()).executeRawOnly(any());
        verify(ragRetrievalExecutionService, never()).executeSelectiveRewrite(any());
    }

    @Test
    void strategyRouterUsesCurrentJavaRouterRange() {
        givenRuntime();
        List<RagRepository.MemoryCandidate> memories = List.of(memory());
        givenMemoryCandidates(memories);
        when(queryStrategyRouter.route(any())).thenReturn(routeDecision(QueryStrategy.SYNTHETIC_SELECTIVE_REWRITE));
        when(ragRetrievalExecutionService.executeSelectiveRewrite(any()))
                .thenReturn(selectiveResult("router rewrite", List.of(doc("chunk-router", 0.88d))));

        RagRetrievalEvalDtos.RagRetrievalEvalResponse response = service.execute(request(null, null, null));

        verify(queryStrategyRouter).route(any());
        verify(ragRetrievalExecutionService).executeSelectiveRewrite(any());
        assertThat(response.forcedMode()).isEqualTo("strategy_router");
        assertThat(response.selectedMode()).isEqualTo("selective_rewrite");
        assertThat(response.retrievedChunkIds()).containsExactly("chunk-router");
    }

    @Test
    void strategyRouterSelectedAgenticRunsNoWriteAgenticEval() {
        givenRuntime();
        givenMemoryCandidates(List.of(memory()));
        when(queryStrategyRouter.route(any())).thenReturn(routeDecision(QueryStrategy.AGENTIC_MULTI_QUERY));
        when(agenticRetrievalService.execute(any()))
                .thenReturn(agenticResult(List.of(doc("chunk-agentic-router", 0.89d))));

        RagRetrievalEvalDtos.RagRetrievalEvalResponse response = service.execute(request(null, null, null));

        ArgumentCaptor<AgenticRetrievalService.AgenticExecutionRequest> captor =
                ArgumentCaptor.forClass(AgenticRetrievalService.AgenticExecutionRequest.class);
        verify(agenticRetrievalService).execute(captor.capture());
        assertThat(captor.getValue().persistPolicy()).isEqualTo(RagPersistPolicy.NONE);
        assertThat(captor.getValue().onlineQueryId()).isNull();
        assertThat(response.forcedMode()).isEqualTo("strategy_router");
        assertThat(response.selectedMode()).isEqualTo("agentic_multi_query");
        assertThat(response.retrievedChunkIds()).containsExactly("chunk-agentic-router");
        verify(ragRetrievalExecutionService, never()).executeRawOnly(any());
        verify(ragRetrievalExecutionService, never()).executeSelectiveRewrite(any());
        verify(ragRetrievalExecutionService, never()).executeAnchorAwareRewrite(any());
    }

    @Test
    void forcedAgenticMultiQueryRunsNoWriteAgenticEval() {
        givenRuntime();
        givenMemoryCandidates(List.of(memory()));
        when(agenticRetrievalService.execute(any()))
                .thenReturn(agenticResult(List.of(doc("chunk-agentic", 0.91d))));

        RagRetrievalEvalDtos.RagRetrievalEvalResponse response =
                service.execute(request("agentic_multi_query", null, null));

        ArgumentCaptor<AgenticRetrievalService.AgenticExecutionRequest> captor =
                ArgumentCaptor.forClass(AgenticRetrievalService.AgenticExecutionRequest.class);
        verify(agenticRetrievalService).execute(captor.capture());
        assertThat(captor.getValue().persistPolicy()).isEqualTo(RagPersistPolicy.NONE);
        assertThat(captor.getValue().onlineQueryId()).isNull();
        assertThat(response.selectedMode()).isEqualTo("agentic_multi_query");
        assertThat(response.retrievedChunkIds()).containsExactly("chunk-agentic");
    }

    @Test
    void serviceDoesNotDependOnAskAnswerGenerationOrPersistenceWriteBeans() {
        List<Class<?>> fieldTypes = Arrays.stream(RagRetrievalEvalService.class.getDeclaredFields())
                .map(Field::getType)
                .toList();

        assertThat(fieldTypes)
                .doesNotContain(RagService.class, ChatAnswerService.class, RagRepository.class);
    }

    private void givenRuntime() {
        ChatRuntimeDtos.ChatRuntimeConfigResponse config = config();
        when(chatRuntimeConfigService.getConfig(DOMAIN_ID)).thenReturn(config);
        when(chatRuntimeConfigService.getReadiness(DOMAIN_ID)).thenReturn(readiness());
        when(domainScopedRetrievalService.retrievalRuntime(config)).thenReturn(retrievalRuntime);
    }

    private void givenMemoryCandidates(List<RagRepository.MemoryCandidate> memories) {
        when(domainScopedRetrievalService.embeddingLiteral(QUERY, retrievalRuntime)).thenReturn("embedding");
        when(domainScopedRetrievalService.findMemoryCandidates(
                eq(QUERY),
                eq("embedding"),
                anyInt(),
                anyString(),
                eq(DOMAIN_ID),
                anyList(),
                anyList(),
                anyList(),
                eq(retrievalRuntime)
        )).thenReturn(memories);
    }

    private RagRetrievalEvalDtos.RagRetrievalEvalRequest request(
            String forcedMode,
            RagPersistPolicy persistPolicy,
            Boolean answerGeneration
    ) {
        return request(forcedMode, persistPolicy, answerGeneration, false, true, false);
    }

    private RagRetrievalEvalDtos.RagRetrievalEvalRequest request(
            String forcedMode,
            RagPersistPolicy persistPolicy,
            Boolean answerGeneration,
            Boolean includeTrace,
            Boolean includeScores,
            Boolean includeMetadata
    ) {
        return new RagRetrievalEvalDtos.RagRetrievalEvalRequest(
                DOMAIN_ID,
                QUERY,
                forcedMode,
                3,
                persistPolicy,
                answerGeneration,
                includeTrace,
                includeScores,
                includeMetadata
        );
    }

    private ChatRuntimeDtos.ChatRuntimeConfigResponse config() {
        return new ChatRuntimeDtos.ChatRuntimeConfigResponse(
                DOMAIN_ID,
                "spring-security",
                "Spring Security",
                "en",
                true,
                "strategy_router",
                List.of("C"),
                "full_gating",
                UUID.fromString("22222222-2222-2222-2222-222222222222"),
                UUID.fromString("33333333-3333-3333-3333-333333333333"),
                List.of(UUID.fromString("22222222-2222-2222-2222-222222222222")),
                List.of(UUID.fromString("33333333-3333-3333-3333-333333333333")),
                "compact_anchor",
                true,
                false,
                "local",
                "intfloat/multilingual-e5-small",
                "dense_only",
                20,
                1.0d,
                0.0d,
                0.0d,
                10,
                5,
                5,
                2,
                0.35d,
                "fallback_raw",
                true,
                objectMapper.createObjectNode(),
                Instant.parse("2026-01-01T00:00:00Z"),
                true,
                "ready"
        );
    }

    private ChatRuntimeDtos.ChatDomainReadinessResponse readiness() {
        return new ChatRuntimeDtos.ChatDomainReadinessResponse(
                DOMAIN_ID,
                "spring-security",
                "Spring Security",
                "en",
                true,
                true,
                "strategy_router",
                true,
                List.of("C"),
                "full_gating",
                null,
                null,
                2L,
                2L,
                null,
                null,
                true,
                List.of(),
                Instant.parse("2026-01-01T00:00:00Z")
        );
    }

    private RagRepository.RetrievalDoc doc(String chunkId, double score) {
        return doc(
                chunkId,
                score,
                "Spring Security FilterChainProxy documentation content that should be previewed"
        );
    }

    private RagRepository.RetrievalDoc doc(String chunkId, double score, String chunkText) {
        return new RagRepository.RetrievalDoc(
                "doc-1",
                chunkId,
                chunkText,
                score
        );
    }

    private RagRepository.MemoryCandidate memory() {
        return new RagRepository.MemoryCandidate(
                UUID.fromString("44444444-4444-4444-4444-444444444444"),
                "historical security filter query",
                "doc-1",
                objectMapper.createArrayNode().add("chunk-memory"),
                objectMapper.createArrayNode(),
                objectMapper.createObjectNode(),
                0.72d,
                "C",
                UUID.fromString("55555555-5555-5555-5555-555555555555"),
                DOMAIN_ID,
                "gated-query-1",
                "33333333-3333-3333-3333-333333333333",
                "22222222-2222-2222-2222-222222222222"
        );
    }

    private RagRetrievalExecutionService.RawOnlyExecutionResult rawResult(
            String query,
            List<RagRepository.RetrievalDoc> docs
    ) {
        return rawResult(query, query, docs);
    }

    private RagRetrievalExecutionService.RawOnlyExecutionResult rawResult(
            String query,
            String finalQuery,
            List<RagRepository.RetrievalDoc> docs
    ) {
        return new RagRetrievalExecutionService.RawOnlyExecutionResult(
                query,
                finalQuery,
                retrieval(query, docs, 0.9d),
                11L
        );
    }

    private RagRetrievalExecutionService.SelectiveRewriteExecutionResult selectiveResult(
            String finalQuery,
            List<RagRepository.RetrievalDoc> docs
    ) {
        return new RagRetrievalExecutionService.SelectiveRewriteExecutionResult(
                QUERY,
                List.of(candidate(finalQuery, docs, 0.85d)),
                "retriever",
                objectMapper.createObjectNode(),
                12L
        );
    }

    private RagRetrievalExecutionService.AnchorAwareRewriteExecutionResult anchorResult(
            String finalQuery,
            List<RagRepository.RetrievalDoc> docs
    ) {
        return new RagRetrievalExecutionService.AnchorAwareRewriteExecutionResult(
                QUERY,
                List.of(candidate(finalQuery, docs, 0.87d)),
                true,
                "retriever",
                objectMapper.createObjectNode(),
                13L
        );
    }

    private RagRetrievalExecutionService.ExecutedRewriteCandidate candidate(
            String query,
            List<RagRepository.RetrievalDoc> docs,
            double confidence
    ) {
        return new RagRetrievalExecutionService.ExecutedRewriteCandidate(
                1,
                "candidate-1",
                query,
                objectMapper.createObjectNode(),
                retrieval(query, docs, confidence)
        );
    }

    private RagRetrievalExecutionService.RetrievalMaterial retrieval(
            String query,
            List<RagRepository.RetrievalDoc> docs,
            double confidence
    ) {
        return new RagRetrievalExecutionService.RetrievalMaterial(
                query,
                docs,
                docs,
                confidence,
                "retriever",
                "reranker",
                objectMapper.createObjectNode(),
                10L
        );
    }

    private QueryRouteDecision routeDecision(QueryStrategy strategy) {
        return new QueryRouteDecision(
                strategy,
                "test",
                true,
                false,
                false,
                null,
                "strategy_router",
                "compact_anchor",
                true,
                Map.of()
        );
    }

    private AgenticRetrievalService.AgenticExecutionResult agenticResult(List<RagRepository.RetrievalDoc> docs) {
        RagDtos.AgenticQueryPlan plan = new RagDtos.AgenticQueryPlan(
                QUERY,
                DOMAIN_ID,
                "spring-security",
                "Spring Security",
                4,
                List.of(new RagDtos.AgenticSubquery(
                        1,
                        QUERY,
                        "original_query",
                        1.0d,
                        objectMapper.createObjectNode()
                )),
                "fallback-original-query",
                true,
                "test",
                objectMapper.createObjectNode()
        );
        return new AgenticRetrievalService.AgenticExecutionResult(
                plan,
                List.of(),
                List.of(),
                docs,
                false,
                "agentic_multi_query_rrf",
                null,
                1L,
                14L,
                objectMapper.createObjectNode()
        );
    }

    private void assertEvalError(
            RagRetrievalEvalDtos.RagRetrievalEvalRequest request,
            String code,
            String message
    ) {
        assertThatThrownBy(() -> service.execute(request))
                .isInstanceOf(RagRetrievalEvalException.class)
                .hasMessage(message)
                .satisfies(exception ->
                        assertThat(((RagRetrievalEvalException) exception).code()).isEqualTo(code));
    }
}
