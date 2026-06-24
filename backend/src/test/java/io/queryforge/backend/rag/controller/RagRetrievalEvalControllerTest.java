package io.queryforge.backend.rag.controller;

import io.queryforge.backend.rag.model.RagLlmCallCount;
import io.queryforge.backend.rag.model.RagPersistPolicy;
import io.queryforge.backend.rag.model.RagRetrievalEvalDtos;
import io.queryforge.backend.rag.repository.RagRepository;
import io.queryforge.backend.rag.service.ChatAnswerService;
import io.queryforge.backend.rag.service.RagRetrievalEvalException;
import io.queryforge.backend.rag.service.RagRetrievalEvalService;
import io.queryforge.backend.rag.service.RagService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = RagRetrievalEvalController.class)
@Import(RagApiExceptionHandler.class)
class RagRetrievalEvalControllerTest {

    private static final UUID DOMAIN_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private RagRetrievalEvalService ragRetrievalEvalService;

    @Test
    void retrievalEvalEndpointReturnsServiceResponseAndPassesRequestBody() throws Exception {
        when(ragRetrievalEvalService.evaluate(any())).thenReturn(successResponse());

        mockMvc.perform(post("/api/rag/eval/retrieval")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "domainId": "11111111-1111-1111-1111-111111111111",
                                  "query": "FilterChainProxy order",
                                  "forcedMode": "raw_only",
                                  "topK": 2,
                                  "persistPolicy": "NONE",
                                  "answerGeneration": false,
                                  "includeTrace": false,
                                  "includeScores": true,
                                  "includeMetadata": false
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.domainId").value(DOMAIN_ID.toString()))
                .andExpect(jsonPath("$.query").value("FilterChainProxy order"))
                .andExpect(jsonPath("$.finalQuery").value("FilterChainProxy order"))
                .andExpect(jsonPath("$.forcedMode").value("raw_only"))
                .andExpect(jsonPath("$.selectedMode").value("raw_only"))
                .andExpect(jsonPath("$.retrievedChunkIds[0]").value("chunk-1"))
                .andExpect(jsonPath("$.retrievedChunkIds[1]").value("chunk-2"))
                .andExpect(jsonPath("$.retrievedDocs[0].rank").value(1))
                .andExpect(jsonPath("$.retrievedDocs[0].score").value(0.91d))
                .andExpect(jsonPath("$.llmCallCount.answerCalls").value(0))
                .andExpect(jsonPath("$.persisted").value(false))
                .andExpect(jsonPath("$.persistPolicy").value("NONE"))
                .andExpect(jsonPath("$.warnings").isArray())
                .andExpect(jsonPath("$.answer").doesNotExist());

        ArgumentCaptor<RagRetrievalEvalDtos.RagRetrievalEvalRequest> captor =
                ArgumentCaptor.forClass(RagRetrievalEvalDtos.RagRetrievalEvalRequest.class);
        verify(ragRetrievalEvalService).evaluate(captor.capture());
        RagRetrievalEvalDtos.RagRetrievalEvalRequest request = captor.getValue();
        assertThat(request.domainId()).isEqualTo(DOMAIN_ID);
        assertThat(request.query()).isEqualTo("FilterChainProxy order");
        assertThat(request.forcedMode()).isEqualTo("raw_only");
        assertThat(request.topK()).isEqualTo(2);
        assertThat(request.persistPolicy()).isEqualTo(RagPersistPolicy.NONE);
        assertThat(request.answerGeneration()).isFalse();
    }

    @Test
    void missingDomainIdMapsToBadRequestProblemDetail() throws Exception {
        assertEvalProblem(
                """
                        {
                          "query": "FilterChainProxy order",
                          "forcedMode": "raw_only"
                        }
                        """,
                "domainId_required",
                "domainId_required: domainId is required"
        );
    }

    @Test
    void blankQueryMapsToBadRequestProblemDetail() throws Exception {
        assertEvalProblem(
                """
                        {
                          "domainId": "11111111-1111-1111-1111-111111111111",
                          "query": "   ",
                          "forcedMode": "raw_only"
                        }
                        """,
                "query_required",
                "query_required: query must not be blank"
        );
    }

    @Test
    void answerGenerationTrueMapsToBadRequestProblemDetail() throws Exception {
        assertEvalProblem(
                """
                        {
                          "domainId": "11111111-1111-1111-1111-111111111111",
                          "query": "FilterChainProxy order",
                          "forcedMode": "raw_only",
                          "answerGeneration": true
                        }
                        """,
                "unsupported_answer_generation",
                "unsupported_answer_generation: answerGeneration=true is unsupported for retrieval eval"
        );
    }

    @Test
    void onlineQueryPersistPolicyMapsToBadRequestProblemDetail() throws Exception {
        assertEvalProblem(
                """
                        {
                          "domainId": "11111111-1111-1111-1111-111111111111",
                          "query": "FilterChainProxy order",
                          "forcedMode": "raw_only",
                          "persistPolicy": "ONLINE_QUERY"
                        }
                        """,
                "unsupported_persist_policy",
                "unsupported_persist_policy: retrieval eval supports only persistPolicy=NONE"
        );
    }

    @Test
    void agenticForcedModeMapsToBadRequestProblemDetail() throws Exception {
        assertEvalProblem(
                """
                        {
                          "domainId": "11111111-1111-1111-1111-111111111111",
                          "query": "FilterChainProxy order",
                          "forcedMode": "agentic_multi_query"
                        }
                        """,
                "unsupported_agentic_eval",
                "unsupported_agentic_eval: agentic_multi_query retrieval eval is blocked until agentic persistPolicy=NONE is implemented"
        );
    }

    @Test
    void controllerDependsOnlyOnRetrievalEvalServiceForRagExecution() {
        List<Class<?>> fieldTypes = Arrays.stream(RagRetrievalEvalController.class.getDeclaredFields())
                .map(Field::getType)
                .toList();

        assertThat(fieldTypes).containsExactly(RagRetrievalEvalService.class);
        assertThat(fieldTypes)
                .doesNotContain(RagService.class, ChatAnswerService.class, RagRepository.class);
    }

    private void assertEvalProblem(String requestBody, String code, String message) throws Exception {
        when(ragRetrievalEvalService.evaluate(any())).thenThrow(new RagRetrievalEvalException(code, message));

        mockMvc.perform(post("/api/rag/eval/retrieval")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Retrieval eval request rejected"))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.detail").value(message))
                .andExpect(jsonPath("$.code").value(code));
    }

    private RagRetrievalEvalDtos.RagRetrievalEvalResponse successResponse() {
        return new RagRetrievalEvalDtos.RagRetrievalEvalResponse(
                DOMAIN_ID,
                "FilterChainProxy order",
                "FilterChainProxy order",
                "raw_only",
                "raw_only",
                List.of("chunk-1", "chunk-2"),
                List.of(
                        new RagRetrievalEvalDtos.RagRetrievalEvalDoc(
                                "chunk-1",
                                "doc-1",
                                null,
                                "Spring Security filter chain preview",
                                0.91d,
                                1
                        ),
                        new RagRetrievalEvalDtos.RagRetrievalEvalDoc(
                                "chunk-2",
                                "doc-2",
                                null,
                                "Second preview",
                                0.82d,
                                2
                        )
                ),
                null,
                RagLlmCallCount.zero(),
                12L,
                false,
                RagPersistPolicy.NONE,
                List.of()
        );
    }
}
