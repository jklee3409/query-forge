package io.queryforge.backend.rag.provider.springai;

import io.queryforge.backend.rag.provider.environment.RuntimeEnvService;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Optional;

@Component
@RequiredArgsConstructor
public class SpringAiChatGateway {

    private final ObjectProvider<ChatClient.Builder> chatClientBuilderProvider;
    private final RuntimeEnvService runtimeEnvService;

    public Optional<String> completeJson(String provider, String systemPrompt, String userPrompt) {
        if (!isEnabledFor(provider)) {
            return Optional.empty();
        }
        ChatClient.Builder builder = chatClientBuilderProvider.getIfAvailable();
        if (builder == null) {
            return Optional.empty();
        }
        try {
            String content = builder.build()
                    .prompt()
                    .system(systemPrompt)
                    .user(userPrompt)
                    .call()
                    .content();
            return content == null || content.isBlank() ? Optional.empty() : Optional.of(content.trim());
        } catch (RuntimeException exception) {
            return Optional.empty();
        }
    }

    private boolean isEnabledFor(String provider) {
        String client = runtimeEnvService.getOrDefault("QUERY_FORGE_LLM_CLIENT", "").trim().toLowerCase(Locale.ROOT);
        if (!client.isBlank()) {
            return "spring_ai".equals(client) || "spring-ai".equals(client);
        }
        String normalizedProvider = provider == null ? "" : provider.trim().toLowerCase(Locale.ROOT);
        return normalizedProvider.startsWith("spring-ai") || normalizedProvider.startsWith("openai");
    }
}
