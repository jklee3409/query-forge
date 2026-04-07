package io.queryforge.backend.admin.pipeline.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "query-forge.admin.pipeline")
public record AdminPipelineProperties(
        String repoRoot,
        String pythonCommand,
        String logsDir,
        String artifactStoreDir,
        String sourceConfigDir,
        String chunkingConfig,
        String rawOutputPath,
        String sectionsOutputPath,
        String chunksOutputPath,
        String glossaryOutputPath,
        String relationsOutputPath,
        String visualizationOutputPath,
        int maxLogChars
) {
}
