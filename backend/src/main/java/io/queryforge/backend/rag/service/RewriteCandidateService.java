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
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

@Component
@RequiredArgsConstructor
public class RewriteCandidateService {

    private static final List<String> PROMPT_FILENAMES = List.of(
            "selective_rewrite_v2.md",
            "selective_rewrite_v1.md"
    );

    private static final int MEMORY_CANDIDATE_LIMIT = 5;

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public record CandidateTemplate(String label, String query) {
    }

    private record PromptAsset(String id, String text) {
    }

    public List<CandidateTemplate> buildCandidates(
            String rawQuery,
            JsonNode sessionContext,
            List<RagRepository.MemoryCandidate> memories,
            int candidateCount
    ) {
        int limitedCount = Math.max(1, Math.min(candidateCount, 3));
        List<CandidateTemplate> fallback = heuristicCandidates(rawQuery, sessionContext, memories, limitedCount);
        Optional<PromptAsset> promptAsset = resolvePromptAsset();
        if (promptAsset.isEmpty()) {
            return fallback;
        }
        Optional<List<CandidateTemplate>> llmCandidates = requestLlmCandidates(
                promptAsset.get(),
                rawQuery,
                sessionContext,
                memories,
                limitedCount
        );
        return llmCandidates.orElse(fallback);
    }

    private Optional<PromptAsset> resolvePromptAsset() {
        List<Path> roots = new ArrayList<>();
        String promptRootEnv = envOrDefault("PROMPT_ROOT", "").trim();
        if (!promptRootEnv.isBlank()) {
            roots.add(Path.of(promptRootEnv));
        }
        roots.add(Path.of("../configs/prompts"));
        roots.add(Path.of("configs/prompts"));
        roots.add(Path.of("../../configs/prompts"));

        for (Path root : roots) {
            for (String filename : PROMPT_FILENAMES) {
                Path path = root.resolve("rewrite").resolve(filename).toAbsolutePath().normalize();
                if (!Files.exists(path)) {
                    continue;
                }
                try {
                    String text = Files.readString(path, StandardCharsets.UTF_8);
                    return Optional.of(new PromptAsset(filename, text));
                } catch (Exception ignored) {
                    // fall through to next candidate path
                }
            }
        }
        return Optional.empty();
    }

    private Optional<List<CandidateTemplate>> requestLlmCandidates(
            PromptAsset promptAsset,
            String rawQuery,
            JsonNode sessionContext,
            List<RagRepository.MemoryCandidate> memories,
            int candidateCount
    ) {
        String provider = envOrDefault("QUERY_FORGE_LLM_PROVIDER", "gemini").toLowerCase(Locale.ROOT);
        if (provider.startsWith("openai")) {
            return requestOpenAiCandidates(promptAsset, rawQuery, sessionContext, memories, candidateCount);
        }
        return requestGeminiCandidates(promptAsset, rawQuery, sessionContext, memories, candidateCount);
    }

