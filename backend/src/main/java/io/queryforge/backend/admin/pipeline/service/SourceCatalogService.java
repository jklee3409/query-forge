package io.queryforge.backend.admin.pipeline.service;

import io.queryforge.backend.admin.pipeline.config.AdminPipelineProperties;
import io.queryforge.backend.admin.pipeline.repository.PipelineAdminRepository;
import lombok.extern.slf4j.Slf4j;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
@Slf4j
@RequiredArgsConstructor
public class SourceCatalogService {

    private final AdminPipelineProperties properties;
    private final PipelineAdminRepository repository;
    private volatile Path repoRoot;

    public void syncSourcesFromConfig() {
        Path sourceDir = resolveWithinRepo(properties.sourceConfigDir(), "source config directory");
        if (!Files.isDirectory(sourceDir)) {
            return;
        }

        try (var paths = Files.list(sourceDir)) {
            paths.filter(path -> {
                        String fileName = path.getFileName().toString().toLowerCase();
                        return fileName.endsWith(".yml") || fileName.endsWith(".yaml");
                    })
                    .sorted()
                    .forEach(this::upsertSourceFile);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to load source config directory: " + sourceDir, exception);
        }
    }

    public Path repoRoot() {
        Path current = repoRoot;
        if (current == null) {
            current = ProjectPathResolver.resolveRepoRoot(properties.repoRoot());
            repoRoot = current;
        }
        return current;
    }

    public Path resolveWithinRepo(String configuredPath, String label) {
        return ProjectPathResolver.resolveWithinRepo(repoRoot(), configuredPath, label);
    }

    @SuppressWarnings("unchecked")
    private void upsertSourceFile(Path path) {
        try (InputStream inputStream = Files.newInputStream(path)) {
            Yaml yaml = new Yaml();
            Object payload = yaml.load(inputStream);
            if (payload == null) {
                return;
            }
            if (payload instanceof Map<?, ?> map && map.containsKey("sources")) {
                Object items = map.get("sources");
                if (items instanceof List<?> list) {
                    for (Object item : list) {
                        if (item instanceof Map<?, ?> sourceMap) {
                            upsertSourceEntry((Map<String, Object>) sourceMap);
                        }
                    }
                }
                return;
            }
            if (payload instanceof List<?> list) {
                for (Object item : list) {
                    if (item instanceof Map<?, ?> sourceMap) {
                        upsertSourceEntry((Map<String, Object>) sourceMap);
                    }
                }
                return;
            }
            if (payload instanceof Map<?, ?> sourceMap) {
                upsertSourceEntry((Map<String, Object>) sourceMap);
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to read source config file: " + path, exception);
        } catch (RuntimeException exception) {
            log.warn("Skipping invalid YAML source config file: {} ({})", path, exception.getMessage());
        }
    }

    private void upsertSourceEntry(Map<String, Object> item) {
        Object sourceIdValue = item.get("source_id");
        Object productValue = item.get("product");
        if (sourceIdValue == null || productValue == null) {
            log.warn("Skipping source config entry due to missing source_id/product: {}", item);
            return;
        }

        String sourceId = String.valueOf(item.get("source_id"));
        String product = String.valueOf(item.get("product"));
        List<String> startUrls = toStringList(item.get("start_urls"));
        List<String> allowPrefixes = toStringList(item.get("allow_prefixes"));
        List<String> denyPatterns = toStringList(item.get("deny_url_patterns"));
        boolean enabled = item.get("enabled") == null || Boolean.parseBoolean(String.valueOf(item.get("enabled")));
        repository.upsertSourceDefinition(
                sourceId,
                "html",
                product,
                sourceId,
                allowPrefixes.isEmpty() ? (startUrls.isEmpty() ? "" : startUrls.getFirst()) : allowPrefixes.getFirst(),
                allowPrefixes,
                denyPatterns,
                null,
                enabled
        );
    }

    private List<String> toStringList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<String> normalized = new ArrayList<>();
        for (Object item : list) {
            if (item != null) {
                normalized.add(String.valueOf(item));
            }
        }
        return normalized;
    }
}
