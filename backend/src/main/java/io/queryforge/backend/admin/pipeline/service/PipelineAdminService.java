package io.queryforge.backend.admin.pipeline.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.queryforge.backend.admin.corpus.model.CorpusAdminDtos;
import io.queryforge.backend.admin.corpus.service.CorpusAdminService;
import io.queryforge.backend.admin.pipeline.config.AdminPipelineProperties;
import io.queryforge.backend.admin.pipeline.model.PipelineAdminDtos;
import io.queryforge.backend.admin.pipeline.repository.PipelineAdminRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PipelineAdminService {

    private final AdminPipelineProperties properties;
    private final DataSourceProperties dataSourceProperties;
    private final PipelineAdminRepository repository;
    private final CorpusAdminService corpusAdminService;
    private final SourceCatalogService sourceCatalogService;
    private final DocumentArtifactStoreService artifactStoreService;
    private final PipelineCommandRunner commandRunner;
    private final ExecutorService executorService;
    private final ObjectMapper objectMapper;
    private final Map<UUID, ManagedRunContext> managedRuns = new ConcurrentHashMap<>();

    @PostConstruct
    void recoverStaleRuns() {
        repository.markStaleRunsFailed();
        sourceCatalogService.syncSourcesFromConfig();
    }

    @Transactional
    public PipelineAdminDtos.DashboardStats getDashboardStats() {
        sourceCatalogService.syncSourcesFromConfig();
        return repository.fetchDashboardStats();
    }

    @Transactional
    public PipelineAdminDtos.PipelineRunActionResponse startCollect(PipelineAdminDtos.PipelineRunRequest request) {
        return startRun("collect", request);
    }

    @Transactional
    public PipelineAdminDtos.PipelineRunActionResponse startNormalize(PipelineAdminDtos.PipelineRunRequest request) {
        return startRun("normalize", request);
    }

    @Transactional
    public PipelineAdminDtos.PipelineRunActionResponse startChunk(PipelineAdminDtos.PipelineRunRequest request) {
        return startRun("chunk", request);
    }

    @Transactional
    public PipelineAdminDtos.PipelineRunActionResponse startGlossary(PipelineAdminDtos.PipelineRunRequest request) {
        return startRun("glossary", request);
    }

    @Transactional
    public PipelineAdminDtos.PipelineRunActionResponse startImport(PipelineAdminDtos.PipelineRunRequest request) {
        return startRun("import", request);
    }

    @Transactional
    public PipelineAdminDtos.PipelineRunActionResponse startFullIngest(PipelineAdminDtos.PipelineRunRequest request) {
        return startRun("full_ingest", request);
    }

    @Transactional
    public PipelineAdminDtos.PipelineRunActionResponse retryRun(UUID runId) {
        CorpusAdminDtos.RunDetail detail = corpusAdminService.getRunDetail(runId);
        Map<String, Object> sourceScope = objectMapper.convertValue(
                detail.run().sourceScope(),
                new TypeReference<>() {
                }
        );
        Map<String, Object> configSnapshot = objectMapper.convertValue(
                detail.run().configSnapshot(),
                new TypeReference<>() {
                }
        );
        PipelineAdminDtos.PipelineRunRequest request = new PipelineAdminDtos.PipelineRunRequest(
                toStringList(sourceScope.get("source_ids")),
                toStringList(sourceScope.get("document_ids")),
                booleanValue(configSnapshot.get("dry_run")),
                detail.run().createdBy(),
                detail.run().triggerType(),
                integerValue(configSnapshot.get("limit")),
                null
        );
        return startRun(detail.run().runType(), request);
    }

    @Transactional
    public PipelineAdminDtos.PipelineRunActionResponse cancelRun(UUID runId) {
        ManagedRunContext context = managedRuns.get(runId);
        if (context == null) {
            repository.requestRunCancellation(runId);
            return new PipelineAdminDtos.PipelineRunActionResponse(
                    runId,
                    corpusAdminService.getRunDetail(runId).run().runType(),
                    "cancel_requested",
                    "실행 중인 프로세스는 없지만 취소 요청 상태를 기록했습니다."
            );
        }

        context.cancelRequested.set(true);
        repository.requestRunCancellation(runId);
        if (context.currentProcess != null) {
            commandRunner.cancel(context.currentProcess);
        }
        return new PipelineAdminDtos.PipelineRunActionResponse(
                runId,
                context.runType,
                "cancel_requested",
                "실행 중인 파이프라인에 취소 요청을 보냈습니다."
        );
    }

    public List<CorpusAdminDtos.RunSummary> listRuns(
            UUID runId,
            String runStatus,
            String runType,
            Integer limit,
            Integer offset
    ) {
        return corpusAdminService.listRuns(runId, runStatus, runType, limit, offset);
    }

    public CorpusAdminDtos.RunDetail getRun(UUID runId) {
        return corpusAdminService.getRunDetail(runId);
    }

    public List<CorpusAdminDtos.RunStep> getRunSteps(UUID runId) {
        return corpusAdminService.getRunDetail(runId).steps();
    }

    public PipelineAdminDtos.PipelineRunLogsResponse getRunLogs(UUID runId) {
        List<PipelineAdminDtos.StepLogDto> logs = corpusAdminService.getRunDetail(runId).steps().stream()
                .map(this::toStepLog)
                .toList();
        return new PipelineAdminDtos.PipelineRunLogsResponse(runId, logs);
    }

    private PipelineAdminDtos.PipelineRunActionResponse startRun(
            String runType,
            PipelineAdminDtos.PipelineRunRequest request
    ) {
        sourceCatalogService.syncSourcesFromConfig();
        Optional<UUID> activeRunId = repository.findActiveRunId();
        if (activeRunId.isPresent()) {
            throw new IllegalStateException("다른 파이프라인 실행이 아직 진행 중입니다. runId=" + activeRunId.get());
        }

        UUID runId = UUID.randomUUID();
        Scope scope = resolveScope(runType, request);
        ArtifactContext artifacts = prepareArtifacts(runId);
        List<String> initialDocumentIds = resolveInitialDocumentIds(runType, scope);
        materializeInitialInputs(runType, initialDocumentIds, artifacts);

        repository.createRun(
                runId,
                runType,
                normalizedTriggerType(request.triggerType()),
                scope.toSourceScope(),
                scope.toConfigSnapshot(artifacts),
                normalizedCreatedBy(request.createdBy())
        );
        if (shouldShortCircuit(runType, initialDocumentIds)) {
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("step_count", 0);
            summary.put("artifacts", artifacts.toSummary());
            summary.put("source_scope", scope.toSourceScope());
            summary.put("document_count", 0);
            summary.put("message", shortCircuitMessage(runType));
            repository.finishRun(runId, "success", summary, null);
            return new PipelineAdminDtos.PipelineRunActionResponse(
                    runId,
                    runType,
                    "success",
                    shortCircuitMessage(runType)
            );
        }

        List<StepPlan> steps = buildPlans(runId, runType, scope, artifacts);
        for (StepPlan step : steps) {
            repository.createStep(
                    step.stepId(),
                    runId,
                    step.stepName(),
                    step.stepOrder(),
                    step.inputArtifactPath(),
                    step.outputArtifactPath()
            );
        }

        ManagedRunContext context = new ManagedRunContext(runId, runType, steps, artifacts, initialDocumentIds);
        managedRuns.put(runId, context);
        executorService.execute(() -> executeRun(context, scope));

        return new PipelineAdminDtos.PipelineRunActionResponse(
                runId,
                runType,
                "queued",
                "파이프라인 실행이 대기열에 등록되었습니다."
        );
    }

    private void executeRun(ManagedRunContext context, Scope scope) {
        repository.markRunRunning(context.runId);
        Map<String, Object> runSummary = new LinkedHashMap<>();
        runSummary.put("step_count", context.steps.size());
        runSummary.put("artifacts", context.artifacts.toSummary());
        runSummary.put("source_scope", scope.toSourceScope());
        runSummary.put("document_scope_count", context.currentDocumentIds.size());
        runSummary.put("document_scope", context.currentDocumentIds);
        StepPlan activeStep = null;

        try {
            for (StepPlan step : context.steps) {
                activeStep = step;
                if (context.cancelRequested.get()) {
                    cancelRemaining(context);
                    repository.finishRun(context.runId, "cancelled", runSummary, "Cancellation requested by user.");
                    return;
                }

                if (shouldSkipStep(step.stepName(), context.currentDocumentIds)) {
                    Map<String, Object> metrics = skippedMetrics(step.stepName(), context.currentDocumentIds);
                    repository.finishStep(
                            step.stepId(),
                            "success",
                            metrics,
                            null,
                            "",
                            "",
                            step.outputArtifactPath()
                    );
                    runSummary.put(step.stepName(), metrics);
                    activeStep = null;
                    continue;
                }

                Path stdoutPath = context.artifacts.logsDirectory.resolve(step.stepName() + ".stdout.log");
                Path stderrPath = context.artifacts.logsDirectory.resolve(step.stepName() + ".stderr.log");
                repository.markStepRunning(
                        step.stepId(),
                        String.join(" ", step.command()),
                        stdoutPath.toString(),
                        stderrPath.toString()
                );

                PipelineCommandRunner.CommandResult result = commandRunner.run(
                        new PipelineCommandRunner.CommandRequest(
                                step.command(),
                                context.artifacts.repoRoot,
                                stdoutPath,
                                stderrPath
                        ),
                        handle -> context.currentProcess = handle
                );
                context.currentProcess = null;

                if (context.cancelRequested.get()) {
                    repository.finishStep(
                            step.stepId(),
                            "cancelled",
                            Map.of("exit_code", result.exitCode()),
                            "Cancellation requested by user.",
                            excerpt(result.stdout()),
                            excerpt(result.stderr()),
                            step.outputArtifactPath()
                    );
                    cancelRemaining(context);
                    repository.finishRun(context.runId, "cancelled", runSummary, "Cancellation requested by user.");
                    return;
                }

                Map<String, Object> metrics = parseStepMetrics(result.stdout());
                metrics.put("exit_code", result.exitCode());
                metrics.put("finished_at", result.finishedAt().toString());

                if (result.exitCode() != 0) {
                    repository.finishStep(
                            step.stepId(),
                            "failed",
                            metrics,
                            firstNonBlank(result.stderr(), "Pipeline command exited with non-zero status."),
                            excerpt(result.stdout()),
                            excerpt(result.stderr()),
                            step.outputArtifactPath()
                    );
                    runSummary.put("failed_step", step.stepName());
                    repository.finishRun(
                            context.runId,
                            "failed",
                            runSummary,
                            firstNonBlank(result.stderr(), "Pipeline command exited with non-zero status.")
                    );
                    return;
                }

                DocumentArtifactStoreService.PersistResult persistResult = applyStepOutputs(step.stepName(), context, scope);
                metrics.put("documents_discovered", persistResult.discoveredDocumentCount());
                metrics.put("documents_persisted", persistResult.persistedDocumentCount());
                metrics.put("document_ids", persistResult.documentIds());
                repository.finishStep(
                        step.stepId(),
                        "success",
                        metrics,
                        null,
                        excerpt(result.stdout()),
                        excerpt(result.stderr()),
                        step.outputArtifactPath()
                );
                runSummary.put(step.stepName(), metrics);
                runSummary.put("current_document_ids", context.currentDocumentIds);
                activeStep = null;
            }

            repository.finishRun(context.runId, "success", runSummary, null);
        } catch (Exception exception) {
            if (activeStep != null) {
                repository.finishStep(
                        activeStep.stepId(),
                        context.cancelRequested.get() ? "cancelled" : "failed",
                        Map.of("exception_type", exception.getClass().getSimpleName()),
                        exception.getMessage(),
                        "",
                        "",
                        activeStep.outputArtifactPath()
                );
            }
            cancelRemaining(context);
            repository.finishRun(
                    context.runId,
                    context.cancelRequested.get() ? "cancelled" : "failed",
                    runSummary,
                    exception.getMessage()
            );
        } finally {
            managedRuns.remove(context.runId);
        }
    }

    private void cancelRemaining(ManagedRunContext context) {
        repository.cancelQueuedSteps(context.runId);
    }

    private Scope resolveScope(String runType, PipelineAdminDtos.PipelineRunRequest request) {
        List<String> explicitSourceIds = normalizeValues(request.sourceIds());
        if (explicitSourceIds.isEmpty() && ("collect".equals(runType) || "full_ingest".equals(runType))) {
            explicitSourceIds = corpusAdminService.listSources().stream()
                    .filter(CorpusAdminDtos.SourceSummary::enabled)
                    .map(CorpusAdminDtos.SourceSummary::sourceId)
                    .toList();
        }

        LinkedHashSet<String> documentIds = new LinkedHashSet<>(normalizeValues(request.documentIds()));
        if (documentIds.isEmpty() && !"collect".equals(runType) && !"full_ingest".equals(runType)) {
            if (!explicitSourceIds.isEmpty()) {
                documentIds.addAll(artifactStoreService.resolveDocumentIdsBySource(explicitSourceIds));
            } else {
                documentIds.addAll(artifactStoreService.resolveAllDocumentIds());
            }
        }

        return new Scope(
                explicitSourceIds,
                List.copyOf(documentIds),
                request.dryRun() != null && request.dryRun(),
                normalizedCreatedBy(request.createdBy()),
                normalizedTriggerType(request.triggerType()),
                request.limit()
        );
    }

    private List<String> resolveInitialDocumentIds(String runType, Scope scope) {
        return switch (runType) {
            case "normalize" -> artifactStoreService.selectDocumentsForNormalize(scope.documentIds());
            case "chunk" -> artifactStoreService.selectDocumentsForChunk(scope.documentIds());
            case "glossary" -> artifactStoreService.selectDocumentsForGlossary(scope.documentIds());
            case "import" -> artifactStoreService.selectDocumentsForImport(scope.documentIds());
            default -> scope.documentIds();
        };
    }

    private void materializeInitialInputs(String runType, List<String> documentIds, ArtifactContext artifacts) {
        switch (runType) {
            case "normalize" -> artifactStoreService.materializeRawArtifacts(documentIds, artifacts.rawPath);
            case "chunk", "glossary" -> artifactStoreService.materializeNormalizedArtifacts(documentIds, artifacts.sectionsPath);
            case "import" -> {
                artifactStoreService.materializeRawArtifacts(documentIds, artifacts.rawPath);
                artifactStoreService.materializeNormalizedArtifacts(documentIds, artifacts.sectionsPath);
                artifactStoreService.materializeChunkArtifacts(documentIds, artifacts.chunksPath);
                artifactStoreService.materializeGlossaryArtifacts(documentIds, artifacts.glossaryPath);
            }
            default -> {
            }
        }
    }

    private boolean shouldShortCircuit(String runType, List<String> initialDocumentIds) {
        return !"collect".equals(runType) && !"full_ingest".equals(runType) && initialDocumentIds.isEmpty();
    }

    private String shortCircuitMessage(String runType) {
        return switch (runType) {
            case "normalize" -> "요청한 문서는 이미 최신 정제 산출물이 있어 정제 단계를 건너뛰었습니다.";
            case "chunk" -> "요청한 문서는 이미 최신 청킹 산출물이 있어 청킹 단계를 건너뛰었습니다.";
            case "glossary" -> "요청한 문서는 이미 최신 용어 산출물이 있어 용어 추출 단계를 건너뛰었습니다.";
            case "import" -> "가져올 최신 문서 산출물이 없어 적재 단계를 건너뛰었습니다.";
            default -> "실행할 작업이 없습니다.";
        };
    }

    private boolean shouldSkipStep(String stepName, List<String> currentDocumentIds) {
        if ("collect".equals(stepName)) {
            return false;
        }
        if (currentDocumentIds.isEmpty()) {
            return true;
        }
        if ("glossary".equals(stepName)) {
            return artifactStoreService.selectDocumentsForGlossary(currentDocumentIds).isEmpty();
        }
        return false;
    }

    private Map<String, Object> skippedMetrics(String stepName, List<String> currentDocumentIds) {
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("skipped", true);
        metrics.put("reason", currentDocumentIds.isEmpty() ? "no_documents_pending" : "already_current");
        metrics.put("document_count", currentDocumentIds.size());
        metrics.put("step", stepName);
        return metrics;
    }

    private DocumentArtifactStoreService.PersistResult applyStepOutputs(
            String stepName,
            ManagedRunContext context,
            Scope scope
    ) {
        return switch (stepName) {
            case "collect" -> {
                DocumentArtifactStoreService.PersistResult persisted = artifactStoreService.persistRawArtifacts(context.artifacts.rawPath, context.runId);
                List<String> nextDocumentIds = persisted.documentIds();
                if (!scope.documentIds().isEmpty()) {
                    nextDocumentIds = nextDocumentIds.stream().filter(scope.documentIds()::contains).toList();
                }
                context.currentDocumentIds = nextDocumentIds;
                artifactStoreService.materializeRawArtifacts(nextDocumentIds, context.artifacts.rawPath);
                yield new DocumentArtifactStoreService.PersistResult(
                        nextDocumentIds,
                        persisted.discoveredDocumentCount(),
                        persisted.persistedDocumentCount()
                );
            }
            case "normalize" -> {
                DocumentArtifactStoreService.PersistResult persisted = artifactStoreService.persistNormalizedArtifacts(context.artifacts.sectionsPath, context.runId);
                context.currentDocumentIds = persisted.documentIds();
                yield persisted;
            }
            case "chunk" -> {
                DocumentArtifactStoreService.PersistResult persisted = artifactStoreService.persistChunkArtifacts(
                        context.artifacts.chunksPath,
                        context.artifacts.glossaryPath,
                        context.runId
                );
                context.currentDocumentIds = persisted.documentIds();
                yield persisted;
            }
            case "glossary" -> {
                DocumentArtifactStoreService.PersistResult persisted = artifactStoreService.persistGlossaryArtifacts(
                        context.artifacts.glossaryPath,
                        context.runId
                );
                context.currentDocumentIds = persisted.documentIds();
                yield persisted;
            }
            case "import" -> new DocumentArtifactStoreService.PersistResult(
                    context.currentDocumentIds,
                    context.currentDocumentIds.size(),
                    context.currentDocumentIds.size()
            );
            default -> DocumentArtifactStoreService.PersistResult.empty();
        };
    }

    private ArtifactContext prepareArtifacts(UUID runId) {
        Path repoRoot = sourceCatalogService.repoRoot();
        Path workspaceRoot = sourceCatalogService.resolveWithinRepo("data/tmp/admin-runs", "admin run workspace");
        Path logsRoot = sourceCatalogService.resolveWithinRepo(properties.logsDir(), "admin pipeline logs directory");
        Path workspace = workspaceRoot.resolve(runId.toString()).normalize();
        Path logsDirectory = logsRoot.resolve(runId.toString()).normalize();
        try {
            Files.createDirectories(workspace);
            Files.createDirectories(logsDirectory);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to prepare run workspace.", exception);
        }

        return new ArtifactContext(
                repoRoot,
                workspace,
                logsDirectory,
                workspace.resolve("spring_docs_raw.jsonl"),
                workspace.resolve("spring_docs_sections.jsonl"),
                workspace.resolve("chunks.jsonl"),
                workspace.resolve("glossary_terms.jsonl"),
                workspace.resolve("chunk_neighbors.sql"),
                workspace.resolve("chunking_visualization.md")
        );
    }

    private List<StepPlan> buildPlans(UUID runId, String runType, Scope scope, ArtifactContext artifacts) {
        List<StepPlan> steps = new ArrayList<>();
        if ("collect".equals(runType) || "full_ingest".equals(runType)) {
            steps.add(new StepPlan(
                    UUID.randomUUID(),
                    "collect",
                    steps.size() + 1,
                    buildCollectCommand(runId, scope, artifacts),
                    null,
                    artifacts.rawPath.toString()
            ));
        }
        if ("normalize".equals(runType) || "full_ingest".equals(runType)) {
            steps.add(new StepPlan(
                    UUID.randomUUID(),
                    "normalize",
                    steps.size() + 1,
                    buildNormalizeCommand(runId, scope, artifacts),
                    artifacts.rawPath.toString(),
                    artifacts.sectionsPath.toString()
            ));
        }
        if ("chunk".equals(runType) || "full_ingest".equals(runType)) {
            steps.add(new StepPlan(
                    UUID.randomUUID(),
                    "chunk",
                    steps.size() + 1,
                    buildChunkCommand(runId, scope, artifacts),
                    artifacts.sectionsPath.toString(),
                    artifacts.chunksPath.toString()
            ));
        }
        if ("glossary".equals(runType) || "full_ingest".equals(runType)) {
            steps.add(new StepPlan(
                    UUID.randomUUID(),
                    "glossary",
                    steps.size() + 1,
                    buildGlossaryCommand(runId, scope, artifacts),
                    artifacts.sectionsPath.toString(),
                    artifacts.glossaryPath.toString()
            ));
        }
        if ("import".equals(runType) || "full_ingest".equals(runType)) {
            steps.add(new StepPlan(
                    UUID.randomUUID(),
                    "import",
                    steps.size() + 1,
                    buildImportCommand(runId, scope, runType, artifacts),
                    artifacts.sectionsPath.toString(),
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
        command.add(artifacts.rawPath.toString());
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
        command.add(artifacts.rawPath.toString());
        command.add("--output");
        command.add(artifacts.sectionsPath.toString());
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
        command.add(artifacts.sectionsPath.toString());
        command.add("--output-chunks");
        command.add(artifacts.chunksPath.toString());
        command.add("--output-glossary");
        command.add(artifacts.glossaryPath.toString());
        command.add("--output-relations-sql");
        command.add(artifacts.relationsPath.toString());
        command.add("--output-visualization");
        command.add(artifacts.visualizationPath.toString());
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
        command.add(artifacts.sectionsPath.toString());
        command.add("--output-glossary");
        command.add(artifacts.glossaryPath.toString());
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
        command.add(artifacts.rawPath.toString());
        command.add("--sections-input");
        command.add(artifacts.sectionsPath.toString());
        command.add("--chunks-input");
        command.add(artifacts.chunksPath.toString());
        command.add("--glossary-input");
        command.add(artifacts.glossaryPath.toString());
        command.add("--external-run-id");
        command.add(runId.toString());
        command.add("--run-type");
        command.add("full_ingest".equals(runType) ? "full_ingest" : "import");
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

    private Map<String, Object> parseStepMetrics(String stdout) {
        if (stdout == null || stdout.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            return objectMapper.readValue(stdout, new TypeReference<>() {
            });
        } catch (Exception exception) {
            Map<String, Object> fallback = new LinkedHashMap<>();
            fallback.put("stdout_excerpt", excerpt(stdout));
            return fallback;
        }
    }

    private PipelineAdminDtos.StepLogDto toStepLog(CorpusAdminDtos.RunStep step) {
        return new PipelineAdminDtos.StepLogDto(
                step.stepId(),
                step.stepName(),
                step.stdoutLogPath(),
                step.stderrLogPath(),
                readLog(step.stdoutLogPath()),
                readLog(step.stderrLogPath()),
                step.updatedAt()
        );
    }

    private String readLog(String path) {
        if (path == null || path.isBlank()) {
            return "";
        }
        Path logPath = Path.of(path);
        if (!Files.exists(logPath)) {
            return "";
        }
        try {
            String content = Files.readString(logPath, StandardCharsets.UTF_8);
            return excerpt(content);
        } catch (IOException exception) {
            return "Failed to read log: " + exception.getMessage();
        }
    }

    private String excerpt(String content) {
        if (content == null || content.isBlank()) {
            return "";
        }
        String trimmed = content.trim();
        if (trimmed.length() <= properties.maxLogChars()) {
            return trimmed;
        }
        return trimmed.substring(trimmed.length() - properties.maxLogChars());
    }

    private String normalizedCreatedBy(String createdBy) {
        return createdBy == null || createdBy.isBlank() ? "admin-ui" : createdBy;
    }

    private String normalizedTriggerType(String triggerType) {
        return triggerType == null || triggerType.isBlank() ? "api" : triggerType;
    }

    private List<String> normalizeValues(List<String> values) {
        if (values == null) {
            return List.of();
        }
        return values.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .distinct()
                .toList();
    }

    private List<String> toStringList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream().filter(Objects::nonNull).map(String::valueOf).toList();
    }

    private Boolean booleanValue(Object value) {
        if (value == null) {
            return null;
        }
        return Boolean.parseBoolean(String.valueOf(value));
    }

    private Integer integerValue(Object value) {
        if (value == null) {
            return null;
        }
        return Integer.parseInt(String.valueOf(value));
    }

    private String firstNonBlank(String primary, String fallback) {
        if (primary != null && !primary.isBlank()) {
            return primary.trim();
        }
        return fallback;
    }

    private Path resolveRepoRelative(String relative) {
        return sourceCatalogService.resolveWithinRepo(relative, "repo-relative path");
    }

    private record Scope(
            List<String> sourceIds,
            List<String> documentIds,
            boolean dryRun,
            String createdBy,
            String triggerType,
            Integer limit
    ) {
        Map<String, Object> toSourceScope() {
            Map<String, Object> scope = new LinkedHashMap<>();
            scope.put("source_ids", sourceIds);
            scope.put("document_ids", documentIds);
            return scope;
        }

        Map<String, Object> toConfigSnapshot(ArtifactContext artifacts) {
            Map<String, Object> config = new LinkedHashMap<>();
            config.put("dry_run", dryRun);
            config.put("trigger_type", triggerType);
            config.put("created_by", createdBy);
            config.put("limit", limit);
            config.put("raw_output_path", artifacts.rawPath.toString());
            config.put("sections_output_path", artifacts.sectionsPath.toString());
            config.put("chunks_output_path", artifacts.chunksPath.toString());
            config.put("glossary_output_path", artifacts.glossaryPath.toString());
            return config;
        }
    }

    private record ArtifactContext(
            Path repoRoot,
            Path workspace,
            Path logsDirectory,
            Path rawPath,
            Path sectionsPath,
            Path chunksPath,
            Path glossaryPath,
            Path relationsPath,
            Path visualizationPath
    ) {
        Map<String, Object> toSummary() {
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("workspace", workspace.toString());
            summary.put("raw_output_path", rawPath.toString());
            summary.put("sections_output_path", sectionsPath.toString());
            summary.put("chunks_output_path", chunksPath.toString());
            summary.put("glossary_output_path", glossaryPath.toString());
            summary.put("relations_output_path", relationsPath.toString());
            summary.put("visualization_output_path", visualizationPath.toString());
            return summary;
        }
    }

    private record StepPlan(
            UUID stepId,
            String stepName,
            int stepOrder,
            List<String> command,
            String inputArtifactPath,
            String outputArtifactPath
    ) {
    }

    private static final class ManagedRunContext {
        private final UUID runId;
        private final String runType;
        private final List<StepPlan> steps;
        private final ArtifactContext artifacts;
        private volatile List<String> currentDocumentIds;
        private final AtomicBoolean cancelRequested = new AtomicBoolean(false);
        private volatile ProcessHandle currentProcess;

        private ManagedRunContext(
                UUID runId,
                String runType,
                List<StepPlan> steps,
                ArtifactContext artifacts,
                List<String> currentDocumentIds
        ) {
            this.runId = runId;
            this.runType = runType;
            this.steps = steps;
            this.artifacts = artifacts;
            this.currentDocumentIds = new ArrayList<>(currentDocumentIds);
        }
    }

    private record DatabaseTarget(
            String host,
            int port,
            String database
    ) {
    }
}

