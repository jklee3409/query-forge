package io.queryforge.backend.rag.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.queryforge.backend.rag.repository.RagRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChatAnswerServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    private RuntimeEnvService runtimeEnvService;

    @Test
    void geminiAnswerRetriesOnceWhenFirstRequestReturnsServiceUnavailable() throws Exception {
        AtomicInteger requestCount = new AtomicInteger();
        HttpServer server = geminiServer(exchange -> {
            int attempt = requestCount.incrementAndGet();
            if (attempt == 1) {
                send(exchange, 503, "{\"error\":{\"status\":\"UNAVAILABLE\"}}");
                return;
            }
            send(exchange, 200, geminiAnswerBody("retry ok"));
        });
        try {
            configureGemini(server);
            ChatAnswerService service = new ChatAnswerService(objectMapper, runtimeEnvService);

            ChatAnswerService.GeneratedAnswer answer = service.generateAnswer(
                    "스프링 필터 순서",
                    "스프링 필터 순서",
                    "Spring",
                    docs()
            );

            assertThat(answer.answerText()).isEqualTo("retry ok");
            assertThat(answer.citedDocumentIds()).containsExactly("doc-1");
            assertThat(answer.citedChunkIds()).containsExactly("chunk-1");
            assertThat(requestCount).hasValue(2);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void geminiAnswerThrowsServiceUnavailableAfterRetryAlsoFails() throws Exception {
        AtomicInteger requestCount = new AtomicInteger();
        HttpServer server = geminiServer(exchange -> {
            requestCount.incrementAndGet();
            send(exchange, 503, "{\"error\":{\"status\":\"UNAVAILABLE\"}}");
        });
        try {
            configureGemini(server);
            ChatAnswerService service = new ChatAnswerService(objectMapper, runtimeEnvService);

            assertThatThrownBy(() -> service.generateAnswer(
                    "스프링 필터 순서",
                    "스프링 필터 순서",
                    "Spring",
                    docs()
            ))
                    .isInstanceOf(GeminiServiceUnavailableException.class)
                    .satisfies(exception -> {
                        GeminiServiceUnavailableException unavailable = (GeminiServiceUnavailableException) exception;
                        assertThat(unavailable.modelName()).isEqualTo("gemini-test-model");
                        assertThat(unavailable.statusCode()).isEqualTo(503);
                        assertThat(unavailable.attempts()).isEqualTo(2);
                    });
            assertThat(requestCount).hasValue(2);
        } finally {
            server.stop(0);
        }
    }

    private void configureGemini(HttpServer server) {
        String baseUrl = "http://localhost:" + server.getAddress().getPort();
        when(runtimeEnvService.getOrDefault("QUERY_FORGE_LLM_PROVIDER", "gemini")).thenReturn("gemini");
        when(runtimeEnvService.get("QUERY_FORGE_GEMINI_API_KEY")).thenReturn("test-key");
        when(runtimeEnvService.get("GEMINI_API_KEY")).thenReturn(null);
        when(runtimeEnvService.get("GOOGLE_API_KEY")).thenReturn(null);
        when(runtimeEnvService.getOrDefault("QUERY_FORGE_LLM_MODEL", "gemini-2.5-flash-lite"))
                .thenReturn("gemini-test-model");
        when(runtimeEnvService.getOrDefault("QUERY_FORGE_GEMINI_BASE_URL", "https://generativelanguage.googleapis.com"))
                .thenReturn(baseUrl);
        when(runtimeEnvService.getOrDefault("QUERY_FORGE_LLM_MAX_OUTPUT_TOKENS", "384")).thenReturn("384");
    }

    private HttpServer geminiServer(ExchangeHandler handler) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/", handler::handle);
        server.start();
        return server;
    }

    private String geminiAnswerBody(String answer) {
        String answerJson = """
                {"answer":"%s","used_document_ids":["doc-1"],"used_chunk_ids":["chunk-1"]}
                """.formatted(answer).trim().replace("\"", "\\\"");
        return """
                {
                  "candidates": [
                    {
                      "content": {
                        "parts": [
                          {"text": "%s"}
                        ]
                      }
                    }
                  ]
                }
                """.formatted(answerJson);
    }

    private List<RagRepository.RetrievalDoc> docs() {
        return List.of(new RagRepository.RetrievalDoc(
                "doc-1",
                "chunk-1",
                "Spring Security filter chain reference",
                0.9d
        ));
    }

    private void send(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream outputStream = exchange.getResponseBody()) {
            outputStream.write(bytes);
        }
    }

    @FunctionalInterface
    private interface ExchangeHandler {
        void handle(HttpExchange exchange) throws IOException;
    }
}
