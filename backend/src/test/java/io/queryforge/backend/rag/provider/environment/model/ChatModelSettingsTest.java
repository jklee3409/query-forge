package io.queryforge.backend.rag.provider.environment.model;

import io.queryforge.backend.rag.provider.environment.model.ChatModelSettings.Stage;
import io.queryforge.backend.rag.provider.environment.service.RuntimeEnvService;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ChatModelSettingsTest {

    @Test
    void stageOverridesKeepTheirPrecedenceAcrossProviders() {
        var environment = environment(Map.of(
                "QUERY_FORGE_LLM_PROVIDER", "OpenAI-compatible",
                "QUERY_FORGE_LLM_MODEL", "base-model",
                "QUERY_FORGE_LLM_REWRITE_MODEL", "rewrite-model",
                "QUERY_FORGE_LLM_PLANNER_MODEL", "planner-model",
                "QUERY_FORGE_LLM_MAX_OUTPUT_TOKENS", "512"
        ));

        assertThat(ChatModelSettings.provider(environment)).isEqualTo("openai-compatible");
        assertThat(ChatModelSettings.direct(environment, Stage.ANSWER, true))
                .isEqualTo(new ChatModelSettings("base-model", "https://api.openai.com/v1", 512));
        assertThat(ChatModelSettings.direct(environment, Stage.REWRITE, false))
                .isEqualTo(new ChatModelSettings("rewrite-model", "https://generativelanguage.googleapis.com", 512));
        assertThat(ChatModelSettings.direct(environment, Stage.PLANNER, true).model()).isEqualTo("planner-model");
        assertThat(ChatModelSettings.springAiModelLabel(environment, Stage.PLANNER, "openai"))
                .isEqualTo("planner-model");
    }

    @Test
    void unsetAndInvalidValuesKeepStageFallbacks() {
        var unset = environment(Map.of());
        assertThat(ChatModelSettings.provider(unset)).isEqualTo("gemini");
        assertThat(ChatModelSettings.direct(unset, Stage.REWRITE, false).model()).isEqualTo("gemini-2.5-flash-lite");
        assertThat(ChatModelSettings.direct(unset, Stage.ANSWER, true).model()).isEqualTo("gpt-4o-mini");
        assertThat(ChatModelSettings.springAiModelLabel(unset, Stage.PLANNER, "openai")).isEqualTo("spring-ai-chat");

        var zero = environment(Map.of("QUERY_FORGE_LLM_MAX_OUTPUT_TOKENS", "0"));
        var invalid = environment(Map.of("QUERY_FORGE_LLM_MAX_OUTPUT_TOKENS", "invalid"));
        assertThat(ChatModelSettings.direct(zero, Stage.ANSWER, true).maxOutputTokens()).isEqualTo(384);
        assertThat(ChatModelSettings.direct(zero, Stage.REWRITE, false).maxOutputTokens()).isEqualTo(384);
        assertThat(ChatModelSettings.direct(zero, Stage.PLANNER, false).maxOutputTokens()).isZero();
        assertThat(ChatModelSettings.direct(invalid, Stage.PLANNER, true).maxOutputTokens()).isEqualTo(384);
    }

    private RuntimeEnvService environment(Map<String, String> values) {
        RuntimeEnvService environment = mock(RuntimeEnvService.class);
        when(environment.getOrDefault(anyString(), anyString()))
                .thenAnswer(call -> values.getOrDefault(call.getArgument(0), call.getArgument(1)));
        return environment;
    }
}
