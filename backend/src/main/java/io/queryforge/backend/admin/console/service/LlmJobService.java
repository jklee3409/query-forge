package io.queryforge.backend.admin.console.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.queryforge.backend.admin.console.model.AdminConsoleDtos;
import io.queryforge.backend.admin.console.repository.AdminConsoleRepository;
import io.queryforge.backend.admin.console.repository.LlmJobRepository;
import io.queryforge.backend.admin.pipeline.config.AdminPipelineProperties;
import io.queryforge.backend.rag.model.RagDtos;
import io.queryforge.backend.rag.service.ExperimentPipelineService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@Service
@RequiredArgsConstructor
@Slf4j
public class LlmJobService {

    private static final int MAX_CONCURRENT_WORKERS = 3;

    private final LlmJobRepository llmJobRepository;
    private final AdminConsoleRepository adminConsoleRepository;
    private final ExperimentPipelineService experimentPipelineService;
    private final AdminPipelineProperties pipelineProperties;
    private final ObjectMapper objectMapper;
    @Autowired
    @Qualifier("llmJobExecutor")
    private ExecutorService llmJobExecutor;

    private final ConcurrentLinkedDeque<UUID> queue = new ConcurrentLinkedDeque<>();
    private final Set<UUID> queuedIds = ConcurrentHashMap.newKeySet();
    private final AtomicInteger activeWorkers = new AtomicInteger(0);

    @PostConstruct
    void recoverQueuedJobs() {
        List<UUID> cancelledJobs = llmJobRepository.finalizeCancelRequestedJobs(200);
        for (UUID jobId : cancelledJobs) {
            llmJobRepository.markRemainingItemsCancelled(jobId);
            llmJobRepository.findJob(jobId).ifPresent(this::markRelatedCancelled);
        }
        if (!cancelledJobs.isEmpty()) {
            log.warn("Recovered {} cancel_requested LLM jobs to cancelled state after restart.", cancelledJobs.size());
        }

        List<UUID> recoveredJobs = llmJobRepository.recoverInterruptedJobs(200);
        for (UUID jobId : recoveredJobs) {
            llmJobRepository.resetRunningItemsToQueued(jobId);
        }
        if (!recoveredJobs.isEmpty()) {
            log.warn("Recovered {} interrupted LLM jobs to queued state after restart.", recoveredJobs.size());
        }

        List<UUID> queuedJobs = llmJobRepository.findQueuedJobIds(500);
        for (UUID jobId : queuedJobs) {
            enqueue(jobId);
        }
        if (!queuedJobs.isEmpty()) {
            log.info("Bootstrapped {} queued LLM jobs into in-memory queue.", queuedJobs.size());
        }
    }

    @Transactional(readOnly = true)
    public List<AdminConsoleDtos.LlmJobRow> listJobs(Integer limit) {
        return llmJobRepository.findJobs(limit);
    }

    @Transactional(readOnly = true)
    public AdminConsoleDtos.LlmJobRow getJob(UUID jobId) {
        return llmJobRepository.findJob(jobId)
                .orElseThrow(() -> new IllegalArgumentException("llm job not found: " + jobId));
    }

    @Transactional(readOnly = true)
    public List<AdminConsoleDtos.LlmJobItemRow> listJobItems(UUID jobId) {
        return llmJobRepository.findJobItems(jobId);
    }

    @Transactional
    public UUID createGenerationJob(UUID generationBatchId, String experimentName, String createdBy) {
        JsonNode commandArgs = objectMapper.valueToTree(Map.of("experiment", experimentName, "command", "generate-queries"));
        UUID jobId = llmJobRepository.createJob(
                "GENERATE_SYNTHETIC_QUERY",
                "generate-queries",
                experimentName,
                commandArgs,
                generationBatchId,
                null,
                null,
                1,
                2,
                createdBy
        );
        llmJobRepository.createJobItem(jobId, 1, "generate-queries", commandArgs, 2);
        enqueue(jobId);
        return jobId;
    }

    @Transactional
    public UUID createGatingJob(UUID gatingBatchId, String experimentName, String createdBy) {
        JsonNode commandArgs = objectMapper.valueToTree(Map.of("experiment", experimentName, "command", "gate-queries"));
        UUID jobId = llmJobRepository.createJob(
                "RUN_LLM_SELF_EVAL",
                "gate-queries",
                experimentName,
                commandArgs,
                null,
                gatingBatchId,
                null,
                1,
                5,
                createdBy
        );
        llmJobRepository.createJobItem(jobId, 1, "gate-queries", commandArgs, 5);
        enqueue(jobId);
        return jobId;
    }

