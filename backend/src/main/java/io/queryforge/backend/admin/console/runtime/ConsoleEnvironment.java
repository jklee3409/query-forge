package io.queryforge.backend.admin.console.runtime;

import io.queryforge.backend.admin.pipeline.AdminPipelineProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

@Component
@RequiredArgsConstructor
public class ConsoleEnvironment {

    private final AdminPipelineProperties pipelineProperties;

    public String readEnv(String key) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) {
            value = readFromDotEnvFile(key);
        }
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private String readFromDotEnvFile(String key) {
        Path envPath = resolveRepoRoot().resolve(".env").normalize();
        if (!Files.exists(envPath)) {
            return null;
        }
        try {
            for (String entry : Files.readAllLines(envPath, StandardCharsets.UTF_8)) {
                String line = entry.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                int delimiter = line.indexOf('=');
                if (delimiter <= 0) {
                    continue;
                }
                String name = line.substring(0, delimiter).trim();
                if (!name.equals(key)) {
                    continue;
                }
                String rawValue = line.substring(delimiter + 1).trim();
                if ((rawValue.startsWith("\"") && rawValue.endsWith("\""))
                        || (rawValue.startsWith("'") && rawValue.endsWith("'"))) {
                    rawValue = rawValue.substring(1, rawValue.length() - 1);
                }
                return rawValue.isBlank() ? null : rawValue.trim();
            }
        } catch (IOException ignored) {
            return null;
        }
        return null;
    }

    public Path resolveRepoRoot() {
        Path configured = Path.of(pipelineProperties.repoRoot()).toAbsolutePath().normalize();
        if (Files.exists(configured.resolve("pipeline/cli.py"))) {
            return configured;
        }
        Path parent = configured.getParent();
        if (parent != null && Files.exists(parent.resolve("pipeline/cli.py"))) {
            return parent;
        }
        throw new IllegalStateException("failed to resolve repository root for admin console");
    }
}
