package io.queryforge.backend.rag.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.queryforge.backend.admin.pipeline.config.AdminPipelineProperties;
import io.queryforge.backend.rag.model.RagDtos;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.stream.Stream;

@Service
@RequiredArgsConstructor
@Slf4j
public class ExperimentPipelineService {

    private static final Set<String> ALLOWED_COMMANDS = Set.of(
            "generate-queries",
            "gate-queries",
            "build-memory",
            "build-eval-dataset",
            "eval-retrieval",
            "eval-answer"
    );

    private final AdminPipelineProperties properties;
    private final ObjectMapper objectMapper;

    public RagDtos.ExperimentCommandResponse run(RagDtos.ExperimentCommandRequest request) {
        String command = normalizedCommand(request.command());
        if (!ALLOWED_COMMANDS.contains(command)) {
            throw new IllegalArgumentException("unsupported command: " + request.command());
        }
        String experiment = request.experiment() == null || request.experiment().isBlank()
                ? "exp4"
                : request.experiment().trim();

        Path repoRoot = resolveRepoRoot();
        List<String> commandLine = List.of(
                properties.pythonCommand(),
                repoRoot.resolve("pipeline/cli.py").normalize().toString(),
                command,
                "--experiment",
                experiment
        );

        ProcessBuilder processBuilder = new ProcessBuilder(commandLine)
                .directory(repoRoot.toFile());
        applyDotEnvForProcess(processBuilder, repoRoot);
        try {
            Process process = processBuilder.start();
            CompletableFuture<String> stdoutFuture = CompletableFuture.supplyAsync(
                    () -> readStream(process.getInputStream())
            );
            CompletableFuture<String> stderrFuture = CompletableFuture.supplyAsync(
                    () -> readStream(process.getErrorStream())
            );
            int exitCode = process.waitFor();
            String stdout = stdoutFuture.join();
            String stderr = stderrFuture.join();
            JsonNode summary = parseSummary(stdout);
            return new RagDtos.ExperimentCommandResponse(
                    command,
                    experiment,
                    exitCode,
                    summary,
                    trim(stdout),
                    trim(stderr)
            );
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("failed to run experiment command", exception);
        } catch (CompletionException exception) {
            throw new IllegalStateException("failed to read experiment command output", exception);
        } catch (IOException exception) {
            throw new IllegalStateException("failed to run experiment command", exception);
        }
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
                if (key.isEmpty()) {
                    continue;
                }
                String existing = env.get(key);
                if (existing != null && !existing.isBlank()) {
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
            log.warn("failed to load .env for experiment subprocess: {}", envPath, exception);
        }
    }

    public JsonNode latestReport(String type) {
        String normalizedType = type == null ? "retrieval" : type.toLowerCase(Locale.ROOT);
        String prefix = normalizedType.startsWith("answer") ? "answer_summary_" : "retrieval_summary_";
        Path reportsRoot = resolveRepoRoot().resolve("data/reports");
        try {
            if (!Files.exists(reportsRoot)) {
                return objectMapper.createObjectNode();
            }
            Path latest;
            try (Stream<Path> paths = Files.list(reportsRoot)) {
                latest = paths
                        .filter(path -> path.getFileName().toString().startsWith(prefix))
                        .filter(path -> path.getFileName().toString().endsWith(".json"))
                        .max(Comparator.comparingLong(path -> path.toFile().lastModified()))
                        .orElse(null);
            }
            if (latest == null) {
                return objectMapper.createObjectNode();
            }
            return objectMapper.readTree(Files.readString(latest, StandardCharsets.UTF_8));
        } catch (IOException exception) {
            throw new IllegalStateException("failed to read report file", exception);
        }
    }

    private String normalizedCommand(String command) {
        if (command == null) {
            return "";
        }
        return command.trim().toLowerCase(Locale.ROOT);
    }

    private JsonNode parseSummary(String stdout) {
        if (stdout == null || stdout.isBlank()) {
            return objectMapper.createObjectNode();
        }
        String trimmed = stdout.trim();
        try {
            return objectMapper.readTree(trimmed);
        } catch (Exception ignored) {
        }
        int jsonStart = trimmed.indexOf('{');
        int jsonEnd = trimmed.lastIndexOf('}');
        if (jsonStart >= 0 && jsonEnd > jsonStart) {
            String candidate = trimmed.substring(jsonStart, jsonEnd + 1);
            try {
                return objectMapper.readTree(candidate);
            } catch (Exception ignored) {
            }
        }
        ObjectNode fallback = objectMapper.createObjectNode();
        fallback.put("raw_stdout", trim(stdout));
        return fallback;
    }

    private String readStream(InputStream inputStream) {
        try (InputStream stream = inputStream) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("failed to read process output stream", exception);
        }
    }

    private String trim(String text) {
        if (text == null) {
            return "";
        }
        String normalized = text.trim();
        if (normalized.length() <= 4000) {
            return normalized;
        }
        return normalized.substring(normalized.length() - 4000);
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
}
