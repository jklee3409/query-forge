package io.queryforge.backend.rag.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.queryforge.backend.rag.repository.RagRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Component
@RequiredArgsConstructor
public class CohereRerankService {

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private final String apiKey = valueOrEmpty("QUERY_FORGE_COHERE_API_KEY", "COHERE_API_KEY");
    private final String model = valueOrDefault("QUERY_FORGE_COHERE_RERANK_MODEL", "rerank-v3.5");
    private final String baseUrl = valueOrDefault("QUERY_FORGE_COHERE_BASE_URL", "https://api.cohere.com");
    private final double timeoutSeconds = parseDouble(valueOrDefault("QUERY_FORGE_COHERE_TIMEOUT_SECONDS", "30"), 30.0d);
    private final int maxRetries = Math.max(1, Math.min(parseInt(valueOrDefault("QUERY_FORGE_COHERE_RERANK_MAX_RETRIES", "4"), 4), 10));
    private final double minIntervalSeconds = 60.0d / Math.max(1.0d, parseDouble(valueOrDefault("QUERY_FORGE_COHERE_RERANK_RPM", "60"), 60.0d));
    private long lastCallNano = 0L;

    public String modelName() {
        return available() ? "cohere-rerank-v2" : "local-rerank-fallback";
    }

    public boolean available() {
        return apiKey != null && !apiKey.isBlank();
    }

    public List<RagRepository.RetrievalDoc> rerank(String query, List<RagRepository.RetrievalDoc> docs, int topN) {
        int limitedTopN = Math.max(1, Math.min(topN, docs.size()));
        if (docs.isEmpty()) {
            return List.of();
        }
        if (!available()) {
            return lexicalFallback(query, docs, limitedTopN);
        }

        try {
            JsonNode payload = objectMapper.valueToTree(java.util.Map.of(
                    "model", model,
                    "query", query,
                    "documents", docs.stream().map(RagRepository.RetrievalDoc::chunkText).toList(),
                    "top_n", limitedTopN
            ));
            for (int attempt = 0; attempt < maxRetries; attempt++) {
                throttle();
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl.replaceAll("/+$", "") + "/v2/rerank"))
                        .timeout(Duration.ofMillis((long) (timeoutSeconds * 1000)))
                        .header("Authorization", "Bearer " + apiKey)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(payload.toString()))
                        .build();
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                int status = response.statusCode();
                if (status == 429 || status >= 500) {
                    if (attempt + 1 >= maxRetries) {
                        return lexicalFallback(query, docs, limitedTopN);
                    }
                    Thread.sleep((long) (Math.min(20.0d, 2.0d * Math.pow(2, attempt)) * 1000));
                    continue;
                }
                if (status >= 400) {
                    return lexicalFallback(query, docs, limitedTopN);
                }

                JsonNode root = objectMapper.readTree(response.body() == null ? "{}" : response.body());
                JsonNode results = root.path("results");
                if (!results.isArray() || results.isEmpty()) {
                    return lexicalFallback(query, docs, limitedTopN);
                }
                List<RagRepository.RetrievalDoc> reranked = new ArrayList<>();
                for (JsonNode row : results) {
                    int index = row.path("index").asInt(-1);
                    if (index < 0 || index >= docs.size()) {
                        continue;
                    }
                    double relevance = row.path("relevance_score").asDouble(0.0d);
                    RagRepository.RetrievalDoc doc = docs.get(index);
                    reranked.add(
                            new RagRepository.RetrievalDoc(
                                    doc.documentId(),
                                    doc.chunkId(),
                                    doc.chunkText(),
                                    Math.max(-1.0d, Math.min(1.0d, (relevance * 2.0d) - 1.0d))
                            )
                    );
                    if (reranked.size() >= limitedTopN) {
                        break;
                    }
                }
                return reranked.isEmpty() ? lexicalFallback(query, docs, limitedTopN) : reranked;
            }
        } catch (Exception ignored) {
            return lexicalFallback(query, docs, limitedTopN);
        }
        return lexicalFallback(query, docs, limitedTopN);
    }

    private synchronized void throttle() throws InterruptedException {
        long now = System.nanoTime();
        long minIntervalNanos = (long) (minIntervalSeconds * 1_000_000_000L);
        long elapsed = now - lastCallNano;
        long wait = minIntervalNanos - elapsed;
        if (wait > 0) {
            long millis = wait / 1_000_000L;
            int nanos = (int) (wait % 1_000_000L);
            Thread.sleep(millis, nanos);
        }
        lastCallNano = System.nanoTime();
    }

    private List<RagRepository.RetrievalDoc> lexicalFallback(
            String query,
            List<RagRepository.RetrievalDoc> docs,
            int topN
    ) {
        List<RagRepository.RetrievalDoc> sorted = new ArrayList<>(docs);
        sorted.sort((left, right) -> {
            double leftScore = (0.7d * left.score()) + (0.3d * lexicalBoost(query, left.chunkText()));
            double rightScore = (0.7d * right.score()) + (0.3d * lexicalBoost(query, right.chunkText()));
            return Double.compare(rightScore, leftScore);
        });
        return sorted.subList(0, Math.min(topN, sorted.size()));
    }

    private double lexicalBoost(String query, String text) {
        if (query == null || query.isBlank() || text == null || text.isBlank()) {
            return 0.0d;
        }
        String[] queryTokens = query.toLowerCase(Locale.ROOT).split("\\s+");
        String lowered = text.toLowerCase(Locale.ROOT);
        int hits = 0;
        for (String token : queryTokens) {
            if (!token.isBlank() && lowered.contains(token)) {
                hits++;
            }
        }
        return (double) hits / Math.max(1, queryTokens.length);
    }

    private static String valueOrEmpty(String... keys) {
        for (String key : keys) {
            String value = System.getenv(key);
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }

    private static String valueOrDefault(String key, String fallback) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value.trim();
    }

    private static int parseInt(String raw, int fallback) {
        try {
            return Integer.parseInt(raw);
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static double parseDouble(String raw, double fallback) {
        try {
            return Double.parseDouble(raw);
        } catch (Exception ignored) {
            return fallback;
        }
    }
}
