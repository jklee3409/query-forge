package io.queryforge.backend.rag.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class ChatAnswerService {

    private static final String SYSTEM_PROMPT = """
            You are the answer-generation stage of a domain-scoped technical-document RAG system.
            Use only the provided context chunks.
            Respond in the user's query language.
            If the context is insufficient, say that clearly and do not invent APIs, behavior, versions, or configuration.
            Return strict JSON with this shape:
            {
              "answer": "final user-facing answer",
              "used_document_ids": ["document id"],
              "used_chunk_ids": ["chunk id"]
            }
            The cited ids must come only from the provided context.
            """;

    private static final int MAX_CONTEXT_CHUNKS = 6;
    private static final int MAX_CHUNK_TEXT_LENGTH = 1400;

    private final ObjectMapper objectMapper;
    private final RuntimeEnvService runtimeEnvService;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public record GeneratedAnswer(
            String answerText,
            List<String> citedDocumentIds,
            List<String> citedChunkIds,
            String modelName
    ) {
    }

    public GeneratedAnswer generateAnswer(
            String rawQuery,
            String finalQuery,
            String domainDisplayName,
            List<RagRepository.RetrievalDoc> retrievedDocs
    ) {
        String provider = runtimeEnvService.getOrDefault("QUERY_FORGE_LLM_PROVIDER", "gemini").toLowerCase(Locale.ROOT);
        if (provider.startsWith("openai")) {
            return requestOpenAiAnswer(rawQuery, finalQuery, domainDisplayName, retrievedDocs);
        }
        return requestGeminiAnswer(rawQuery, finalQuery, domainDisplayName, retrievedDocs);
    }

    private GeneratedAnswer requestGeminiAnswer(
            String rawQuery,
            String finalQuery,
            String domainDisplayName,
            List<RagRepository.RetrievalDoc> retrievedDocs
    ) {
        String apiKey = firstNonBlank(
                runtimeEnvService.get("QUERY_FORGE_GEMINI_API_KEY"),
                runtimeEnvService.get("GEMINI_API_KEY"),
                runtimeEnvService.get("GOOGLE_API_KEY")
        );
        if (apiKey.isBlank()) {
            throw new IllegalStateException("chat answer generation is not configured: missing Gemini API key");
        }
        String model = runtimeEnvService.getOrDefault("QUERY_FORGE_LLM_MODEL", "gemini-2.5-flash-lite");
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
                    .put("text", buildUserPrompt(rawQuery, finalQuery, domainDisplayName, retrievedDocs));

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
                    .timeout(Duration.ofSeconds(60))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload.toString()))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                throw new IllegalStateException("chat answer generation failed with status: " + response.statusCode());
            }
            JsonNode root = objectMapper.readTree(response.body() == null ? "{}" : response.body());
            return parseAnswerPayload(extractGeminiText(root), model, retrievedDocs);
        } catch (IllegalStateException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("chat answer generation failed", exception);
        }
    }

    private GeneratedAnswer requestOpenAiAnswer(
            String rawQuery,
            String finalQuery,
            String domainDisplayName,
            List<RagRepository.RetrievalDoc> retrievedDocs
    ) {
        String apiKey = runtimeEnvService.get("OPENAI_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("chat answer generation is not configured: missing OpenAI API key");
        }
        String model = runtimeEnvService.getOrDefault("QUERY_FORGE_LLM_MODEL", "gpt-4o-mini");
        String baseUrl = runtimeEnvService.getOrDefault("QUERY_FORGE_OPENAI_BASE_URL", "https://api.openai.com/v1");
        int maxTokens = parseInt(runtimeEnvService.getOrDefault("QUERY_FORGE_LLM_MAX_OUTPUT_TOKENS", "384"), 384);
        try {
            ObjectNode payload = objectMapper.createObjectNode();
            payload.put("model", model);
            payload.put("temperature", 0.1d);
            payload.put("max_tokens", maxTokens);
            payload.putObject("response_format").put("type", "json_object");
            ArrayNode messages = payload.putArray("messages");
            messages.addObject()
                    .put("role", "system")
                    .put("content", SYSTEM_PROMPT);
            messages.addObject()
                    .put("role", "user")
                    .put("content", buildUserPrompt(rawQuery, finalQuery, domainDisplayName, retrievedDocs));

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl.replaceAll("/+$", "") + "/chat/completions"))
                    .timeout(Duration.ofSeconds(60))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload.toString()))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                throw new IllegalStateException("chat answer generation failed with status: " + response.statusCode());
            }
            JsonNode root = objectMapper.readTree(response.body() == null ? "{}" : response.body());
            JsonNode choices = root.path("choices");
            if (!choices.isArray() || choices.isEmpty()) {
                throw new IllegalStateException("chat answer generation returned no choices");
            }
            String content = choices.get(0).path("message").path("content").asText("");
            return parseAnswerPayload(content, model, retrievedDocs);
        } catch (IllegalStateException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("chat answer generation failed", exception);
        }
    }

    private String buildUserPrompt(
            String rawQuery,
            String finalQuery,
            String domainDisplayName,
            List<RagRepository.RetrievalDoc> retrievedDocs
    ) {
        try {
            ObjectNode payload = objectMapper.createObjectNode();
            payload.put("raw_query", safeTrim(rawQuery));
            payload.put("final_query", safeTrim(finalQuery));
            payload.put("query_language", looksKorean(rawQuery) ? "ko" : "en");
            payload.put("technical_domain", safeTrim(domainDisplayName));
            payload.put("context_chunk_count", Math.min(MAX_CONTEXT_CHUNKS, retrievedDocs.size()));
            payload.set("context_chunks", contextChunks(retrievedDocs));
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(payload);
        } catch (Exception exception) {
            throw new IllegalStateException("failed to build chat answer prompt", exception);
        }
    }

    private ArrayNode contextChunks(List<RagRepository.RetrievalDoc> retrievedDocs) {
        ArrayNode rows = objectMapper.createArrayNode();
        for (int index = 0; index < Math.min(MAX_CONTEXT_CHUNKS, retrievedDocs.size()); index++) {
            RagRepository.RetrievalDoc row = retrievedDocs.get(index);
            ObjectNode item = rows.addObject();
            item.put("rank", index + 1);
            item.put("document_id", safeTrim(row.documentId()));
            item.put("chunk_id", safeTrim(row.chunkId()));
            item.put("score", row.score());
            item.put("chunk_text", compactChunkText(row.chunkText()));
        }
        return rows;
    }

    private GeneratedAnswer parseAnswerPayload(String rawContent, String modelName, List<RagRepository.RetrievalDoc> retrievedDocs) {
        if (rawContent == null || rawContent.isBlank()) {
            throw new IllegalStateException("chat answer generation returned empty content");
        }
        JsonNode root = readJsonObject(unwrapJsonCodeFence(rawContent));
        String answer = normalizeWhitespace(firstNonBlank(
                root.path("answer").asText(""),
                root.path("final_answer").asText(""),
                root.path("answer_text").asText("")
        ));
        if (answer.isBlank()) {
            throw new IllegalStateException("chat answer generation returned empty answer");
        }
        Map<String, String> chunkToDoc = new LinkedHashMap<>();
        LinkedHashSet<String> allowedDocIds = new LinkedHashSet<>();
        LinkedHashSet<String> allowedChunkIds = new LinkedHashSet<>();
        List<String> fallbackDocIds = new ArrayList<>();
        List<String> fallbackChunkIds = new ArrayList<>();
        for (RagRepository.RetrievalDoc row : retrievedDocs) {
            String chunkId = safeTrim(row.chunkId());
            String documentId = safeTrim(row.documentId());
            if (!chunkId.isBlank()) {
                allowedChunkIds.add(chunkId);
                fallbackChunkIds.add(chunkId);
                chunkToDoc.put(chunkId, documentId);
            }
            if (!documentId.isBlank()) {
                allowedDocIds.add(documentId);
                fallbackDocIds.add(documentId);
            }
        }

        List<String> citedChunkIds = filterAllowedIds(root.path("used_chunk_ids"), allowedChunkIds, fallbackChunkIds);
        List<String> citedDocumentIds = filterAllowedIds(root.path("used_document_ids"), allowedDocIds, fallbackDocIds);
        if (citedDocumentIds.isEmpty() && !citedChunkIds.isEmpty()) {
            LinkedHashSet<String> derived = new LinkedHashSet<>();
            for (String chunkId : citedChunkIds) {
                String documentId = chunkToDoc.get(chunkId);
                if (documentId != null && !documentId.isBlank()) {
                    derived.add(documentId);
                }
            }
            citedDocumentIds = List.copyOf(derived);
        }
        return new GeneratedAnswer(answer, citedDocumentIds, citedChunkIds, modelName);
    }

    private List<String> filterAllowedIds(JsonNode value, LinkedHashSet<String> allowedIds, List<String> fallbackIds) {
        LinkedHashSet<String> rows = new LinkedHashSet<>();
        if (value.isArray()) {
            for (JsonNode item : value) {
                String normalized = safeTrim(item.asText(""));
                if (!normalized.isBlank() && allowedIds.contains(normalized)) {
                    rows.add(normalized);
                }
            }
        }
        if (!rows.isEmpty()) {
            return List.copyOf(rows);
        }
        for (String fallbackId : fallbackIds) {
            String normalized = safeTrim(fallbackId);
            if (!normalized.isBlank() && allowedIds.contains(normalized)) {
                rows.add(normalized);
            }
            if (rows.size() >= 2) {
                break;
            }
        }
        return List.copyOf(rows);
    }

    private JsonNode readJsonObject(String raw) {
        try {
            return objectMapper.readTree(raw);
        } catch (Exception ignored) {
            int first = raw.indexOf('{');
            int last = raw.lastIndexOf('}');
            if (first >= 0 && last > first) {
                String trimmed = raw.substring(first, last + 1);
                try {
                    return objectMapper.readTree(trimmed);
                } catch (Exception ignoredAgain) {
                    throw new IllegalStateException("chat answer generation returned invalid json");
                }
            }
            throw new IllegalStateException("chat answer generation returned invalid json");
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

    private String compactChunkText(String value) {
        String normalized = normalizeWhitespace(value);
        if (normalized.length() <= MAX_CHUNK_TEXT_LENGTH) {
            return normalized;
        }
        return normalized.substring(0, MAX_CHUNK_TEXT_LENGTH).trim() + "...";
    }

    private String safeTrim(String value) {
        return value == null ? "" : value.trim();
    }

    private String normalizeWhitespace(String value) {
        if (value == null) {
            return "";
        }
        return value.replaceAll("\\s+", " ").trim();
    }

    private boolean looksKorean(String value) {
        return value != null && value.matches(".*\\p{IsHangul}.*");
    }

    private int parseInt(String raw, int fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            int parsed = Integer.parseInt(raw.trim());
            return parsed > 0 ? parsed : fallback;
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private String firstNonBlank(String... values) {
        if (values == null || values.length == 0) {
            return "";
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }
}
