package io.queryforge.backend.rag.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.queryforge.backend.rag.model.ChatRuntimeDtos;
import io.queryforge.backend.rag.model.RagDtos;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RagServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final UUID domainId = UUID.randomUUID();

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

    private RagService ragService;

    @BeforeEach
    void setUp() {
        ragService = new RagService(
                repository,
                embeddingService,
                denseEmbeddingService,
                cohereRerankService,
                rewriteCandidateService,
                chatAnswerService,
                chatRuntimeConfigService,
                new QueryStrategyRouter(),
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

    private ChatRuntimeDtos.ChatRuntimeConfigResponse config(String mode, boolean routerEnabled, boolean anchorInjectionEnabled) {
        ObjectNode metadata = objectMapper.createObjectNode();
        if (routerEnabled) {
            metadata.put("routerEnabled", true);
        }
        return new ChatRuntimeDtos.ChatRuntimeConfigResponse(
                domainId,
                "spring",
                "Spring",
                "en",
                true,
                mode,
                List.of("KO_TECHNICAL"),
                "full_gating",
                null,
                null,
                List.of(),
                List.of(),
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
}
