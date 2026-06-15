package io.queryforge.backend.rag.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.queryforge.backend.admin.pipeline.config.AdminPipelineProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@Component
@RequiredArgsConstructor
public class DenseEmbeddingService {

    private static final int EXPECTED_DIMENSION = 384;
    private static final int MAX_CACHE_SIZE = 128;
    private static final String PYTHON_SCRIPT = """
            import base64
            import json
            import sys

            from pipeline.common.local_retriever import build_retriever_config, embed_query_with_retriever_config

            query = base64.b64decode(sys.argv[1]).decode("utf-8")
            retriever_mode = sys.argv[2]
            dense_model = sys.argv[3]
            config = build_retriever_config({
                "retriever_mode": retriever_mode,
                "dense_embedding_model": dense_model,
                "dense_embedding_required": True,
                "dense_fallback_enabled": False,
                "dense_embedding_device": "cpu",
                "dense_embedding_batch_size": 32,
            })
            embedding, model_name, fallback_used = embed_query_with_retriever_config(
                query,
                retriever_config=config,
                require_real_dense=True,
            )
            print(json.dumps({
                "embedding": embedding,
                "model": model_name,
                "fallback": fallback_used,
            }))
            """;

    private final AdminPipelineProperties properties;
    private final ObjectMapper objectMapper;
    private final Map<String, List<Double>> queryCache = new ConcurrentHashMap<>();

    public List<Double> embedQuery(String query, String retrieverMode, String denseEmbeddingModel) {
        String cacheKey = denseEmbeddingModel + "|" + retrieverMode + "|" + query;
        List<Double> cached = queryCache.get(cacheKey);
        if (cached != null) {
            return cached;
        }
        List<Double> embedding = runPythonEmbedding(query, retrieverMode, denseEmbeddingModel);
        if (queryCache.size() >= MAX_CACHE_SIZE) {
            queryCache.clear();
        }
        queryCache.put(cacheKey, embedding);
        return embedding;
    }

    private List<Double> runPythonEmbedding(String query, String retrieverMode, String denseEmbeddingModel) {
        Path repoRoot = resolveRepoRoot();
        String encodedQuery = Base64.getEncoder().encodeToString(query.getBytes(StandardCharsets.UTF_8));
        ProcessBuilder processBuilder = new ProcessBuilder(
                properties.pythonCommand(),
                "-c",
                PYTHON_SCRIPT,
                encodedQuery,
                retrieverMode,
                denseEmbeddingModel
        ).directory(repoRoot.toFile()).redirectErrorStream(true);
        applyDotEnvForProcess(processBuilder, repoRoot);
        try {
            Process process = processBuilder.start();
            CompletableFuture<String> outputFuture = CompletableFuture.supplyAsync(() -> readOutput(process));
            boolean finished = process.waitFor(Duration.ofSeconds(300).toSeconds(), TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new IllegalStateException("dense query embedding timed out for model: " + denseEmbeddingModel);
            }
            String output = outputFuture.join();
            if (process.exitValue() != 0) {
                throw new IllegalStateException("dense query embedding failed: " + trim(output));
            }
            return parseEmbedding(output, denseEmbeddingModel);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("dense query embedding interrupted", exception);
        } catch (CompletionException exception) {
            throw new IllegalStateException("failed to read dense query embedding output", exception);
        } catch (IOException exception) {
            throw new IllegalStateException("failed to run dense query embedding", exception);
        }
    }

    private String readOutput(Process process) {
        try {
            return new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("failed to read dense query embedding output", exception);
        }
    }

    private List<Double> parseEmbedding(String stdout, String expectedModel) throws IOException {
        String payload = extractJson(stdout);
        JsonNode root = objectMapper.readTree(payload);
        if (root.path("fallback").asBoolean(false)) {
            throw new IllegalStateException("dense query embedding fell back to hash embeddings");
        }
        String actualModel = root.path("model").asText("");
        if (!expectedModel.equals(actualModel)) {
            throw new IllegalStateException("query embedding model mismatch: expected=" + expectedModel + ", actual=" + actualModel);
        }
        JsonNode embeddingNode = root.path("embedding");
        if (!embeddingNode.isArray() || embeddingNode.size() != EXPECTED_DIMENSION) {
            throw new IllegalStateException("dense query embedding must contain " + EXPECTED_DIMENSION + " values");
        }
        List<Double> values = new ArrayList<>(EXPECTED_DIMENSION);
        for (JsonNode value : embeddingNode) {
            values.add(value.asDouble());
        }
        return values;
    }

    private String extractJson(String stdout) {
        if (stdout == null) {
            return "{}";
        }
        String trimmed = stdout.trim();
        int start = trimmed.indexOf('{');
        int end = trimmed.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return trimmed.substring(start, end + 1);
        }
        return trimmed;
    }

    private void applyDotEnvForProcess(ProcessBuilder processBuilder, Path repoRoot) {
        Path envPath = repoRoot.resolve(".env").normalize();
        if (!Files.exists(envPath)) {
            return;
        }
        Map<String, String> env = processBuilder.environment();
        try {
            for (String rawLine : Files.readAllLines(envPath, StandardCharsets.UTF_8)) {
                String line = rawLine == null ? "" : rawLine.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                int separator = line.indexOf('=');
                if (separator <= 0) {
                    continue;
                }
                String key = line.substring(0, separator).trim();
                if (key.isEmpty() || env.containsKey(key)) {
                    continue;
                }
                String value = line.substring(separator + 1).trim();
                if ((value.startsWith("\"") && value.endsWith("\""))
                        || (value.startsWith("'") && value.endsWith("'"))) {
                    value = value.substring(1, value.length() - 1);
                }
                env.put(key, value);
            }
        } catch (IOException exception) {
            throw new IllegalStateException("failed to load .env for dense embedding process", exception);
        }
    }

    private Path resolveRepoRoot() {
        Path configured = Path.of(properties.repoRoot()).toAbsolutePath().normalize();
        if (Files.exists(configured.resolve("pipeline/cli.py"))) {
            return configured;
        }
        Path parent = configured.getParent();
        if (parent != null && Files.exists(parent.resolve("pipeline/cli.py"))) {
            return parent;
        }
        throw new IllegalStateException("Failed to resolve repository root containing pipeline/cli.py");
    }

    private String trim(String text) {
        if (text == null) {
            return "";
        }
        String normalized = text.trim();
        return normalized.length() <= 1000 ? normalized : normalized.substring(normalized.length() - 1000);
    }
}
