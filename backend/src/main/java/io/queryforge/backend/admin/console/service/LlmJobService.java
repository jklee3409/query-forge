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
                2,
                createdBy
        );
        llmJobRepository.createJobItem(jobId, 1, "gate-queries", commandArgs, 2);
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
        int totalItems = Math.max(1, items.size());
        int processed = 0;
        Map<String, JsonNode> resultByCommand = new LinkedHashMap<>();

        try {
            for (AdminConsoleDtos.LlmJobItemRow item : items) {
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
                String command = item.payloadJson().path("command").asText(item.itemType());
                String experiment = item.payloadJson().path("experiment").asText(job.experimentName());
                RagDtos.ExperimentCommandResponse response = experimentPipelineService.run(
                        new RagDtos.ExperimentCommandRequest(command, experiment)
                );
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

            finalizeRelatedRows(job, resultByCommand);
            llmJobRepository.markJobCompleted(job.jobId(), objectMapper.valueToTree(resultByCommand));
        } catch (RuntimeException exception) {
            handleJobFailure(job, resultByCommand, exception);
        }
    }

    private void finalizeRelatedRows(AdminConsoleDtos.LlmJobRow job, Map<String, JsonNode> resultByCommand) {
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
            JsonNode summaryRow = firstArrayItem(retrievalSummary.path("summary"));
            JsonNode latencyRow = firstArrayItem(retrievalSummary.path("latency_summary"));
            JsonNode answerMetrics = answerSummary.path("summary");

            Double recall = nullableDouble(summaryRow.path("recall@5"));
            Double hit = nullableDouble(summaryRow.path("hit@5"));
            Double mrr = nullableDouble(summaryRow.path("mrr@10"));
            Double ndcg = nullableDouble(summaryRow.path("ndcg@10"));
            Double latency = nullableDouble(latencyRow.path("avg_latency_ms"));
            Double adoption = nullableDouble(summaryRow.path("adoption_rate"));
            Double rejectionRate = nullableDouble(summaryRow.path("rewrite_rejection_rate"));
            Double avgConfidenceDelta = nullableDouble(summaryRow.path("avg_confidence_delta"));

            Map<String, Object> metrics = new LinkedHashMap<>();
            metrics.put("retrieval", retrievalSummary);
            metrics.put("answer", answerSummary);
            metrics.put("memory", memorySummary);
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
        JsonNode resultJson = objectMapper.valueToTree(
                Map.of(
                        "error", exception.getMessage() == null ? "job_failed" : exception.getMessage(),
                        "steps", resultByCommand
                )
        );
        if (nextRetry <= maxRetries) {
            long backoffSeconds = (long) Math.min(60, Math.pow(2, nextRetry) * 2);
            Instant nextRunAt = Instant.now().plusSeconds(backoffSeconds);
            llmJobRepository.queueJobWithBackoff(job.jobId(), nextRetry, nextRunAt, exception.getMessage());
            scheduleEnqueue(job.jobId(), backoffSeconds);
            return;
        }
        llmJobRepository.markJobFailed(job.jobId(), exception.getMessage(), resultJson);
        if (job.generationBatchId() != null) {
            adminConsoleRepository.failGenerationBatch(job.generationBatchId(), exception.getMessage(), resultJson);
        }
        if (job.gatingBatchId() != null) {
            adminConsoleRepository.failGatingBatch(job.gatingBatchId(), exception.getMessage());
        }
        if (job.ragTestRunId() != null) {
            adminConsoleRepository.failRagTestRun(job.ragTestRunId(), exception.getMessage());
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
                JsonNode metricContribution = objectMapper.valueToTree(Map.of(
                        "mode", row.path("mode").asText(""),
                        "raw_mrr", row.path("raw_mrr").asDouble(0.0),
                        "mode_mrr", row.path("mode_mrr").asDouble(0.0),
                        "raw_ndcg", row.path("raw_ndcg").asDouble(0.0),
                        "mode_ndcg", row.path("mode_ndcg").asDouble(0.0)
                ));
                boolean hitTarget = row.path("mode_mrr").asDouble(0.0) > 0.0 || row.path("mode_ndcg").asDouble(0.0) > 0.0;
                details.add(new AdminConsoleDtos.RagTestResultDetailRow(
                        UUID.randomUUID(),
                        runId,
                        sampleId,
                        meta != null ? meta.queryCategory() : null,
                        meta != null ? meta.userQueryKo() : sampleId,
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

    private JsonNode nullableJson(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return objectMapper.createArrayNode();
        }
        return node;
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
        if (normalized.length() <= 500) {
            return normalized;
        }
        return normalized.substring(0, 500);
    }
}
