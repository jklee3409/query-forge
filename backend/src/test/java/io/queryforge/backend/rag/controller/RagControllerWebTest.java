package io.queryforge.backend.rag.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.queryforge.backend.rag.model.RagDtos;
import io.queryforge.backend.rag.service.ExperimentPipelineService;
import io.queryforge.backend.rag.service.GeminiServiceUnavailableException;
import io.queryforge.backend.rag.service.RagService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = RagController.class)
class RagControllerWebTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private RagService ragService;

    @MockBean
    private ExperimentPipelineService experimentPipelineService;

    @Test
    void askEndpointReturnsTracePayload() throws Exception {
        UUID queryId = UUID.randomUUID();
        when(ragService.ask(any())).thenReturn(
                new RagDtos.AskResponse(
                        queryId,
                        "answer",
                        "final query",
                        "raw query",
                        true,
                        List.of(),
                        List.of(),
                        List.of(),
                        objectMapper.createArrayNode(),
                        "gemini-2.5-flash-lite",
                        List.of(),
                        List.of(),
                        null,
                        Map.of("totalMs", 12L),
                        null
                )
        );

        mockMvc.perform(post("/api/chat/ask")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "query": "스프링 시큐리티 필터 체인 순서가 궁금해요"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.onlineQueryId").value(queryId.toString()))
                .andExpect(jsonPath("$.answer").value("answer"))
                .andExpect(jsonPath("$.rewriteApplied").value(true));
    }

    @Test
    void askEndpointReturnsGeminiServiceUnavailableProblemDetail() throws Exception {
        when(ragService.ask(any())).thenThrow(new GeminiServiceUnavailableException(
                "gemini-2.5-flash-lite",
                503,
                2
        ));

        mockMvc.perform(post("/api/chat/ask")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "query": "스프링 시큐리티 필터 체인 순서가 궁금해요"
                                }
                                """))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.errorCode").value("GEMINI_SERVICE_UNAVAILABLE"))
                .andExpect(jsonPath("$.detail").value("Gemini 모델에 문제가 발생하였습니다. 잠시 후 다시 시도해주세요."))
                .andExpect(jsonPath("$.retryMessage").value("Gemini 모델에 문제가 발생하였습니다. 답변을 다시 생성 중입니다"))
                .andExpect(jsonPath("$.attempts").value(2));
    }

    @Test
    void retrievalEvalEndpointReturnsLatestPayload() throws Exception {
        when(ragService.readEvalReport("retrieval")).thenReturn(
                new RagDtos.EvalReportResponse("retrieval", objectMapper.readTree("{\"ok\":true}"))
        );

        mockMvc.perform(get("/api/eval/retrieval"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reportType").value("retrieval"))
                .andExpect(jsonPath("$.payload.ok").value(true));
    }
}
