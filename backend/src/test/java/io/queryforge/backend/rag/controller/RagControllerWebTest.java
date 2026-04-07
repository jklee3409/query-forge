package io.queryforge.backend.rag.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.queryforge.backend.rag.model.RagDtos;
import io.queryforge.backend.rag.service.ExperimentPipelineService;
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
                        Map.of("totalMs", 12L)
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
