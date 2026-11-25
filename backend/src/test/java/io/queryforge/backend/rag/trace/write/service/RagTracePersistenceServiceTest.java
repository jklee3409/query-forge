package io.queryforge.backend.rag.trace.write.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.queryforge.backend.rag.chat.answer.service.ChatAnswerService;
import io.queryforge.backend.rag.retrieval.candidate.model.RetrievalDoc;
import io.queryforge.backend.rag.retrieval.execution.dto.RagRetrievalExecutionResult;
import io.queryforge.backend.rag.retrieval.execution.dto.RetrievalExecutionDtos;
import io.queryforge.backend.rag.retrieval.execution.model.ForcedRetrievalMode;
import io.queryforge.backend.rag.retrieval.execution.model.RagPersistPolicy;
import io.queryforge.backend.rag.retrieval.memory.model.MemoryCandidate;
import io.queryforge.backend.rag.trace.write.agentic.dto.AgenticTraceWriteDtos;
import io.queryforge.backend.rag.trace.write.agentic.service.AgenticTracePersistenceService;
import io.queryforge.backend.rag.trace.write.dto.RagTraceWriteDtos;
import io.queryforge.backend.rag.trace.write.dto.TraceWriteResults;
import io.queryforge.backend.rag.trace.write.repository.RagTraceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.util.Arrays;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RagTracePersistenceServiceTest {

    @Mock
    private RagTraceRepository traceRepository;

    private RagTracePersistenceService service;
    private AgenticTracePersistenceService agenticTracePersistenceService;

    @BeforeEach
    void setUp() {
        service = new RagTracePersistenceService(traceRepository, new ObjectMapper());
        agenticTracePersistenceService = new AgenticTracePersistenceService(traceRepository);
    }

    @Test
    void nonePolicyPerformsNoRepositoryWrites() {
        UUID onlineQueryId = UUID.fromString("11111111-1111-1111-1111-111111111111");

        TraceWriteResults.RagTracePersistenceResult result = service.persist(
                request(RagPersistPolicy.NONE, onlineQueryId)
        );

        assertThat(result.persistPolicy()).isEqualTo(RagPersistPolicy.NONE);
        assertThat(result.onlineQueryId()).isEqualTo(onlineQueryId);
        assertThat(result.persisted()).isFalse();
        assertThat(result.status()).isEqualTo("skipped_none");
        verifyNoPersistenceWrites();
        verifyNoInteractions(traceRepository);
    }

    @Test
    void nullPolicyDefaultsToNoneWithoutRepositoryWrites() {
        TraceWriteResults.RagTracePersistenceResult result = service.persist(
                request(null, null)
        );

        assertThat(result.persistPolicy()).isEqualTo(RagPersistPolicy.NONE);
        assertThat(result.onlineQueryId()).isNull();
        assertThat(result.persisted()).isFalse();
        assertThat(result.status()).isEqualTo("skipped_none");
        verifyNoInteractions(traceRepository);
    }

    @Test
    void traceOnlyPolicyIsExplicitlyUnsupportedInPhase5F() {
        assertThatThrownBy(() -> service.persist(request(RagPersistPolicy.TRACE_ONLY, null)))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("TRACE_ONLY")
                .hasMessageContaining("Phase 5F");

        verifyNoInteractions(traceRepository);
    }

    @Test
    void onlineQueryGenericPolicyIsExplicitlyUnsupportedInPhase5F() {
        assertThatThrownBy(() -> service.persist(request(RagPersistPolicy.ONLINE_QUERY, null)))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("ONLINE_QUERY")
                .hasMessageContaining("phase-specific");

        verifyNoInteractions(traceRepository);
    }

    @Test
    void onlineQueryRawOnlyDecisionPersistsDecisionOnly() {
        UUID onlineQueryId = UUID.fromString("20202020-2020-2020-2020-202020202020");
        ObjectNode memoryTopN = JsonNodeFactory.instance.objectNode().put("count", 0);
        ObjectNode latency = JsonNodeFactory.instance.objectNode().put("totalMs", 25L);

        TraceWriteResults.RagTracePersistenceResult result = service.persistOnlineQueryDecision(
                onlineDecisionRequest(
                        RagPersistPolicy.ONLINE_QUERY,
                        onlineQueryId,
                        RetrievalExecutionDtos.NonAgenticExecutionKind.RAW_ONLY,
                        "FilterChainProxy order",
                        "FilterChainProxy order",
                        false,
                        memoryTopN,
                        0.42d,
                        null,
                        3,
                        "mode_raw_only",
                        "query_router_strategy=raw_only",
                        latency
                )
        );

        assertThat(result.persistPolicy()).isEqualTo(RagPersistPolicy.ONLINE_QUERY);
        assertThat(result.onlineQueryId()).isEqualTo(onlineQueryId);
        assertThat(result.persisted()).isTrue();
        assertThat(result.status()).isEqualTo("persisted_raw_only_online_query_decision");
        verify(traceRepository).upsertOnlineQueryDecision(
                eq(onlineQueryId),
                eq("FilterChainProxy order"),
                eq(false),
                eq(memoryTopN),
                eq(0.42d),
                isNull(),
                eq("mode_raw_only"),
                eq("query_router_strategy=raw_only"),
                eq(latency)
        );
        verifyNoForbiddenOnlineWritesExceptOnlineQueryDecision();
    }

    @Test
    void onlineQuerySelectiveRewriteDecisionPersistsDecisionOnly() {
        UUID onlineQueryId = UUID.fromString("21212121-2121-2121-2121-212121212121");
        UUID rewriteCandidateId = UUID.fromString("22222222-3333-4444-5555-666666666666");
        ObjectNode memoryTopN = JsonNodeFactory.instance.objectNode().put("count", 2);
        ObjectNode latency = JsonNodeFactory.instance.objectNode().put("totalMs", 31L);

        TraceWriteResults.RagTracePersistenceResult result = service.persistOnlineQueryDecision(
                onlineDecisionRequest(
                        RagPersistPolicy.ONLINE_QUERY,
                        onlineQueryId,
                        RetrievalExecutionDtos.NonAgenticExecutionKind.SELECTIVE_REWRITE,
                        "spring filter order",
                        "FilterChainProxy SecurityFilterChain order",
                        true,
                        memoryTopN,
                        0.21d,
                        rewriteCandidateId,
                        4,
                        "delta_above_threshold",
                        null,
                        latency
                )
        );

        assertThat(result.persisted()).isTrue();
        assertThat(result.status()).isEqualTo("persisted_selective_rewrite_online_query_decision");
        verify(traceRepository).upsertOnlineQueryDecision(
                eq(onlineQueryId),
                eq("FilterChainProxy SecurityFilterChain order"),
                eq(true),
                eq(memoryTopN),
                eq(0.21d),
                eq(rewriteCandidateId),
                eq("delta_above_threshold"),
                isNull(),
                eq(latency)
        );
        verifyNoForbiddenOnlineWritesExceptOnlineQueryDecision();
    }

    @Test
    void onlineQueryAnchorAwareRewriteDecisionPersistsDecisionOnly() {
        UUID onlineQueryId = UUID.fromString("23232323-2323-2323-2323-232323232323");
        UUID rewriteCandidateId = UUID.fromString("24242424-2424-2424-2424-242424242424");
        ObjectNode memoryTopN = JsonNodeFactory.instance.objectNode().put("count", 1);
        ObjectNode latency = JsonNodeFactory.instance.objectNode().put("totalMs", 38L);

        TraceWriteResults.RagTracePersistenceResult result = service.persistOnlineQueryDecision(
                onlineDecisionRequest(
                        RagPersistPolicy.ONLINE_QUERY,
                        onlineQueryId,
                        RetrievalExecutionDtos.NonAgenticExecutionKind.ANCHOR_AWARE_REWRITE,
                        "FilterChainProxy order",
                        "FilterChainProxy order in Spring Security",
                        true,
                        memoryTopN,
                        0.33d,
                        rewriteCandidateId,
                        5,
                        "delta_above_threshold",
                        null,
                        latency
                )
        );

        assertThat(result.persisted()).isTrue();
        assertThat(result.status()).isEqualTo("persisted_anchor_aware_rewrite_online_query_decision");
        verify(traceRepository).upsertOnlineQueryDecision(
                eq(onlineQueryId),
                eq("FilterChainProxy order in Spring Security"),
                eq(true),
                eq(memoryTopN),
                eq(0.33d),
                eq(rewriteCandidateId),
                eq("delta_above_threshold"),
                isNull(),
                eq(latency)
        );
        verifyNoForbiddenOnlineWritesExceptOnlineQueryDecision();
    }

    @Test
    void onlineQueryMetadataMergePersistsMetadataOnly() {
        UUID onlineQueryId = UUID.fromString("25252525-2525-2525-2525-252525252525");
        ObjectNode metadata = JsonNodeFactory.instance.objectNode();
        metadata.putObject("router").put("strategy", "SYNTHETIC_SELECTIVE_REWRITE");

        TraceWriteResults.RagTracePersistenceResult result = service.mergeOnlineQueryMetadata(
                metadataMergeRequest(
                        RagPersistPolicy.ONLINE_QUERY,
                        onlineQueryId,
                        RetrievalExecutionDtos.NonAgenticExecutionKind.SELECTIVE_REWRITE,
                        metadata,
                        "router"
                )
        );

        assertThat(result.persistPolicy()).isEqualTo(RagPersistPolicy.ONLINE_QUERY);
        assertThat(result.onlineQueryId()).isEqualTo(onlineQueryId);
        assertThat(result.persisted()).isTrue();
        assertThat(result.status()).isEqualTo("persisted_selective_rewrite_online_query_metadata");
        verify(traceRepository).mergeOnlineQueryMetadata(eq(onlineQueryId), eq(metadata));
        verifyNoForbiddenOnlineWritesExceptOnlineQueryMetadata();
    }

    @Test
    void decisionAndMetadataNonePolicyPerformsNoRepositoryWrites() {
        UUID onlineQueryId = UUID.fromString("26262626-2626-2626-2626-262626262626");

        TraceWriteResults.RagTracePersistenceResult decisionResult = service.persistOnlineQueryDecision(
                onlineDecisionRequest(
                        RagPersistPolicy.NONE,
                        onlineQueryId,
                        RetrievalExecutionDtos.NonAgenticExecutionKind.RAW_ONLY,
                        "FilterChainProxy order",
                        "FilterChainProxy order",
                        false,
                        JsonNodeFactory.instance.objectNode(),
                        0.42d,
                        null,
                        3,
                        "mode_raw_only",
                        "query_router_strategy=raw_only",
                        JsonNodeFactory.instance.objectNode()
                )
        );
        TraceWriteResults.RagTracePersistenceResult metadataResult = service.mergeOnlineQueryMetadata(
                metadataMergeRequest(
                        RagPersistPolicy.NONE,
                        onlineQueryId,
                        RetrievalExecutionDtos.NonAgenticExecutionKind.RAW_ONLY,
                        JsonNodeFactory.instance.objectNode(),
                        "router"
                )
        );

        assertThat(decisionResult.persisted()).isFalse();
        assertThat(decisionResult.status()).isEqualTo("skipped_none");
        assertThat(metadataResult.persisted()).isFalse();
        assertThat(metadataResult.status()).isEqualTo("skipped_none");
        verifyNoInteractions(traceRepository);
    }

    @Test
    void decisionAndMetadataTraceOnlyPolicyRemainsUnsupported() {
        UUID onlineQueryId = UUID.fromString("27272727-2727-2727-2727-272727272727");

        assertThatThrownBy(() -> service.persistOnlineQueryDecision(onlineDecisionRequest(
                RagPersistPolicy.TRACE_ONLY,
                onlineQueryId,
                RetrievalExecutionDtos.NonAgenticExecutionKind.RAW_ONLY,
                "FilterChainProxy order",
                "FilterChainProxy order",
                false,
                JsonNodeFactory.instance.objectNode(),
                0.42d,
                null,
                3,
                "mode_raw_only",
                "query_router_strategy=raw_only",
                JsonNodeFactory.instance.objectNode()
        )))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("TRACE_ONLY")
                .hasMessageContaining("Phase 5F");

        assertThatThrownBy(() -> service.mergeOnlineQueryMetadata(metadataMergeRequest(
                RagPersistPolicy.TRACE_ONLY,
                onlineQueryId,
                RetrievalExecutionDtos.NonAgenticExecutionKind.RAW_ONLY,
                JsonNodeFactory.instance.objectNode(),
                "router"
        )))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("TRACE_ONLY")
                .hasMessageContaining("Phase 5F");

        verifyNoInteractions(traceRepository);
    }

    @Test
    void onlineQueryRawOnlyRetrievalTracePersistsRetrievalResultsOnly() {
        UUID onlineQueryId = UUID.fromString("22222222-2222-2222-2222-222222222222");
        List<RetrievalDoc> retrievedDocs = docs("doc-1", "chunk-1", 0.82d);
        List<RetrievalDoc> rerankedDocs = docs("doc-2", "chunk-2", 0.92d);
        ObjectNode metadata = JsonNodeFactory.instance.objectNode().put("retriever_mode", "dense_only");

        TraceWriteResults.RagTracePersistenceResult result = service.persistRawOnlyTrace(
                rawOnlyRequest(
                        RagPersistPolicy.ONLINE_QUERY,
                        onlineQueryId,
                        retrievedDocs,
                        rerankedDocs,
                        metadata,
                        RagTraceWriteDtos.RawOnlyTraceWriteScope.RETRIEVAL
                )
        );

        assertThat(result.persistPolicy()).isEqualTo(RagPersistPolicy.ONLINE_QUERY);
        assertThat(result.onlineQueryId()).isEqualTo(onlineQueryId);
        assertThat(result.persisted()).isTrue();
        assertThat(result.status()).isEqualTo("persisted_raw_only_retrieval");
        ArgumentCaptor<List<RetrievalDoc>> docsCaptor = ArgumentCaptor.forClass(List.class);
        verify(traceRepository).insertRetrievalResults(
                eq(onlineQueryId),
                isNull(),
                eq("raw"),
                docsCaptor.capture(),
                eq("raw_only"),
                eq("local:dense_only:hash-embedding-v1"),
                eq(metadata)
        );
        assertThat(docsCaptor.getValue()).isEqualTo(retrievedDocs);
        verify(traceRepository, never()).insertRerankResults(any(), any(), anyList(), anyString());
        verifyNoForbiddenOnlineWrites();
    }

    @Test
    void onlineQueryRawOnlyRerankTracePersistsRerankResultsOnly() {
        UUID onlineQueryId = UUID.fromString("33333333-3333-3333-3333-333333333333");
        List<RetrievalDoc> retrievedDocs = docs("doc-1", "chunk-1", 0.82d);
        List<RetrievalDoc> rerankedDocs = docs("doc-2", "chunk-2", 0.92d);
        ObjectNode metadata = JsonNodeFactory.instance.objectNode().put("retriever_mode", "dense_only");

        TraceWriteResults.RagTracePersistenceResult result = service.persistRawOnlyTrace(
                rawOnlyRequest(
                        RagPersistPolicy.ONLINE_QUERY,
                        onlineQueryId,
                        retrievedDocs,
                        rerankedDocs,
                        metadata,
                        RagTraceWriteDtos.RawOnlyTraceWriteScope.RERANK
                )
        );

        assertThat(result.persistPolicy()).isEqualTo(RagPersistPolicy.ONLINE_QUERY);
        assertThat(result.onlineQueryId()).isEqualTo(onlineQueryId);
        assertThat(result.persisted()).isTrue();
        assertThat(result.status()).isEqualTo("persisted_raw_only_rerank");
        verify(traceRepository).insertRerankResults(
                eq(onlineQueryId),
                isNull(),
                eq(rerankedDocs),
                eq("local-rerank-fallback")
        );
        verify(traceRepository, never()).insertRetrievalResults(any(), any(), anyString(), anyList(), anyString(), anyString(), any());
        verifyNoForbiddenOnlineWrites();
    }

    @Test
    void rawOnlyTraceNonePolicyPerformsNoRepositoryWrites() {
        TraceWriteResults.RagTracePersistenceResult result = service.persistRawOnlyTrace(
                rawOnlyRequest(
                        RagPersistPolicy.NONE,
                        UUID.fromString("44444444-4444-4444-4444-444444444444"),
                        docs("doc-1", "chunk-1", 0.82d),
                        docs("doc-2", "chunk-2", 0.92d),
                        JsonNodeFactory.instance.objectNode(),
                        RagTraceWriteDtos.RawOnlyTraceWriteScope.RETRIEVAL
                )
        );

        assertThat(result.persistPolicy()).isEqualTo(RagPersistPolicy.NONE);
        assertThat(result.persisted()).isFalse();
        assertThat(result.status()).isEqualTo("skipped_none");
        verifyNoInteractions(traceRepository);
    }

    @Test
    void rawOnlyTraceOnlyPolicyRemainsUnsupported() {
        assertThatThrownBy(() -> service.persistRawOnlyTrace(rawOnlyRequest(
                RagPersistPolicy.TRACE_ONLY,
                UUID.fromString("55555555-5555-5555-5555-555555555555"),
                docs("doc-1", "chunk-1", 0.82d),
                docs("doc-2", "chunk-2", 0.92d),
                JsonNodeFactory.instance.objectNode(),
                RagTraceWriteDtos.RawOnlyTraceWriteScope.RETRIEVAL
        )))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("TRACE_ONLY")
                .hasMessageContaining("Phase 5F");

        verifyNoInteractions(traceRepository);
    }

    @Test
    void onlineQuerySelectiveRewriteCandidateRetrievalPersistsRetrievalResultsOnly() {
        UUID onlineQueryId = UUID.fromString("66666666-6666-6666-6666-666666666666");
        UUID rewriteCandidateId = UUID.fromString("77777777-7777-7777-7777-777777777777");
        List<RetrievalDoc> retrievedDocs = docs("doc-1", "chunk-1", 0.81d);
        ObjectNode metadata = JsonNodeFactory.instance.objectNode().put("retriever_mode", "dense_only");

        TraceWriteResults.RagTracePersistenceResult result = service.persistRewriteCandidateTrace(
                rewriteCandidateRequest(
                        RagPersistPolicy.ONLINE_QUERY,
                        onlineQueryId,
                        rewriteCandidateId,
                        RetrievalExecutionDtos.NonAgenticExecutionKind.SELECTIVE_REWRITE,
                        retrievedDocs,
                        docs("doc-2", "chunk-2", 0.91d),
                        metadata,
                        RagTraceWriteDtos.RewriteCandidateTraceWriteScope.CANDIDATE_RETRIEVAL
                )
        );

        assertThat(result.persistPolicy()).isEqualTo(RagPersistPolicy.ONLINE_QUERY);
        assertThat(result.onlineQueryId()).isEqualTo(onlineQueryId);
        assertThat(result.persisted()).isTrue();
        assertThat(result.status()).isEqualTo("persisted_selective_rewrite_candidate_retrieval");
        verify(traceRepository).insertRetrievalResults(
                eq(onlineQueryId),
                eq(rewriteCandidateId),
                eq("rewrite_candidate"),
                eq(retrievedDocs),
                eq("selective_rewrite"),
                eq("local:dense_only:hash-embedding-v1"),
                eq(metadata)
        );
        verify(traceRepository, never()).insertRerankResults(any(), any(), anyList(), anyString());
        verifyNoForbiddenOnlineWrites();
    }

    @Test
    void onlineQuerySelectiveRewriteCandidateRerankPersistsRerankResultsOnly() {
        UUID onlineQueryId = UUID.fromString("88888888-8888-8888-8888-888888888888");
        UUID rewriteCandidateId = UUID.fromString("99999999-9999-9999-9999-999999999999");
        List<RetrievalDoc> rerankedDocs = docs("doc-2", "chunk-2", 0.91d);

        TraceWriteResults.RagTracePersistenceResult result = service.persistRewriteCandidateTrace(
                rewriteCandidateRequest(
                        RagPersistPolicy.ONLINE_QUERY,
                        onlineQueryId,
                        rewriteCandidateId,
                        RetrievalExecutionDtos.NonAgenticExecutionKind.SELECTIVE_REWRITE,
                        docs("doc-1", "chunk-1", 0.81d),
                        rerankedDocs,
                        JsonNodeFactory.instance.objectNode(),
                        RagTraceWriteDtos.RewriteCandidateTraceWriteScope.CANDIDATE_RERANK
                )
        );

        assertThat(result.persistPolicy()).isEqualTo(RagPersistPolicy.ONLINE_QUERY);
        assertThat(result.persisted()).isTrue();
        assertThat(result.status()).isEqualTo("persisted_selective_rewrite_candidate_rerank");
        verify(traceRepository).insertRerankResults(
                eq(onlineQueryId),
                eq(rewriteCandidateId),
                eq(rerankedDocs),
                eq("local-rerank-fallback")
        );
        verify(traceRepository, never()).insertRetrievalResults(any(), any(), anyString(), anyList(), anyString(), anyString(), any());
        verifyNoForbiddenOnlineWrites();
    }

    @Test
    void onlineQueryAnchorAwareRewriteCandidateRetrievalPersistsRetrievalResultsOnly() {
        UUID onlineQueryId = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
        UUID rewriteCandidateId = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
        List<RetrievalDoc> retrievedDocs = docs("doc-3", "chunk-3", 0.84d);
        ObjectNode metadata = JsonNodeFactory.instance.objectNode().put("retriever_mode", "hybrid");

        TraceWriteResults.RagTracePersistenceResult result = service.persistRewriteCandidateTrace(
                rewriteCandidateRequest(
                        RagPersistPolicy.ONLINE_QUERY,
                        onlineQueryId,
                        rewriteCandidateId,
                        RetrievalExecutionDtos.NonAgenticExecutionKind.ANCHOR_AWARE_REWRITE,
                        retrievedDocs,
                        docs("doc-4", "chunk-4", 0.94d),
                        metadata,
                        RagTraceWriteDtos.RewriteCandidateTraceWriteScope.CANDIDATE_RETRIEVAL
                )
        );

        assertThat(result.persistPolicy()).isEqualTo(RagPersistPolicy.ONLINE_QUERY);
        assertThat(result.persisted()).isTrue();
        assertThat(result.status()).isEqualTo("persisted_anchor_aware_rewrite_candidate_retrieval");
        verify(traceRepository).insertRetrievalResults(
                eq(onlineQueryId),
                eq(rewriteCandidateId),
                eq("rewrite_candidate"),
                eq(retrievedDocs),
                eq("selective_rewrite"),
                eq("local:dense_only:hash-embedding-v1"),
                eq(metadata)
        );
        verify(traceRepository, never()).insertRerankResults(any(), any(), anyList(), anyString());
        verifyNoForbiddenOnlineWrites();
    }

    @Test
    void onlineQueryAnchorAwareRewriteCandidateRerankPersistsRerankResultsOnly() {
        UUID onlineQueryId = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");
        UUID rewriteCandidateId = UUID.fromString("dddddddd-dddd-dddd-dddd-dddddddddddd");
        List<RetrievalDoc> rerankedDocs = docs("doc-4", "chunk-4", 0.94d);

        TraceWriteResults.RagTracePersistenceResult result = service.persistRewriteCandidateTrace(
                rewriteCandidateRequest(
                        RagPersistPolicy.ONLINE_QUERY,
                        onlineQueryId,
                        rewriteCandidateId,
                        RetrievalExecutionDtos.NonAgenticExecutionKind.ANCHOR_AWARE_REWRITE,
                        docs("doc-3", "chunk-3", 0.84d),
                        rerankedDocs,
                        JsonNodeFactory.instance.objectNode(),
                        RagTraceWriteDtos.RewriteCandidateTraceWriteScope.CANDIDATE_RERANK
                )
        );

        assertThat(result.persistPolicy()).isEqualTo(RagPersistPolicy.ONLINE_QUERY);
        assertThat(result.persisted()).isTrue();
        assertThat(result.status()).isEqualTo("persisted_anchor_aware_rewrite_candidate_rerank");
        verify(traceRepository).insertRerankResults(
                eq(onlineQueryId),
                eq(rewriteCandidateId),
                eq(rerankedDocs),
                eq("local-rerank-fallback")
        );
        verify(traceRepository, never()).insertRetrievalResults(any(), any(), anyString(), anyList(), anyString(), anyString(), any());
        verifyNoForbiddenOnlineWrites();
    }

    @Test
    void rewriteCandidateTraceNonePolicyPerformsNoRepositoryWrites() {
        TraceWriteResults.RagTracePersistenceResult result = service.persistRewriteCandidateTrace(
                rewriteCandidateRequest(
                        RagPersistPolicy.NONE,
                        UUID.fromString("eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee"),
                        UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff"),
                        RetrievalExecutionDtos.NonAgenticExecutionKind.SELECTIVE_REWRITE,
                        docs("doc-1", "chunk-1", 0.81d),
                        docs("doc-2", "chunk-2", 0.91d),
                        JsonNodeFactory.instance.objectNode(),
                        RagTraceWriteDtos.RewriteCandidateTraceWriteScope.CANDIDATE_RETRIEVAL
                )
        );

        assertThat(result.persistPolicy()).isEqualTo(RagPersistPolicy.NONE);
        assertThat(result.persisted()).isFalse();
        assertThat(result.status()).isEqualTo("skipped_none");
        verifyNoInteractions(traceRepository);
    }

    @Test
    void rewriteCandidateTraceOnlyPolicyRemainsUnsupported() {
        assertThatThrownBy(() -> service.persistRewriteCandidateTrace(rewriteCandidateRequest(
                RagPersistPolicy.TRACE_ONLY,
                UUID.fromString("12121212-1212-1212-1212-121212121212"),
                UUID.fromString("34343434-3434-3434-3434-343434343434"),
                RetrievalExecutionDtos.NonAgenticExecutionKind.SELECTIVE_REWRITE,
                docs("doc-1", "chunk-1", 0.81d),
                docs("doc-2", "chunk-2", 0.91d),
                JsonNodeFactory.instance.objectNode(),
                RagTraceWriteDtos.RewriteCandidateTraceWriteScope.CANDIDATE_RETRIEVAL
        )))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("TRACE_ONLY")
                .hasMessageContaining("Phase 5F");

        verifyNoInteractions(traceRepository);
    }

    @Test
    void onlineQueryAgenticSubqueryRawRetrievalPersistsRetrievalResultsOnly() {
        UUID onlineQueryId = UUID.fromString("46464646-4646-4646-4646-464646464646");
        List<RetrievalDoc> retrievedDocs = docs("doc-agentic-raw", "chunk-agentic-raw", 0.83d);
        ObjectNode metadata = JsonNodeFactory.instance.objectNode()
                .put("agentic_phase", "raw")
                .put("subquery_index", 1);

        TraceWriteResults.RagTracePersistenceResult result = agenticTracePersistenceService.persistAgenticSubqueryRetrievalTrace(
                agenticSubqueryRequest(
                        RagPersistPolicy.ONLINE_QUERY,
                        onlineQueryId,
                        null,
                        retrievedDocs,
                        metadata,
                        AgenticTraceWriteDtos.AgenticSubqueryRetrievalTraceWriteScope.SUBQUERY_RAW_RETRIEVAL
                )
        );

        assertThat(result.persistPolicy()).isEqualTo(RagPersistPolicy.ONLINE_QUERY);
        assertThat(result.onlineQueryId()).isEqualTo(onlineQueryId);
        assertThat(result.persisted()).isTrue();
        assertThat(result.status()).isEqualTo("persisted_agentic_multi_query_subquery_raw_retrieval");
        verify(traceRepository).insertRetrievalResults(
                eq(onlineQueryId),
                isNull(),
                eq("raw"),
                eq(retrievedDocs),
                eq("agentic_multi_query"),
                eq("local:dense_only:hash-embedding-v1"),
                eq(metadata)
        );
        verify(traceRepository, never()).insertRerankResults(any(), any(), anyList(), anyString());
        verifyNoForbiddenOnlineWrites();
    }

    @Test
    void onlineQueryAgenticSubqueryCandidateRetrievalPersistsRetrievalResultsOnly() {
        UUID onlineQueryId = UUID.fromString("47474747-4747-4747-4747-474747474747");
        UUID rewriteCandidateId = UUID.fromString("48484848-4848-4848-4848-484848484848");
        List<RetrievalDoc> retrievedDocs = docs("doc-agentic-candidate", "chunk-agentic-candidate", 0.91d);
        ObjectNode metadata = JsonNodeFactory.instance.objectNode()
                .put("agentic_phase", "rewrite_candidate")
                .put("subquery_index", 1);

        TraceWriteResults.RagTracePersistenceResult result = agenticTracePersistenceService.persistAgenticSubqueryRetrievalTrace(
                agenticSubqueryRequest(
                        RagPersistPolicy.ONLINE_QUERY,
                        onlineQueryId,
                        rewriteCandidateId,
                        retrievedDocs,
                        metadata,
                        AgenticTraceWriteDtos.AgenticSubqueryRetrievalTraceWriteScope.SUBQUERY_CANDIDATE_RETRIEVAL
                )
        );

        assertThat(result.persistPolicy()).isEqualTo(RagPersistPolicy.ONLINE_QUERY);
        assertThat(result.persisted()).isTrue();
        assertThat(result.status()).isEqualTo("persisted_agentic_multi_query_subquery_candidate_retrieval");
        verify(traceRepository).insertRetrievalResults(
                eq(onlineQueryId),
                eq(rewriteCandidateId),
                eq("rewrite_candidate"),
                eq(retrievedDocs),
                eq("agentic_multi_query"),
                eq("local:dense_only:hash-embedding-v1"),
                eq(metadata)
        );
        verify(traceRepository, never()).insertRerankResults(any(), any(), anyList(), anyString());
        verifyNoForbiddenOnlineWrites();
    }

    @Test
    void agenticSubqueryRetrievalNonePolicyPerformsNoRepositoryWrites() {
        TraceWriteResults.RagTracePersistenceResult result = agenticTracePersistenceService.persistAgenticSubqueryRetrievalTrace(
                agenticSubqueryRequest(
                        RagPersistPolicy.NONE,
                        UUID.fromString("49494949-4949-4949-4949-494949494949"),
                        null,
                        docs("doc-agentic-raw", "chunk-agentic-raw", 0.83d),
                        JsonNodeFactory.instance.objectNode(),
                        AgenticTraceWriteDtos.AgenticSubqueryRetrievalTraceWriteScope.SUBQUERY_RAW_RETRIEVAL
                )
        );

        assertThat(result.persistPolicy()).isEqualTo(RagPersistPolicy.NONE);
        assertThat(result.persisted()).isFalse();
        assertThat(result.status()).isEqualTo("skipped_none");
        verifyNoInteractions(traceRepository);
    }

    @Test
    void agenticSubqueryRetrievalTraceOnlyPolicyRemainsUnsupported() {
        assertThatThrownBy(() -> agenticTracePersistenceService.persistAgenticSubqueryRetrievalTrace(agenticSubqueryRequest(
                RagPersistPolicy.TRACE_ONLY,
                UUID.fromString("50505050-5050-5050-5050-505050505050"),
                null,
                docs("doc-agentic-raw", "chunk-agentic-raw", 0.83d),
                JsonNodeFactory.instance.objectNode(),
                AgenticTraceWriteDtos.AgenticSubqueryRetrievalTraceWriteScope.SUBQUERY_RAW_RETRIEVAL
        )))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("TRACE_ONLY")
                .hasMessageContaining("Phase 6A");

        verifyNoInteractions(traceRepository);
    }

    @Test
    void onlineQueryAgenticRewriteCandidateCreationPersistsCandidateRootOnly() {
        UUID onlineQueryId = UUID.fromString("51515151-5151-5151-5151-515151515151");
        UUID rewriteCandidateId = UUID.fromString("52525252-5252-5252-5252-525252525252");
        ObjectNode candidateMetadata = JsonNodeFactory.instance.objectNode()
                .put("subquery_index", 2)
                .put("candidate_template_label", "candidate-1");
        ObjectNode scoreBreakdown = JsonNodeFactory.instance.objectNode().put("r1", 0.91d);

        when(traceRepository.createRewriteCandidate(
                eq(onlineQueryId),
                eq(21),
                eq("subquery_2_candidate-1"),
                eq("FilterChainProxy SecurityFilterChain order"),
                any(),
                any(),
                eq(0.91d),
                eq(scoreBreakdown)
        )).thenReturn(rewriteCandidateId);

        TraceWriteResults.RewriteCandidatePersistenceResult result = agenticTracePersistenceService.createAgenticRewriteCandidateTrace(
                agenticCreateCandidateRequest(
                        RagPersistPolicy.ONLINE_QUERY,
                        onlineQueryId,
                        candidateMetadata,
                        scoreBreakdown
                )
        );

        assertThat(result.persistPolicy()).isEqualTo(RagPersistPolicy.ONLINE_QUERY);
        assertThat(result.onlineQueryId()).isEqualTo(onlineQueryId);
        assertThat(result.rewriteCandidateId()).isEqualTo(rewriteCandidateId);
        assertThat(result.persisted()).isTrue();
        assertThat(result.status()).isEqualTo("persisted_agentic_multi_query_candidate_root");
        verify(traceRepository).createRewriteCandidate(
                eq(onlineQueryId),
                eq(21),
                eq("subquery_2_candidate-1"),
                eq("FilterChainProxy SecurityFilterChain order"),
                any(),
                any(),
                eq(0.91d),
                eq(scoreBreakdown)
        );
        verifyNoForbiddenOnlineWritesExceptCandidateRoot();
    }

    @Test
    void onlineQueryAgenticRewriteCandidateAdoptionPersistsAdoptionOnly() {
        UUID onlineQueryId = UUID.fromString("53535353-5353-5353-5353-535353535353");
        UUID rewriteCandidateId = UUID.fromString("54545454-5454-5454-5454-545454545454");

        TraceWriteResults.RewriteCandidatePersistenceResult result = agenticTracePersistenceService.markAgenticRewriteCandidateAdopted(
                new AgenticTraceWriteDtos.AgenticRewriteCandidateAdoptionPersistenceRequest(
                        RagPersistPolicy.ONLINE_QUERY,
                        onlineQueryId,
                        rewriteCandidateId,
                        AgenticTraceWriteDtos.AgenticRetrievalExecutionKind.AGENTIC_MULTI_QUERY,
                        2,
                        true,
                        null
                )
        );

        assertThat(result.persistPolicy()).isEqualTo(RagPersistPolicy.ONLINE_QUERY);
        assertThat(result.onlineQueryId()).isEqualTo(onlineQueryId);
        assertThat(result.rewriteCandidateId()).isEqualTo(rewriteCandidateId);
        assertThat(result.persisted()).isTrue();
        assertThat(result.status()).isEqualTo("persisted_agentic_multi_query_candidate_adoption");
        verify(traceRepository).markRewriteCandidateAdopted(eq(rewriteCandidateId), eq(true), isNull());
        verifyNoForbiddenOnlineWritesExceptCandidateAdoption();
    }

    @Test
    void agenticRewriteCandidateCreationAndAdoptionNonePolicyPerformsNoRepositoryWrites() {
        TraceWriteResults.RewriteCandidatePersistenceResult createResult = agenticTracePersistenceService.createAgenticRewriteCandidateTrace(
                agenticCreateCandidateRequest(
                        RagPersistPolicy.NONE,
                        UUID.fromString("55555555-5555-5555-5555-555555555555"),
                        JsonNodeFactory.instance.objectNode(),
                        JsonNodeFactory.instance.objectNode()
                )
        );
        TraceWriteResults.RewriteCandidatePersistenceResult adoptionResult = agenticTracePersistenceService.markAgenticRewriteCandidateAdopted(
                new AgenticTraceWriteDtos.AgenticRewriteCandidateAdoptionPersistenceRequest(
                        RagPersistPolicy.NONE,
                        UUID.fromString("56565656-5656-5656-5656-565656565656"),
                        UUID.fromString("57575757-5757-5757-5757-575757575757"),
                        AgenticTraceWriteDtos.AgenticRetrievalExecutionKind.AGENTIC_MULTI_QUERY,
                        2,
                        false,
                        "below_threshold"
                )
        );

        assertThat(createResult.persisted()).isFalse();
        assertThat(createResult.status()).isEqualTo("skipped_none");
        assertThat(adoptionResult.persisted()).isFalse();
        assertThat(adoptionResult.status()).isEqualTo("skipped_none");
        verifyNoInteractions(traceRepository);
    }

    @Test
    void agenticRewriteCandidateCreationAndAdoptionTraceOnlyPolicyRemainsUnsupported() {
        assertThatThrownBy(() -> agenticTracePersistenceService.createAgenticRewriteCandidateTrace(agenticCreateCandidateRequest(
                RagPersistPolicy.TRACE_ONLY,
                UUID.fromString("58585858-5858-5858-5858-585858585858"),
                JsonNodeFactory.instance.objectNode(),
                JsonNodeFactory.instance.objectNode()
        )))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("TRACE_ONLY")
                .hasMessageContaining("Phase 6B");

        assertThatThrownBy(() -> agenticTracePersistenceService.markAgenticRewriteCandidateAdopted(
                new AgenticTraceWriteDtos.AgenticRewriteCandidateAdoptionPersistenceRequest(
                        RagPersistPolicy.TRACE_ONLY,
                        UUID.fromString("59595959-5959-5959-5959-595959595959"),
                        UUID.fromString("60606060-6060-6060-6060-606060606060"),
                        AgenticTraceWriteDtos.AgenticRetrievalExecutionKind.AGENTIC_MULTI_QUERY,
                        2,
                        false,
                        "below_threshold"
                )
        ))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("TRACE_ONLY")
                .hasMessageContaining("Phase 6B");

        verifyNoInteractions(traceRepository);
    }

    @Test
    void onlineQueryAgenticFinalRrfRerankPersistsRerankResultsOnly() {
        UUID onlineQueryId = UUID.fromString("61616161-6161-6161-6161-616161616161");
        List<RetrievalDoc> finalMergedDocs = docs("doc-agentic-final", "chunk-agentic-final", 0.96d);

        TraceWriteResults.RagTracePersistenceResult result = agenticTracePersistenceService.persistAgenticFinalRerankTrace(
                agenticFinalRerankRequest(
                        RagPersistPolicy.ONLINE_QUERY,
                        onlineQueryId,
                        finalMergedDocs
                )
        );

        assertThat(result.persistPolicy()).isEqualTo(RagPersistPolicy.ONLINE_QUERY);
        assertThat(result.onlineQueryId()).isEqualTo(onlineQueryId);
        assertThat(result.persisted()).isTrue();
        assertThat(result.status()).isEqualTo("persisted_agentic_multi_query_final_rrf_rerank");
        verify(traceRepository).insertRerankResults(
                eq(onlineQueryId),
                isNull(),
                eq(finalMergedDocs),
                eq("agentic-rrf")
        );
        verify(traceRepository, never()).insertRetrievalResults(any(), any(), anyString(), anyList(), anyString(), anyString(), any());
        verifyNoForbiddenOnlineWrites();
    }

    @Test
    void agenticFinalRrfRerankNonePolicyPerformsNoRepositoryWrites() {
        TraceWriteResults.RagTracePersistenceResult result = agenticTracePersistenceService.persistAgenticFinalRerankTrace(
                agenticFinalRerankRequest(
                        RagPersistPolicy.NONE,
                        UUID.fromString("62626262-6262-6262-6262-626262626262"),
                        docs("doc-agentic-final", "chunk-agentic-final", 0.96d)
                )
        );

        assertThat(result.persistPolicy()).isEqualTo(RagPersistPolicy.NONE);
        assertThat(result.persisted()).isFalse();
        assertThat(result.status()).isEqualTo("skipped_none");
        verifyNoInteractions(traceRepository);
    }

    @Test
    void agenticFinalRrfRerankTraceOnlyPolicyRemainsUnsupported() {
        assertThatThrownBy(() -> agenticTracePersistenceService.persistAgenticFinalRerankTrace(agenticFinalRerankRequest(
                RagPersistPolicy.TRACE_ONLY,
                UUID.fromString("63636363-6363-6363-6363-636363636363"),
                docs("doc-agentic-final", "chunk-agentic-final", 0.96d)
        )))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("TRACE_ONLY")
                .hasMessageContaining("Phase 6C");

        verifyNoInteractions(traceRepository);
    }

    @Test
    void onlineQueryAgenticRewriteLogPersistsRewriteLogOnly() {
        UUID onlineQueryId = UUID.fromString("64646464-6464-6464-6464-646464646464");
        UUID rewriteLogId = UUID.fromString("65656565-6565-6565-6565-656565656565");
        ObjectNode metadata = JsonNodeFactory.instance.objectNode().put("agentic_multi_query", true);

        when(traceRepository.createOnlineRewriteLog(
                eq(onlineQueryId),
                isNull(),
                eq("spring security and mvc filter order"),
                eq("spring security and mvc filter order"),
                eq("selective_rewrite"),
                any(),
                any(),
                eq(true),
                eq("full_gating"),
                eq(false),
                eq(true),
                eq(false),
                eq(0.88d),
                eq(0.88d),
                eq(0.0d),
                eq("agentic_multi_query_rrf"),
                isNull(),
                eq(metadata)
        )).thenReturn(rewriteLogId);

        TraceWriteResults.OnlineRewriteLogPersistenceResult result =
                agenticTracePersistenceService.createAgenticOnlineRewriteLogTrace(agenticOnlineRewriteLogRequest(
                        RagPersistPolicy.ONLINE_QUERY,
                        onlineQueryId,
                        metadata
                ));

        assertThat(result.persistPolicy()).isEqualTo(RagPersistPolicy.ONLINE_QUERY);
        assertThat(result.onlineQueryId()).isEqualTo(onlineQueryId);
        assertThat(result.rewriteLogId()).isEqualTo(rewriteLogId);
        assertThat(result.persisted()).isTrue();
        assertThat(result.status()).isEqualTo("persisted_agentic_multi_query_rewrite_log");
        verify(traceRepository).createOnlineRewriteLog(
                eq(onlineQueryId),
                isNull(),
                eq("spring security and mvc filter order"),
                eq("spring security and mvc filter order"),
                eq("selective_rewrite"),
                any(),
                any(),
                eq(true),
                eq("full_gating"),
                eq(false),
                eq(true),
                eq(false),
                eq(0.88d),
                eq(0.88d),
                eq(0.0d),
                eq("agentic_multi_query_rrf"),
                isNull(),
                eq(metadata)
        );
        verifyNoForbiddenOnlineWritesExceptRewriteLog();
    }

    @Test
    void onlineQueryAgenticMemoryRetrievalTracePersistsMemoryLogOnly() {
        UUID onlineQueryId = UUID.fromString("66666666-6666-6666-6666-666666666666");
        UUID rewriteLogId = UUID.fromString("67676767-6767-6767-6767-676767676767");
        MemoryCandidate memoryCandidate = memoryCandidate();
        ObjectNode metadata = JsonNodeFactory.instance.objectNode()
                .put("gating_preset", "full_gating")
                .put("agentic_role", "planner_memory_hint");

        TraceWriteResults.RagTracePersistenceResult result =
                agenticTracePersistenceService.insertAgenticMemoryRetrievalTrace(agenticMemoryLogRequest(
                        RagPersistPolicy.ONLINE_QUERY,
                        onlineQueryId,
                        rewriteLogId,
                        memoryCandidate,
                        metadata
                ));

        assertThat(result.persistPolicy()).isEqualTo(RagPersistPolicy.ONLINE_QUERY);
        assertThat(result.onlineQueryId()).isEqualTo(onlineQueryId);
        assertThat(result.persisted()).isTrue();
        assertThat(result.status()).isEqualTo("persisted_agentic_multi_query_memory_retrieval_log");
        verify(traceRepository).insertMemoryRetrievalLog(
                eq(rewriteLogId),
                eq(onlineQueryId),
                eq(1),
                eq(memoryCandidate),
                eq(metadata)
        );
        verifyNoForbiddenOnlineWritesExceptMemoryRetrievalLog();
    }

    @Test
    void onlineQueryAgenticRewriteCandidateTracePersistsCandidateLogOnly() {
        UUID onlineQueryId = UUID.fromString("68686868-6868-6868-6868-686868686868");
        UUID rewriteLogId = UUID.fromString("69696969-6969-6969-6969-696969696969");
        UUID rewriteCandidateId = UUID.fromString("70707070-7070-7070-7070-707070707070");
        ObjectNode metadata = JsonNodeFactory.instance.objectNode()
                .put("mode", "selective_rewrite")
                .put("agentic_multi_query", true);
        ObjectNode scoreBreakdown = JsonNodeFactory.instance.objectNode().put("rrf_score", 0.88d);

        TraceWriteResults.RagTracePersistenceResult result =
                agenticTracePersistenceService.insertAgenticRewriteCandidateTrace(agenticCandidateLogRequest(
                        RagPersistPolicy.ONLINE_QUERY,
                        onlineQueryId,
                        rewriteLogId,
                        rewriteCandidateId,
                        scoreBreakdown,
                        metadata
                ));

        assertThat(result.persistPolicy()).isEqualTo(RagPersistPolicy.ONLINE_QUERY);
        assertThat(result.onlineQueryId()).isEqualTo(onlineQueryId);
        assertThat(result.persisted()).isTrue();
        assertThat(result.status()).isEqualTo("persisted_agentic_multi_query_rewrite_candidate_log");
        verify(traceRepository).insertRewriteCandidateLog(
                eq(rewriteLogId),
                eq(onlineQueryId),
                eq(rewriteCandidateId),
                eq(1),
                eq("agentic-candidate-1"),
                eq("FilterChainProxy order"),
                eq(0.88d),
                eq(false),
                eq("agentic_not_selected"),
                any(),
                eq(scoreBreakdown),
                eq(metadata)
        );
        verifyNoForbiddenOnlineWritesExceptRewriteCandidateLog();
    }

    @Test
    void agenticRewriteMemoryAndCandidateLogNonePolicyPerformsNoRepositoryWrites() {
        UUID onlineQueryId = UUID.fromString("71717171-7171-7171-7171-717171717171");
        UUID rewriteLogId = UUID.fromString("72727272-7272-7272-7272-727272727272");
        UUID rewriteCandidateId = UUID.fromString("73737373-7373-7373-7373-737373737373");

        TraceWriteResults.OnlineRewriteLogPersistenceResult rewriteLogResult =
                agenticTracePersistenceService.createAgenticOnlineRewriteLogTrace(agenticOnlineRewriteLogRequest(
                        RagPersistPolicy.NONE,
                        onlineQueryId,
                        JsonNodeFactory.instance.objectNode()
                ));
        TraceWriteResults.RagTracePersistenceResult memoryResult =
                agenticTracePersistenceService.insertAgenticMemoryRetrievalTrace(agenticMemoryLogRequest(
                        RagPersistPolicy.NONE,
                        onlineQueryId,
                        rewriteLogId,
                        memoryCandidate(),
                        JsonNodeFactory.instance.objectNode()
                ));
        TraceWriteResults.RagTracePersistenceResult candidateResult =
                agenticTracePersistenceService.insertAgenticRewriteCandidateTrace(agenticCandidateLogRequest(
                        RagPersistPolicy.NONE,
                        onlineQueryId,
                        rewriteLogId,
                        rewriteCandidateId,
                        JsonNodeFactory.instance.objectNode(),
                        JsonNodeFactory.instance.objectNode()
                ));

        assertThat(rewriteLogResult.persisted()).isFalse();
        assertThat(rewriteLogResult.rewriteLogId()).isNull();
        assertThat(memoryResult.persisted()).isFalse();
        assertThat(candidateResult.persisted()).isFalse();
        verifyNoInteractions(traceRepository);
    }

    @Test
    void agenticRewriteMemoryAndCandidateLogTraceOnlyPolicyRemainsUnsupported() {
        UUID onlineQueryId = UUID.fromString("74747474-7474-7474-7474-747474747474");
        UUID rewriteLogId = UUID.fromString("75757575-7575-7575-7575-757575757575");
        UUID rewriteCandidateId = UUID.fromString("76767676-7676-7676-7676-767676767676");

        assertThatThrownBy(() -> agenticTracePersistenceService.createAgenticOnlineRewriteLogTrace(agenticOnlineRewriteLogRequest(
                RagPersistPolicy.TRACE_ONLY,
                onlineQueryId,
                JsonNodeFactory.instance.objectNode()
        )))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("TRACE_ONLY")
                .hasMessageContaining("Phase 6D");

        assertThatThrownBy(() -> agenticTracePersistenceService.insertAgenticMemoryRetrievalTrace(agenticMemoryLogRequest(
                RagPersistPolicy.TRACE_ONLY,
                onlineQueryId,
                rewriteLogId,
                memoryCandidate(),
                JsonNodeFactory.instance.objectNode()
        )))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("TRACE_ONLY")
                .hasMessageContaining("Phase 6D");

        assertThatThrownBy(() -> agenticTracePersistenceService.insertAgenticRewriteCandidateTrace(agenticCandidateLogRequest(
                RagPersistPolicy.TRACE_ONLY,
                onlineQueryId,
                rewriteLogId,
                rewriteCandidateId,
                JsonNodeFactory.instance.objectNode(),
                JsonNodeFactory.instance.objectNode()
        )))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("TRACE_ONLY")
                .hasMessageContaining("Phase 6D");

        verifyNoInteractions(traceRepository);
    }

    @Test
    void onlineQueryAgenticOnlineQueryDecisionPersistsDecisionOnly() {
        UUID onlineQueryId = UUID.fromString("77777777-7777-7777-7777-777777777777");
        ObjectNode memoryTopN = JsonNodeFactory.instance.objectNode().put("count", 1);
        ObjectNode latency = JsonNodeFactory.instance.objectNode().put("totalMs", 44L);

        TraceWriteResults.RagTracePersistenceResult result =
                agenticTracePersistenceService.persistAgenticOnlineQueryDecision(agenticOnlineDecisionRequest(
                        RagPersistPolicy.ONLINE_QUERY,
                        onlineQueryId,
                        "spring security and mvc filter order",
                        "spring security and mvc filter order",
                        false,
                        memoryTopN,
                        0.91d,
                        null,
                        3,
                        "agentic_multi_query_rrf",
                        null,
                        latency
                ));

        assertThat(result.persistPolicy()).isEqualTo(RagPersistPolicy.ONLINE_QUERY);
        assertThat(result.onlineQueryId()).isEqualTo(onlineQueryId);
        assertThat(result.persisted()).isTrue();
        assertThat(result.status()).isEqualTo("persisted_agentic_multi_query_online_query_decision");
        verify(traceRepository).upsertOnlineQueryDecision(
                eq(onlineQueryId),
                eq("spring security and mvc filter order"),
                eq(false),
                eq(memoryTopN),
                eq(0.91d),
                isNull(),
                eq("agentic_multi_query_rrf"),
                isNull(),
                eq(latency)
        );
        verifyNoForbiddenOnlineWritesExceptOnlineQueryDecision();
    }

    @Test
    void onlineQueryAgenticMetadataMergePersistsMetadataOnly() {
        UUID onlineQueryId = UUID.fromString("78787878-7878-7878-7878-787878787878");
        ObjectNode metadata = JsonNodeFactory.instance.objectNode();
        metadata.putObject("agentic_retrieval").put("enabled", true);

        TraceWriteResults.RagTracePersistenceResult result =
                agenticTracePersistenceService.mergeAgenticOnlineQueryMetadata(agenticMetadataMergeRequest(
                        RagPersistPolicy.ONLINE_QUERY,
                        onlineQueryId,
                        metadata,
                        "agentic_retrieval"
                ));

        assertThat(result.persistPolicy()).isEqualTo(RagPersistPolicy.ONLINE_QUERY);
        assertThat(result.onlineQueryId()).isEqualTo(onlineQueryId);
        assertThat(result.persisted()).isTrue();
        assertThat(result.status()).isEqualTo("persisted_agentic_multi_query_online_query_metadata");
        verify(traceRepository).mergeOnlineQueryMetadata(eq(onlineQueryId), eq(metadata));
        verifyNoForbiddenOnlineWritesExceptOnlineQueryMetadata();
    }

    @Test
    void agenticDecisionAndMetadataNonePolicyPerformsNoRepositoryWrites() {
        UUID onlineQueryId = UUID.fromString("79797979-7979-7979-7979-797979797979");

        TraceWriteResults.RagTracePersistenceResult decisionResult =
                agenticTracePersistenceService.persistAgenticOnlineQueryDecision(agenticOnlineDecisionRequest(
                        RagPersistPolicy.NONE,
                        onlineQueryId,
                        "spring security and mvc filter order",
                        "spring security and mvc filter order",
                        false,
                        JsonNodeFactory.instance.objectNode(),
                        0.91d,
                        null,
                        3,
                        "agentic_multi_query_rrf",
                        null,
                        JsonNodeFactory.instance.objectNode()
                ));
        TraceWriteResults.RagTracePersistenceResult metadataResult =
                agenticTracePersistenceService.mergeAgenticOnlineQueryMetadata(agenticMetadataMergeRequest(
                        RagPersistPolicy.NONE,
                        onlineQueryId,
                        JsonNodeFactory.instance.objectNode(),
                        "agentic_retrieval"
                ));

        assertThat(decisionResult.persisted()).isFalse();
        assertThat(decisionResult.status()).isEqualTo("skipped_none");
        assertThat(metadataResult.persisted()).isFalse();
        assertThat(metadataResult.status()).isEqualTo("skipped_none");
        verifyNoInteractions(traceRepository);
    }

    @Test
    void agenticDecisionAndMetadataTraceOnlyPolicyRemainsUnsupported() {
        UUID onlineQueryId = UUID.fromString("80808080-8080-8080-8080-808080808080");

        assertThatThrownBy(() -> agenticTracePersistenceService.persistAgenticOnlineQueryDecision(agenticOnlineDecisionRequest(
                RagPersistPolicy.TRACE_ONLY,
                onlineQueryId,
                "spring security and mvc filter order",
                "spring security and mvc filter order",
                false,
                JsonNodeFactory.instance.objectNode(),
                0.91d,
                null,
                3,
                "agentic_multi_query_rrf",
                null,
                JsonNodeFactory.instance.objectNode()
        )))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("TRACE_ONLY")
                .hasMessageContaining("Phase 6E");

        assertThatThrownBy(() -> agenticTracePersistenceService.mergeAgenticOnlineQueryMetadata(agenticMetadataMergeRequest(
                RagPersistPolicy.TRACE_ONLY,
                onlineQueryId,
                JsonNodeFactory.instance.objectNode(),
                "agentic_retrieval"
        )))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("TRACE_ONLY")
                .hasMessageContaining("Phase 6E");

        verifyNoInteractions(traceRepository);
    }

    @Test
    void onlineQuerySelectiveRewriteCandidateCreationPersistsCandidateRootOnly() {
        UUID onlineQueryId = UUID.fromString("15151515-1515-1515-1515-151515151515");
        UUID rewriteCandidateId = UUID.fromString("16161616-1616-1616-1616-161616161616");
        ObjectNode candidateMetadata = JsonNodeFactory.instance.objectNode().put("candidate_index", 1);
        ObjectNode scoreBreakdown = JsonNodeFactory.instance.objectNode().put("r1", 0.91d);

        when(traceRepository.createRewriteCandidate(
                eq(onlineQueryId),
                eq(1),
                eq("candidate-1"),
                eq("FilterChainProxy SecurityFilterChain order"),
                any(),
                any(),
                eq(0.91d),
                eq(scoreBreakdown)
        )).thenReturn(rewriteCandidateId);

        TraceWriteResults.RewriteCandidatePersistenceResult result = service.createRewriteCandidateTrace(
                createCandidateRequest(
                        RagPersistPolicy.ONLINE_QUERY,
                        onlineQueryId,
                        RetrievalExecutionDtos.NonAgenticExecutionKind.SELECTIVE_REWRITE,
                        candidateMetadata,
                        scoreBreakdown
                )
        );

        assertThat(result.persistPolicy()).isEqualTo(RagPersistPolicy.ONLINE_QUERY);
        assertThat(result.onlineQueryId()).isEqualTo(onlineQueryId);
        assertThat(result.rewriteCandidateId()).isEqualTo(rewriteCandidateId);
        assertThat(result.persisted()).isTrue();
        assertThat(result.status()).isEqualTo("persisted_selective_rewrite_candidate_root");
        verify(traceRepository).createRewriteCandidate(
                eq(onlineQueryId),
                eq(1),
                eq("candidate-1"),
                eq("FilterChainProxy SecurityFilterChain order"),
                any(),
                any(),
                eq(0.91d),
                eq(scoreBreakdown)
        );
        verifyNoForbiddenOnlineWritesExceptCandidateRoot();
    }

    @Test
    void onlineQueryAnchorAwareRewriteCandidateCreationPersistsCandidateRootOnly() {
        UUID onlineQueryId = UUID.fromString("17171717-1717-1717-1717-171717171717");
        UUID rewriteCandidateId = UUID.fromString("18181818-1818-1818-1818-181818181818");
        ObjectNode scoreBreakdown = JsonNodeFactory.instance.objectNode().put("r1", 0.93d);

        when(traceRepository.createRewriteCandidate(
                eq(onlineQueryId),
                eq(1),
                eq("candidate-1"),
                eq("FilterChainProxy SecurityFilterChain order"),
                any(),
                any(),
                eq(0.91d),
                eq(scoreBreakdown)
        )).thenReturn(rewriteCandidateId);

        TraceWriteResults.RewriteCandidatePersistenceResult result = service.createRewriteCandidateTrace(
                createCandidateRequest(
                        RagPersistPolicy.ONLINE_QUERY,
                        onlineQueryId,
                        RetrievalExecutionDtos.NonAgenticExecutionKind.ANCHOR_AWARE_REWRITE,
                        JsonNodeFactory.instance.objectNode(),
                        scoreBreakdown
                )
        );

        assertThat(result.persisted()).isTrue();
        assertThat(result.rewriteCandidateId()).isEqualTo(rewriteCandidateId);
        assertThat(result.status()).isEqualTo("persisted_anchor_aware_rewrite_candidate_root");
        verify(traceRepository).createRewriteCandidate(
                eq(onlineQueryId),
                eq(1),
                eq("candidate-1"),
                eq("FilterChainProxy SecurityFilterChain order"),
                any(),
                any(),
                eq(0.91d),
                eq(scoreBreakdown)
        );
        verifyNoForbiddenOnlineWritesExceptCandidateRoot();
    }

    @Test
    void onlineQueryRewriteCandidateAdoptionPersistsAdoptionOnly() {
        UUID onlineQueryId = UUID.fromString("19191919-1919-1919-1919-191919191919");
        UUID rewriteCandidateId = UUID.fromString("20202020-2020-2020-2020-202020202020");

        TraceWriteResults.RewriteCandidatePersistenceResult result = service.markRewriteCandidateAdopted(
                new RagTraceWriteDtos.RewriteCandidateAdoptionPersistenceRequest(
                        RagPersistPolicy.ONLINE_QUERY,
                        onlineQueryId,
                        rewriteCandidateId,
                        RetrievalExecutionDtos.NonAgenticExecutionKind.SELECTIVE_REWRITE,
                        true,
                        null
                )
        );

        assertThat(result.persistPolicy()).isEqualTo(RagPersistPolicy.ONLINE_QUERY);
        assertThat(result.onlineQueryId()).isEqualTo(onlineQueryId);
        assertThat(result.rewriteCandidateId()).isEqualTo(rewriteCandidateId);
        assertThat(result.persisted()).isTrue();
        assertThat(result.status()).isEqualTo("persisted_selective_rewrite_candidate_adoption");
        verify(traceRepository).markRewriteCandidateAdopted(eq(rewriteCandidateId), eq(true), isNull());
        verifyNoForbiddenOnlineWritesExceptCandidateAdoption();
    }

    @Test
    void rewriteCandidateCreationAndAdoptionNonePolicyPerformsNoRepositoryWrites() {
        TraceWriteResults.RewriteCandidatePersistenceResult createResult = service.createRewriteCandidateTrace(
                createCandidateRequest(
                        RagPersistPolicy.NONE,
                        UUID.fromString("21212121-2121-2121-2121-212121212121"),
                        RetrievalExecutionDtos.NonAgenticExecutionKind.SELECTIVE_REWRITE,
                        JsonNodeFactory.instance.objectNode(),
                        JsonNodeFactory.instance.objectNode()
                )
        );
        TraceWriteResults.RewriteCandidatePersistenceResult adoptionResult = service.markRewriteCandidateAdopted(
                new RagTraceWriteDtos.RewriteCandidateAdoptionPersistenceRequest(
                        RagPersistPolicy.NONE,
                        UUID.fromString("23232323-2323-2323-2323-232323232323"),
                        UUID.fromString("24242424-2424-2424-2424-242424242424"),
                        RetrievalExecutionDtos.NonAgenticExecutionKind.SELECTIVE_REWRITE,
                        false,
                        "below_threshold"
                )
        );

        assertThat(createResult.persisted()).isFalse();
        assertThat(createResult.status()).isEqualTo("skipped_none");
        assertThat(adoptionResult.persisted()).isFalse();
        assertThat(adoptionResult.status()).isEqualTo("skipped_none");
        verifyNoInteractions(traceRepository);
    }

    @Test
    void rewriteCandidateCreationAndAdoptionTraceOnlyPolicyRemainsUnsupported() {
        assertThatThrownBy(() -> service.createRewriteCandidateTrace(createCandidateRequest(
                RagPersistPolicy.TRACE_ONLY,
                UUID.fromString("25252525-2525-2525-2525-252525252525"),
                RetrievalExecutionDtos.NonAgenticExecutionKind.SELECTIVE_REWRITE,
                JsonNodeFactory.instance.objectNode(),
                JsonNodeFactory.instance.objectNode()
        )))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("TRACE_ONLY")
                .hasMessageContaining("Phase 5F");

        assertThatThrownBy(() -> service.markRewriteCandidateAdopted(
                new RagTraceWriteDtos.RewriteCandidateAdoptionPersistenceRequest(
                        RagPersistPolicy.TRACE_ONLY,
                        UUID.fromString("26262626-2626-2626-2626-262626262626"),
                        UUID.fromString("27272727-2727-2727-2727-272727272727"),
                        RetrievalExecutionDtos.NonAgenticExecutionKind.SELECTIVE_REWRITE,
                        false,
                        "below_threshold"
                )
        ))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("TRACE_ONLY")
                .hasMessageContaining("Phase 5F");

        verifyNoInteractions(traceRepository);
    }

    @Test
    void onlineRewriteLogsPersistParentMemoryAndCandidateInOrder() {
        UUID onlineQueryId = UUID.randomUUID();
        UUID rewriteLogId = UUID.randomUUID();
        UUID rewriteCandidateId = UUID.randomUUID();
        ObjectNode metadata = JsonNodeFactory.instance.objectNode().put("router_strategy", "ANCHOR_AWARE_REWRITE");
        var request = onlineRewriteLogRequest(
                RagPersistPolicy.ONLINE_QUERY, onlineQueryId,
                RetrievalExecutionDtos.NonAgenticExecutionKind.ANCHOR_AWARE_REWRITE, metadata
        );
        MemoryCandidate memory = memoryCandidate();
        List<RetrievalDoc> docs = docs("rewrite-doc", "rewrite-chunk", 0.87d);
        var candidate = new RetrievalExecutionDtos.ExecutedRewriteCandidate(
                1, "candidate-1", request.finalQuery(), null,
                new RetrievalExecutionDtos.RetrievalMaterial(
                        request.finalQuery(), docs, docs, 0.87d, "retriever", "reranker", null, 0L
                )
        );
        when(traceRepository.createOnlineRewriteLog(
                eq(onlineQueryId), isNull(), eq(request.rawQuery()), eq(request.finalQuery()),
                eq("selective_rewrite"), eq(request.generationMethodCodes()), eq(request.generationBatchIds()),
                eq(true), eq("full_gating"), eq(true), eq(true), eq(false),
                eq(0.31d), eq(0.87d), eq(0.56d), eq("delta_above_threshold"), isNull(), eq(metadata)
        )).thenReturn(rewriteLogId);

        service.persistOnlineRewriteLogs(
                request, List.of(memory), List.of(candidate), Map.of(candidate.index(), rewriteCandidateId), candidate.index()
        );

        var writes = inOrder(traceRepository);
        writes.verify(traceRepository).createOnlineRewriteLog(
                eq(onlineQueryId), isNull(), eq(request.rawQuery()), eq(request.finalQuery()),
                eq("selective_rewrite"), eq(request.generationMethodCodes()), eq(request.generationBatchIds()),
                eq(true), eq("full_gating"), eq(true), eq(true), eq(false),
                eq(0.31d), eq(0.87d), eq(0.56d), eq("delta_above_threshold"), isNull(), eq(metadata)
        );
        var memoryMetadata = ArgumentCaptor.forClass(JsonNode.class);
        writes.verify(traceRepository).insertMemoryRetrievalLog(
                eq(rewriteLogId), eq(onlineQueryId), eq(1), eq(memory), memoryMetadata.capture()
        );
        assertThat(memoryMetadata.getValue().path("gating_preset").asText()).isEqualTo("full_gating");
        assertThat(memoryMetadata.getValue().path("source_gating_batch_id").asText()).isEqualTo(memory.sourceGatingBatchId());

        var candidateMetadata = ArgumentCaptor.forClass(JsonNode.class);
        writes.verify(traceRepository).insertRewriteCandidateLog(
                eq(rewriteLogId), eq(onlineQueryId), eq(rewriteCandidateId), eq(1),
                eq("candidate-1"), eq(request.finalQuery()), eq(0.87d), eq(true), isNull(),
                any(), any(), candidateMetadata.capture()
        );
        assertThat(candidateMetadata.getValue().path("selected_reason").asText()).isEqualTo("delta_above_threshold");
        writes.verifyNoMoreInteractions();
    }

    @Test
    void onlineRewriteLogsWithNonePolicyPerformNoWrites() {
        service.persistOnlineRewriteLogs(
                onlineRewriteLogRequest(RagPersistPolicy.NONE, UUID.randomUUID(),
                        RetrievalExecutionDtos.NonAgenticExecutionKind.RAW_ONLY, JsonNodeFactory.instance.objectNode()),
                List.of(memoryCandidate()), List.of(), Map.of(), null
        );

        verifyNoInteractions(traceRepository);
    }

    @Test
    void onlineRewriteLogsRejectTraceOnlyPolicyWithoutWrites() {
        var request = onlineRewriteLogRequest(RagPersistPolicy.TRACE_ONLY, UUID.randomUUID(),
                RetrievalExecutionDtos.NonAgenticExecutionKind.SELECTIVE_REWRITE, JsonNodeFactory.instance.objectNode());

        assertThatThrownBy(() -> service.persistOnlineRewriteLogs(request, List.of(), List.of(), Map.of(), null))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("TRACE_ONLY");
        verifyNoInteractions(traceRepository);
    }

    @Test
    void serviceDoesNotDependOnChatAnswerService() {
        assertThat(Arrays.stream(RagTracePersistenceService.class.getDeclaredFields())
                .map(Field::getType))
                .doesNotContain(ChatAnswerService.class);
    }

    private RagTraceWriteDtos.RagTracePersistenceRequest request(
            RagPersistPolicy policy,
            UUID onlineQueryId
    ) {
        return new RagTraceWriteDtos.RagTracePersistenceRequest(
                policy,
                "eval",
                "eval-run-1",
                "sample-1",
                ForcedRetrievalMode.RAW_ONLY,
                onlineQueryId,
                new RagRetrievalExecutionResult(
                        List.of("chunk-1"),
                        List.of(),
                        "FilterChainProxy order",
                        "filter order",
                        ForcedRetrievalMode.RAW_ONLY,
                        "RAW_ONLY",
                        null,
                        null,
                        12L,
                        false,
                        null
                )
        );
    }

    private RagTraceWriteDtos.OnlineQueryDecisionPersistenceRequest onlineDecisionRequest(
            RagPersistPolicy policy,
            UUID onlineQueryId,
            RetrievalExecutionDtos.NonAgenticExecutionKind executionKind,
            String rawQuery,
            String finalQuery,
            boolean rewriteApplied,
            ObjectNode memoryTopN,
            Double rawScore,
            UUID selectedRewriteCandidateId,
            int finalRetrievedDocsCount,
            String selectedReason,
            String rejectedReason,
            ObjectNode latencyBreakdown
    ) {
        return new RagTraceWriteDtos.OnlineQueryDecisionPersistenceRequest(
                policy,
                onlineQueryId,
                executionKind,
                rawQuery,
                finalQuery,
                rewriteApplied,
                memoryTopN,
                rawScore,
                selectedRewriteCandidateId,
                finalRetrievedDocsCount,
                selectedReason,
                rejectedReason,
                latencyBreakdown
        );
    }

    private RagTraceWriteDtos.OnlineQueryMetadataMergePersistenceRequest metadataMergeRequest(
            RagPersistPolicy policy,
            UUID onlineQueryId,
            RetrievalExecutionDtos.NonAgenticExecutionKind executionKind,
            ObjectNode metadata,
            String sourceMarker
    ) {
        return new RagTraceWriteDtos.OnlineQueryMetadataMergePersistenceRequest(
                policy,
                onlineQueryId,
                executionKind,
                metadata,
                sourceMarker
        );
    }

    private RagTraceWriteDtos.RawOnlyTracePersistenceRequest rawOnlyRequest(
            RagPersistPolicy policy,
            UUID onlineQueryId,
            List<RetrievalDoc> retrievedDocs,
            List<RetrievalDoc> rerankedDocs,
            ObjectNode metadata,
            RagTraceWriteDtos.RawOnlyTraceWriteScope writeScope
    ) {
        return new RagTraceWriteDtos.RawOnlyTracePersistenceRequest(
                policy,
                onlineQueryId,
                "FilterChainProxy order",
                "FilterChainProxy order",
                "raw_only",
                retrievedDocs,
                rerankedDocs,
                metadata,
                "local:dense_only:hash-embedding-v1",
                "local-rerank-fallback",
                12L,
                writeScope
        );
    }

    private RagTraceWriteDtos.RewriteCandidateTracePersistenceRequest rewriteCandidateRequest(
            RagPersistPolicy policy,
            UUID onlineQueryId,
            UUID rewriteCandidateId,
            RetrievalExecutionDtos.NonAgenticExecutionKind executionKind,
            List<RetrievalDoc> retrievedDocs,
            List<RetrievalDoc> rerankedDocs,
            ObjectNode metadata,
            RagTraceWriteDtos.RewriteCandidateTraceWriteScope writeScope
    ) {
        return new RagTraceWriteDtos.RewriteCandidateTracePersistenceRequest(
                policy,
                onlineQueryId,
                rewriteCandidateId,
                executionKind,
                1,
                "FilterChainProxy SecurityFilterChain order",
                "candidate-1",
                "selective_rewrite",
                retrievedDocs,
                rerankedDocs,
                metadata,
                "local:dense_only:hash-embedding-v1",
                "local-rerank-fallback",
                14L,
                writeScope
        );
    }

    private AgenticTraceWriteDtos.AgenticSubqueryRetrievalTracePersistenceRequest agenticSubqueryRequest(
            RagPersistPolicy policy,
            UUID onlineQueryId,
            UUID rewriteCandidateId,
            List<RetrievalDoc> retrievedDocs,
            ObjectNode metadata,
            AgenticTraceWriteDtos.AgenticSubqueryRetrievalTraceWriteScope writeScope
    ) {
        return new AgenticTraceWriteDtos.AgenticSubqueryRetrievalTracePersistenceRequest(
                policy,
                onlineQueryId,
                AgenticTraceWriteDtos.AgenticRetrievalExecutionKind.AGENTIC_MULTI_QUERY,
                1,
                "FilterChainProxy order",
                "agentic_multi_query",
                rewriteCandidateId,
                retrievedDocs,
                metadata,
                "local:dense_only:hash-embedding-v1",
                16L,
                writeScope
        );
    }

    private AgenticTraceWriteDtos.AgenticRewriteCandidateTracePersistenceRequest agenticCreateCandidateRequest(
            RagPersistPolicy policy,
            UUID onlineQueryId,
            ObjectNode candidateMetadata,
            ObjectNode scoreBreakdown
    ) {
        return new AgenticTraceWriteDtos.AgenticRewriteCandidateTracePersistenceRequest(
                policy,
                onlineQueryId,
                AgenticTraceWriteDtos.AgenticRetrievalExecutionKind.AGENTIC_MULTI_QUERY,
                2,
                "security filter order",
                21,
                "subquery_2_candidate-1",
                "FilterChainProxy SecurityFilterChain order",
                candidateMetadata,
                JsonNodeFactory.instance.arrayNode().add("memory-1"),
                JsonNodeFactory.instance.arrayNode().add("rewrite-chunk"),
                0.91d,
                scoreBreakdown
        );
    }

    private AgenticTraceWriteDtos.AgenticFinalRerankTracePersistenceRequest agenticFinalRerankRequest(
            RagPersistPolicy policy,
            UUID onlineQueryId,
            List<RetrievalDoc> finalMergedDocs
    ) {
        return new AgenticTraceWriteDtos.AgenticFinalRerankTracePersistenceRequest(
                policy,
                onlineQueryId,
                AgenticTraceWriteDtos.AgenticRetrievalExecutionKind.AGENTIC_MULTI_QUERY,
                finalMergedDocs,
                "agentic-rrf",
                "agentic-rrf",
                0L
        );
    }

    private AgenticTraceWriteDtos.AgenticOnlineRewriteLogPersistenceRequest agenticOnlineRewriteLogRequest(
            RagPersistPolicy policy,
            UUID onlineQueryId,
            ObjectNode metadata
    ) {
        return new AgenticTraceWriteDtos.AgenticOnlineRewriteLogPersistenceRequest(
                policy,
                onlineQueryId,
                null,
                AgenticTraceWriteDtos.AgenticRetrievalExecutionKind.AGENTIC_MULTI_QUERY,
                "spring security and mvc filter order",
                "spring security and mvc filter order",
                "selective_rewrite",
                JsonNodeFactory.instance.arrayNode().add("C"),
                JsonNodeFactory.instance.arrayNode().add("generation-batch-1"),
                true,
                "full_gating",
                false,
                true,
                false,
                0.88d,
                0.88d,
                0.0d,
                "agentic_multi_query_rrf",
                null,
                metadata
        );
    }

    private AgenticTraceWriteDtos.AgenticMemoryRetrievalLogPersistenceRequest agenticMemoryLogRequest(
            RagPersistPolicy policy,
            UUID onlineQueryId,
            UUID rewriteLogId,
            MemoryCandidate memoryCandidate,
            ObjectNode metadata
    ) {
        return new AgenticTraceWriteDtos.AgenticMemoryRetrievalLogPersistenceRequest(
                policy,
                onlineQueryId,
                rewriteLogId,
                AgenticTraceWriteDtos.AgenticRetrievalExecutionKind.AGENTIC_MULTI_QUERY,
                1,
                memoryCandidate,
                metadata
        );
    }

    private AgenticTraceWriteDtos.AgenticRewriteCandidateLogPersistenceRequest agenticCandidateLogRequest(
            RagPersistPolicy policy,
            UUID onlineQueryId,
            UUID rewriteLogId,
            UUID rewriteCandidateId,
            ObjectNode scoreBreakdown,
            ObjectNode metadata
    ) {
        return new AgenticTraceWriteDtos.AgenticRewriteCandidateLogPersistenceRequest(
                policy,
                onlineQueryId,
                rewriteLogId,
                rewriteCandidateId,
                AgenticTraceWriteDtos.AgenticRetrievalExecutionKind.AGENTIC_MULTI_QUERY,
                1,
                "agentic-candidate-1",
                "FilterChainProxy order",
                0.88d,
                false,
                "agentic_not_selected",
                JsonNodeFactory.instance.arrayNode().add("agentic-chunk"),
                scoreBreakdown,
                metadata
        );
    }

    private AgenticTraceWriteDtos.AgenticOnlineQueryDecisionPersistenceRequest agenticOnlineDecisionRequest(
            RagPersistPolicy policy,
            UUID onlineQueryId,
            String rawQuery,
            String finalQuery,
            boolean rewriteApplied,
            ObjectNode memoryTopN,
            Double selectedConfidence,
            UUID selectedCandidateId,
            int finalRetrievedDocsCount,
            String selectedReason,
            String rejectedReason,
            ObjectNode latency
    ) {
        return new AgenticTraceWriteDtos.AgenticOnlineQueryDecisionPersistenceRequest(
                policy,
                onlineQueryId,
                AgenticTraceWriteDtos.AgenticRetrievalExecutionKind.AGENTIC_MULTI_QUERY,
                rawQuery,
                finalQuery,
                rewriteApplied,
                memoryTopN,
                selectedConfidence,
                selectedCandidateId,
                finalRetrievedDocsCount,
                selectedReason,
                rejectedReason,
                latency
        );
    }

    private AgenticTraceWriteDtos.AgenticOnlineQueryMetadataMergePersistenceRequest agenticMetadataMergeRequest(
            RagPersistPolicy policy,
            UUID onlineQueryId,
            ObjectNode metadata,
            String sourceMarker
    ) {
        return new AgenticTraceWriteDtos.AgenticOnlineQueryMetadataMergePersistenceRequest(
                policy,
                onlineQueryId,
                AgenticTraceWriteDtos.AgenticRetrievalExecutionKind.AGENTIC_MULTI_QUERY,
                metadata,
                sourceMarker
        );
    }

    private RagTraceWriteDtos.CreateRewriteCandidateTracePersistenceRequest createCandidateRequest(
            RagPersistPolicy policy,
            UUID onlineQueryId,
            RetrievalExecutionDtos.NonAgenticExecutionKind executionKind,
            ObjectNode candidateMetadata,
            ObjectNode scoreBreakdown
    ) {
        return new RagTraceWriteDtos.CreateRewriteCandidateTracePersistenceRequest(
                policy,
                onlineQueryId,
                executionKind,
                1,
                "candidate-1",
                "FilterChainProxy SecurityFilterChain order",
                candidateMetadata,
                JsonNodeFactory.instance.arrayNode().add("memory-1"),
                JsonNodeFactory.instance.arrayNode().add("rewrite-chunk"),
                0.91d,
                scoreBreakdown
        );
    }

    private RagTraceWriteDtos.OnlineRewriteLogPersistenceRequest onlineRewriteLogRequest(
            RagPersistPolicy policy,
            UUID onlineQueryId,
            RetrievalExecutionDtos.NonAgenticExecutionKind executionKind,
            ObjectNode metadata
    ) {
        return new RagTraceWriteDtos.OnlineRewriteLogPersistenceRequest(
                policy,
                onlineQueryId,
                null,
                executionKind,
                "spring filter order",
                "FilterChainProxy SecurityFilterChain order",
                "selective_rewrite",
                JsonNodeFactory.instance.arrayNode().add("C"),
                JsonNodeFactory.instance.arrayNode().add("generation-batch-1"),
                true,
                "full_gating",
                true,
                true,
                false,
                0.31d,
                0.87d,
                0.56d,
                "delta_above_threshold",
                null,
                metadata
        );
    }

    private List<RetrievalDoc> docs(String documentId, String chunkId, double score) {
        return List.of(new RetrievalDoc(
                documentId,
                chunkId,
                "content",
                score
        ));
    }

    private MemoryCandidate memoryCandidate() {
        return new MemoryCandidate(
                UUID.fromString("43434343-4343-4343-4343-434343434343"),
                "FilterChainProxy SecurityFilterChain order",
                "memory-doc",
                JsonNodeFactory.instance.arrayNode().add("memory-chunk"),
                JsonNodeFactory.instance.arrayNode().add("FilterChainProxy"),
                JsonNodeFactory.instance.objectNode(),
                0.72d,
                "C",
                UUID.fromString("44444444-4444-4444-4444-444444444444"),
                UUID.fromString("45454545-4545-4545-4545-454545454545"),
                "gated-query-1",
                "source-gate-run-1",
                "source-gating-batch-1"
        );
    }

    private void verifyNoForbiddenOnlineWrites() {
        verify(traceRepository, never()).createOnlineQuery(any(), any(), anyString(), any(), anyString(), anyDouble(), any());
        verify(traceRepository, never()).createRewriteCandidate(any(), anyInt(), anyString(), anyString(), any(), any(), anyDouble(), any());
        verify(traceRepository, never()).markRewriteCandidateAdopted(any(), anyBoolean(), any());
        verify(traceRepository, never()).insertAnswer(any(), anyString(), any(), any(), anyString(), any());
        verify(traceRepository, never()).upsertOnlineQueryDecision(any(), anyString(), anyBoolean(), any(), any(), any(), anyString(), any(), any());
        verify(traceRepository, never()).mergeOnlineQueryMetadata(any(), any());
        verify(traceRepository, never()).createOnlineRewriteLog(any(), any(), anyString(), anyString(), anyString(), any(), any(), anyBoolean(), anyString(), anyBoolean(), any(), any(), any(), any(), any(), any(), any(), any());
        verify(traceRepository, never()).insertMemoryRetrievalLog(any(), any(), anyInt(), any(), any());
        verify(traceRepository, never()).insertRewriteCandidateLog(any(), any(), any(), anyInt(), anyString(), anyString(), any(), anyBoolean(), any(), any(), any(), any());
    }

    private void verifyNoForbiddenOnlineWritesExceptOnlineQueryDecision() {
        verify(traceRepository, never()).createOnlineQuery(any(), any(), anyString(), any(), anyString(), anyDouble(), any());
        verify(traceRepository, never()).createRewriteCandidate(any(), anyInt(), anyString(), anyString(), any(), any(), anyDouble(), any());
        verify(traceRepository, never()).markRewriteCandidateAdopted(any(), anyBoolean(), any());
        verify(traceRepository, never()).insertRetrievalResults(any(), any(), anyString(), anyList(), anyString(), anyString(), any());
        verify(traceRepository, never()).insertRerankResults(any(), any(), anyList(), anyString());
        verify(traceRepository, never()).insertAnswer(any(), anyString(), any(), any(), anyString(), any());
        verify(traceRepository, never()).mergeOnlineQueryMetadata(any(), any());
        verify(traceRepository, never()).createOnlineRewriteLog(any(), any(), anyString(), anyString(), anyString(), any(), any(), anyBoolean(), anyString(), anyBoolean(), any(), any(), any(), any(), any(), any(), any(), any());
        verify(traceRepository, never()).insertMemoryRetrievalLog(any(), any(), anyInt(), any(), any());
        verify(traceRepository, never()).insertRewriteCandidateLog(any(), any(), any(), anyInt(), anyString(), anyString(), any(), anyBoolean(), any(), any(), any(), any());
    }

    private void verifyNoForbiddenOnlineWritesExceptOnlineQueryMetadata() {
        verify(traceRepository, never()).createOnlineQuery(any(), any(), anyString(), any(), anyString(), anyDouble(), any());
        verify(traceRepository, never()).createRewriteCandidate(any(), anyInt(), anyString(), anyString(), any(), any(), anyDouble(), any());
        verify(traceRepository, never()).markRewriteCandidateAdopted(any(), anyBoolean(), any());
        verify(traceRepository, never()).insertRetrievalResults(any(), any(), anyString(), anyList(), anyString(), anyString(), any());
        verify(traceRepository, never()).insertRerankResults(any(), any(), anyList(), anyString());
        verify(traceRepository, never()).insertAnswer(any(), anyString(), any(), any(), anyString(), any());
        verify(traceRepository, never()).upsertOnlineQueryDecision(any(), anyString(), anyBoolean(), any(), any(), any(), anyString(), any(), any());
        verify(traceRepository, never()).createOnlineRewriteLog(any(), any(), anyString(), anyString(), anyString(), any(), any(), anyBoolean(), anyString(), anyBoolean(), any(), any(), any(), any(), any(), any(), any(), any());
        verify(traceRepository, never()).insertMemoryRetrievalLog(any(), any(), anyInt(), any(), any());
        verify(traceRepository, never()).insertRewriteCandidateLog(any(), any(), any(), anyInt(), anyString(), anyString(), any(), anyBoolean(), any(), any(), any(), any());
    }

    private void verifyNoForbiddenOnlineWritesExceptCandidateRoot() {
        verify(traceRepository, never()).createOnlineQuery(any(), any(), anyString(), any(), anyString(), anyDouble(), any());
        verify(traceRepository, never()).markRewriteCandidateAdopted(any(), anyBoolean(), any());
        verify(traceRepository, never()).insertRetrievalResults(any(), any(), anyString(), anyList(), anyString(), anyString(), any());
        verify(traceRepository, never()).insertRerankResults(any(), any(), anyList(), anyString());
        verify(traceRepository, never()).insertAnswer(any(), anyString(), any(), any(), anyString(), any());
        verify(traceRepository, never()).upsertOnlineQueryDecision(any(), anyString(), anyBoolean(), any(), any(), any(), anyString(), any(), any());
        verify(traceRepository, never()).mergeOnlineQueryMetadata(any(), any());
        verify(traceRepository, never()).createOnlineRewriteLog(any(), any(), anyString(), anyString(), anyString(), any(), any(), anyBoolean(), anyString(), anyBoolean(), any(), any(), any(), any(), any(), any(), any(), any());
        verify(traceRepository, never()).insertMemoryRetrievalLog(any(), any(), anyInt(), any(), any());
        verify(traceRepository, never()).insertRewriteCandidateLog(any(), any(), any(), anyInt(), anyString(), anyString(), any(), anyBoolean(), any(), any(), any(), any());
    }

    private void verifyNoForbiddenOnlineWritesExceptCandidateAdoption() {
        verify(traceRepository, never()).createOnlineQuery(any(), any(), anyString(), any(), anyString(), anyDouble(), any());
        verify(traceRepository, never()).createRewriteCandidate(any(), anyInt(), anyString(), anyString(), any(), any(), anyDouble(), any());
        verify(traceRepository, never()).insertRetrievalResults(any(), any(), anyString(), anyList(), anyString(), anyString(), any());
        verify(traceRepository, never()).insertRerankResults(any(), any(), anyList(), anyString());
        verify(traceRepository, never()).insertAnswer(any(), anyString(), any(), any(), anyString(), any());
        verify(traceRepository, never()).upsertOnlineQueryDecision(any(), anyString(), anyBoolean(), any(), any(), any(), anyString(), any(), any());
        verify(traceRepository, never()).mergeOnlineQueryMetadata(any(), any());
        verify(traceRepository, never()).createOnlineRewriteLog(any(), any(), anyString(), anyString(), anyString(), any(), any(), anyBoolean(), anyString(), anyBoolean(), any(), any(), any(), any(), any(), any(), any(), any());
        verify(traceRepository, never()).insertMemoryRetrievalLog(any(), any(), anyInt(), any(), any());
        verify(traceRepository, never()).insertRewriteCandidateLog(any(), any(), any(), anyInt(), anyString(), anyString(), any(), anyBoolean(), any(), any(), any(), any());
    }

    private void verifyNoForbiddenOnlineWritesExceptRewriteLog() {
        verify(traceRepository, never()).createOnlineQuery(any(), any(), anyString(), any(), anyString(), anyDouble(), any());
        verify(traceRepository, never()).createRewriteCandidate(any(), anyInt(), anyString(), anyString(), any(), any(), anyDouble(), any());
        verify(traceRepository, never()).markRewriteCandidateAdopted(any(), anyBoolean(), any());
        verify(traceRepository, never()).insertRetrievalResults(any(), any(), anyString(), anyList(), anyString(), anyString(), any());
        verify(traceRepository, never()).insertRerankResults(any(), any(), anyList(), anyString());
        verify(traceRepository, never()).insertAnswer(any(), anyString(), any(), any(), anyString(), any());
        verify(traceRepository, never()).upsertOnlineQueryDecision(any(), anyString(), anyBoolean(), any(), any(), any(), anyString(), any(), any());
        verify(traceRepository, never()).mergeOnlineQueryMetadata(any(), any());
        verify(traceRepository, never()).insertMemoryRetrievalLog(any(), any(), anyInt(), any(), any());
        verify(traceRepository, never()).insertRewriteCandidateLog(any(), any(), any(), anyInt(), anyString(), anyString(), any(), anyBoolean(), any(), any(), any(), any());
    }

    private void verifyNoForbiddenOnlineWritesExceptMemoryRetrievalLog() {
        verify(traceRepository, never()).createOnlineQuery(any(), any(), anyString(), any(), anyString(), anyDouble(), any());
        verify(traceRepository, never()).createRewriteCandidate(any(), anyInt(), anyString(), anyString(), any(), any(), anyDouble(), any());
        verify(traceRepository, never()).markRewriteCandidateAdopted(any(), anyBoolean(), any());
        verify(traceRepository, never()).insertRetrievalResults(any(), any(), anyString(), anyList(), anyString(), anyString(), any());
        verify(traceRepository, never()).insertRerankResults(any(), any(), anyList(), anyString());
        verify(traceRepository, never()).insertAnswer(any(), anyString(), any(), any(), anyString(), any());
        verify(traceRepository, never()).upsertOnlineQueryDecision(any(), anyString(), anyBoolean(), any(), any(), any(), anyString(), any(), any());
        verify(traceRepository, never()).mergeOnlineQueryMetadata(any(), any());
        verify(traceRepository, never()).createOnlineRewriteLog(any(), any(), anyString(), anyString(), anyString(), any(), any(), anyBoolean(), anyString(), anyBoolean(), any(), any(), any(), any(), any(), any(), any(), any());
        verify(traceRepository, never()).insertRewriteCandidateLog(any(), any(), any(), anyInt(), anyString(), anyString(), any(), anyBoolean(), any(), any(), any(), any());
    }

    private void verifyNoForbiddenOnlineWritesExceptRewriteCandidateLog() {
        verify(traceRepository, never()).createOnlineQuery(any(), any(), anyString(), any(), anyString(), anyDouble(), any());
        verify(traceRepository, never()).createRewriteCandidate(any(), anyInt(), anyString(), anyString(), any(), any(), anyDouble(), any());
        verify(traceRepository, never()).markRewriteCandidateAdopted(any(), anyBoolean(), any());
        verify(traceRepository, never()).insertRetrievalResults(any(), any(), anyString(), anyList(), anyString(), anyString(), any());
        verify(traceRepository, never()).insertRerankResults(any(), any(), anyList(), anyString());
        verify(traceRepository, never()).insertAnswer(any(), anyString(), any(), any(), anyString(), any());
        verify(traceRepository, never()).upsertOnlineQueryDecision(any(), anyString(), anyBoolean(), any(), any(), any(), anyString(), any(), any());
        verify(traceRepository, never()).mergeOnlineQueryMetadata(any(), any());
        verify(traceRepository, never()).createOnlineRewriteLog(any(), any(), anyString(), anyString(), anyString(), any(), any(), anyBoolean(), anyString(), anyBoolean(), any(), any(), any(), any(), any(), any(), any(), any());
        verify(traceRepository, never()).insertMemoryRetrievalLog(any(), any(), anyInt(), any(), any());
    }

    private void verifyNoPersistenceWrites() {
        verify(traceRepository, never()).createOnlineQuery(any(), any(), anyString(), any(), anyString(), anyDouble(), any());
        verify(traceRepository, never()).createRewriteCandidate(any(), anyInt(), anyString(), anyString(), any(), any(), anyDouble(), any());
        verify(traceRepository, never()).markRewriteCandidateAdopted(any(), anyBoolean(), any());
        verify(traceRepository, never()).insertRetrievalResults(any(), any(), anyString(), anyList(), anyString(), anyString(), any());
        verify(traceRepository, never()).insertRerankResults(any(), any(), anyList(), anyString());
        verify(traceRepository, never()).insertAnswer(any(), anyString(), any(), any(), anyString(), any());
        verify(traceRepository, never()).upsertOnlineQueryDecision(any(), anyString(), anyBoolean(), any(), any(), any(), anyString(), any(), any());
        verify(traceRepository, never()).mergeOnlineQueryMetadata(any(), any());
        verify(traceRepository, never()).createOnlineRewriteLog(any(), any(), anyString(), anyString(), anyString(), any(), any(), anyBoolean(), anyString(), anyBoolean(), any(), any(), any(), any(), any(), any(), any(), any());
        verify(traceRepository, never()).insertMemoryRetrievalLog(any(), any(), anyInt(), any(), any());
        verify(traceRepository, never()).insertRewriteCandidateLog(any(), any(), any(), anyInt(), anyString(), anyString(), any(), anyBoolean(), any(), any(), any(), any());
    }
}
