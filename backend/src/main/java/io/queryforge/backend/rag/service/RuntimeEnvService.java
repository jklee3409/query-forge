package io.queryforge.backend.rag.service;

import io.queryforge.backend.admin.pipeline.config.AdminPipelineProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class RuntimeEnvService {

    private final AdminPipelineProperties properties;

    public String get(String key) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) {
            value = readFromDotEnv(key);
        }
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    public String getOrDefault(String key, String fallback) {
        String value = get(key);
        return value == null || value.isBlank() ? fallback : value;
    }

    private String readFromDotEnv(String key) {
        Path envPath = resolveRepoRoot().resolve(".env").normalize();
        if (!Files.exists(envPath)) {
            return null;
        }
        try {
            Map<String, String> values = parseDotEnv(envPath);
            String value = values.get(key);
            return value == null || value.isBlank() ? null : value;
        } catch (IOException ignored) {
            return null;
        }
    }

    private Map<String, String> parseDotEnv(Path envPath) throws IOException {
        Map<String, String> values = new LinkedHashMap<>();
        for (String entry : Files.readAllLines(envPath, StandardCharsets.UTF_8)) {
            String line = entry == null ? "" : entry.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            int delimiter = line.indexOf('=');
            if (delimiter <= 0) {
                continue;
            }
            String name = line.substring(0, delimiter).trim();
            if (name.isEmpty() || values.containsKey(name)) {
                continue;
            }
            String rawValue = line.substring(delimiter + 1).trim();
            if ((rawValue.startsWith("\"") && rawValue.endsWith("\""))
                    || (rawValue.startsWith("'") && rawValue.endsWith("'"))) {
                rawValue = rawValue.substring(1, rawValue.length() - 1);
            }
            values.put(name, rawValue);
        }
        return values;
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
        Path cwd = Path.of("").toAbsolutePath().normalize();
        if (Files.exists(cwd.resolve("pipeline/cli.py"))) {
            return cwd;
        }
        Path cwdParent = cwd.getParent();
        if (cwdParent != null && Files.exists(cwdParent.resolve("pipeline/cli.py"))) {
            return cwdParent;
        }
        throw new IllegalStateException("Failed to resolve repository root containing pipeline/cli.py");
    }
}
