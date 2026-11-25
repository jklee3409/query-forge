package io.queryforge.backend.admin.pipeline.execution.service;

import io.queryforge.backend.admin.pipeline.configuration.AdminPipelineProperties;
import io.queryforge.backend.admin.pipeline.execution.service.PipelineAdminService.ArtifactContext;
import io.queryforge.backend.admin.pipeline.execution.service.PipelineAdminService.Scope;
import io.queryforge.backend.admin.pipeline.execution.service.PipelineAdminService.StepPlan;
import io.queryforge.backend.admin.pipeline.source.service.SourceCatalogService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Component
@RequiredArgsConstructor
class PipelineStepPlanFactory {

    private final AdminPipelineProperties properties;
    private final DataSourceProperties dataSourceProperties;
    private final SourceCatalogService sourceCatalogService;

    List<StepPlan> buildPlans(UUID runId, String runType, Scope scope, ArtifactContext artifacts) {
        List<StepPlan> steps = new ArrayList<>();
        if ("collect".equals(runType) || "full_ingest".equals(runType)) {
            steps.add(new StepPlan(
                    UUID.randomUUID(),
                    "collect",
                    steps.size() + 1,
                    buildCollectCommand(runId, scope, artifacts),
                    null,
                    artifacts.rawPath().toString()
            ));
        }
        if ("normalize".equals(runType) || "full_ingest".equals(runType)) {
            steps.add(new StepPlan(
                    UUID.randomUUID(),
                    "normalize",
                    steps.size() + 1,
                    buildNormalizeCommand(runId, scope, artifacts),
                    artifacts.rawPath().toString(),
                    artifacts.sectionsPath().toString()
            ));
        }
        if ("chunk".equals(runType) || "full_ingest".equals(runType)) {
            steps.add(new StepPlan(
                    UUID.randomUUID(),
                    "chunk",
                    steps.size() + 1,
                    buildChunkCommand(runId, scope, artifacts),
                    artifacts.sectionsPath().toString(),
                    artifacts.chunksPath().toString()
            ));
        }
        if ("glossary".equals(runType) || "full_ingest".equals(runType)) {
            steps.add(new StepPlan(
                    UUID.randomUUID(),
                    "glossary",
                    steps.size() + 1,
                    buildGlossaryCommand(runId, scope, artifacts),
                    artifacts.sectionsPath().toString(),
                    artifacts.glossaryPath().toString()
            ));
        }
        if ("import".equals(runType) || "full_ingest".equals(runType)) {
            steps.add(new StepPlan(
                    UUID.randomUUID(),
                    "import",
                    steps.size() + 1,
                    buildImportCommand(runId, scope, runType, artifacts),
                    artifacts.sectionsPath().toString(),
                    "postgresql://corpus"
            ));
        }
        return steps;
    }

    private List<String> buildCollectCommand(UUID runId, Scope scope, ArtifactContext artifacts) {
        List<String> command = baseCommand("collect-docs");
        command.add("--config-dir");
        command.add(resolveRepoRelative(properties.sourceConfigDir()).toString());
        command.add("--output");
        command.add(artifacts.rawPath().toString());
        command.add("--run-id");
        command.add(runId.toString());
        if (scope.limit() != null) {
            command.add("--limit");
            command.add(scope.limit().toString());
        }
        for (String sourceId : scope.sourceIds()) {
            command.add("--source-id");
            command.add(sourceId);
        }
        return command;
    }

    private List<String> buildNormalizeCommand(UUID runId, Scope scope, ArtifactContext artifacts) {
        List<String> command = baseCommand("preprocess");
        command.add("--input");
        command.add(artifacts.rawPath().toString());
        command.add("--output");
        command.add(artifacts.sectionsPath().toString());
        command.add("--run-id");
        command.add(runId.toString());
        if (scope.limit() != null) {
            command.add("--limit");
            command.add(scope.limit().toString());
        }
        return command;
    }

