package io.queryforge.backend.rag.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.queryforge.backend.rag.model.ChatRuntimeDtos;
import io.queryforge.backend.rag.model.RagDtos;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AgenticQueryPlannerServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    private RuntimeEnvService runtimeEnvService;
    @Mock
    private SpringAiChatGateway springAiChatGateway;

    private AgenticQueryPlannerService plannerService;

    @BeforeEach
    void setUp() {
        plannerService = new AgenticQueryPlannerService(objectMapper, runtimeEnvService);
        lenient().when(runtimeEnvService.getOrDefault(anyString(), anyString()))
                .thenAnswer(invocation -> invocation.getArgument(1));
        lenient().when(runtimeEnvService.get(anyString())).thenReturn(null);
    }

    @Test
    void planFallsBackToOriginalQueryWhenLlmPlannerIsUnavailable() {
        UUID domainId = UUID.randomUUID();
        RagDtos.AgenticQueryPlan plan = plannerService.plan(
                "스프링 DB 연결 오류 설정 확인",
                config(domainId),
                objectMapper.createObjectNode(),
                List.of(),
                3
        );

        assertThat(plan.fallbackApplied()).isTrue();
        assertThat(plan.subqueries()).hasSize(1);
        assertThat(plan.subqueries().getFirst().query()).isEqualTo("스프링 DB 연결 오류 설정 확인");
        assertThat(plan.domainId()).isEqualTo(domainId);
    }

    @Test
    void planCanUseSpringAiGatewayAndKeepsExistingPlanNormalization() {
        UUID domainId = UUID.randomUUID();
        AgenticQueryPlannerService springAiPlanner = new AgenticQueryPlannerService(
                objectMapper,
                runtimeEnvService,
                springAiChatGateway
        );
        when(runtimeEnvService.getOrDefault("QUERY_FORGE_LLM_PROVIDER", "gemini")).thenReturn("openai");
        when(runtimeEnvService.getOrDefault("QUERY_FORGE_LLM_MODEL", "spring-ai-chat")).thenReturn("gpt-test");
        when(springAiChatGateway.completeJson(eq("openai"), anyString(), anyString()))
                .thenReturn(Optional.of("""
                        {
                          "subqueries": [
                            {"query": "FilterChainProxy ordering", "intent": "filter_chain", "weight": 0.8},
                            {"query": "FilterChainProxy ordering", "intent": "duplicate", "weight": 0.2},
                            {"query": "SecurityFilterChain matching", "intent": "matching", "weight": 1.2}
                          ]
                        }
                        """));

        RagDtos.AgenticQueryPlan plan = springAiPlanner.plan(
                "How does Spring Security filter ordering work?",
                config(domainId),
                objectMapper.createObjectNode(),
                List.of(),
                4
        );

        assertThat(plan.fallbackApplied()).isFalse();
        assertThat(plan.plannerModel()).isEqualTo("gpt-test");
        assertThat(plan.metadata().path("planner_provider").asText()).isEqualTo("spring-ai");
        assertThat(plan.subqueries()).extracting(RagDtos.AgenticSubquery::query)
                .containsExactly("FilterChainProxy ordering", "SecurityFilterChain matching");
        assertThat(plan.subqueries().get(1).weight()).isEqualTo(1.0d);
    }

    private ChatRuntimeDtos.ChatRuntimeConfigResponse config(UUID domainId) {
        return new ChatRuntimeDtos.ChatRuntimeConfigResponse(
                domainId,
                "spring",
                "Spring",
                "en",
                true,
                "selective_rewrite",
                List.of("C"),
                "full_gating",
                null,
                null,
                List.of(),
                List.of(),
                "compact_anchor",
                true,
                false,
                "local",
                "intfloat/multilingual-e5-small",
                "hybrid",
                20,
                0.6d,
                0.32d,
                0.08d,
                10,
                5,
                5,
                2,
                0.05d,
                "skip_to_raw",
                false,
                objectMapper.createObjectNode(),
                Instant.now(),
                true,
                "ready"
        );
    }
}
