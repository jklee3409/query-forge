package io.queryforge.backend.rag.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.queryforge.backend.rag.model.ChatRuntimeDtos;
import io.queryforge.backend.rag.model.RagDtos;
import io.queryforge.backend.rag.repository.RagRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

@Component
@RequiredArgsConstructor
public class AgenticQueryPlannerService {

    private static final String SYSTEM_PROMPT = """
            You are the query-planning stage of a domain-scoped technical-document RAG system.
            Split the user's query into retrieval subqueries only inside the currently selected technical domain.
            Do not route to other domains, do not mention cross-domain execution, and do not invent unsupported technologies.
            Prefer concise retrieval-oriented English/code-mixed technical terms when the source domain language is English.
            Return strict JSON with this shape:
            {
              "subqueries": [
                {"query": "retrieval query", "intent": "short intent label", "weight": 1.0}
              ]
            }
            """;

    private static final int MEMORY_HINT_LIMIT = 5;

    private final ObjectMapper objectMapper;
    private final RuntimeEnvService runtimeEnvService;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public RagDtos.AgenticQueryPlan plan(
            String rawQuery,
            ChatRuntimeDtos.ChatRuntimeConfigResponse config,
            JsonNode domainContext,
            List<RagRepository.MemoryCandidate> memoryHints,
            int maxSubqueries
    ) {
        int limitedMax = Math.max(1, Math.min(maxSubqueries, 4));
        Optional<PlannerPayload> payload = requestPlan(rawQuery, config, domainContext, memoryHints, limitedMax);
        if (payload.isEmpty()) {
            return fallbackPlan(rawQuery, config, limitedMax, "planner unavailable or returned no valid subquery");
        }
        List<RagDtos.AgenticSubquery> subqueries = normalizeSubqueries(
                rawQuery,
                payload.get().subqueries(),
                limitedMax
        );
        if (subqueries.isEmpty()) {
            return fallbackPlan(rawQuery, config, limitedMax, "planner returned empty subqueries");
        }
        ObjectNode metadata = objectMapper.createObjectNode();
        metadata.put("planner_provider", payload.get().provider());
        metadata.put("source", "llm-planner");
        return new RagDtos.AgenticQueryPlan(
                safeTrim(rawQuery),
                config.domainId(),
                safeTrim(config.domainKey()),
                safeTrim(config.displayName()),
                limitedMax,
                subqueries,
                payload.get().model(),
                false,
                null,
                metadata
        );
    }

    private RagDtos.AgenticQueryPlan fallbackPlan(
            String rawQuery,
            ChatRuntimeDtos.ChatRuntimeConfigResponse config,
            int maxSubqueries,
            String reason
    ) {
        ObjectNode metadata = objectMapper.createObjectNode();
        metadata.put("source", "fallback-original-query");
        RagDtos.AgenticSubquery subquery = new RagDtos.AgenticSubquery(
                1,
                safeTrim(rawQuery),
                "original_query",
                1.0d,
                objectMapper.createObjectNode()
        );
        return new RagDtos.AgenticQueryPlan(
                safeTrim(rawQuery),
                config.domainId(),
                safeTrim(config.domainKey()),
                safeTrim(config.displayName()),
                maxSubqueries,
                List.of(subquery),
                "fallback-original-query",
                true,
                reason,
                metadata
        );
    }

    private Optional<PlannerPayload> requestPlan(
            String rawQuery,
            ChatRuntimeDtos.ChatRuntimeConfigResponse config,
            JsonNode domainContext,
            List<RagRepository.MemoryCandidate> memoryHints,
            int maxSubqueries
    ) {
        String provider = runtimeEnvService.getOrDefault("QUERY_FORGE_LLM_PROVIDER", "gemini").toLowerCase(Locale.ROOT);
        if (provider.startsWith("openai")) {
            return requestOpenAiPlan(rawQuery, config, domainContext, memoryHints, maxSubqueries);
        }
        return requestGeminiPlan(rawQuery, config, domainContext, memoryHints, maxSubqueries);
    }

