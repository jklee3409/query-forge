package io.queryforge.backend.rag.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.queryforge.backend.rag.model.ChatRuntimeDtos;
import io.queryforge.backend.rag.model.RagDtos;
import io.queryforge.backend.rag.repository.RagRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
        verify(repository).insertRetrievalResults(eq(onlineQueryId), isNull(), eq("raw"), anyList(), eq("raw_only"), eq("local:dense_only:hash-embedding-v1"), any());
        verify(repository, never()).findMemoryTopN(anyString(), anyInt(), anyString(), any(), anyList(), anyList(), anyList());
        verify(rewriteCandidateService, never()).buildCandidates(anyString(), any(), anyList(), anyInt(), anyString(), anyBoolean(), any());
    }

    private ChatRuntimeDtos.ChatRuntimeConfigResponse config() {
        ObjectNode metadata = objectMapper.createObjectNode();
        metadata.put("routerEnabled", true);
        return new ChatRuntimeDtos.ChatRuntimeConfigResponse(
                domainId,
                "spring",
                "Spring",
                "en",
                true,
                "raw_only",
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
                true,
                metadata,
                Instant.now(),
                true,
                "ready"
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