    private List<String> buildChunkCommand(UUID runId, Scope scope, ArtifactContext artifacts) {
        List<String> command = baseCommand("chunk-docs");
        command.add("--input");
        command.add(artifacts.sectionsPath().toString());
        command.add("--output-chunks");
        command.add(artifacts.chunksPath().toString());
        command.add("--output-glossary");
        command.add(artifacts.glossaryPath().toString());
        command.add("--output-relations-sql");
        command.add(artifacts.relationsPath().toString());
        command.add("--output-visualization");
        command.add(artifacts.visualizationPath().toString());
        command.add("--config");
        command.add(resolveRepoRelative(properties.chunkingConfig()).toString());
        command.add("--run-id");
        command.add(runId.toString());
        if (scope.limit() != null) {
            command.add("--limit-documents");
            command.add(scope.limit().toString());
        }
        return command;
    }

    private List<String> buildGlossaryCommand(UUID runId, Scope scope, ArtifactContext artifacts) {
        List<String> command = baseCommand("glossary-docs");
        command.add("--input");
        command.add(artifacts.sectionsPath().toString());
        command.add("--output-glossary");
        command.add(artifacts.glossaryPath().toString());
        command.add("--config");
        command.add(resolveRepoRelative(properties.chunkingConfig()).toString());
        command.add("--run-id");
        command.add(runId.toString());
        if (scope.limit() != null) {
            command.add("--limit-documents");
            command.add(scope.limit().toString());
        }
        return command;
    }

    private List<String> buildImportCommand(UUID runId, Scope scope, String runType, ArtifactContext artifacts) {
        DatabaseTarget databaseTarget = resolveDatabaseTarget();
        List<String> command = baseCommand("import-corpus");
        command.add("--db-host");
        command.add(databaseTarget.host());
        command.add("--db-port");
        command.add(String.valueOf(databaseTarget.port()));
        command.add("--db-name");
        command.add(databaseTarget.database());
        command.add("--db-user");
        command.add(Objects.requireNonNullElse(dataSourceProperties.getUsername(), ""));
        command.add("--db-password");
        command.add(Objects.requireNonNullElse(dataSourceProperties.getPassword(), ""));
        command.add("--source-config-dir");
        command.add(resolveRepoRelative(properties.sourceConfigDir()).toString());
        command.add("--raw-input");
        command.add(artifacts.rawPath().toString());
        command.add("--sections-input");
        command.add(artifacts.sectionsPath().toString());
        command.add("--chunks-input");
        command.add(artifacts.chunksPath().toString());
        command.add("--glossary-input");
        command.add(artifacts.glossaryPath().toString());
        command.add("--external-run-id");
        command.add(runId.toString());
        command.add("--run-type");
        command.add("full_ingest".equals(runType) ? "full_ingest" : "import");
        if (scope.domainId() != null) {
            command.add("--domain-id");
            command.add(scope.domainId().toString());
        }
        command.add("--trigger-type");
        command.add(scope.triggerType());
        command.add("--created-by");
        command.add(scope.createdBy());
        command.add("--run-id");
        command.add(runId.toString());
        if (scope.dryRun()) {
            command.add("--dry-run");
        }
        return command;
    }

    private List<String> baseCommand(String subCommand) {
        List<String> command = new ArrayList<>();
        command.add(properties.pythonCommand());
        command.add(sourceCatalogService.resolveWithinRepo("pipeline/cli.py", "pipeline CLI entrypoint").toString());
        command.add(subCommand);
        return command;
    }

    private DatabaseTarget resolveDatabaseTarget() {
        String jdbcUrl = dataSourceProperties.getUrl();
        String normalized = jdbcUrl.replace("jdbc:postgresql://", "");
        String[] parts = normalized.split("/", 2);
        String hostPort = parts[0];
        String database = parts.length > 1 ? parts[1] : "query_forge";
        String[] hostPortParts = hostPort.split(":", 2);
        String host = hostPortParts[0];
        int port = hostPortParts.length > 1 ? Integer.parseInt(hostPortParts[1]) : 5432;
        return new DatabaseTarget(host, port, database);
    }

    private Path resolveRepoRelative(String relative) {
        return sourceCatalogService.resolveWithinRepo(relative, "repo-relative path");
    }

    private record DatabaseTarget(
            String host,
            int port,
            String database
    ) {
    }
}
