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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AgenticQueryPlannerServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    private RuntimeEnvService runtimeEnvService;

    private AgenticQueryPlannerService plannerService;

    @BeforeEach
    void setUp() {
        plannerService = new AgenticQueryPlannerService(objectMapper, runtimeEnvService);
        when(runtimeEnvService.getOrDefault(anyString(), anyString()))
                .thenAnswer(invocation -> invocation.getArgument(1));
        when(runtimeEnvService.get(anyString())).thenReturn(null);
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