    private Optional<PlannerPayload> requestGeminiPlan(
            String rawQuery,
            ChatRuntimeDtos.ChatRuntimeConfigResponse config,
            JsonNode domainContext,
            List<RagRepository.MemoryCandidate> memoryHints,
            int maxSubqueries
    ) {
        String apiKey = firstNonBlank(
                runtimeEnvService.get("QUERY_FORGE_GEMINI_API_KEY"),
                runtimeEnvService.get("GEMINI_API_KEY"),
                runtimeEnvService.get("GOOGLE_API_KEY")
        );
        if (apiKey.isBlank()) {
            return Optional.empty();
        }
        String model = runtimeEnvService.getOrDefault(
                "QUERY_FORGE_LLM_PLANNER_MODEL",
                runtimeEnvService.getOrDefault("QUERY_FORGE_LLM_REWRITE_MODEL",
                        runtimeEnvService.getOrDefault("QUERY_FORGE_LLM_MODEL", "gemini-2.5-flash-lite"))
        );
        String baseUrl = runtimeEnvService.getOrDefault("QUERY_FORGE_GEMINI_BASE_URL", "https://generativelanguage.googleapis.com");
        int maxOutputTokens = parseInt(runtimeEnvService.getOrDefault("QUERY_FORGE_LLM_MAX_OUTPUT_TOKENS", "384"), 384);
        try {
            ObjectNode payload = objectMapper.createObjectNode();
            payload.putObject("systemInstruction")
                    .putArray("parts")
                    .addObject()
                    .put("text", SYSTEM_PROMPT);
            payload.putArray("contents")
                    .addObject()
                    .put("role", "user")
                    .putArray("parts")
                    .addObject()
                    .put("text", buildPlannerPrompt(rawQuery, config, domainContext, memoryHints, maxSubqueries));
            ObjectNode generationConfig = payload.putObject("generationConfig");
            generationConfig.put("responseMimeType", "application/json");
            generationConfig.put("temperature", 0.1d);
            generationConfig.put("maxOutputTokens", maxOutputTokens);

            String encodedModel = URLEncoder.encode(model, StandardCharsets.UTF_8);
            URI uri = URI.create(
                    baseUrl.replaceAll("/+$", "")
                            + "/v1beta/models/"
                            + encodedModel
                            + ":generateContent?key="
                            + URLEncoder.encode(apiKey, StandardCharsets.UTF_8)
            );
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(uri)
                    .timeout(Duration.ofSeconds(45))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload.toString()))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                return Optional.empty();
            }
            JsonNode root = objectMapper.readTree(response.body() == null ? "{}" : response.body());
            return parsePlannerPayload(extractGeminiText(root))
                    .map(subqueries -> new PlannerPayload("gemini", model, subqueries));
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    private Optional<PlannerPayload> requestOpenAiPlan(
            String rawQuery,
            ChatRuntimeDtos.ChatRuntimeConfigResponse config,
            JsonNode domainContext,
            List<RagRepository.MemoryCandidate> memoryHints,
            int maxSubqueries
    ) {
        String apiKey = runtimeEnvService.getOrDefault("OPENAI_API_KEY", "").trim();
        if (apiKey.isBlank()) {
            return Optional.empty();
        }
        String model = runtimeEnvService.getOrDefault(
                "QUERY_FORGE_LLM_PLANNER_MODEL",
                runtimeEnvService.getOrDefault("QUERY_FORGE_LLM_REWRITE_MODEL",
                        runtimeEnvService.getOrDefault("QUERY_FORGE_LLM_MODEL", "gpt-4o-mini"))
        );
        String baseUrl = runtimeEnvService.getOrDefault("QUERY_FORGE_OPENAI_BASE_URL", "https://api.openai.com/v1");
        int maxTokens = parseInt(runtimeEnvService.getOrDefault("QUERY_FORGE_LLM_MAX_OUTPUT_TOKENS", "384"), 384);
        try {
            ObjectNode payload = objectMapper.createObjectNode();
            payload.put("model", model);
            payload.put("temperature", 0.1d);
            payload.put("max_tokens", maxTokens);
            payload.putObject("response_format").put("type", "json_object");
            ArrayNode messages = payload.putArray("messages");
            messages.addObject().put("role", "system").put("content", SYSTEM_PROMPT);
            messages.addObject()
                    .put("role", "user")
                    .put("content", buildPlannerPrompt(rawQuery, config, domainContext, memoryHints, maxSubqueries));

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl.replaceAll("/+$", "") + "/chat/completions"))
                    .timeout(Duration.ofSeconds(45))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload.toString()))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                return Optional.empty();
            }
            JsonNode root = objectMapper.readTree(response.body() == null ? "{}" : response.body());
            JsonNode choices = root.path("choices");
            if (!choices.isArray() || choices.isEmpty()) {
                return Optional.empty();
            }
            String content = choices.get(0).path("message").path("content").asText("");
            return parsePlannerPayload(content)
                    .map(subqueries -> new PlannerPayload("openai", model, subqueries));
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    private String buildPlannerPrompt(
            String rawQuery,
            ChatRuntimeDtos.ChatRuntimeConfigResponse config,
            JsonNode domainContext,
            List<RagRepository.MemoryCandidate> memoryHints,
            int maxSubqueries
    ) {
        try {
            ObjectNode payload = objectMapper.createObjectNode();
            payload.put("raw_query", safeTrim(rawQuery));
            payload.put("query_language", looksKorean(rawQuery) ? "ko" : "en");
            payload.put("max_subqueries", maxSubqueries);
            payload.put("technical_domain", safeTrim(config.displayName()));
            payload.put("domain_key", safeTrim(config.domainKey()));
            payload.put("source_language", safeTrim(config.sourceLanguage()));
            payload.set("domain_context", domainContext == null ? objectMapper.createObjectNode() : domainContext);
            payload.set("memory_hints", memoryHints(memoryHints));
            payload.put(
                    "domain_guardrail",
                    "Every subquery must stay inside the current domain: " + safeTrim(config.displayName())
            );
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(payload);
        } catch (Exception ignored) {
            return "{}";
        }
    }

    private ArrayNode memoryHints(List<RagRepository.MemoryCandidate> memoryHints) {
        ArrayNode rows = objectMapper.createArrayNode();
        if (memoryHints == null || memoryHints.isEmpty()) {
            return rows;
        }
        for (int index = 0; index < Math.min(MEMORY_HINT_LIMIT, memoryHints.size()); index++) {
            RagRepository.MemoryCandidate memory = memoryHints.get(index);
            ObjectNode row = rows.addObject();
            row.put("query_text", safeTrim(memory.queryText()));
            row.put("target_doc_id", safeTrim(memory.targetDocId()));
            row.set("target_chunk_ids", memory.targetChunkIds() == null ? objectMapper.createArrayNode() : memory.targetChunkIds());
            row.put("generation_strategy", safeTrim(memory.generationStrategy()));
            row.put("similarity", memory.similarity());
        }
        return rows;
    }

    private Optional<List<JsonNode>> parsePlannerPayload(String rawContent) {
        if (rawContent == null || rawContent.isBlank()) {
            return Optional.empty();
        }
        Optional<JsonNode> root = readJsonObject(unwrapJsonCodeFence(rawContent));
        if (root.isEmpty()) {
            return Optional.empty();
        }
        JsonNode subqueries = root.get().path("subqueries");
        if (!subqueries.isArray() || subqueries.isEmpty()) {
            subqueries = root.get().path("queries");
        }
        if (!subqueries.isArray() || subqueries.isEmpty()) {
            return Optional.empty();
        }
        List<JsonNode> rows = new ArrayList<>();
        subqueries.forEach(rows::add);
        return Optional.of(rows);
    }

    private List<RagDtos.AgenticSubquery> normalizeSubqueries(
            String rawQuery,
            List<JsonNode> candidates,
            int maxSubqueries
    ) {
        List<RagDtos.AgenticSubquery> rows = new ArrayList<>();
        LinkedHashSet<String> dedupe = new LinkedHashSet<>();
        int ordinal = 0;
        for (JsonNode candidate : candidates) {
            String query = candidate.isTextual()
                    ? normalizeWhitespace(candidate.asText(""))
                    : normalizeWhitespace(candidate.path("query").asText(""));
            if (query.isBlank()) {
                continue;
            }
            String key = query.toLowerCase(Locale.ROOT);
            if (!dedupe.add(key)) {
                continue;
            }
            ordinal++;
            String intent = candidate.isObject()
                    ? normalizeWhitespace(candidate.path("intent").asText("subquery_" + ordinal))
                    : "subquery_" + ordinal;
            double weight = candidate.isObject() ? candidate.path("weight").asDouble(1.0d) : 1.0d;
            ObjectNode metadata = objectMapper.createObjectNode();
            metadata.put("raw_query_overlap", query.equalsIgnoreCase(safeTrim(rawQuery)));
            rows.add(new RagDtos.AgenticSubquery(
                    ordinal,
                    query,
                    intent.isBlank() ? "subquery_" + ordinal : intent,
                    Math.max(0.0d, Math.min(1.0d, weight)),
                    metadata
            ));
            if (rows.size() >= maxSubqueries) {
                break;
            }
        }
        return rows;
    }

    private Optional<JsonNode> readJsonObject(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readTree(raw));
        } catch (Exception ignored) {
            int first = raw.indexOf('{');
            int last = raw.lastIndexOf('}');
            if (first >= 0 && last > first) {
                String trimmed = raw.substring(first, last + 1);
                try {
                    return Optional.of(objectMapper.readTree(trimmed));
                } catch (Exception ignoredAgain) {
                    return Optional.empty();
                }
            }
            return Optional.empty();
        }
    }

    private String unwrapJsonCodeFence(String raw) {
        String trimmed = safeTrim(raw);
        if (trimmed.startsWith("```")) {
            int firstBreak = trimmed.indexOf('\n');
            int lastFence = trimmed.lastIndexOf("```");
            if (firstBreak >= 0 && lastFence > firstBreak) {
                return trimmed.substring(firstBreak + 1, lastFence).trim();
            }
        }
        return trimmed;
    }

    private String extractGeminiText(JsonNode root) {
        JsonNode candidates = root.path("candidates");
        if (!candidates.isArray() || candidates.isEmpty()) {
            return "";
        }
        JsonNode parts = candidates.get(0).path("content").path("parts");
        if (!parts.isArray() || parts.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (JsonNode part : parts) {
            String text = part.path("text").asText("");
            if (!text.isBlank()) {
                if (builder.length() > 0) {
                    builder.append('\n');
                }
                builder.append(text);
            }
        }
        return builder.toString().trim();
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }

    private int parseInt(String raw, int fallback) {
        try {
            return Integer.parseInt(raw);
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private String normalizeWhitespace(String value) {
        return safeTrim(value).replaceAll("\\s+", " ");
    }

    private String safeTrim(String value) {
        return value == null ? "" : value.trim();
    }

    private boolean looksKorean(String value) {
        return value != null && value.codePoints().anyMatch(codePoint -> codePoint >= 0xAC00 && codePoint <= 0xD7A3);
    }

    private record PlannerPayload(
            String provider,
            String model,
            List<JsonNode> subqueries
    ) {
    }
}