    @Transactional
    public UUID createRagTestJob(UUID ragTestRunId, String experimentName, String createdBy) {
        JsonNode commandArgs = objectMapper.valueToTree(Map.of("experiment", experimentName));
        UUID jobId = llmJobRepository.createJob(
                "RUN_RAG_TEST",
                "rag-pipeline",
                experimentName,
                commandArgs,
                null,
                null,
                ragTestRunId,
                3,
                1,
                createdBy
        );
        llmJobRepository.createJobItem(
                jobId,
                1,
                "build-memory",
                objectMapper.valueToTree(Map.of("experiment", experimentName, "command", "build-memory")),
                1
        );
        llmJobRepository.createJobItem(
                jobId,
                2,
                "eval-retrieval",
                objectMapper.valueToTree(Map.of("experiment", experimentName, "command", "eval-retrieval")),
                1
        );
        llmJobRepository.createJobItem(
                jobId,
                3,
                "eval-answer",
                objectMapper.valueToTree(Map.of("experiment", experimentName, "command", "eval-answer")),
                1
        );
        enqueue(jobId);
        return jobId;
    }

    @Transactional
    public void pauseJob(UUID jobId) {
        llmJobRepository.requestPause(jobId);
    }

    @Transactional
    public void resumeJob(UUID jobId) {
        llmJobRepository.resumePaused(jobId);
        enqueue(jobId);
    }

    @Transactional
    public void cancelJob(UUID jobId) {
        llmJobRepository.requestCancel(jobId);
        llmJobRepository.findJob(jobId).ifPresent(job -> {
            if ("cancelled".equalsIgnoreCase(job.jobStatus())) {
                llmJobRepository.markRemainingItemsCancelled(jobId);
                markRelatedCancelled(job);
                queuedIds.remove(jobId);
                queue.remove(jobId);
            }
        });
    }

    @Transactional
    public void retryJob(UUID jobId) {
        llmJobRepository.retryFailed(jobId);
        llmJobRepository.prepareItemsForRetry(jobId);
        enqueue(jobId);
    }

