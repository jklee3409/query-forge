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
    void traceOnlyPolicyIsExplicitlyUnsupportedInPhase5C() {
        assertThatThrownBy(() -> service.persist(request(RagPersistPolicy.TRACE_ONLY, null)))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("TRACE_ONLY")
                .hasMessageContaining("Phase 5C");

        verifyNoInteractions(repository);
    }

    @Test
    void onlineQueryGenericPolicyIsExplicitlyUnsupportedInPhase5C() {
        assertThatThrownBy(() -> service.persist(request(RagPersistPolicy.ONLINE_QUERY, null)))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("ONLINE_QUERY")
                .hasMessageContaining("phase-specific");

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
                .hasMessageContaining("Phase 5C");

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
                .hasMessageContaining("Phase 5C");

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

    private List<RagRepository.RetrievalDoc> docs(String documentId, String chunkId, double score) {
        return List.of(new RagRepository.RetrievalDoc(
                documentId,
                chunkId,
                "content",
                score
        ));
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
