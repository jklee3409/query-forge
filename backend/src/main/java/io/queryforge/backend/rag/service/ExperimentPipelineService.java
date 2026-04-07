package io.queryforge.backend.rag.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.queryforge.backend.admin.pipeline.config.AdminPipelineProperties;
import io.queryforge.backend.rag.model.RagDtos;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;

@Service
@RequiredArgsConstructor
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
        try {
            Process process = processBuilder.start();
            String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
            int exitCode = process.waitFor();
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
        } catch (IOException exception) {
            throw new IllegalStateException("failed to run experiment command", exception);
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
        int jsonStart = trimmed.lastIndexOf('{');
        if (jsonStart >= 0) {
            String candidate = trimmed.substring(jsonStart);
            try {
                return objectMapper.readTree(candidate);
            } catch (Exception ignored) {
            }
        }
        ObjectNode fallback = objectMapper.createObjectNode();
        fallback.put("raw_stdout", trim(stdout));
        return fallback;
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