    private void enqueue(UUID jobId) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    enqueueNow(jobId);
                }
            });
            return;
        }
        enqueueNow(jobId);
    }

    private void enqueueNow(UUID jobId) {
        if (!queuedIds.add(jobId)) {
            return;
        }
        queue.offer(jobId);
        runWorkersIfNeeded();
    }

    private void runWorkersIfNeeded() {
        while (true) {
            if (queue.isEmpty()) {
                return;
            }
            int current = activeWorkers.get();
            if (current >= MAX_CONCURRENT_WORKERS) {
                return;
            }
            if (activeWorkers.compareAndSet(current, current + 1)) {
                try {
                    llmJobExecutor.execute(this::workerLoop);
                } catch (RuntimeException exception) {
                    activeWorkers.decrementAndGet();
                    throw exception;
                }
                return;
            }
        }
    }

    private void workerLoop() {
        try {
            while (true) {
                UUID jobId = queue.poll();
                if (jobId == null) {
                    break;
                }
                queuedIds.remove(jobId);
                Optional<AdminConsoleDtos.LlmJobRow> jobOptional = llmJobRepository.findJob(jobId);
                if (jobOptional.isEmpty()) {
                    continue;
                }
                AdminConsoleDtos.LlmJobRow job = jobOptional.get();
                if (!"queued".equalsIgnoreCase(job.jobStatus())) {
                    continue;
                }
                if (job.nextRunAt() != null && job.nextRunAt().isAfter(Instant.now())) {
                    long delaySeconds = Math.max(1L, job.nextRunAt().getEpochSecond() - Instant.now().getEpochSecond());
                    scheduleEnqueue(job.jobId(), delaySeconds);
                    continue;
                }
                executeJob(job);
            }
        } finally {
            activeWorkers.decrementAndGet();
            if (!queue.isEmpty()) {
                runWorkersIfNeeded();
            }
        }
    }

    private void executeJob(AdminConsoleDtos.LlmJobRow job) {
        llmJobRepository.markJobRunning(job.jobId());
        if (job.generationBatchId() != null) {
            adminConsoleRepository.markGenerationBatchRunning(job.generationBatchId());
        }
        if (job.gatingBatchId() != null) {
            adminConsoleRepository.markGatingBatchRunning(job.gatingBatchId());
        }
        if (job.ragTestRunId() != null) {
            adminConsoleRepository.markRagTestRunRunning(job.ragTestRunId());
        }

        List<AdminConsoleDtos.LlmJobItemRow> items = llmJobRepository.findJobItems(job.jobId());
        long jobStartedAtNano = System.nanoTime();
        Map<String, Long> commandDurationMs = new LinkedHashMap<>();
        int totalItems = Math.max(1, items.size());
        int processed = 0;
        Map<String, JsonNode> resultByCommand = new LinkedHashMap<>();
        for (AdminConsoleDtos.LlmJobItemRow item : items) {
            String command = item.payloadJson().path("command").asText(item.itemType());
            if ("completed".equalsIgnoreCase(item.itemStatus())) {
                resultByCommand.putIfAbsent(command, item.resultJson());
                if (item.startedAt() != null && item.finishedAt() != null) {
                    long durationMs = Math.max(0L, Duration.between(item.startedAt(), item.finishedAt()).toMillis());
                    commandDurationMs.putIfAbsent(command, durationMs);
                }
                processed += 1;
            }
        }
        if (processed > 0) {
            llmJobRepository.markJobProgress(
                    job.jobId(),
                    processed,
                    totalItems,
                    (processed * 100.0) / totalItems,
                    objectMapper.valueToTree(Map.of("resume_from_completed_items", processed))
            );
        }

        try {
            for (AdminConsoleDtos.LlmJobItemRow item : items) {
                String command = item.payloadJson().path("command").asText(item.itemType());
                String experiment = item.payloadJson().path("experiment").asText(job.experimentName());
                if ("completed".equalsIgnoreCase(item.itemStatus())) {
                    continue;
                }
                AdminConsoleDtos.LlmJobRow current = llmJobRepository.findJob(job.jobId())
                        .orElseThrow(() -> new IllegalStateException("llm job missing while running: " + job.jobId()));
                if ("pause_requested".equalsIgnoreCase(current.jobStatus())) {
                    llmJobRepository.markRemainingItemsCancelled(job.jobId());
                    llmJobRepository.markPaused(job.jobId(), objectMapper.valueToTree(Map.of("reason", "pause_requested")));
                    return;
                }
                if ("cancel_requested".equalsIgnoreCase(current.jobStatus())) {
                    llmJobRepository.markRemainingItemsCancelled(job.jobId());
                    llmJobRepository.markCancelled(job.jobId(), objectMapper.valueToTree(Map.of("reason", "cancel_requested")));
                    markRelatedCancelled(current);
                    return;
                }
                llmJobRepository.markItemRunning(item.jobItemId());
                long commandStartedAtNano = System.nanoTime();
                RagDtos.ExperimentCommandResponse response = experimentPipelineService.run(
                        new RagDtos.ExperimentCommandRequest(command, experiment)
                );
                commandDurationMs.put(command, elapsedMillis(commandStartedAtNano));
                resultByCommand.put(command, response.summary());
                AdminConsoleDtos.LlmJobRow afterRun = llmJobRepository.findJob(job.jobId())
                        .orElseThrow(() -> new IllegalStateException("llm job missing after command run: " + job.jobId()));
                if ("cancel_requested".equalsIgnoreCase(afterRun.jobStatus())) {
                    llmJobRepository.markItemCompleted(item.jobItemId(), response.summary());
                    llmJobRepository.markRemainingItemsCancelled(job.jobId());
                    llmJobRepository.markCancelled(
                            job.jobId(),
                            objectMapper.valueToTree(Map.of("reason", "cancel_requested_during_command", "last_command", command))
                    );
                    markRelatedCancelled(afterRun);
                    return;
                }
                if (response.exitCode() != 0) {
                    llmJobRepository.markItemFailed(
                            item.jobItemId(),
                            trimError(response.stderr(), response.stdout()),
                            response.summary()
                    );
                    throw new IllegalStateException(command + " failed: " + trimError(response.stderr(), response.stdout()));
                }
                llmJobRepository.markItemCompleted(item.jobItemId(), response.summary());
                processed += 1;
                llmJobRepository.markJobProgress(
                        job.jobId(),
                        processed,
                        totalItems,
                        (processed * 100.0) / totalItems,
                        objectMapper.valueToTree(Map.of("last_command", command))
                );
            }

            finalizeRelatedRows(job, resultByCommand, commandDurationMs, elapsedMillis(jobStartedAtNano));
            llmJobRepository.markJobCompleted(job.jobId(), objectMapper.valueToTree(resultByCommand));
        } catch (RuntimeException exception) {
            handleJobFailure(job, resultByCommand, exception);
        }
    }

    private void finalizeRelatedRows(
            AdminConsoleDtos.LlmJobRow job,
            Map<String, JsonNode> resultByCommand,
            Map<String, Long> commandDurationMs,
            long totalJobDurationMs
    ) {
        if ("GENERATE_SYNTHETIC_QUERY".equals(job.jobType()) && job.generationBatchId() != null) {
            JsonNode summary = resultByCommand.getOrDefault("generate-queries", objectMapper.createObjectNode());
            UUID sourceRunId = asUuid(summary.path("experiment_run_id").asText(null));
            int generatedCount = summary.path("generated_queries").asInt(0);
            adminConsoleRepository.completeGenerationBatch(job.generationBatchId(), sourceRunId, generatedCount, summary);
            if (sourceRunId != null) {
                adminConsoleRepository.syncSyntheticQueryBatchProvenance(job.generationBatchId(), sourceRunId);
            }
            return;
        }
        if ("RUN_LLM_SELF_EVAL".equals(job.jobType()) && job.gatingBatchId() != null) {
            JsonNode summary = resultByCommand.getOrDefault("gate-queries", objectMapper.createObjectNode());
            UUID sourceRunId = asUuid(summary.path("experiment_run_id").asText(null));
            int processed = summary.path("processed_queries").asInt(0);
            int accepted = summary.path("accepted_queries").asInt(0);
            int rejected = summary.path("rejected_queries").asInt(0);
            JsonNode rejectionReasons = summary.path("rejection_reasons");
            adminConsoleRepository.completeGatingBatch(
                    job.gatingBatchId(),
                    sourceRunId,
                    processed,
                    accepted,
                    rejected,
                    rejectionReasons.isMissingNode() ? objectMapper.createObjectNode() : rejectionReasons
            );
            if (sourceRunId != null) {
                adminConsoleRepository.syncGatingBatchResults(job.gatingBatchId(), sourceRunId);
            }
            return;
        }
        if ("RUN_RAG_TEST".equals(job.jobType()) && job.ragTestRunId() != null) {
            JsonNode retrievalSummary = resultByCommand.getOrDefault("eval-retrieval", objectMapper.createObjectNode());
            JsonNode answerSummary = resultByCommand.getOrDefault("eval-answer", objectMapper.createObjectNode());
            JsonNode memorySummary = resultByCommand.getOrDefault("build-memory", objectMapper.createObjectNode());
            JsonNode summaryRows = retrievalSummary.path("summary");
            JsonNode latencyRows = retrievalSummary.path("latency_summary");
            Map<String, JsonNode> summaryByMode = rowsByMode(summaryRows);
            Map<String, JsonNode> latencyByMode = rowsByMode(latencyRows);
            JsonNode summaryRow = selectRepresentativeModeRow(summaryByMode);
            JsonNode latencyRow = selectLatencyRow(summaryRow, latencyByMode);
            JsonNode answerMetrics = answerSummary.path("summary");

            Double recall = nullableDouble(summaryRow.path("recall@5"));
            Double hit = nullableDouble(summaryRow.path("hit@5"));
            Double mrr = nullableDouble(summaryRow.path("mrr@10"));
            Double ndcg = nullableDouble(summaryRow.path("ndcg@10"));
            Double latency = nullableDouble(latencyRow.path("avg_latency_ms"));
            Double adoption = nullableDouble(summaryRow.path("adoption_rate"));
            Double rejectionRate = nullableDouble(summaryRow.path("rewrite_rejection_rate"));
            Double avgConfidenceDelta = nullableDouble(summaryRow.path("avg_confidence_delta"));
            Map<String, Object> performance = buildRagPerformanceMetrics(
                    summaryByMode,
                    latencyByMode,
                    commandDurationMs,
                    totalJobDurationMs
            );

            Map<String, Object> metrics = new LinkedHashMap<>();
            metrics.put("retrieval", retrievalSummary);
            metrics.put("answer", answerSummary);
            metrics.put("memory", memorySummary);
            metrics.put("retrieval_by_mode", objectMapper.valueToTree(summaryByMode));
            metrics.put("latency_by_mode", objectMapper.valueToTree(latencyByMode));
            metrics.put("representative_mode", summaryRow.path("mode").asText(""));
            metrics.put("performance", objectMapper.valueToTree(performance));
            JsonNode metricsJson = objectMapper.valueToTree(metrics);
            UUID sourceExperimentRunId = asUuid(retrievalSummary.path("experiment_run_id").asText(null));
            adminConsoleRepository.upsertRagSummary(
                    job.ragTestRunId(),
                    recall,
                    hit,
                    mrr,
                    ndcg,
                    latency,
                    adoption,
                    rejectionRate,
                    avgConfidenceDelta,
                    answerMetrics.isMissingNode() ? objectMapper.createObjectNode() : answerMetrics,
                    metricsJson
            );
            JsonNode runConfig = adminConsoleRepository.findRagTestRunConfig(job.ragTestRunId())
                    .orElseGet(objectMapper::createObjectNode);
            String snapshotId = firstNonBlank(
                    runConfig.path("snapshot_id").asText(""),
                    runConfig.path("source_gating_batch_id").asText(""),
                    "UNSPECIFIED"
            );
            JsonNode generationStrategies = runConfig.path("memory_generation_strategies").isArray()
                    ? runConfig.path("memory_generation_strategies")
                    : runConfig.path("source_generation_strategies");
            if (!generationStrategies.isArray()) {
                generationStrategies = objectMapper.createArrayNode();
            }
            Map<String, Object> gatingConfig = new LinkedHashMap<>();
            gatingConfig.put("gating_preset", runConfig.path("gating_preset").asText(""));
            gatingConfig.put("gating_applied", runConfig.path("gating_applied").asBoolean(true));
            gatingConfig.put("comparison_snapshots", nullableJson(runConfig.get("comparison_snapshots")));

            Map<String, Object> retrievalConfig = new LinkedHashMap<>();
            retrievalConfig.put("retrieval_top_k", runConfig.path("retrieval_top_k").asInt(20));
            retrievalConfig.put("rerank_top_n", runConfig.path("rerank_top_n").asInt(5));
            retrievalConfig.put("retrieval_modes", nullableJson(runConfig.get("retrieval_modes")));
            retrievalConfig.put("active_modes", nullableJson(retrievalSummary.get("active_modes")));

            Map<String, Object> rewriteConfig = new LinkedHashMap<>();
            rewriteConfig.put("rewrite_enabled", runConfig.path("rewrite_enabled").asBoolean(true));
            rewriteConfig.put("selective_rewrite", runConfig.path("selective_rewrite").asBoolean(true));
            rewriteConfig.put("use_session_context", runConfig.path("use_session_context").asBoolean(false));
            rewriteConfig.put("rewrite_threshold", runConfig.path("rewrite_threshold").asDouble(0.05));

            Integer memorySize = memorySummary.path("memory_entries_built").isNumber()
                    ? memorySummary.path("memory_entries_built").asInt()
                    : null;
            String datasetVersion = adminConsoleRepository.findRagDatasetVersion(job.ragTestRunId());
            adminConsoleRepository.upsertRagExperimentRecord(
                    job.ragTestRunId(),
                    snapshotId,
                    generationStrategies,
                    objectMapper.valueToTree(gatingConfig),
                    memorySize,
                    objectMapper.valueToTree(retrievalConfig),
                    objectMapper.valueToTree(rewriteConfig),
                    datasetVersion,
                    Instant.now(),
                    metricsJson
            );
            List<AdminConsoleDtos.RagTestResultDetailRow> details = loadRewriteCasesForRun(job.ragTestRunId(), job.experimentName());
            adminConsoleRepository.replaceRagDetailRows(job.ragTestRunId(), details);
            adminConsoleRepository.completeRagTestRun(job.ragTestRunId(), metricsJson, sourceExperimentRunId);
        }
    }

    private void handleJobFailure(
            AdminConsoleDtos.LlmJobRow job,
            Map<String, JsonNode> resultByCommand,
            RuntimeException exception
    ) {
        int nextRetry = (job.retryCount() == null ? 0 : job.retryCount()) + 1;
        int maxRetries = job.maxRetries() == null ? 0 : job.maxRetries();
        String errorMessage = exception.getMessage() == null ? "job_failed" : exception.getMessage();
        if (nextRetry <= maxRetries) {
            long backoffSeconds = (long) Math.min(60, Math.pow(2, nextRetry) * 2);
            Instant nextRunAt = Instant.now().plusSeconds(backoffSeconds);
            llmJobRepository.prepareItemsForRetry(job.jobId());
            llmJobRepository.queueJobWithBackoff(job.jobId(), nextRetry, nextRunAt, errorMessage);
            scheduleEnqueue(job.jobId(), backoffSeconds);
            return;
        }

        Integer purgedSyntheticQueries = null;
        String cleanupError = null;
        if (job.generationBatchId() != null) {
            try {
                purgedSyntheticQueries = adminConsoleRepository.deleteSyntheticQueriesByGenerationBatch(job.generationBatchId());
            } catch (RuntimeException cleanupException) {
                cleanupError = cleanupException.getMessage();
                log.error(
                        "Failed to purge synthetic queries for failed generation batch. batchId={}, jobId={}",
                        job.generationBatchId(),
                        job.jobId(),
                        cleanupException
                );
            }
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("error", errorMessage);
        payload.put("steps", resultByCommand);
        if (purgedSyntheticQueries != null || cleanupError != null) {
            Map<String, Object> cleanupPayload = new LinkedHashMap<>();
            if (purgedSyntheticQueries != null) {
                cleanupPayload.put("purged_synthetic_queries", purgedSyntheticQueries);
            }
            if (cleanupError != null && !cleanupError.isBlank()) {
                cleanupPayload.put("cleanup_error", cleanupError);
            }
            payload.put("cleanup", cleanupPayload);
        }
        JsonNode resultJson = objectMapper.valueToTree(payload);
        llmJobRepository.markJobFailed(job.jobId(), errorMessage, resultJson);
        if (job.generationBatchId() != null) {
            adminConsoleRepository.failGenerationBatch(job.generationBatchId(), errorMessage, resultJson);
        }
        if (job.gatingBatchId() != null) {
            adminConsoleRepository.failGatingBatch(job.gatingBatchId(), errorMessage);
        }
        if (job.ragTestRunId() != null) {
            adminConsoleRepository.failRagTestRun(job.ragTestRunId(), errorMessage);
        }
    }

    private void markRelatedCancelled(AdminConsoleDtos.LlmJobRow job) {
        if (job.generationBatchId() != null) {
            adminConsoleRepository.cancelGenerationBatch(job.generationBatchId(), "cancel requested by user");
        }
        if (job.gatingBatchId() != null) {
            adminConsoleRepository.cancelGatingBatch(job.gatingBatchId(), "cancel requested by user");
        }
        if (job.ragTestRunId() != null) {
            adminConsoleRepository.cancelRagTestRun(job.ragTestRunId(), "cancel requested by user");
        }
    }

    private void scheduleEnqueue(UUID jobId, long delaySeconds) {
        long safeDelaySeconds = Math.max(1L, delaySeconds);
        CompletableFuture.runAsync(
                () -> enqueue(jobId),
                CompletableFuture.delayedExecutor(safeDelaySeconds, TimeUnit.SECONDS)
        );
    }

    private List<AdminConsoleDtos.RagTestResultDetailRow> loadRewriteCasesForRun(UUID runId, String experimentName) {
        if (experimentName == null || experimentName.isBlank()) {
            return List.of();
        }
        Path casePath = resolveRepoRoot()
                .resolve("data/reports")
                .resolve("rewrite_cases_" + experimentName + ".json")
                .normalize();
        if (!Files.exists(casePath)) {
            return List.of();
        }
        try {
            JsonNode root = objectMapper.readTree(Files.readString(casePath, StandardCharsets.UTF_8));
            if (!root.isArray() || root.isEmpty()) {
                return List.of();
            }
            List<String> sampleIds = new ArrayList<>();
            for (JsonNode row : root) {
                String sampleId = row.path("sample_id").asText("");
                if (!sampleId.isBlank()) {
                    sampleIds.add(sampleId);
                }
            }
            Map<String, AdminConsoleRepository.EvalSampleMeta> sampleMetaMap = new LinkedHashMap<>();
            for (AdminConsoleRepository.EvalSampleMeta meta : adminConsoleRepository.findEvalSampleMeta(sampleIds)) {
                sampleMetaMap.put(meta.sampleId(), meta);
            }

            List<AdminConsoleDtos.RagTestResultDetailRow> details = new ArrayList<>();
            for (JsonNode row : root) {
                String sampleId = row.path("sample_id").asText("");
                if (sampleId.isBlank()) {
                    continue;
                }
                AdminConsoleRepository.EvalSampleMeta meta = sampleMetaMap.get(sampleId);
                Map<String, Object> metricContributionPayload = new LinkedHashMap<>();
                metricContributionPayload.put("mode", row.path("mode").asText(""));
                metricContributionPayload.put("raw_mrr", row.path("raw_mrr").asDouble(0.0));
                metricContributionPayload.put("mode_mrr", row.path("mode_mrr").asDouble(0.0));
                metricContributionPayload.put("raw_ndcg", row.path("raw_ndcg").asDouble(0.0));
                metricContributionPayload.put("mode_ndcg", row.path("mode_ndcg").asDouble(0.0));
                metricContributionPayload.put("raw_confidence", row.path("raw_confidence").asDouble(0.0));
                metricContributionPayload.put("best_candidate_confidence", row.path("best_candidate_confidence").asDouble(0.0));
                metricContributionPayload.put("confidence_delta", row.path("confidence_delta").asDouble(0.0));
                metricContributionPayload.put("rewrite_reason", row.path("rewrite_reason").asText(""));
                JsonNode metricContribution = objectMapper.valueToTree(metricContributionPayload);
                boolean hitTarget = row.path("mode_mrr").asDouble(0.0) > 0.0 || row.path("mode_ndcg").asDouble(0.0) > 0.0;
                details.add(new AdminConsoleDtos.RagTestResultDetailRow(
                        UUID.randomUUID(),
                        runId,
                        sampleId,
                        meta != null ? meta.queryCategory() : null,
                        resolveEvalSampleQueryText(meta),
                        row.path("final_query").asText(""),
                        row.path("rewrite_applied").asBoolean(false),
                        nullableJson(row.get("memory_top_n")),
                        nullableJson(row.get("rewrite_candidates")),
                        nullableJson(row.get("retrieved_top_k")),
                        metricContribution,
                        hitTarget
                ));
            }
            return details;
        } catch (IOException exception) {
            return List.of();
        }
    }

    private Path resolveRepoRoot() {
        Path configured = Path.of(pipelineProperties.repoRoot()).toAbsolutePath().normalize();
        if (Files.exists(configured.resolve("pipeline/cli.py"))) {
            return configured;
        }
        Path parent = configured.getParent();
        if (parent != null && Files.exists(parent.resolve("pipeline/cli.py"))) {
            return parent;
        }
        throw new IllegalStateException("failed to resolve repository root for llm jobs");
    }

    private String resolveEvalSampleQueryText(AdminConsoleRepository.EvalSampleMeta meta) {
        if (meta == null) {
            return "";
        }
        if ("en".equalsIgnoreCase(meta.queryLanguage()) && meta.userQueryEn() != null && !meta.userQueryEn().isBlank()) {
            return meta.userQueryEn();
        }
        if (meta.userQueryKo() != null && !meta.userQueryKo().isBlank()) {
            return meta.userQueryKo();
        }
        return meta.userQueryEn() == null ? "" : meta.userQueryEn();
    }

    private Map<String, Object> buildRagPerformanceMetrics(
            Map<String, JsonNode> summaryByMode,
            Map<String, JsonNode> latencyByMode,
            Map<String, Long> commandDurationMs,
            long totalJobDurationMs
    ) {
        long buildMemoryMs = stageDurationMs(commandDurationMs, "build-memory");
        long evalRetrievalMs = stageDurationMs(commandDurationMs, "eval-retrieval");
        long evalAnswerMs = stageDurationMs(commandDurationMs, "eval-answer");
        long stageTotalMs = buildMemoryMs + evalRetrievalMs + evalAnswerMs;
        long effectiveTotalJobDurationMs = Math.max(totalJobDurationMs, stageTotalMs);
        long orchestrationOverheadMs = Math.max(0L, effectiveTotalJobDurationMs - stageTotalMs);

        JsonNode representativeSummary = selectRepresentativeModeRow(summaryByMode);
        JsonNode representativeLatency = selectLatencyRow(representativeSummary, latencyByMode);
        Double representativeLatencyAvg = nullableDouble(representativeLatency.path("avg_latency_ms"));
        Double representativeLatencyP95 = nullableDouble(representativeLatency.path("p95_latency_ms"));

        String rewriteMode = selectRewriteMode(summaryByMode);
        Double rewriteAdoptionRate = null;
        if (rewriteMode != null) {
            JsonNode rewriteRow = summaryByMode.get(rewriteMode);
            rewriteAdoptionRate = nullableDouble(rewriteRow == null ? null : rewriteRow.path("adoption_rate"));
        }
        Double rewriteOverheadAvgLatencyMs = computeRewriteOverheadLatencyMs(latencyByMode, rewriteMode);

        Map<String, Object> latencyByModeMs = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> entry : latencyByMode.entrySet()) {
            JsonNode row = entry.getValue();
            if (row == null || row.isMissingNode()) {
                continue;
            }
            Map<String, Object> latency = new LinkedHashMap<>();
            latency.put("avg_latency_ms", nullableDouble(row.path("avg_latency_ms")));
            latency.put("p95_latency_ms", nullableDouble(row.path("p95_latency_ms")));
            latencyByModeMs.put(entry.getKey(), latency);
        }

        Map<String, Object> stageDurationMs = new LinkedHashMap<>();
        stageDurationMs.put("build_memory_ms", buildMemoryMs);
        stageDurationMs.put("eval_retrieval_ms", evalRetrievalMs);
        stageDurationMs.put("eval_answer_ms", evalAnswerMs);
        stageDurationMs.put("stage_total_ms", stageTotalMs);

        Map<String, Object> performance = new LinkedHashMap<>();
        performance.put("total_duration_ms", effectiveTotalJobDurationMs);
        performance.put("orchestration_overhead_ms", orchestrationOverheadMs);
        performance.put("stage_duration_ms", stageDurationMs);
        performance.put("representative_mode", representativeSummary.path("mode").asText(""));
        performance.put("representative_mode_latency_avg_ms", representativeLatencyAvg);
        performance.put("representative_mode_latency_p95_ms", representativeLatencyP95);
        performance.put("rewrite_mode", rewriteMode);
        performance.put("rewrite_adoption_rate", rewriteAdoptionRate);
        performance.put("rewrite_overhead_avg_latency_ms", rewriteOverheadAvgLatencyMs);
        performance.put("latency_by_mode_ms", latencyByModeMs);
        return performance;
    }

    private Double computeRewriteOverheadLatencyMs(Map<String, JsonNode> latencyByMode, String rewriteMode) {
        if (rewriteMode == null || rewriteMode.isBlank()) {
            return null;
        }
        JsonNode rawRow = latencyByMode.get("raw_only");
        JsonNode rewriteRow = latencyByMode.get(rewriteMode);
        Double rawAvg = nullableDouble(rawRow == null ? null : rawRow.path("avg_latency_ms"));
        Double rewriteAvg = nullableDouble(rewriteRow == null ? null : rewriteRow.path("avg_latency_ms"));
        if (rawAvg == null || rewriteAvg == null) {
            return null;
        }
        return rewriteAvg - rawAvg;
    }

    private String selectRewriteMode(Map<String, JsonNode> summaryByMode) {
        for (String mode : List.of("selective_rewrite_with_session", "selective_rewrite", "rewrite_always")) {
            JsonNode row = summaryByMode.get(mode);
            if (row != null && !row.isMissingNode()) {
                return mode;
            }
        }
        return null;
    }

    private long stageDurationMs(Map<String, Long> commandDurationMs, String command) {
        if (commandDurationMs == null || command == null || command.isBlank()) {
            return 0L;
        }
        Long value = commandDurationMs.get(command);
        if (value == null) {
            return 0L;
        }
        return Math.max(0L, value);
    }

    private long elapsedMillis(long startedAtNano) {
        long elapsedNs = System.nanoTime() - startedAtNano;
        if (elapsedNs <= 0L) {
            return 0L;
        }
        return TimeUnit.NANOSECONDS.toMillis(elapsedNs);
    }

    private JsonNode nullableJson(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return objectMapper.createArrayNode();
        }
        return node;
    }

    private Map<String, JsonNode> rowsByMode(JsonNode node) {
        Map<String, JsonNode> rows = new LinkedHashMap<>();
        if (node == null || !node.isArray()) {
            return rows;
        }
        for (JsonNode row : node) {
            if (row == null || !row.isObject()) {
                continue;
            }
            String mode = row.path("mode").asText("").trim();
            if (mode.isEmpty()) {
                continue;
            }
            rows.put(mode, row);
        }
        return rows;
    }

    private JsonNode selectRepresentativeModeRow(Map<String, JsonNode> rowsByMode) {
        if (rowsByMode.isEmpty()) {
            return objectMapper.createObjectNode();
        }
        List<String> priority = List.of(
                "selective_rewrite",
                "selective_rewrite_with_session",
                "memory_only_full_gating",
                "memory_only_gated",
                "raw_only"
        );
        for (String candidate : priority) {
            JsonNode row = rowsByMode.get(candidate);
            if (row != null && !row.isMissingNode()) {
                return row;
            }
        }
        return rowsByMode.values().iterator().next();
    }

    private JsonNode selectLatencyRow(JsonNode summaryRow, Map<String, JsonNode> latencyByMode) {
        if (summaryRow == null || summaryRow.isMissingNode() || latencyByMode.isEmpty()) {
            return objectMapper.createObjectNode();
        }
        String mode = summaryRow.path("mode").asText("");
        JsonNode row = latencyByMode.get(mode);
        if (row == null || row.isMissingNode()) {
            return objectMapper.createObjectNode();
        }
        return row;
    }

    private JsonNode firstArrayItem(JsonNode node) {
        if (node == null || !node.isArray() || node.isEmpty()) {
            return objectMapper.createObjectNode();
        }
        return node.get(0);
    }

    private Double nullableDouble(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        return node.asDouble();
    }

    private String firstNonBlank(String... values) {
        if (values == null || values.length == 0) {
            return "";
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }

    private UUID asUuid(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return UUID.fromString(value.trim());
    }

    private String trimError(String stderr, String stdout) {
        String target = stderr != null && !stderr.isBlank() ? stderr : stdout;
        if (target == null) {
            return "";
        }
        String normalized = target.trim();
        if (normalized.length() <= 2000) {
            return normalized;
        }
        return normalized.substring(normalized.length() - 2000);
    }
}
