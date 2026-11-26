package io.queryforge.backend.rag.provider.springai.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.queryforge.backend.rag.chat.answer.service.ChatAnswerService;
import io.queryforge.backend.rag.provider.environment.service.RuntimeEnvService;
import io.queryforge.backend.rag.provider.springai.gateway.SpringAiChatGateway;
import io.queryforge.backend.rag.retrieval.agentic.planning.service.AgenticQueryPlannerService;
import io.queryforge.backend.rag.retrieval.candidate.model.RetrievalDoc;
import io.queryforge.backend.rag.retrieval.rewrite.dto.RewriteDtos.CandidateTemplate;
import io.queryforge.backend.rag.retrieval.rewrite.service.RewriteCandidateService;
import io.queryforge.backend.rag.runtime.configuration.dto.ChatConfigDtos.ChatRuntimeConfigResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ProviderRequestContractTest {

    private static final String RAW_QUERY = "How does {Order} work?";
    private final ObjectMapper mapper = new ObjectMapper();
    private final List<CapturedRequest> requests = new CopyOnWriteArrayList<>();

    @TempDir
    Path promptRoot;
    private HttpServer server;

    @AfterEach
    void stopProvider() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void rewriteFallsBackFromInvalidSpringAiJsonToDirectRequest() throws Exception {
        var environment = provider(200, """
                {"candidates":[{"query":"Order filter chain","label":"expanded"}]}
                """);
        String systemPrompt = "Rewrite this query and return JSON {\"candidates\": []}.\n";
        Path rewriteDirectory = Files.createDirectory(promptRoot.resolve("rewrite"));
        Files.writeString(rewriteDirectory.resolve("selective_rewrite_detailed_intent_v1.md"), systemPrompt);
        var gateway = mock(SpringAiChatGateway.class);
        when(gateway.completeJson(eq("openai"), eq(systemPrompt), anyString()))
                .thenReturn(Optional.of("invalid JSON"));
        var service = new RewriteCandidateService(mapper, environment, gateway);

        var candidates = service.buildCandidates(RAW_QUERY, mapper.readTree("""
                {"previous_user_question":"How do filters work?"}
                """), List.of(), 1, "detailed_intent", true, mapper.createObjectNode());

        assertThat(candidates).containsExactly(new CandidateTemplate("expanded", "Order filter chain"));
        JsonNode payload = openAiRequest("rewrite-model", 0.2);
        assertThat(payload.at("/messages/0/content").asText()).isEqualTo(systemPrompt);
        JsonNode userMessage = userMessage(payload);
        assertThat(userMessage.path("raw_query").asText()).isEqualTo(RAW_QUERY);
        assertThat(userMessage.at("/session_context/previous_user_question").asText())
                .isEqualTo("How do filters work?");
        assertThat(userMessage.path("rewrite_query_profile").asText()).isEqualTo("detailed_intent");
        assertThat(userMessage.at("/candidate_policy/anchor_hints_allowed").asBoolean()).isTrue();
    }

    @Test
    void plannerUsesStageModelAndReturnsOriginalQueryAfterOne503() throws Exception {
        var service = new AgenticQueryPlannerService(mapper, provider(503, "unavailable"));
        var config = mock(ChatRuntimeConfigResponse.class);
        when(config.domainId()).thenReturn(UUID.fromString("11111111-1111-1111-1111-111111111111"));
        when(config.domainKey()).thenReturn("spring");
        when(config.displayName()).thenReturn("Spring");
        when(config.sourceLanguage()).thenReturn("en");

        var plan = service.plan(RAW_QUERY, config, mapper.createObjectNode(), List.of(), 2);

        assertThat(plan.fallbackApplied()).isTrue();
        assertThat(plan.subqueries()).hasSize(1);
        assertThat(plan.subqueries().getFirst().query()).isEqualTo(RAW_QUERY);
        JsonNode payload = openAiRequest("planner-model", 0.1);
        assertThat(payload.at("/messages/0/content").asText()).contains("query-planning stage", "subqueries");
        JsonNode userMessage = userMessage(payload);
        assertThat(userMessage.path("domain_key").asText()).isEqualTo("spring");
        assertThat(userMessage.path("technical_domain").asText()).isEqualTo("Spring");
        assertThat(userMessage.path("max_subqueries").asInt()).isEqualTo(2);
    }

    @Test
    void answerUsesCommonModelAndPreservesContextOrder() throws Exception {
        var service = new ChatAnswerService(mapper, provider(200, """
                {"answer":"Ordered filters","used_chunk_ids":["chunk-2","unknown"]}
                """));

        var answer = service.generateAnswer(RAW_QUERY, "Order filter chain", "Spring", List.of(
                new RetrievalDoc("doc-1", "chunk-1", "First {Order}", 0.8),
                new RetrievalDoc("doc-2", "chunk-2", "Second", 0.7)
        ));

        assertThat(answer.answerText()).isEqualTo("Ordered filters");
        assertThat(answer.modelName()).isEqualTo("answer-model");
        assertThat(answer.citedChunkIds()).containsExactly("chunk-2");
        JsonNode payload = openAiRequest("answer-model", 0.1);
        assertThat(payload.at("/messages/0/content").asText()).contains("answer-generation stage", "used_chunk_ids");
        JsonNode userMessage = userMessage(payload);
        assertThat(userMessage.path("final_query").asText()).isEqualTo("Order filter chain");
        assertThat(userMessage.at("/context_chunks/0/chunk_id").asText()).isEqualTo("chunk-1");
        assertThat(userMessage.at("/context_chunks/0/chunk_text").asText()).isEqualTo("First {Order}");
        assertThat(userMessage.at("/context_chunks/1/chunk_id").asText()).isEqualTo("chunk-2");
    }

    @Test
    void answerReportsOpenAi503WithoutRetry() throws Exception {
        var service = new ChatAnswerService(mapper, provider(503, "unavailable"));

        assertThatThrownBy(() -> service.generateAnswer(RAW_QUERY, RAW_QUERY, "Spring", List.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("chat answer generation failed with status: 503");
        openAiRequest("answer-model", 0.1);
    }

    private RuntimeEnvService provider(int status, String content) throws IOException {
        var envelope = mapper.createObjectNode();
        envelope.putArray("choices").addObject().putObject("message").put("content", content);
        byte[] response = mapper.writeValueAsBytes(envelope);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> capture(exchange, status, response));
        server.start();

        var values = Map.of(
                "QUERY_FORGE_LLM_PROVIDER", "openai",
                "QUERY_FORGE_LLM_MODEL", "answer-model",
                "QUERY_FORGE_LLM_REWRITE_MODEL", "rewrite-model",
                "QUERY_FORGE_LLM_PLANNER_MODEL", "planner-model",
                "QUERY_FORGE_LLM_MAX_OUTPUT_TOKENS", "263",
                "QUERY_FORGE_OPENAI_BASE_URL", "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/",
                "OPENAI_API_KEY", "fixture-key",
                "PROMPT_ROOT", promptRoot.toString()
        );
        var environment = mock(RuntimeEnvService.class);
        when(environment.getOrDefault(anyString(), anyString())).thenAnswer(invocation ->
                values.getOrDefault(invocation.getArgument(0), invocation.getArgument(1)));
        when(environment.get(anyString())).thenAnswer(invocation -> values.get(invocation.getArgument(0)));
        return environment;
    }

    private void capture(HttpExchange exchange, int status, byte[] response) throws IOException {
        try (exchange) {
            var body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            requests.add(new CapturedRequest(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
                    exchange.getRequestHeaders().getFirst("Authorization"), body));
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, response.length);
            exchange.getResponseBody().write(response);
        }
    }

    private JsonNode openAiRequest(String model, double temperature) throws IOException {
        assertThat(requests).hasSize(1);
        CapturedRequest request = requests.getFirst();
        assertThat(request.method()).isEqualTo("POST");
        assertThat(request.path()).isEqualTo("/v1/chat/completions");
        assertThat(request.authorization()).isEqualTo("Bearer fixture-key");
        JsonNode payload = mapper.readTree(request.body());
        assertThat(payload.path("model").asText()).isEqualTo(model);
        assertThat(payload.path("temperature").asDouble()).isEqualTo(temperature);
        assertThat(payload.path("max_tokens").asInt()).isEqualTo(263);
        assertThat(payload.at("/response_format/type").asText()).isEqualTo("json_object");
        assertThat(payload.path("messages").size()).isEqualTo(2);
        assertThat(payload.at("/messages/0/role").asText()).isEqualTo("system");
        assertThat(payload.at("/messages/1/role").asText()).isEqualTo("user");
        return payload;
    }

    private JsonNode userMessage(JsonNode payload) throws IOException {
        return mapper.readTree(payload.at("/messages/1/content").asText());
    }

    private record CapturedRequest(String method, String path, String authorization, String body) {
    }
}
