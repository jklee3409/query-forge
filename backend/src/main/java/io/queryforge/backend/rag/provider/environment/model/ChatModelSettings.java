package io.queryforge.backend.rag.provider.environment.model;

import io.queryforge.backend.rag.provider.environment.service.RuntimeEnvService;

import java.util.Locale;

public record ChatModelSettings(String model, String baseUrl, int maxOutputTokens) {

    private static final String DEFAULT_OPENAI_MODEL = "gpt-4o-mini";
    private static final String DEFAULT_GEMINI_MODEL = "gemini-2.5-flash-lite";
    private static final int DEFAULT_MAX_OUTPUT_TOKENS = 384;

    public enum Stage {
        ANSWER, REWRITE, PLANNER
    }

    public static String provider(RuntimeEnvService environment) {
        return environment.getOrDefault("QUERY_FORGE_LLM_PROVIDER", "gemini").toLowerCase(Locale.ROOT);
    }

    public static String springAiModelLabel(RuntimeEnvService environment, Stage stage, String provider) {
        String fallback = stage == Stage.ANSWER && provider.startsWith("openai")
                ? DEFAULT_OPENAI_MODEL : "spring-ai-chat";
        return stageModel(environment, stage, fallback);
    }

    public static ChatModelSettings direct(RuntimeEnvService environment, Stage stage, boolean openAi) {
        String model = stageModel(environment, stage, openAi ? DEFAULT_OPENAI_MODEL : DEFAULT_GEMINI_MODEL);
        String baseUrl = openAi
                ? environment.getOrDefault("QUERY_FORGE_OPENAI_BASE_URL", "https://api.openai.com/v1")
                : environment.getOrDefault("QUERY_FORGE_GEMINI_BASE_URL", "https://generativelanguage.googleapis.com");
        String rawLimit = environment.getOrDefault(
                "QUERY_FORGE_LLM_MAX_OUTPUT_TOKENS", Integer.toString(DEFAULT_MAX_OUTPUT_TOKENS)
        );
        return new ChatModelSettings(model, baseUrl, outputTokenLimit(rawLimit, stage));
    }

    private static String stageModel(RuntimeEnvService environment, Stage stage, String fallback) {
        String model = environment.getOrDefault("QUERY_FORGE_LLM_MODEL", fallback);
        if (stage != Stage.ANSWER) {
            model = environment.getOrDefault("QUERY_FORGE_LLM_REWRITE_MODEL", model);
        }
        if (stage == Stage.PLANNER) {
            model = environment.getOrDefault("QUERY_FORGE_LLM_PLANNER_MODEL", model);
        }
        return model;
    }

    private static int outputTokenLimit(String value, Stage stage) {
        try {
            int parsed = Integer.parseInt(stage == Stage.PLANNER || value == null ? value : value.trim());
            // The planner historically forwards zero and negative integers to its provider.
            return stage == Stage.PLANNER || parsed > 0 ? parsed : DEFAULT_MAX_OUTPUT_TOKENS;
        } catch (NumberFormatException ignored) {
            return DEFAULT_MAX_OUTPUT_TOKENS;
        }
    }
}
