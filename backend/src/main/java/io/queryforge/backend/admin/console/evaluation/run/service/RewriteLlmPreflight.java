package io.queryforge.backend.admin.console.evaluation.run.service;

import io.queryforge.backend.admin.console.runtime.service.ConsoleEnvironment;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.regex.Pattern;

import static io.queryforge.backend.admin.console.configuration.support.ConsoleParameters.asTrimmedString;
import static io.queryforge.backend.admin.console.configuration.support.ConsoleParameters.firstNonBlank;

@Component
@RequiredArgsConstructor
public class RewriteLlmPreflight {

    private final ConsoleEnvironment environment;
    private static final Pattern NON_ALNUM_PATTERN = Pattern.compile("[^A-Z0-9]");

    private String normalizeLlmProviderName(String provider) {
        if (provider == null || provider.isBlank()) {
            return "";
        }
        return provider.trim().toLowerCase().replace("_", "-");
    }

    private String providerEnvSuffix(String provider) {
        String normalized = normalizeLlmProviderName(provider).toUpperCase();
        return NON_ALNUM_PATTERN.matcher(normalized).replaceAll("_");
    }

    private String resolveApiKeyForProvider(String provider, String rewriteStageApiKey, String genericLlmApiKey) {
        String envSuffix = providerEnvSuffix(provider);
        String rewriteEnvApiKey = environment.readEnv("QUERY_FORGE_LLM_REWRITE_API_KEY");
        String sharedEnvApiKey = environment.readEnv("QUERY_FORGE_LLM_API_KEY");
        String providerScopedEnvApiKey = environment.readEnv("QUERY_FORGE_LLM_" + envSuffix + "_API_KEY");
        return switch (normalizeLlmProviderName(provider)) {
            case "gemini", "gemini-native" -> firstNonBlank(
                    rewriteStageApiKey,
                    genericLlmApiKey,
                    rewriteEnvApiKey,
                    sharedEnvApiKey,
                    providerScopedEnvApiKey,
                    environment.readEnv("QUERY_FORGE_GEMINI_API_KEY"),
                    environment.readEnv("QUERY_FORGE_LLM_GEMINI_API_KEY"),
                    environment.readEnv("GEMINI_API_KEY"),
                    environment.readEnv("GOOGLE_API_KEY")
            );
            case "openai" -> firstNonBlank(
                    rewriteStageApiKey,
                    genericLlmApiKey,
                    rewriteEnvApiKey,
                    sharedEnvApiKey,
                    providerScopedEnvApiKey,
                    environment.readEnv("OPENAI_API_KEY")
            );
            case "groq" -> firstNonBlank(
                    rewriteStageApiKey,
                    genericLlmApiKey,
                    rewriteEnvApiKey,
                    sharedEnvApiKey,
                    providerScopedEnvApiKey,
                    environment.readEnv("GROQ_API_KEY")
            );
            default -> firstNonBlank(
                    rewriteStageApiKey,
                    genericLlmApiKey,
                    rewriteEnvApiKey,
                    sharedEnvApiKey,
                    providerScopedEnvApiKey
            );
        };
    }

    public void validateRewriteStageLlmConfig(Map<String, Object> config, boolean rewriteEnabled) {
        if (!rewriteEnabled) {
            return;
        }
        String provider = normalizeLlmProviderName(
                firstNonBlank(
                        asTrimmedString(config.get("llm_rewrite_provider")),
                        asTrimmedString(config.get("llm_provider")),
                        environment.readEnv("QUERY_FORGE_LLM_REWRITE_PROVIDER"),
                        environment.readEnv("QUERY_FORGE_LLM_PROVIDER"),
                        "gemini-native"
                )
        );
        if (provider.isBlank()) {
            throw new IllegalArgumentException(
                    "rewrite_enabled=true requires llm_rewrite_provider (or llm_provider) for stage=rewrite"
            );
        }
        String model = firstNonBlank(
                asTrimmedString(config.get("llm_rewrite_model")),
                asTrimmedString(config.get("llm_model")),
                environment.readEnv("QUERY_FORGE_LLM_REWRITE_MODEL"),
                environment.readEnv("QUERY_FORGE_LLM_MODEL")
        );
        if (model.isBlank()) {
            throw new IllegalArgumentException(
                    "rewrite_enabled=true requires llm_rewrite_model (or llm_model) for stage=rewrite"
            );
        }
        String apiKey = resolveApiKeyForProvider(
                provider,
                asTrimmedString(config.get("llm_rewrite_api_key")),
                asTrimmedString(config.get("llm_api_key"))
        );
        if (apiKey.isBlank()) {
            throw new IllegalArgumentException(
                    "rewrite_enabled=true requires rewrite-stage LLM API key "
                            + "(llm_rewrite_api_key / QUERY_FORGE_LLM_REWRITE_API_KEY / QUERY_FORGE_LLM_API_KEY "
                            + "/ GEMINI_API_KEY / provider API key env)"
            );
        }
    }
}