    private Optional<List<CandidateTemplate>> requestGeminiCandidates(
            PromptAsset promptAsset,
            String rawQuery,
            JsonNode sessionContext,
            List<RagRepository.MemoryCandidate> memories,
            int candidateCount
    ) {
        String apiKey = firstNonBlank(
                envOrDefault("QUERY_FORGE_GEMINI_API_KEY", ""),
                envOrDefault("GEMINI_API_KEY", ""),
                envOrDefault("GOOGLE_API_KEY", "")
        );
        if (apiKey.isBlank()) {
            return Optional.empty();
        }
        String model = envOrDefault(
                "QUERY_FORGE_LLM_REWRITE_MODEL",
                envOrDefault("QUERY_FORGE_LLM_MODEL", "gemini-2.5-flash-lite")
        );
        String baseUrl = envOrDefault("QUERY_FORGE_GEMINI_BASE_URL", "https://generativelanguage.googleapis.com");
        int maxOutputTokens = parseInt(envOrDefault("QUERY_FORGE_LLM_MAX_OUTPUT_TOKENS", "384"), 384);
        try {
            ObjectNode payload = objectMapper.createObjectNode();
            ObjectNode systemInstruction = payload.putObject("systemInstruction");
            ArrayNode systemParts = systemInstruction.putArray("parts");
            systemParts.addObject().put("text", promptAsset.text());

            ArrayNode contents = payload.putArray("contents");
            ObjectNode userNode = contents.addObject();
            userNode.put("role", "user");
            ArrayNode userParts = userNode.putArray("parts");
            userParts.addObject().put("text", buildRewriteUserPrompt(rawQuery, sessionContext, memories, candidateCount));

            ObjectNode generationConfig = payload.putObject("generationConfig");
            generationConfig.put("responseMimeType", "application/json");
            generationConfig.put("temperature", 0.2d);
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
            String content = extractGeminiText(root);
            return parseCandidatePayload(content, candidateCount);
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    private Optional<List<CandidateTemplate>> requestOpenAiCandidates(
            PromptAsset promptAsset,
            String rawQuery,
            JsonNode sessionContext,
            List<RagRepository.MemoryCandidate> memories,
            int candidateCount
    ) {
        String apiKey = envOrDefault("OPENAI_API_KEY", "").trim();
        if (apiKey.isBlank()) {
            return Optional.empty();
        }
        String model = envOrDefault(
                "QUERY_FORGE_LLM_REWRITE_MODEL",
                envOrDefault("QUERY_FORGE_LLM_MODEL", "gpt-4o-mini")
        );
        String baseUrl = envOrDefault("QUERY_FORGE_OPENAI_BASE_URL", "https://api.openai.com/v1");
        int maxTokens = parseInt(envOrDefault("QUERY_FORGE_LLM_MAX_OUTPUT_TOKENS", "384"), 384);
        try {
            ObjectNode payload = objectMapper.createObjectNode();
            payload.put("model", model);
            payload.put("temperature", 0.2d);
            payload.put("max_tokens", maxTokens);
            payload.putObject("response_format").put("type", "json_object");
            ArrayNode messages = payload.putArray("messages");
            messages.addObject()
                    .put("role", "system")
                    .put("content", promptAsset.text());
            messages.addObject()
                    .put("role", "user")
                    .put("content", buildRewriteUserPrompt(rawQuery, sessionContext, memories, candidateCount));

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
            return parseCandidatePayload(content, candidateCount);
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    private String buildRewriteUserPrompt(
            String rawQuery,
            JsonNode sessionContext,
            List<RagRepository.MemoryCandidate> memories,
            int candidateCount
    ) {
        try {
            ObjectNode payload = objectMapper.createObjectNode();
            payload.put("raw_query", safeTrim(rawQuery));
            payload.set("session_context", safeSessionContext(sessionContext));
            payload.set("top_memory_candidates", topMemoryCandidates(memories));
            payload.put("candidate_count", candidateCount);
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(payload);
        } catch (Exception ignored) {
            return "{}";
        }
    }

    private JsonNode safeSessionContext(JsonNode value) {
        if (value == null || value.isNull()) {
            return objectMapper.createObjectNode();
        }
        return value;
    }

    private ArrayNode topMemoryCandidates(List<RagRepository.MemoryCandidate> memories) {
        ArrayNode rows = objectMapper.createArrayNode();
        for (int index = 0; index < Math.min(MEMORY_CANDIDATE_LIMIT, memories.size()); index++) {
            RagRepository.MemoryCandidate memory = memories.get(index);
            ObjectNode row = rows.addObject();
            if (memory.memoryId() != null) {
                row.put("memory_id", memory.memoryId().toString());
            } else {
                row.put("memory_id", "");
            }
            row.put("query_text", safeTrim(memory.queryText()));
            row.put("target_doc_id", safeTrim(memory.targetDocId()));
            row.set("target_chunk_ids", memory.targetChunkIds() == null ? objectMapper.createArrayNode() : memory.targetChunkIds());
            row.put("generation_strategy", safeTrim(memory.generationStrategy()));
            row.put("similarity", memory.similarity());
        }
        return rows;
    }

    private Optional<List<CandidateTemplate>> parseCandidatePayload(String rawContent, int candidateCount) {
        if (rawContent == null || rawContent.isBlank()) {
            return Optional.empty();
        }
        String normalizedContent = unwrapJsonCodeFence(rawContent);
        JsonNode root = readJsonObject(normalizedContent).orElse(null);
        if (root == null) {
            return Optional.empty();
        }
        JsonNode candidates = root.path("candidates");
        if (!candidates.isArray() || candidates.isEmpty()) {
            return Optional.empty();
        }
        List<CandidateTemplate> normalized = normalizeCandidates(candidates, candidateCount);
        return normalized.isEmpty() ? Optional.empty() : Optional.of(normalized);
    }

    private List<CandidateTemplate> normalizeCandidates(JsonNode candidates, int candidateCount) {
        List<CandidateTemplate> rows = new ArrayList<>();
        LinkedHashSet<String> dedupe = new LinkedHashSet<>();
        int ordinal = 0;
        for (JsonNode row : candidates) {
            if (!row.isObject()) {
                continue;
            }
            String query = normalizeWhitespace(row.path("query").asText(""));
            if (query.isBlank()) {
                continue;
            }
            String dedupeKey = query.toLowerCase(Locale.ROOT);
            if (!dedupe.add(dedupeKey)) {
                continue;
            }
            ordinal += 1;
            String label = canonicalLabel(row.path("label").asText(""), ordinal);
            rows.add(new CandidateTemplate(label, query));
            if (rows.size() >= candidateCount) {
                break;
            }
        }
        return rows;
    }

    private String canonicalLabel(String label, int ordinal) {
        String normalized = safeTrim(label).toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "explicit_standalone" -> "explicit_standalone";
            case "product_version_anchored", "memory_anchored" -> "product_version_anchored";
            case "error_or_task_focused", "task_or_error_focused" -> "error_or_task_focused";
            default -> "candidate_" + ordinal;
        };
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

    private List<CandidateTemplate> heuristicCandidates(
            String rawQuery,
            JsonNode sessionContext,
            List<RagRepository.MemoryCandidate> memories,
            int candidateCount
    ) {
        String normalizedRaw = normalizeWhitespace(rawQuery);
        String memoryAnchor = memories.isEmpty() ? normalizedRaw : normalizeWhitespace(memories.getFirst().queryText());
        if (memoryAnchor.length() > 140) {
            memoryAnchor = memoryAnchor.substring(0, 140).trim();
        }
        String prevQuestion = sessionContext == null ? "" : safeTrim(sessionContext.path("previous_user_question").asText(""));
        String prevSummary = sessionContext == null ? "" : safeTrim(sessionContext.path("previous_assistant_summary").asText(""));
        String contextPrefix = normalizeWhitespace((prevQuestion + " " + prevSummary).trim());

        List<CandidateTemplate> base = List.of(
                new CandidateTemplate(
                        "explicit_standalone",
                        normalizeWhitespace((contextPrefix.isBlank() ? "" : contextPrefix + " ") + normalizedRaw)
                ),
                new CandidateTemplate(
                        "product_version_anchored",
                        normalizeWhitespace(normalizedRaw + " " + memoryAnchor)
                ),
                new CandidateTemplate(
                        "error_or_task_focused",
                        normalizeWhitespace(normalizedRaw + " 오류 원인 해결 절차")
                )
        );
        List<CandidateTemplate> deduped = new ArrayList<>();
        LinkedHashSet<String> keys = new LinkedHashSet<>();
        for (CandidateTemplate candidate : base) {
            String key = candidate.query().toLowerCase(Locale.ROOT);
            if (!keys.add(key)) {
                continue;
            }
            deduped.add(candidate);
            if (deduped.size() >= candidateCount) {
                break;
            }
        }
        if (deduped.isEmpty()) {
            deduped.add(new CandidateTemplate("explicit_standalone", normalizedRaw));
        }
        return deduped;
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

    private String envOrDefault(String key, String fallback) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value.trim();
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
