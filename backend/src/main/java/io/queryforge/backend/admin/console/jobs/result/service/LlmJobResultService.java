package io.queryforge.backend.admin.console.jobs.result.service;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.queryforge.backend.admin.console.evaluation.anchor.dto.RagRewriteAnchorDtos;
import io.queryforge.backend.admin.console.evaluation.anchor.repository.RagRewriteAnchorRepository;
import io.queryforge.backend.admin.console.evaluation.anchor.service.RagRewriteAnchorEvalService;
import io.queryforge.backend.admin.console.evaluation.anchor.support.CanonicalAnchorVersionMetadata;
import io.queryforge.backend.admin.console.evaluation.dataset.dto.EvalDatasetDtos;
import io.queryforge.backend.admin.console.evaluation.dataset.repository.AdminEvalDatasetRepository;
import io.queryforge.backend.admin.console.evaluation.run.dto.RagTestDtos;
import io.queryforge.backend.admin.console.evaluation.run.repository.RagTestRunRepository;
import io.queryforge.backend.admin.console.gating.repository.GatingRepository;
import io.queryforge.backend.admin.console.jobs.dto.LlmJobDtos;
import io.queryforge.backend.admin.console.jobs.model.LlmJobType;
import io.queryforge.backend.admin.console.jobs.repository.LlmJobRepository;
import io.queryforge.backend.admin.console.synthetic.repository.SyntheticGenerationRepository;
import io.queryforge.backend.admin.pipeline.configuration.AdminPipelineProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class LlmJobResultService {

    private final RagRewriteAnchorRepository anchorRepository;
    private final AdminEvalDatasetRepository evalDatasetRepository;
    private final RagTestRunRepository ragTestRunRepository;
    private final GatingRepository gatingRepository;
    private final SyntheticGenerationRepository generationRepository;
    private final AdminPipelineProperties pipelineProperties;
    private final ObjectMapper objectMapper;
    private final RagRewriteAnchorEvalService ragRewriteAnchorEvalService;
    private final LlmJobRepository llmJobRepository;
    private final PlatformTransactionManager transactionManager;

    public String finalizeJob(LlmJobDtos.LlmJobRow job, Map<String, JsonNode> resultByCommand) {
        Runnable saveRelatedResults = prepareRelatedResults(job, resultByCommand);
        JsonNode jobResult = objectMapper.valueToTree(resultByCommand);

        return new TransactionTemplate(transactionManager).execute(status -> {
            String jobStatus = llmJobRepository.lockJobStatus(job.jobId()).orElse(null);
            if (!"running".equals(jobStatus)) {
                return jobStatus;
            }
            if (saveRelatedResults != null) {
                saveRelatedResults.run();
            }
            llmJobRepository.markJobCompleted(job.jobId(), jobResult);
            return "completed";
        });
    }

    private Runnable prepareRelatedResults(LlmJobDtos.LlmJobRow job, Map<String, JsonNode> resultByCommand) {
        if (LlmJobType.GENERATE_SYNTHETIC_QUERY.name().equals(job.jobType()) && job.generationBatchId() != null) {
            JsonNode summary = resultByCommand.getOrDefault("generate-queries", objectMapper.createObjectNode());
            UUID sourceRunId = asUuid(summary.path("experiment_run_id").asText(null));
            int generatedCount = summary.path("generated_queries").asInt(0);
            return () -> {
                if (sourceRunId != null) {
                    generationRepository.syncSyntheticQueryBatchProvenance(job.generationBatchId(), sourceRunId);
                }
                generationRepository.completeGenerationBatch(job.generationBatchId(), sourceRunId, generatedCount, summary);
            };
        }
        if (LlmJobType.RUN_LLM_SELF_EVAL.name().equals(job.jobType()) && job.gatingBatchId() != null) {
            JsonNode summary = resultByCommand.getOrDefault("gate-queries", objectMapper.createObjectNode());
            UUID sourceRunId = asUuid(summary.path("experiment_run_id").asText(null));
            int processed = summary.path("processed_queries").asInt(0);
            int accepted = summary.path("accepted_queries").asInt(0);
            int rejected = summary.path("rejected_queries").asInt(0);
            JsonNode rejectionReasons = summary.path("rejection_reasons");
            return () -> {
                if (sourceRunId != null) {
                    gatingRepository.syncGatingBatchResults(job.gatingBatchId(), sourceRunId);
                }
                gatingRepository.completeGatingBatch(
                        job.gatingBatchId(),
                        sourceRunId,
                        processed,
                        accepted,
                        rejected,
                        rejectionReasons.isMissingNode() ? objectMapper.createObjectNode() : rejectionReasons
                );
            };
        }
        if (LlmJobType.RUN_RAG_TEST.name().equals(job.jobType()) && job.ragTestRunId() != null) {
            return prepareRagResults(job, resultByCommand);
        }
        return null;
    }

    private Runnable prepareRagResults(LlmJobDtos.LlmJobRow job, Map<String, JsonNode> resultByCommand) {
        UUID runId = job.ragTestRunId();
        JsonNode retrievalSummary = resultByCommand.getOrDefault("eval-retrieval", objectMapper.createObjectNode());
        JsonNode answerSummary = resultByCommand.getOrDefault("eval-answer", objectMapper.createObjectNode());
        JsonNode memorySummary = resultByCommand.getOrDefault("build-memory", objectMapper.createObjectNode());
        JsonNode summaryRows = retrievalSummary.path("summary");
        Map<String, JsonNode> summaryByMode = rowsByMode(summaryRows);
        JsonNode summaryRow = selectRepresentativeModeRow(summaryByMode);
        JsonNode answerMetrics = answerSummary.path("summary");
        JsonNode sanitizedRetrievalSummary = sanitizeRetrievalSummary(retrievalSummary);

        Double recall = nullableDouble(summaryRow.path("recall@5"));
        Double hit = nullableDouble(summaryRow.path("hit@5"));
        Double mrr = nullableDouble(summaryRow.path("mrr@10"));
        Double ndcg = nullableDouble(summaryRow.path("ndcg@10"));
        Double latency = null;
        Double adoption = null;
        Double rejectionRate = null;
        Double avgConfidenceDelta = null;
        Map<String, Object> performance = buildRagPerformanceMetrics(answerSummary);
        JsonNode runConfig = ragTestRunRepository.findRagTestRunConfig(runId)
                .orElseGet(objectMapper::createObjectNode);
        Map<String, Object> canonicalAnchorVersions = CanonicalAnchorVersionMetadata.fromConfig(runConfig);

        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.putAll(canonicalAnchorVersions);
        metrics.put("canonical_anchor_versions", canonicalAnchorVersions);
        metrics.put(
                "multi_source_anchor_expansion_enabled",
                runConfig.path("multi_source_anchor_expansion_enabled").asBoolean(false)
        );
        metrics.put("retrieval", sanitizedRetrievalSummary);
        metrics.put("answer", answerSummary);
        metrics.put("memory", memorySummary);
        metrics.put("performance", objectMapper.valueToTree(performance));
        JsonNode metricsJson = objectMapper.valueToTree(metrics);
        UUID sourceExperimentRunId = asUuid(retrievalSummary.path("experiment_run_id").asText(null));
        String snapshotId = firstNonBlank(
                runConfig.path("snapshot_id").asText(""),
                runConfig.path("source_gating_batch_id").asText(""),
                "UNSPECIFIED"
        );
        JsonNode configuredStrategies = runConfig.path("memory_generation_strategies").isArray()
                ? runConfig.path("memory_generation_strategies")
                : runConfig.path("source_generation_strategies");
        JsonNode generationStrategies = configuredStrategies.isArray() ? configuredStrategies : objectMapper.createArrayNode();
        Map<String, Object> gatingConfig = new LinkedHashMap<>();
        gatingConfig.putAll(canonicalAnchorVersions);
        gatingConfig.put("gating_preset", runConfig.path("gating_preset").asText(""));
        gatingConfig.put("gating_applied", runConfig.path("gating_applied").asBoolean(true));
        gatingConfig.put("comparison_snapshots", nullableJson(runConfig.get("comparison_snapshots")));
        gatingConfig.put("stage_cutoff_enabled", runConfig.path("stage_cutoff_enabled").asBoolean(false));
        gatingConfig.put("stage_cutoff_level", nullableText(runConfig.path("stage_cutoff_level")));
        gatingConfig.put(
                "stage_cutoff_source_gating_batch_id",
                nullableText(runConfig.path("stage_cutoff_source_gating_batch_id"))
        );

        Map<String, Object> retrievalConfig = new LinkedHashMap<>();
        retrievalConfig.putAll(canonicalAnchorVersions);
        retrievalConfig.put("retrieval_backend", runConfig.path("retrieval_backend").asText("local"));
        retrievalConfig.put(
                "embedding_model",
                firstNonBlank(
                        runConfig.path("chunk_embedding_model").asText(""),
                        runConfig.path("dense_embedding_model").asText(""),
                        runConfig.path("retriever_config").path("dense_embedding_model").asText("")
                )
        );
        retrievalConfig.put("vector_store", nullableText(runConfig.path("vector_store")));
        retrievalConfig.put("fallback_used", runConfig.path("fallback_used").asBoolean(false));
        retrievalConfig.put("retrieval_top_k", nullableInteger(runConfig.path("retrieval_top_k")));
        retrievalConfig.put("rerank_top_n", nullableInteger(runConfig.path("rerank_top_n")));
        retrievalConfig.put("retrieval_modes", nullableJson(runConfig.get("retrieval_modes")));
        retrievalConfig.put("active_modes", nullableJson(retrievalSummary.get("active_modes")));
        retrievalConfig.put("synthetic_free_baseline", runConfig.path("synthetic_free_baseline").asBoolean(false));
        retrievalConfig.put("retriever_config", nullableJson(runConfig.get("retriever_config")));

        Map<String, Object> rewriteConfig = new LinkedHashMap<>();
        rewriteConfig.putAll(canonicalAnchorVersions);
        rewriteConfig.put("rewrite_enabled", runConfig.path("rewrite_enabled").asBoolean(true));
        rewriteConfig.put("selective_rewrite", runConfig.path("selective_rewrite").asBoolean(true));
        rewriteConfig.put("use_session_context", runConfig.path("use_session_context").asBoolean(false));
        rewriteConfig.put("rewrite_threshold", nullableDouble(runConfig.path("rewrite_threshold")));
        rewriteConfig.put(
                "rewrite_anchor_injection_enabled",
                runConfig.path("rewrite_anchor_injection_enabled").asBoolean(false)
        );
        rewriteConfig.put(
                "multi_source_anchor_expansion_enabled",
                runConfig.path("multi_source_anchor_expansion_enabled").asBoolean(false)
        );
        rewriteConfig.put(
                "multi_source_anchor_relation_version",
                runConfig.path("multi_source_anchor_relation_version").asText("multi-source-anchor-v1")
        );
        rewriteConfig.put("multi_source_anchor_relation_types", nullableJson(runConfig.get("multi_source_anchor_relation_types")));
        rewriteConfig.put("multi_source_anchor_min_score", runConfig.path("multi_source_anchor_min_score").asDouble(0.72));
        rewriteConfig.put("multi_source_anchor_max_total", runConfig.path("multi_source_anchor_max_total").asInt(8));
        rewriteConfig.put("multi_source_anchor_max_per_seed", runConfig.path("multi_source_anchor_max_per_seed").asInt(2));
        rewriteConfig.put("rewrite_failure_policy", runConfig.path("rewrite_failure_policy").asText("fail_run"));
        rewriteConfig.put("rewrite_prompt_profile", runConfig.path("rewrite_prompt_profile").asText("ko"));

        Integer memorySize = memorySummary.path("memory_entries_built").isNumber()
                ? memorySummary.path("memory_entries_built").asInt()
                : null;
        String datasetVersion = ragTestRunRepository.findRagDatasetVersion(runId);
        JsonNode caseRows = readRewriteCases(job.experimentName());
        List<RagTestDtos.RagTestResultDetailRow> details = buildRewriteDetails(runId, caseRows);
        List<RagRewriteAnchorDtos.RagRewriteAnchorEvalRow> anchorEvalRows =
                ragRewriteAnchorEvalService.buildRowsForRun(runId, caseRows, details.stream()
                        .map(detail -> new RagRewriteAnchorDtos.AnchorEvaluationCase(
                                detail.detailId(), detail.ragTestRunId(), detail.sampleId(), detail.rawQuery(),
                                detail.rewriteQuery(), detail.rewriteApplied(), detail.memoryCandidates(),
                                detail.metricContribution()))
                        .toList());

        JsonNode gatingConfigJson = objectMapper.valueToTree(gatingConfig);
        JsonNode retrievalConfigJson = objectMapper.valueToTree(retrievalConfig);
        JsonNode rewriteConfigJson = objectMapper.valueToTree(rewriteConfig);
        return () -> {
            ragTestRunRepository.upsertRagSummary(
                    runId,
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
            ragTestRunRepository.upsertRagExperimentRecord(
                    runId,
                    snapshotId,
                    generationStrategies,
                    gatingConfigJson,
                    memorySize,
                    retrievalConfigJson,
                    rewriteConfigJson,
                    datasetVersion,
                    Instant.now(),
                    metricsJson
            );
            ragTestRunRepository.replaceRagDetailRows(runId, details);
            anchorRepository.replaceRagRewriteAnchorEvalRows(runId, anchorEvalRows);
            ragTestRunRepository.completeRagTestRun(runId, metricsJson, sourceExperimentRunId);
        };
    }

    private JsonNode readRewriteCases(String experimentName) {
        if (experimentName == null || experimentName.isBlank()) {
            throw new IllegalStateException("RAG job has no experiment name for its rewrite case report");
        }
        Path casePath = resolveRepoRoot()
                .resolve("data/reports")
                .resolve("rewrite_cases_" + experimentName + ".json")
                .normalize();
        try {
            JsonNode rows = objectMapper.reader()
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .readTree(Files.readString(casePath, StandardCharsets.UTF_8));
            if (rows == null || !rows.isArray()) {
                throw new IllegalStateException("expected a JSON array in rewrite case report: " + casePath);
            }
            return rows;
        } catch (IOException exception) {
            throw new IllegalStateException("failed to read rewrite case report: " + casePath, exception);
        }
    }

    private List<RagTestDtos.RagTestResultDetailRow> buildRewriteDetails(UUID runId, JsonNode root) {
        if (root.isEmpty()) {
            return List.of();
        }
        List<String> sampleIds = new ArrayList<>();
        for (JsonNode row : root) {
            String sampleId = row.path("sample_id").asText("");
            if (sampleId.isBlank()) {
                throw new IllegalStateException("missing sample_id in rewrite case report for RAG run: " + runId);
            }
            sampleIds.add(sampleId);
        }
        Map<String, EvalDatasetDtos.EvalSampleMeta> sampleMetaMap = new LinkedHashMap<>();
        for (EvalDatasetDtos.EvalSampleMeta meta : evalDatasetRepository.findEvalSampleMeta(sampleIds)) {
            sampleMetaMap.put(meta.sampleId(), meta);
        }

        List<RagTestDtos.RagTestResultDetailRow> details = new ArrayList<>();
        for (JsonNode row : root) {
            String sampleId = row.path("sample_id").asText("");
            EvalDatasetDtos.EvalSampleMeta meta = sampleMetaMap.get(sampleId);
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
            metricContributionPayload.put("raw_rank", row.hasNonNull("raw_rank") ? row.path("raw_rank").asInt() : null);
            metricContributionPayload.put("final_rank", row.hasNonNull("final_rank") ? row.path("final_rank").asInt() : null);
            metricContributionPayload.put("raw_metrics", nullableJson(row.get("raw_metrics")));
            metricContributionPayload.put("rewrite_metrics", nullableJson(row.get("rewrite_metrics")));
            metricContributionPayload.put("selected_rewrite", nullableJson(row.get("selected_rewrite")));
            metricContributionPayload.put("memory_hint_query", nullableText(row.get("memory_hint_query")));
            metricContributionPayload.put(
                    "memory_hint_retrieval_applied",
                    row.path("memory_hint_retrieval_applied").asBoolean(false)
            );
            metricContributionPayload.put("rewrite_llm_attempted", row.path("rewrite_llm_attempted").asBoolean(false));
            metricContributionPayload.put("rewrite_llm_succeeded", row.path("rewrite_llm_succeeded").asBoolean(false));
            metricContributionPayload.put(
                    "rewrite_heuristic_fallback_used",
                    row.path("rewrite_heuristic_fallback_used").asBoolean(false)
            );
            metricContributionPayload.put(
                    "final_rewrite_latency_ms",
                    nullableDouble(row.get("final_rewrite_latency_ms"))
            );
            metricContributionPayload.put(
                    "pure_rewrite_latency_ms",
                    nullableDouble(row.get("pure_rewrite_latency_ms"))
            );
            JsonNode metricContribution = objectMapper.valueToTree(metricContributionPayload);
            boolean hitTarget = row.path("mode_mrr").asDouble(0.0) > 0.0 || row.path("mode_ndcg").asDouble(0.0) > 0.0;
            details.add(new RagTestDtos.RagTestResultDetailRow(
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
                    hitTarget,
                    List.of()
            ));
        }
        return details;
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

    private String resolveEvalSampleQueryText(EvalDatasetDtos.EvalSampleMeta meta) {
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

    private Map<String, Object> buildRagPerformanceMetrics(JsonNode answerSummary) {
        JsonNode performanceNode = answerSummary == null
                ? objectMapper.createObjectNode()
                : answerSummary.path("performance");
        Map<String, Object> performance = new LinkedHashMap<>();
        performance.put(
                "avg_query_eval_total_latency_ms",
                nullableDouble(performanceNode.path("avg_query_eval_total_latency_ms"))
        );
        performance.put(
                "avg_final_rewrite_latency_ms",
                nullableDouble(performanceNode.path("avg_final_rewrite_latency_ms"))
        );
        performance.put(
                "avg_pure_rewrite_latency_ms",
                nullableDouble(performanceNode.path("avg_pure_rewrite_latency_ms"))
        );
        performance.put("eval_sample_count", nullableInteger(performanceNode.path("eval_sample_count")));
        performance.put("rewrite_sample_count", nullableInteger(performanceNode.path("rewrite_sample_count")));
        performance.put(
                "pure_rewrite_sample_count",
                nullableInteger(performanceNode.path("pure_rewrite_sample_count"))
        );
        performance.put("excluded_sample_count", nullableInteger(performanceNode.path("excluded_sample_count")));
        return performance;
    }

    private JsonNode nullableJson(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return objectMapper.createArrayNode();
        }
        return node;
    }

    private String nullableText(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        String value = node.asText("").trim();
        return value.isEmpty() ? null : value;
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

    private JsonNode sanitizeRetrievalSummary(JsonNode retrievalSummary) {
        if (retrievalSummary == null || retrievalSummary.isMissingNode() || !retrievalSummary.isObject()) {
            return objectMapper.createObjectNode();
        }
        JsonNode sanitized = retrievalSummary.deepCopy();
        if (sanitized.isObject()) {
            ((com.fasterxml.jackson.databind.node.ObjectNode) sanitized).remove("latency_summary");
        }
        return sanitized;
    }

    private Double nullableDouble(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        return node.asDouble();
    }

    private Integer nullableInteger(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull() || !node.isNumber()) {
            return null;
        }
        return node.asInt();
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
}
