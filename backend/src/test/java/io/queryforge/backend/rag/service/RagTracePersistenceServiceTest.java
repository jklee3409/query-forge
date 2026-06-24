package io.queryforge.backend.rag.service;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.queryforge.backend.rag.model.ForcedRetrievalMode;
import io.queryforge.backend.rag.model.RagPersistPolicy;
import io.queryforge.backend.rag.model.RagRetrievalExecutionResult;
import io.queryforge.backend.rag.repository.RagRepository;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.util.Arrays;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RagTracePersistenceServiceTest {

    @Mock
    private RagRepository repository;

    private RagTracePersistenceService service;

    @BeforeEach
    void setUp() {
        service = new RagTracePersistenceService(repository);
    }

    @Test
    void nonePolicyPerformsNoRepositoryWrites() {
        UUID onlineQueryId = UUID.fromString("11111111-1111-1111-1111-111111111111");

        RagTracePersistenceService.RagTracePersistenceResult result = service.persist(
                request(RagPersistPolicy.NONE, onlineQueryId)
        );

        assertThat(result.persistPolicy()).isEqualTo(RagPersistPolicy.NONE);
        assertThat(result.onlineQueryId()).isEqualTo(onlineQueryId);
        assertThat(result.persisted()).isFalse();
        assertThat(result.status()).isEqualTo("skipped_none");
        verifyNoPersistenceWrites();
        verifyNoInteractions(repository);
    }

    @Test
    void nullPolicyDefaultsToNoneWithoutRepositoryWrites() {
        RagTracePersistenceService.RagTracePersistenceResult result = service.persist(
                request(null, null)
        );

        assertThat(result.persistPolicy()).isEqualTo(RagPersistPolicy.NONE);
        assertThat(result.onlineQueryId()).isNull();
        assertThat(result.persisted()).isFalse();
        assertThat(result.status()).isEqualTo("skipped_none");
        verifyNoInteractions(repository);
    }

    @Test
    void traceOnlyPolicyIsExplicitlyUnsupportedInPhase5F() {
        assertThatThrownBy(() -> service.persist(request(RagPersistPolicy.TRACE_ONLY, null)))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("TRACE_ONLY")
                .hasMessageContaining("Phase 5F");

        verifyNoInteractions(repository);
    }

    @Test
    void onlineQueryGenericPolicyIsExplicitlyUnsupportedInPhase5F() {
        assertThatThrownBy(() -> service.persist(request(RagPersistPolicy.ONLINE_QUERY, null)))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("ONLINE_QUERY")
                .hasMessageContaining("phase-specific");

        verifyNoInteractions(repository);
    }

    @Test
    void onlineQueryRawOnlyDecisionPersistsDecisionOnly() {
        UUID onlineQueryId = UUID.fromString("20202020-2020-2020-2020-202020202020");
        ObjectNode memoryTopN = JsonNodeFactory.instance.objectNode().put("count", 0);
        ObjectNode latency = JsonNodeFactory.instance.objectNode().put("totalMs", 25L);

        RagTracePersistenceService.RagTracePersistenceResult result = service.persistOnlineQueryDecision(
                onlineDecisionRequest(
                        RagPersistPolicy.ONLINE_QUERY,
                        onlineQueryId,
                        RagRetrievalExecutionService.NonAgenticExecutionKind.RAW_ONLY,
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
        verify(repository).upsertOnlineQueryDecision(
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

        RagTracePersistenceService.RagTracePersistenceResult result = service.persistOnlineQueryDecision(
                onlineDecisionRequest(
                        RagPersistPolicy.ONLINE_QUERY,
                        onlineQueryId,
                        RagRetrievalExecutionService.NonAgenticExecutionKind.SELECTIVE_REWRITE,
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
        verify(repository).upsertOnlineQueryDecision(
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

        RagTracePersistenceService.RagTracePersistenceResult result = service.persistOnlineQueryDecision(
                onlineDecisionRequest(
                        RagPersistPolicy.ONLINE_QUERY,
                        onlineQueryId,
                        RagRetrievalExecutionService.NonAgenticExecutionKind.ANCHOR_AWARE_REWRITE,
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
        verify(repository).upsertOnlineQueryDecision(
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

        RagTracePersistenceService.RagTracePersistenceResult result = service.mergeOnlineQueryMetadata(
                metadataMergeRequest(
                        RagPersistPolicy.ONLINE_QUERY,
                        onlineQueryId,
                        RagRetrievalExecutionService.NonAgenticExecutionKind.SELECTIVE_REWRITE,
                        metadata,
                        "router"
                )
        );

        assertThat(result.persistPolicy()).isEqualTo(RagPersistPolicy.ONLINE_QUERY);
        assertThat(result.onlineQueryId()).isEqualTo(onlineQueryId);
        assertThat(result.persisted()).isTrue();
        assertThat(result.status()).isEqualTo("persisted_selective_rewrite_online_query_metadata");
        verify(repository).mergeOnlineQueryMetadata(eq(onlineQueryId), eq(metadata));
        verifyNoForbiddenOnlineWritesExceptOnlineQueryMetadata();
    }

    @Test
    void decisionAndMetadataNonePolicyPerformsNoRepositoryWrites() {
        UUID onlineQueryId = UUID.fromString("26262626-2626-2626-2626-262626262626");

        RagTracePersistenceService.RagTracePersistenceResult decisionResult = service.persistOnlineQueryDecision(
                onlineDecisionRequest(
                        RagPersistPolicy.NONE,
                        onlineQueryId,
                        RagRetrievalExecutionService.NonAgenticExecutionKind.RAW_ONLY,
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
        RagTracePersistenceService.RagTracePersistenceResult metadataResult = service.mergeOnlineQueryMetadata(
                metadataMergeRequest(
                        RagPersistPolicy.NONE,
                        onlineQueryId,
                        RagRetrievalExecutionService.NonAgenticExecutionKind.RAW_ONLY,
                        JsonNodeFactory.instance.objectNode(),
                        "router"
                )
        );

        assertThat(decisionResult.persisted()).isFalse();
        assertThat(decisionResult.status()).isEqualTo("skipped_none");
        assertThat(metadataResult.persisted()).isFalse();
        assertThat(metadataResult.status()).isEqualTo("skipped_none");
        verifyNoInteractions(repository);
    }

    @Test
    void decisionAndMetadataTraceOnlyPolicyRemainsUnsupported() {
        UUID onlineQueryId = UUID.fromString("27272727-2727-2727-2727-272727272727");

        assertThatThrownBy(() -> service.persistOnlineQueryDecision(onlineDecisionRequest(
                RagPersistPolicy.TRACE_ONLY,
                onlineQueryId,
                RagRetrievalExecutionService.NonAgenticExecutionKind.RAW_ONLY,
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
                RagRetrievalExecutionService.NonAgenticExecutionKind.RAW_ONLY,
                JsonNodeFactory.instance.objectNode(),
                "router"
        )))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("TRACE_ONLY")
                .hasMessageContaining("Phase 5F");

        verifyNoInteractions(repository);
    }

    @Test
    void onlineQueryRawOnlyRetrievalTracePersistsRetrievalResultsOnly() {
        UUID onlineQueryId = UUID.fromString("22222222-2222-2222-2222-222222222222");
        List<RagRepository.RetrievalDoc> retrievedDocs = docs("doc-1", "chunk-1", 0.82d);
        List<RagRepository.RetrievalDoc> rerankedDocs = docs("doc-2", "chunk-2", 0.92d);
        ObjectNode metadata = JsonNodeFactory.instance.objectNode().put("retriever_mode", "dense_only");

        RagTracePersistenceService.RagTracePersistenceResult result = service.persistRawOnlyTrace(
                rawOnlyRequest(
                        RagPersistPolicy.ONLINE_QUERY,
                        onlineQueryId,
                        retrievedDocs,
                        rerankedDocs,
                        metadata,
                        RagTracePersistenceService.RawOnlyTraceWriteScope.RETRIEVAL
                )
        );

        assertThat(result.persistPolicy()).isEqualTo(RagPersistPolicy.ONLINE_QUERY);
        assertThat(result.onlineQueryId()).isEqualTo(onlineQueryId);
        assertThat(result.persisted()).isTrue();
        assertThat(result.status()).isEqualTo("persisted_raw_only_retrieval");
        ArgumentCaptor<List<RagRepository.RetrievalDoc>> docsCaptor = ArgumentCaptor.forClass(List.class);
        verify(repository).insertRetrievalResults(
                eq(onlineQueryId),
                isNull(),
                eq("raw"),
                docsCaptor.capture(),
                eq("raw_only"),
                eq("local:dense_only:hash-embedding-v1"),
                eq(metadata)
        );
        assertThat(docsCaptor.getValue()).isEqualTo(retrievedDocs);
        verify(repository, never()).insertRerankResults(any(), any(), anyList(), anyString());
        verifyNoForbiddenOnlineWrites();
    }

    @Test
    void onlineQueryRawOnlyRerankTracePersistsRerankResultsOnly() {
        UUID onlineQueryId = UUID.fromString("33333333-3333-3333-3333-333333333333");
        List<RagRepository.RetrievalDoc> retrievedDocs = docs("doc-1", "chunk-1", 0.82d);
        List<RagRepository.RetrievalDoc> rerankedDocs = docs("doc-2", "chunk-2", 0.92d);
        ObjectNode metadata = JsonNodeFactory.instance.objectNode().put("retriever_mode", "dense_only");

        RagTracePersistenceService.RagTracePersistenceResult result = service.persistRawOnlyTrace(
                rawOnlyRequest(
                        RagPersistPolicy.ONLINE_QUERY,
                        onlineQueryId,
                        retrievedDocs,
                        rerankedDocs,
                        metadata,
                        RagTracePersistenceService.RawOnlyTraceWriteScope.RERANK
                )
        );

        assertThat(result.persistPolicy()).isEqualTo(RagPersistPolicy.ONLINE_QUERY);
        assertThat(result.onlineQueryId()).isEqualTo(onlineQueryId);
        assertThat(result.persisted()).isTrue();
        assertThat(result.status()).isEqualTo("persisted_raw_only_rerank");
        verify(repository).insertRerankResults(
                eq(onlineQueryId),
                isNull(),
                eq(rerankedDocs),
                eq("local-rerank-fallback")
        );
        verify(repository, never()).insertRetrievalResults(any(), any(), anyString(), anyList(), anyString(), anyString(), any());
        verifyNoForbiddenOnlineWrites();
    }

    @Test
    void rawOnlyTraceNonePolicyPerformsNoRepositoryWrites() {
        RagTracePersistenceService.RagTracePersistenceResult result = service.persistRawOnlyTrace(
                rawOnlyRequest(
                        RagPersistPolicy.NONE,
                        UUID.fromString("44444444-4444-4444-4444-444444444444"),
                        docs("doc-1", "chunk-1", 0.82d),
                        docs("doc-2", "chunk-2", 0.92d),
                        JsonNodeFactory.instance.objectNode(),
                        RagTracePersistenceService.RawOnlyTraceWriteScope.RETRIEVAL
                )
        );

        assertThat(result.persistPolicy()).isEqualTo(RagPersistPolicy.NONE);
        assertThat(result.persisted()).isFalse();
        assertThat(result.status()).isEqualTo("skipped_none");
        verifyNoInteractions(repository);
    }

    @Test
    void rawOnlyTraceOnlyPolicyRemainsUnsupported() {
        assertThatThrownBy(() -> service.persistRawOnlyTrace(rawOnlyRequest(
                RagPersistPolicy.TRACE_ONLY,
                UUID.fromString("55555555-5555-5555-5555-555555555555"),
                docs("doc-1", "chunk-1", 0.82d),
                docs("doc-2", "chunk-2", 0.92d),
                JsonNodeFactory.instance.objectNode(),
                RagTracePersistenceService.RawOnlyTraceWriteScope.RETRIEVAL
        )))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("TRACE_ONLY")
                .hasMessageContaining("Phase 5F");

        verifyNoInteractions(repository);
    }

    @Test
    void onlineQuerySelectiveRewriteCandidateRetrievalPersistsRetrievalResultsOnly() {
        UUID onlineQueryId = UUID.fromString("66666666-6666-6666-6666-666666666666");
        UUID rewriteCandidateId = UUID.fromString("77777777-7777-7777-7777-777777777777");
        List<RagRepository.RetrievalDoc> retrievedDocs = docs("doc-1", "chunk-1", 0.81d);
        ObjectNode metadata = JsonNodeFactory.instance.objectNode().put("retriever_mode", "dense_only");

        RagTracePersistenceService.RagTracePersistenceResult result = service.persistRewriteCandidateTrace(
                rewriteCandidateRequest(
                        RagPersistPolicy.ONLINE_QUERY,
                        onlineQueryId,
                        rewriteCandidateId,
                        RagRetrievalExecutionService.NonAgenticExecutionKind.SELECTIVE_REWRITE,
                        retrievedDocs,
                        docs("doc-2", "chunk-2", 0.91d),
                        metadata,
                        RagTracePersistenceService.RewriteCandidateTraceWriteScope.CANDIDATE_RETRIEVAL
                )
        );

        assertThat(result.persistPolicy()).isEqualTo(RagPersistPolicy.ONLINE_QUERY);
        assertThat(result.onlineQueryId()).isEqualTo(onlineQueryId);
        assertThat(result.persisted()).isTrue();
        assertThat(result.status()).isEqualTo("persisted_selective_rewrite_candidate_retrieval");
        verify(repository).insertRetrievalResults(
                eq(onlineQueryId),
                eq(rewriteCandidateId),
                eq("rewrite_candidate"),
                eq(retrievedDocs),
                eq("selective_rewrite"),
                eq("local:dense_only:hash-embedding-v1"),
                eq(metadata)
        );
        verify(repository, never()).insertRerankResults(any(), any(), anyList(), anyString());
        verifyNoForbiddenOnlineWrites();
    }

    @Test
    void onlineQuerySelectiveRewriteCandidateRerankPersistsRerankResultsOnly() {
        UUID onlineQueryId = UUID.fromString("88888888-8888-8888-8888-888888888888");
        UUID rewriteCandidateId = UUID.fromString("99999999-9999-9999-9999-999999999999");
        List<RagRepository.RetrievalDoc> rerankedDocs = docs("doc-2", "chunk-2", 0.91d);

        RagTracePersistenceService.RagTracePersistenceResult result = service.persistRewriteCandidateTrace(
                rewriteCandidateRequest(
                        RagPersistPolicy.ONLINE_QUERY,
                        onlineQueryId,
                        rewriteCandidateId,
                        RagRetrievalExecutionService.NonAgenticExecutionKind.SELECTIVE_REWRITE,
                        docs("doc-1", "chunk-1", 0.81d),
                        rerankedDocs,
                        JsonNodeFactory.instance.objectNode(),
                        RagTracePersistenceService.RewriteCandidateTraceWriteScope.CANDIDATE_RERANK
                )
        );

        assertThat(result.persistPolicy()).isEqualTo(RagPersistPolicy.ONLINE_QUERY);
        assertThat(result.persisted()).isTrue();
        assertThat(result.status()).isEqualTo("persisted_selective_rewrite_candidate_rerank");
        verify(repository).insertRerankResults(
                eq(onlineQueryId),
                eq(rewriteCandidateId),
                eq(rerankedDocs),
                eq("local-rerank-fallback")
        );
        verify(repository, never()).insertRetrievalResults(any(), any(), anyString(), anyList(), anyString(), anyString(), any());
        verifyNoForbiddenOnlineWrites();
    }

    @Test
    void onlineQueryAnchorAwareRewriteCandidateRetrievalPersistsRetrievalResultsOnly() {
        UUID onlineQueryId = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
        UUID rewriteCandidateId = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
        List<RagRepository.RetrievalDoc> retrievedDocs = docs("doc-3", "chunk-3", 0.84d);
        ObjectNode metadata = JsonNodeFactory.instance.objectNode().put("retriever_mode", "hybrid");

        RagTracePersistenceService.RagTracePersistenceResult result = service.persistRewriteCandidateTrace(
                rewriteCandidateRequest(
                        RagPersistPolicy.ONLINE_QUERY,
                        onlineQueryId,
                        rewriteCandidateId,
                        RagRetrievalExecutionService.NonAgenticExecutionKind.ANCHOR_AWARE_REWRITE,
                        retrievedDocs,
                        docs("doc-4", "chunk-4", 0.94d),
                        metadata,
                        RagTracePersistenceService.RewriteCandidateTraceWriteScope.CANDIDATE_RETRIEVAL
                )
        );

        assertThat(result.persistPolicy()).isEqualTo(RagPersistPolicy.ONLINE_QUERY);
        assertThat(result.persisted()).isTrue();
        assertThat(result.status()).isEqualTo("persisted_anchor_aware_rewrite_candidate_retrieval");
        verify(repository).insertRetrievalResults(
                eq(onlineQueryId),
                eq(rewriteCandidateId),
                eq("rewrite_candidate"),
                eq(retrievedDocs),
                eq("selective_rewrite"),
                eq("local:dense_only:hash-embedding-v1"),
                eq(metadata)
        );
        verify(repository, never()).insertRerankResults(any(), any(), anyList(), anyString());
        verifyNoForbiddenOnlineWrites();
    }

    @Test
    void onlineQueryAnchorAwareRewriteCandidateRerankPersistsRerankResultsOnly() {
        UUID onlineQueryId = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");
        UUID rewriteCandidateId = UUID.fromString("dddddddd-dddd-dddd-dddd-dddddddddddd");
        List<RagRepository.RetrievalDoc> rerankedDocs = docs("doc-4", "chunk-4", 0.94d);

        RagTracePersistenceService.RagTracePersistenceResult result = service.persistRewriteCandidateTrace(
                rewriteCandidateRequest(
                        RagPersistPolicy.ONLINE_QUERY,
                        onlineQueryId,
                        rewriteCandidateId,
                        RagRetrievalExecutionService.NonAgenticExecutionKind.ANCHOR_AWARE_REWRITE,
                        docs("doc-3", "chunk-3", 0.84d),
                        rerankedDocs,
                        JsonNodeFactory.instance.objectNode(),
                        RagTracePersistenceService.RewriteCandidateTraceWriteScope.CANDIDATE_RERANK
                )
        );

        assertThat(result.persistPolicy()).isEqualTo(RagPersistPolicy.ONLINE_QUERY);
        assertThat(result.persisted()).isTrue();
        assertThat(result.status()).isEqualTo("persisted_anchor_aware_rewrite_candidate_rerank");
        verify(repository).insertRerankResults(
                eq(onlineQueryId),
                eq(rewriteCandidateId),
                eq(rerankedDocs),
                eq("local-rerank-fallback")
        );
        verify(repository, never()).insertRetrievalResults(any(), any(), anyString(), anyList(), anyString(), anyString(), any());
        verifyNoForbiddenOnlineWrites();
    }

    @Test
    void rewriteCandidateTraceNonePolicyPerformsNoRepositoryWrites() {
        RagTracePersistenceService.RagTracePersistenceResult result = service.persistRewriteCandidateTrace(
                rewriteCandidateRequest(
                        RagPersistPolicy.NONE,
                        UUID.fromString("eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee"),
                        UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff"),
                        RagRetrievalExecutionService.NonAgenticExecutionKind.SELECTIVE_REWRITE,
                        docs("doc-1", "chunk-1", 0.81d),
                        docs("doc-2", "chunk-2", 0.91d),
                        JsonNodeFactory.instance.objectNode(),
                        RagTracePersistenceService.RewriteCandidateTraceWriteScope.CANDIDATE_RETRIEVAL
                )
        );

        assertThat(result.persistPolicy()).isEqualTo(RagPersistPolicy.NONE);
        assertThat(result.persisted()).isFalse();
        assertThat(result.status()).isEqualTo("skipped_none");
        verifyNoInteractions(repository);
    }

    @Test
    void rewriteCandidateTraceOnlyPolicyRemainsUnsupported() {
        assertThatThrownBy(() -> service.persistRewriteCandidateTrace(rewriteCandidateRequest(
                RagPersistPolicy.TRACE_ONLY,
                UUID.fromString("12121212-1212-1212-1212-121212121212"),
                UUID.fromString("34343434-3434-3434-3434-343434343434"),
                RagRetrievalExecutionService.NonAgenticExecutionKind.SELECTIVE_REWRITE,
                docs("doc-1", "chunk-1", 0.81d),
                docs("doc-2", "chunk-2", 0.91d),
                JsonNodeFactory.instance.objectNode(),
                RagTracePersistenceService.RewriteCandidateTraceWriteScope.CANDIDATE_RETRIEVAL
        )))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("TRACE_ONLY")
                .hasMessageContaining("Phase 5F");

        verifyNoInteractions(repository);
    }

    @Test
    void onlineQueryAgenticSubqueryRawRetrievalPersistsRetrievalResultsOnly() {
        UUID onlineQueryId = UUID.fromString("46464646-4646-4646-4646-464646464646");
        List<RagRepository.RetrievalDoc> retrievedDocs = docs("doc-agentic-raw", "chunk-agentic-raw", 0.83d);
        ObjectNode metadata = JsonNodeFactory.instance.objectNode()
                .put("agentic_phase", "raw")
                .put("subquery_index", 1);

        RagTracePersistenceService.RagTracePersistenceResult result = service.persistAgenticSubqueryRetrievalTrace(
                agenticSubqueryRequest(
                        RagPersistPolicy.ONLINE_QUERY,
                        onlineQueryId,
                        null,
                        retrievedDocs,
                        metadata,
                        RagTracePersistenceService.AgenticSubqueryRetrievalTraceWriteScope.SUBQUERY_RAW_RETRIEVAL
                )
        );

        assertThat(result.persistPolicy()).isEqualTo(RagPersistPolicy.ONLINE_QUERY);
        assertThat(result.onlineQueryId()).isEqualTo(onlineQueryId);
        assertThat(result.persisted()).isTrue();
        assertThat(result.status()).isEqualTo("persisted_agentic_multi_query_subquery_raw_retrieval");
        verify(repository).insertRetrievalResults(
                eq(onlineQueryId),
                isNull(),
                eq("raw"),
                eq(retrievedDocs),
                eq("agentic_multi_query"),
                eq("local:dense_only:hash-embedding-v1"),
                eq(metadata)
        );
        verify(repository, never()).insertRerankResults(any(), any(), anyList(), anyString());
        verifyNoForbiddenOnlineWrites();
    }

    @Test
    void onlineQueryAgenticSubqueryCandidateRetrievalPersistsRetrievalResultsOnly() {
        UUID onlineQueryId = UUID.fromString("47474747-4747-4747-4747-474747474747");
        UUID rewriteCandidateId = UUID.fromString("48484848-4848-4848-4848-484848484848");
        List<RagRepository.RetrievalDoc> retrievedDocs = docs("doc-agentic-candidate", "chunk-agentic-candidate", 0.91d);
        ObjectNode metadata = JsonNodeFactory.instance.objectNode()
                .put("agentic_phase", "rewrite_candidate")
                .put("subquery_index", 1);

        RagTracePersistenceService.RagTracePersistenceResult result = service.persistAgenticSubqueryRetrievalTrace(
                agenticSubqueryRequest(
                        RagPersistPolicy.ONLINE_QUERY,
                        onlineQueryId,
                        rewriteCandidateId,
                        retrievedDocs,
                        metadata,
                        RagTracePersistenceService.AgenticSubqueryRetrievalTraceWriteScope.SUBQUERY_CANDIDATE_RETRIEVAL
                )
        );

        assertThat(result.persistPolicy()).isEqualTo(RagPersistPolicy.ONLINE_QUERY);
        assertThat(result.persisted()).isTrue();
        assertThat(result.status()).isEqualTo("persisted_agentic_multi_query_subquery_candidate_retrieval");
        verify(repository).insertRetrievalResults(
                eq(onlineQueryId),
                eq(rewriteCandidateId),
                eq("rewrite_candidate"),
                eq(retrievedDocs),
                eq("agentic_multi_query"),
                eq("local:dense_only:hash-embedding-v1"),
                eq(metadata)
        );
        verify(repository, never()).insertRerankResults(any(), any(), anyList(), anyString());
        verifyNoForbiddenOnlineWrites();
    }

    @Test
    void agenticSubqueryRetrievalNonePolicyPerformsNoRepositoryWrites() {
        RagTracePersistenceService.RagTracePersistenceResult result = service.persistAgenticSubqueryRetrievalTrace(
                agenticSubqueryRequest(
                        RagPersistPolicy.NONE,
                        UUID.fromString("49494949-4949-4949-4949-494949494949"),
                        null,
                        docs("doc-agentic-raw", "chunk-agentic-raw", 0.83d),
                        JsonNodeFactory.instance.objectNode(),
                        RagTracePersistenceService.AgenticSubqueryRetrievalTraceWriteScope.SUBQUERY_RAW_RETRIEVAL
                )
        );

        assertThat(result.persistPolicy()).isEqualTo(RagPersistPolicy.NONE);
        assertThat(result.persisted()).isFalse();
        assertThat(result.status()).isEqualTo("skipped_none");
        verifyNoInteractions(repository);
    }

    @Test
    void agenticSubqueryRetrievalTraceOnlyPolicyRemainsUnsupported() {
        assertThatThrownBy(() -> service.persistAgenticSubqueryRetrievalTrace(agenticSubqueryRequest(
                RagPersistPolicy.TRACE_ONLY,
                UUID.fromString("50505050-5050-5050-5050-505050505050"),
                null,
                docs("doc-agentic-raw", "chunk-agentic-raw", 0.83d),
                JsonNodeFactory.instance.objectNode(),
                RagTracePersistenceService.AgenticSubqueryRetrievalTraceWriteScope.SUBQUERY_RAW_RETRIEVAL
        )))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("TRACE_ONLY")
                .hasMessageContaining("Phase 6A");

        verifyNoInteractions(repository);
    }

    @Test
    void onlineQuerySelectiveRewriteCandidateCreationPersistsCandidateRootOnly() {
        UUID onlineQueryId = UUID.fromString("15151515-1515-1515-1515-151515151515");
        UUID rewriteCandidateId = UUID.fromString("16161616-1616-1616-1616-161616161616");
        ObjectNode candidateMetadata = JsonNodeFactory.instance.objectNode().put("candidate_index", 1);
        ObjectNode scoreBreakdown = JsonNodeFactory.instance.objectNode().put("r1", 0.91d);

        when(repository.createRewriteCandidate(
                eq(onlineQueryId),
                eq(1),
                eq("candidate-1"),
                eq("FilterChainProxy SecurityFilterChain order"),
                any(),
                any(),
                eq(0.91d),
                eq(scoreBreakdown)
        )).thenReturn(rewriteCandidateId);

        RagTracePersistenceService.RewriteCandidatePersistenceResult result = service.createRewriteCandidateTrace(
                createCandidateRequest(
                        RagPersistPolicy.ONLINE_QUERY,
                        onlineQueryId,
                        RagRetrievalExecutionService.NonAgenticExecutionKind.SELECTIVE_REWRITE,
                        candidateMetadata,
                        scoreBreakdown
                )
        );

        assertThat(result.persistPolicy()).isEqualTo(RagPersistPolicy.ONLINE_QUERY);
        assertThat(result.onlineQueryId()).isEqualTo(onlineQueryId);
        assertThat(result.rewriteCandidateId()).isEqualTo(rewriteCandidateId);
        assertThat(result.persisted()).isTrue();
        assertThat(result.status()).isEqualTo("persisted_selective_rewrite_candidate_root");
        verify(repository).createRewriteCandidate(
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

        when(repository.createRewriteCandidate(
                eq(onlineQueryId),
                eq(1),
                eq("candidate-1"),
                eq("FilterChainProxy SecurityFilterChain order"),
                any(),
                any(),
                eq(0.91d),
                eq(scoreBreakdown)
        )).thenReturn(rewriteCandidateId);

        RagTracePersistenceService.RewriteCandidatePersistenceResult result = service.createRewriteCandidateTrace(
                createCandidateRequest(
                        RagPersistPolicy.ONLINE_QUERY,
                        onlineQueryId,
                        RagRetrievalExecutionService.NonAgenticExecutionKind.ANCHOR_AWARE_REWRITE,
                        JsonNodeFactory.instance.objectNode(),
                        scoreBreakdown
                )
        );

        assertThat(result.persisted()).isTrue();
        assertThat(result.rewriteCandidateId()).isEqualTo(rewriteCandidateId);
        assertThat(result.status()).isEqualTo("persisted_anchor_aware_rewrite_candidate_root");
        verify(repository).createRewriteCandidate(
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

        RagTracePersistenceService.RewriteCandidatePersistenceResult result = service.markRewriteCandidateAdopted(
                new RagTracePersistenceService.RewriteCandidateAdoptionPersistenceRequest(
                        RagPersistPolicy.ONLINE_QUERY,
                        onlineQueryId,
                        rewriteCandidateId,
                        RagRetrievalExecutionService.NonAgenticExecutionKind.SELECTIVE_REWRITE,
                        true,
                        null
                )
        );

        assertThat(result.persistPolicy()).isEqualTo(RagPersistPolicy.ONLINE_QUERY);
        assertThat(result.onlineQueryId()).isEqualTo(onlineQueryId);
        assertThat(result.rewriteCandidateId()).isEqualTo(rewriteCandidateId);
        assertThat(result.persisted()).isTrue();
        assertThat(result.status()).isEqualTo("persisted_selective_rewrite_candidate_adoption");
        verify(repository).markRewriteCandidateAdopted(eq(rewriteCandidateId), eq(true), isNull());
        verifyNoForbiddenOnlineWritesExceptCandidateAdoption();
    }

    @Test
    void rewriteCandidateCreationAndAdoptionNonePolicyPerformsNoRepositoryWrites() {
        RagTracePersistenceService.RewriteCandidatePersistenceResult createResult = service.createRewriteCandidateTrace(
                createCandidateRequest(
                        RagPersistPolicy.NONE,
                        UUID.fromString("21212121-2121-2121-2121-212121212121"),
                        RagRetrievalExecutionService.NonAgenticExecutionKind.SELECTIVE_REWRITE,
                        JsonNodeFactory.instance.objectNode(),
                        JsonNodeFactory.instance.objectNode()
                )
        );
        RagTracePersistenceService.RewriteCandidatePersistenceResult adoptionResult = service.markRewriteCandidateAdopted(
                new RagTracePersistenceService.RewriteCandidateAdoptionPersistenceRequest(
                        RagPersistPolicy.NONE,
                        UUID.fromString("23232323-2323-2323-2323-232323232323"),
                        UUID.fromString("24242424-2424-2424-2424-242424242424"),
                        RagRetrievalExecutionService.NonAgenticExecutionKind.SELECTIVE_REWRITE,
                        false,
                        "below_threshold"
                )
        );

        assertThat(createResult.persisted()).isFalse();
        assertThat(createResult.status()).isEqualTo("skipped_none");
        assertThat(adoptionResult.persisted()).isFalse();
        assertThat(adoptionResult.status()).isEqualTo("skipped_none");
        verifyNoInteractions(repository);
    }

    @Test
    void rewriteCandidateCreationAndAdoptionTraceOnlyPolicyRemainsUnsupported() {
        assertThatThrownBy(() -> service.createRewriteCandidateTrace(createCandidateRequest(
                RagPersistPolicy.TRACE_ONLY,
                UUID.fromString("25252525-2525-2525-2525-252525252525"),
                RagRetrievalExecutionService.NonAgenticExecutionKind.SELECTIVE_REWRITE,
                JsonNodeFactory.instance.objectNode(),
                JsonNodeFactory.instance.objectNode()
        )))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("TRACE_ONLY")
                .hasMessageContaining("Phase 5F");

        assertThatThrownBy(() -> service.markRewriteCandidateAdopted(
                new RagTracePersistenceService.RewriteCandidateAdoptionPersistenceRequest(
                        RagPersistPolicy.TRACE_ONLY,
                        UUID.fromString("26262626-2626-2626-2626-262626262626"),
                        UUID.fromString("27272727-2727-2727-2727-272727272727"),
                        RagRetrievalExecutionService.NonAgenticExecutionKind.SELECTIVE_REWRITE,
                        false,
                        "below_threshold"
                )
        ))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("TRACE_ONLY")
                .hasMessageContaining("Phase 5F");

        verifyNoInteractions(repository);
    }

    @Test
    void onlineQuerySelectiveRewriteLogPersistsRewriteLogOnly() {
        UUID onlineQueryId = UUID.fromString("28282828-2828-2828-2828-282828282828");
        UUID rewriteLogId = UUID.fromString("29292929-2929-2929-2929-292929292929");
        ObjectNode metadata = JsonNodeFactory.instance.objectNode().put("router_strategy", "SYNTHETIC_SELECTIVE_REWRITE");

        when(repository.createOnlineRewriteLog(
                eq(onlineQueryId),
                isNull(),
                eq("spring filter order"),
                eq("FilterChainProxy SecurityFilterChain order"),
                eq("selective_rewrite"),
                any(),
                any(),
                eq(true),
                eq("full_gating"),
                eq(true),
                eq(true),
                eq(false),
                eq(0.31d),
                eq(0.87d),
                eq(0.56d),
                eq("delta_above_threshold"),
                isNull(),
                eq(metadata)
        )).thenReturn(rewriteLogId);

        RagTracePersistenceService.OnlineRewriteLogPersistenceResult result = service.createOnlineRewriteLogTrace(
                onlineRewriteLogRequest(
                        RagPersistPolicy.ONLINE_QUERY,
                        onlineQueryId,
                        RagRetrievalExecutionService.NonAgenticExecutionKind.SELECTIVE_REWRITE,
                        metadata
                )
        );

        assertThat(result.persistPolicy()).isEqualTo(RagPersistPolicy.ONLINE_QUERY);
        assertThat(result.onlineQueryId()).isEqualTo(onlineQueryId);
        assertThat(result.rewriteLogId()).isEqualTo(rewriteLogId);
        assertThat(result.persisted()).isTrue();
        assertThat(result.status()).isEqualTo("persisted_selective_rewrite_rewrite_log");
        verify(repository).createOnlineRewriteLog(
                eq(onlineQueryId),
                isNull(),
                eq("spring filter order"),
                eq("FilterChainProxy SecurityFilterChain order"),
                eq("selective_rewrite"),
                any(),
                any(),
                eq(true),
                eq("full_gating"),
                eq(true),
                eq(true),
                eq(false),
                eq(0.31d),
                eq(0.87d),
                eq(0.56d),
                eq("delta_above_threshold"),
                isNull(),
                eq(metadata)
        );
        verifyNoForbiddenOnlineWritesExceptRewriteLog();
    }

    @Test
    void onlineQueryAnchorAwareRewriteLogPersistsRewriteLogOnly() {
        UUID onlineQueryId = UUID.fromString("30303030-3030-3030-3030-303030303030");
        UUID rewriteLogId = UUID.fromString("31313131-3131-3131-3131-313131313131");
        ObjectNode metadata = JsonNodeFactory.instance.objectNode().put("router_strategy", "ANCHOR_AWARE_REWRITE");

        when(repository.createOnlineRewriteLog(
                eq(onlineQueryId),
                isNull(),
                anyString(),
                anyString(),
                eq("selective_rewrite"),
                any(),
                any(),
                eq(true),
                eq("full_gating"),
                eq(true),
                eq(true),
                eq(false),
                anyDouble(),
                anyDouble(),
                anyDouble(),
                eq("delta_above_threshold"),
                isNull(),
                eq(metadata)
        )).thenReturn(rewriteLogId);

        RagTracePersistenceService.OnlineRewriteLogPersistenceResult result = service.createOnlineRewriteLogTrace(
                onlineRewriteLogRequest(
                        RagPersistPolicy.ONLINE_QUERY,
                        onlineQueryId,
                        RagRetrievalExecutionService.NonAgenticExecutionKind.ANCHOR_AWARE_REWRITE,
                        metadata
                )
        );

        assertThat(result.persisted()).isTrue();
        assertThat(result.rewriteLogId()).isEqualTo(rewriteLogId);
        assertThat(result.status()).isEqualTo("persisted_anchor_aware_rewrite_rewrite_log");
        verify(repository).createOnlineRewriteLog(
                eq(onlineQueryId),
                isNull(),
                eq("spring filter order"),
                eq("FilterChainProxy SecurityFilterChain order"),
                eq("selective_rewrite"),
                any(),
                any(),
                eq(true),
                eq("full_gating"),
                eq(true),
                eq(true),
                eq(false),
                eq(0.31d),
                eq(0.87d),
                eq(0.56d),
                eq("delta_above_threshold"),
                isNull(),
                eq(metadata)
        );
        verifyNoForbiddenOnlineWritesExceptRewriteLog();
    }

    @Test
    void onlineQueryMemoryRetrievalTracePersistsMemoryLogOnly() {
        UUID onlineQueryId = UUID.fromString("32323232-3232-3232-3232-323232323232");
        UUID rewriteLogId = UUID.fromString("33333333-3333-3333-3333-333333333331");
        RagRepository.MemoryCandidate memoryCandidate = memoryCandidate();
        ObjectNode metadata = JsonNodeFactory.instance.objectNode().put("gating_preset", "full_gating");

        RagTracePersistenceService.RagTracePersistenceResult result = service.insertMemoryRetrievalTrace(
                memoryLogRequest(
                        RagPersistPolicy.ONLINE_QUERY,
                        onlineQueryId,
                        rewriteLogId,
                        RagRetrievalExecutionService.NonAgenticExecutionKind.SELECTIVE_REWRITE,
                        memoryCandidate,
                        metadata
                )
        );

        assertThat(result.persistPolicy()).isEqualTo(RagPersistPolicy.ONLINE_QUERY);
        assertThat(result.onlineQueryId()).isEqualTo(onlineQueryId);
        assertThat(result.persisted()).isTrue();
        assertThat(result.status()).isEqualTo("persisted_selective_rewrite_memory_retrieval_log");
        verify(repository).insertMemoryRetrievalLog(
                eq(rewriteLogId),
                eq(onlineQueryId),
                eq(1),
                eq(memoryCandidate),
                eq(metadata)
        );
        verifyNoForbiddenOnlineWritesExceptMemoryRetrievalLog();
    }

    @Test
    void onlineQueryRewriteCandidateTracePersistsCandidateLogOnly() {
        UUID onlineQueryId = UUID.fromString("34343434-3434-3434-3434-343434343431");
        UUID rewriteLogId = UUID.fromString("35353535-3535-3535-3535-353535353535");
        UUID rewriteCandidateId = UUID.fromString("36363636-3636-3636-3636-363636363636");
        ObjectNode metadata = JsonNodeFactory.instance.objectNode().put("mode", "selective_rewrite");
        ObjectNode scoreBreakdown = JsonNodeFactory.instance.objectNode().put("r1", 0.87d);

        RagTracePersistenceService.RagTracePersistenceResult result = service.insertRewriteCandidateTrace(
                candidateLogRequest(
                        RagPersistPolicy.ONLINE_QUERY,
                        onlineQueryId,
                        rewriteLogId,
                        rewriteCandidateId,
                        RagRetrievalExecutionService.NonAgenticExecutionKind.ANCHOR_AWARE_REWRITE,
                        scoreBreakdown,
                        metadata
                )
        );

        assertThat(result.persistPolicy()).isEqualTo(RagPersistPolicy.ONLINE_QUERY);
        assertThat(result.onlineQueryId()).isEqualTo(onlineQueryId);
        assertThat(result.persisted()).isTrue();
        assertThat(result.status()).isEqualTo("persisted_anchor_aware_rewrite_rewrite_candidate_log");
        verify(repository).insertRewriteCandidateLog(
                eq(rewriteLogId),
                eq(onlineQueryId),
                eq(rewriteCandidateId),
                eq(1),
                eq("candidate-1"),
                eq("FilterChainProxy SecurityFilterChain order"),
                eq(0.87d),
                eq(true),
                isNull(),
                any(),
                eq(scoreBreakdown),
                eq(metadata)
        );
        verifyNoForbiddenOnlineWritesExceptRewriteCandidateLog();
    }

    @Test
    void rewriteMemoryAndCandidateLogNonePolicyPerformsNoRepositoryWrites() {
        UUID onlineQueryId = UUID.fromString("37373737-3737-3737-3737-373737373737");
        UUID rewriteLogId = UUID.fromString("38383838-3838-3838-3838-383838383838");
        UUID rewriteCandidateId = UUID.fromString("39393939-3939-3939-3939-393939393939");

        RagTracePersistenceService.OnlineRewriteLogPersistenceResult rewriteLogResult =
                service.createOnlineRewriteLogTrace(onlineRewriteLogRequest(
                        RagPersistPolicy.NONE,
                        onlineQueryId,
                        RagRetrievalExecutionService.NonAgenticExecutionKind.SELECTIVE_REWRITE,
                        JsonNodeFactory.instance.objectNode()
                ));
        RagTracePersistenceService.RagTracePersistenceResult memoryResult =
                service.insertMemoryRetrievalTrace(memoryLogRequest(
                        RagPersistPolicy.NONE,
                        onlineQueryId,
                        rewriteLogId,
                        RagRetrievalExecutionService.NonAgenticExecutionKind.SELECTIVE_REWRITE,
                        memoryCandidate(),
                        JsonNodeFactory.instance.objectNode()
                ));
        RagTracePersistenceService.RagTracePersistenceResult candidateResult =
                service.insertRewriteCandidateTrace(candidateLogRequest(
                        RagPersistPolicy.NONE,
                        onlineQueryId,
                        rewriteLogId,
                        rewriteCandidateId,
                        RagRetrievalExecutionService.NonAgenticExecutionKind.SELECTIVE_REWRITE,
                        JsonNodeFactory.instance.objectNode(),
                        JsonNodeFactory.instance.objectNode()
                ));

        assertThat(rewriteLogResult.persisted()).isFalse();
        assertThat(rewriteLogResult.rewriteLogId()).isNull();
        assertThat(memoryResult.persisted()).isFalse();
        assertThat(candidateResult.persisted()).isFalse();
        verifyNoInteractions(repository);
    }

    @Test
    void rewriteMemoryAndCandidateLogTraceOnlyPolicyRemainsUnsupported() {
        UUID onlineQueryId = UUID.fromString("40404040-4040-4040-4040-404040404040");
        UUID rewriteLogId = UUID.fromString("41414141-4141-4141-4141-414141414141");
        UUID rewriteCandidateId = UUID.fromString("42424242-4242-4242-4242-424242424242");

        assertThatThrownBy(() -> service.createOnlineRewriteLogTrace(onlineRewriteLogRequest(
                RagPersistPolicy.TRACE_ONLY,
                onlineQueryId,
                RagRetrievalExecutionService.NonAgenticExecutionKind.SELECTIVE_REWRITE,
                JsonNodeFactory.instance.objectNode()
        )))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("TRACE_ONLY")
                .hasMessageContaining("Phase 5F");

        assertThatThrownBy(() -> service.insertMemoryRetrievalTrace(memoryLogRequest(
                RagPersistPolicy.TRACE_ONLY,
                onlineQueryId,
                rewriteLogId,
                RagRetrievalExecutionService.NonAgenticExecutionKind.SELECTIVE_REWRITE,
                memoryCandidate(),
                JsonNodeFactory.instance.objectNode()
        )))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("TRACE_ONLY")
                .hasMessageContaining("Phase 5F");

        assertThatThrownBy(() -> service.insertRewriteCandidateTrace(candidateLogRequest(
                RagPersistPolicy.TRACE_ONLY,
                onlineQueryId,
                rewriteLogId,
                rewriteCandidateId,
                RagRetrievalExecutionService.NonAgenticExecutionKind.SELECTIVE_REWRITE,
                JsonNodeFactory.instance.objectNode(),
                JsonNodeFactory.instance.objectNode()
        )))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("TRACE_ONLY")
                .hasMessageContaining("Phase 5F");

        verifyNoInteractions(repository);
    }

    @Test
    void serviceDoesNotDependOnChatAnswerService() {
        assertThat(Arrays.stream(RagTracePersistenceService.class.getDeclaredFields())
                .map(Field::getType))
                .doesNotContain(ChatAnswerService.class);
    }

    private RagTracePersistenceService.RagTracePersistenceRequest request(
            RagPersistPolicy policy,
            UUID onlineQueryId
    ) {
        return new RagTracePersistenceService.RagTracePersistenceRequest(
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

    private RagTracePersistenceService.OnlineQueryDecisionPersistenceRequest onlineDecisionRequest(
            RagPersistPolicy policy,
            UUID onlineQueryId,
            RagRetrievalExecutionService.NonAgenticExecutionKind executionKind,
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
        return new RagTracePersistenceService.OnlineQueryDecisionPersistenceRequest(
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

    private RagTracePersistenceService.OnlineQueryMetadataMergePersistenceRequest metadataMergeRequest(
            RagPersistPolicy policy,
            UUID onlineQueryId,
            RagRetrievalExecutionService.NonAgenticExecutionKind executionKind,
            ObjectNode metadata,
            String sourceMarker
    ) {
        return new RagTracePersistenceService.OnlineQueryMetadataMergePersistenceRequest(
                policy,
                onlineQueryId,
                executionKind,
                metadata,
                sourceMarker
        );
    }

    private RagTracePersistenceService.RawOnlyTracePersistenceRequest rawOnlyRequest(
            RagPersistPolicy policy,
            UUID onlineQueryId,
            List<RagRepository.RetrievalDoc> retrievedDocs,
            List<RagRepository.RetrievalDoc> rerankedDocs,
            ObjectNode metadata,
            RagTracePersistenceService.RawOnlyTraceWriteScope writeScope
    ) {
        return new RagTracePersistenceService.RawOnlyTracePersistenceRequest(
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

    private RagTracePersistenceService.RewriteCandidateTracePersistenceRequest rewriteCandidateRequest(
            RagPersistPolicy policy,
            UUID onlineQueryId,
            UUID rewriteCandidateId,
            RagRetrievalExecutionService.NonAgenticExecutionKind executionKind,
            List<RagRepository.RetrievalDoc> retrievedDocs,
            List<RagRepository.RetrievalDoc> rerankedDocs,
            ObjectNode metadata,
            RagTracePersistenceService.RewriteCandidateTraceWriteScope writeScope
    ) {
        return new RagTracePersistenceService.RewriteCandidateTracePersistenceRequest(
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

    private RagTracePersistenceService.AgenticSubqueryRetrievalTracePersistenceRequest agenticSubqueryRequest(
            RagPersistPolicy policy,
            UUID onlineQueryId,
            UUID rewriteCandidateId,
            List<RagRepository.RetrievalDoc> retrievedDocs,
            ObjectNode metadata,
            RagTracePersistenceService.AgenticSubqueryRetrievalTraceWriteScope writeScope
    ) {
        return new RagTracePersistenceService.AgenticSubqueryRetrievalTracePersistenceRequest(
                policy,
                onlineQueryId,
                RagTracePersistenceService.AgenticRetrievalExecutionKind.AGENTIC_MULTI_QUERY,
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

    private RagTracePersistenceService.CreateRewriteCandidateTracePersistenceRequest createCandidateRequest(
            RagPersistPolicy policy,
            UUID onlineQueryId,
            RagRetrievalExecutionService.NonAgenticExecutionKind executionKind,
            ObjectNode candidateMetadata,
            ObjectNode scoreBreakdown
    ) {
        return new RagTracePersistenceService.CreateRewriteCandidateTracePersistenceRequest(
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

    private RagTracePersistenceService.OnlineRewriteLogPersistenceRequest onlineRewriteLogRequest(
            RagPersistPolicy policy,
            UUID onlineQueryId,
            RagRetrievalExecutionService.NonAgenticExecutionKind executionKind,
            ObjectNode metadata
    ) {
        return new RagTracePersistenceService.OnlineRewriteLogPersistenceRequest(
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

    private RagTracePersistenceService.MemoryRetrievalLogPersistenceRequest memoryLogRequest(
            RagPersistPolicy policy,
            UUID onlineQueryId,
            UUID rewriteLogId,
            RagRetrievalExecutionService.NonAgenticExecutionKind executionKind,
            RagRepository.MemoryCandidate memoryCandidate,
            ObjectNode metadata
    ) {
        return new RagTracePersistenceService.MemoryRetrievalLogPersistenceRequest(
                policy,
                onlineQueryId,
                rewriteLogId,
                executionKind,
                1,
                memoryCandidate,
                metadata
        );
    }

    private RagTracePersistenceService.RewriteCandidateLogPersistenceRequest candidateLogRequest(
            RagPersistPolicy policy,
            UUID onlineQueryId,
            UUID rewriteLogId,
            UUID rewriteCandidateId,
            RagRetrievalExecutionService.NonAgenticExecutionKind executionKind,
            ObjectNode scoreBreakdown,
            ObjectNode metadata
    ) {
        return new RagTracePersistenceService.RewriteCandidateLogPersistenceRequest(
                policy,
                onlineQueryId,
                rewriteLogId,
                rewriteCandidateId,
                executionKind,
                1,
                "candidate-1",
                "FilterChainProxy SecurityFilterChain order",
                0.87d,
                true,
                null,
                JsonNodeFactory.instance.arrayNode().add("rewrite-chunk"),
                scoreBreakdown,
                metadata
        );
    }

    private List<RagRepository.RetrievalDoc> docs(String documentId, String chunkId, double score) {
        return List.of(new RagRepository.RetrievalDoc(
                documentId,
                chunkId,
                "content",
                score
        ));
    }

    private RagRepository.MemoryCandidate memoryCandidate() {
        return new RagRepository.MemoryCandidate(
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
        verify(repository, never()).createOnlineQuery(any(), any(), anyString(), any(), anyString(), anyDouble(), any());
        verify(repository, never()).createRewriteCandidate(any(), anyInt(), anyString(), anyString(), any(), any(), anyDouble(), any());
        verify(repository, never()).markRewriteCandidateAdopted(any(), anyBoolean(), any());
        verify(repository, never()).insertAnswer(any(), anyString(), any(), any(), anyString(), any());
        verify(repository, never()).upsertOnlineQueryDecision(any(), anyString(), anyBoolean(), any(), any(), any(), anyString(), any(), any());
        verify(repository, never()).mergeOnlineQueryMetadata(any(), any());
        verify(repository, never()).createOnlineRewriteLog(any(), any(), anyString(), anyString(), anyString(), any(), any(), anyBoolean(), anyString(), anyBoolean(), any(), any(), any(), any(), any(), any(), any(), any());
        verify(repository, never()).insertMemoryRetrievalLog(any(), any(), anyInt(), any(), any());
        verify(repository, never()).insertRewriteCandidateLog(any(), any(), any(), anyInt(), anyString(), anyString(), any(), anyBoolean(), any(), any(), any(), any());
    }

    private void verifyNoForbiddenOnlineWritesExceptOnlineQueryDecision() {
        verify(repository, never()).createOnlineQuery(any(), any(), anyString(), any(), anyString(), anyDouble(), any());
        verify(repository, never()).createRewriteCandidate(any(), anyInt(), anyString(), anyString(), any(), any(), anyDouble(), any());
        verify(repository, never()).markRewriteCandidateAdopted(any(), anyBoolean(), any());
        verify(repository, never()).insertRetrievalResults(any(), any(), anyString(), anyList(), anyString(), anyString(), any());
        verify(repository, never()).insertRerankResults(any(), any(), anyList(), anyString());
        verify(repository, never()).insertAnswer(any(), anyString(), any(), any(), anyString(), any());
        verify(repository, never()).mergeOnlineQueryMetadata(any(), any());
        verify(repository, never()).createOnlineRewriteLog(any(), any(), anyString(), anyString(), anyString(), any(), any(), anyBoolean(), anyString(), anyBoolean(), any(), any(), any(), any(), any(), any(), any(), any());
        verify(repository, never()).insertMemoryRetrievalLog(any(), any(), anyInt(), any(), any());
        verify(repository, never()).insertRewriteCandidateLog(any(), any(), any(), anyInt(), anyString(), anyString(), any(), anyBoolean(), any(), any(), any(), any());
    }

    private void verifyNoForbiddenOnlineWritesExceptOnlineQueryMetadata() {
        verify(repository, never()).createOnlineQuery(any(), any(), anyString(), any(), anyString(), anyDouble(), any());
        verify(repository, never()).createRewriteCandidate(any(), anyInt(), anyString(), anyString(), any(), any(), anyDouble(), any());
        verify(repository, never()).markRewriteCandidateAdopted(any(), anyBoolean(), any());
        verify(repository, never()).insertRetrievalResults(any(), any(), anyString(), anyList(), anyString(), anyString(), any());
        verify(repository, never()).insertRerankResults(any(), any(), anyList(), anyString());
        verify(repository, never()).insertAnswer(any(), anyString(), any(), any(), anyString(), any());
        verify(repository, never()).upsertOnlineQueryDecision(any(), anyString(), anyBoolean(), any(), any(), any(), anyString(), any(), any());
        verify(repository, never()).createOnlineRewriteLog(any(), any(), anyString(), anyString(), anyString(), any(), any(), anyBoolean(), anyString(), anyBoolean(), any(), any(), any(), any(), any(), any(), any(), any());
        verify(repository, never()).insertMemoryRetrievalLog(any(), any(), anyInt(), any(), any());
        verify(repository, never()).insertRewriteCandidateLog(any(), any(), any(), anyInt(), anyString(), anyString(), any(), anyBoolean(), any(), any(), any(), any());
    }

    private void verifyNoForbiddenOnlineWritesExceptCandidateRoot() {
        verify(repository, never()).createOnlineQuery(any(), any(), anyString(), any(), anyString(), anyDouble(), any());
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

    private void verifyNoForbiddenOnlineWritesExceptCandidateAdoption() {
        verify(repository, never()).createOnlineQuery(any(), any(), anyString(), any(), anyString(), anyDouble(), any());
        verify(repository, never()).createRewriteCandidate(any(), anyInt(), anyString(), anyString(), any(), any(), anyDouble(), any());
        verify(repository, never()).insertRetrievalResults(any(), any(), anyString(), anyList(), anyString(), anyString(), any());
        verify(repository, never()).insertRerankResults(any(), any(), anyList(), anyString());
        verify(repository, never()).insertAnswer(any(), anyString(), any(), any(), anyString(), any());
        verify(repository, never()).upsertOnlineQueryDecision(any(), anyString(), anyBoolean(), any(), any(), any(), anyString(), any(), any());
        verify(repository, never()).mergeOnlineQueryMetadata(any(), any());
        verify(repository, never()).createOnlineRewriteLog(any(), any(), anyString(), anyString(), anyString(), any(), any(), anyBoolean(), anyString(), anyBoolean(), any(), any(), any(), any(), any(), any(), any(), any());
        verify(repository, never()).insertMemoryRetrievalLog(any(), any(), anyInt(), any(), any());
        verify(repository, never()).insertRewriteCandidateLog(any(), any(), any(), anyInt(), anyString(), anyString(), any(), anyBoolean(), any(), any(), any(), any());
    }

    private void verifyNoForbiddenOnlineWritesExceptRewriteLog() {
        verify(repository, never()).createOnlineQuery(any(), any(), anyString(), any(), anyString(), anyDouble(), any());
        verify(repository, never()).createRewriteCandidate(any(), anyInt(), anyString(), anyString(), any(), any(), anyDouble(), any());
        verify(repository, never()).markRewriteCandidateAdopted(any(), anyBoolean(), any());
        verify(repository, never()).insertRetrievalResults(any(), any(), anyString(), anyList(), anyString(), anyString(), any());
        verify(repository, never()).insertRerankResults(any(), any(), anyList(), anyString());
        verify(repository, never()).insertAnswer(any(), anyString(), any(), any(), anyString(), any());
        verify(repository, never()).upsertOnlineQueryDecision(any(), anyString(), anyBoolean(), any(), any(), any(), anyString(), any(), any());
        verify(repository, never()).mergeOnlineQueryMetadata(any(), any());
        verify(repository, never()).insertMemoryRetrievalLog(any(), any(), anyInt(), any(), any());
        verify(repository, never()).insertRewriteCandidateLog(any(), any(), any(), anyInt(), anyString(), anyString(), any(), anyBoolean(), any(), any(), any(), any());
    }

    private void verifyNoForbiddenOnlineWritesExceptMemoryRetrievalLog() {
        verify(repository, never()).createOnlineQuery(any(), any(), anyString(), any(), anyString(), anyDouble(), any());
        verify(repository, never()).createRewriteCandidate(any(), anyInt(), anyString(), anyString(), any(), any(), anyDouble(), any());
        verify(repository, never()).markRewriteCandidateAdopted(any(), anyBoolean(), any());
        verify(repository, never()).insertRetrievalResults(any(), any(), anyString(), anyList(), anyString(), anyString(), any());
        verify(repository, never()).insertRerankResults(any(), any(), anyList(), anyString());
        verify(repository, never()).insertAnswer(any(), anyString(), any(), any(), anyString(), any());
        verify(repository, never()).upsertOnlineQueryDecision(any(), anyString(), anyBoolean(), any(), any(), any(), anyString(), any(), any());
        verify(repository, never()).mergeOnlineQueryMetadata(any(), any());
        verify(repository, never()).createOnlineRewriteLog(any(), any(), anyString(), anyString(), anyString(), any(), any(), anyBoolean(), anyString(), anyBoolean(), any(), any(), any(), any(), any(), any(), any(), any());
        verify(repository, never()).insertRewriteCandidateLog(any(), any(), any(), anyInt(), anyString(), anyString(), any(), anyBoolean(), any(), any(), any(), any());
    }

    private void verifyNoForbiddenOnlineWritesExceptRewriteCandidateLog() {
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
    }

    private void verifyNoPersistenceWrites() {
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
