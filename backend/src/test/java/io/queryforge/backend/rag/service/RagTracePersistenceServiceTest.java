package io.queryforge.backend.rag.service;

import io.queryforge.backend.rag.model.ForcedRetrievalMode;
import io.queryforge.backend.rag.model.RagPersistPolicy;
import io.queryforge.backend.rag.model.RagRetrievalExecutionResult;
import io.queryforge.backend.rag.repository.RagRepository;
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
    void traceOnlyPolicyIsExplicitlyUnsupportedInPhase5A() {
        assertThatThrownBy(() -> service.persist(request(RagPersistPolicy.TRACE_ONLY, null)))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("TRACE_ONLY")
                .hasMessageContaining("Phase 5A");

        verifyNoInteractions(repository);
    }

    @Test
    void onlineQueryPolicyIsExplicitlyUnsupportedInPhase5A() {
        assertThatThrownBy(() -> service.persist(request(RagPersistPolicy.ONLINE_QUERY, null)))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("ONLINE_QUERY")
                .hasMessageContaining("Phase 5A");

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
