package io.queryforge.backend.admin.console.evaluation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static io.queryforge.backend.admin.console.ConsoleJdbc.normalizeLimit;
import static io.queryforge.backend.admin.console.ConsoleJdbc.readInstant;
import static io.queryforge.backend.admin.console.ConsoleJdbc.readJson;
import static io.queryforge.backend.admin.console.ConsoleJdbc.readUuid;

@Repository
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RagTestRunRepository {

    private final RagRewriteAnchorRepository anchorRepository;

    private final NamedParameterJdbcTemplate jdbcTemplate;

    private final ObjectMapper objectMapper;

    @Transactional
    public UUID createRagTestRun(
            String runLabel,
            UUID datasetId,
            UUID domainId,
            JsonNode generationMethodCodes,
            JsonNode generationBatchIds,
            Boolean gatingApplied,
            String gatingPreset,
            Boolean rewriteEnabled,
            Boolean selectiveRewrite,
            Boolean useSessionContext,
            Boolean rewriteAnchorInjectionEnabled,
            Boolean multiSourceAnchorExpansionEnabled,
            Integer topK,
            Double threshold,
            Integer retrievalTopK,
            Integer rerankTopN,
            String experimentConfigName,
            String createdBy
    ) {
        UUID runId = UUID.randomUUID();
        String sql = """
                INSERT INTO rag_test_run (
                    rag_test_run_id,
                    run_label,
                    status,
                    dataset_id,
                    domain_id,
                    generation_method_codes,
                    generation_batch_ids,
                    gating_applied,
                    gating_preset,
                    rewrite_enabled,
                    selective_rewrite,
                    use_session_context,
                    rewrite_anchor_injection_enabled,
                    multi_source_anchor_expansion_enabled,
                    top_k,
                    threshold,
                    retrieval_top_k,
                    rerank_top_n,
                    experiment_config_name,
                    created_by
                ) VALUES (
                    :runId,
                    :runLabel,
                    'planned',
                    :datasetId,
                    :domainId,
                    CAST(:generationMethodCodes AS jsonb),
                    CAST(:generationBatchIds AS jsonb),
                    :gatingApplied,
                    :gatingPreset,
                    :rewriteEnabled,
                    :selectiveRewrite,
                    :useSessionContext,
                    :rewriteAnchorInjectionEnabled,
                    :multiSourceAnchorExpansionEnabled,
                    :topK,
                    :threshold,
                    :retrievalTopK,
                    :rerankTopN,
                    :experimentConfigName,
                    :createdBy
                )
                """;
        jdbcTemplate.update(
                sql,
                new MapSqlParameterSource()
                        .addValue("runId", runId)
                        .addValue("runLabel", runLabel)
                        .addValue("datasetId", datasetId)
                        .addValue("domainId", domainId)
                        .addValue("generationMethodCodes", generationMethodCodes.toString())
                        .addValue("generationBatchIds", generationBatchIds.toString())
                        .addValue("gatingApplied", gatingApplied)
                        .addValue("gatingPreset", gatingPreset)
                        .addValue("rewriteEnabled", rewriteEnabled)
                        .addValue("selectiveRewrite", selectiveRewrite)
                        .addValue("useSessionContext", useSessionContext)
                        .addValue("rewriteAnchorInjectionEnabled", rewriteAnchorInjectionEnabled)
                        .addValue("multiSourceAnchorExpansionEnabled", multiSourceAnchorExpansionEnabled)
                        .addValue("topK", topK)
                        .addValue("threshold", threshold)
                        .addValue("retrievalTopK", retrievalTopK)
                        .addValue("rerankTopN", rerankTopN)
                        .addValue("experimentConfigName", experimentConfigName)
                        .addValue("createdBy", createdBy)
        );
        return runId;
    }

    @Transactional
    public void upsertRagTestRunConfig(UUID runId, JsonNode configJson) {
        String sql = """
                INSERT INTO rag_test_run_config (
                    config_id,
                    rag_test_run_id,
                    config_json
                ) VALUES (
                    gen_random_uuid(),
                    :runId,
                    CAST(:configJson AS jsonb)
                )
                ON CONFLICT (rag_test_run_id) DO UPDATE
                SET config_json = EXCLUDED.config_json
                """;
        jdbcTemplate.update(
                sql,
                new MapSqlParameterSource()
                        .addValue("runId", runId)
                        .addValue("configJson", configJson.toString())
        );
    }

    public Optional<JsonNode> findRagTestRunConfig(UUID runId) {
        String sql = """
                SELECT config_json::text AS config_json
                FROM rag_test_run_config
                WHERE rag_test_run_id = :runId
                """;
        List<String> rows = jdbcTemplate.query(
                sql,
                new MapSqlParameterSource("runId", runId),
                (rs, rowNum) -> rs.getString("config_json")
        );
        return rows.stream().findFirst().map(raw -> readJson(objectMapper, raw));
    }

    public String findRagDatasetVersion(UUID runId) {
        String sql = """
                SELECT COALESCE(d.version, '')
                FROM rag_test_run r
                LEFT JOIN eval_dataset d
                  ON d.dataset_id = r.dataset_id
                WHERE r.rag_test_run_id = :runId
                """;
        List<String> rows = jdbcTemplate.query(
                sql,
                new MapSqlParameterSource("runId", runId),
                (rs, rowNum) -> rs.getString(1)
        );
        return rows.stream().findFirst().orElse("");
    }

    @Transactional
    public void upsertRagExperimentRecord(
            UUID runId,
            String snapshotId,
            JsonNode generationStrategy,
            JsonNode gatingConfig,
            Integer memorySize,
            JsonNode retrievalConfig,
            JsonNode rewriteConfig,
            String datasetVersion,
            Instant runTimestamp,
            JsonNode metrics
    ) {
        String sql = """
                INSERT INTO rag_eval_experiment_record (
                    record_id,
                    rag_test_run_id,
                    snapshot_id,
                    generation_strategy,
                    gating_config,
                    memory_size,
                    retrieval_config,
                    rewrite_config,
                    dataset_version,
                    run_timestamp,
                    metrics
                ) VALUES (
                    gen_random_uuid(),
                    :runId,
                    :snapshotId,
                    CAST(:generationStrategy AS jsonb),
                    CAST(:gatingConfig AS jsonb),
                    :memorySize,
                    CAST(:retrievalConfig AS jsonb),
                    CAST(:rewriteConfig AS jsonb),
                    :datasetVersion,
                    :runTimestamp,
                    CAST(:metrics AS jsonb)
                )
                ON CONFLICT (rag_test_run_id) DO UPDATE
                SET snapshot_id = EXCLUDED.snapshot_id,
                    generation_strategy = EXCLUDED.generation_strategy,
                    gating_config = COALESCE(rag_eval_experiment_record.gating_config, '{}'::jsonb)
                        || EXCLUDED.gating_config,
                    memory_size = EXCLUDED.memory_size,
                    retrieval_config = COALESCE(rag_eval_experiment_record.retrieval_config, '{}'::jsonb)
                        || EXCLUDED.retrieval_config,
                    rewrite_config = COALESCE(rag_eval_experiment_record.rewrite_config, '{}'::jsonb)
                        || EXCLUDED.rewrite_config,
                    dataset_version = EXCLUDED.dataset_version,
                    run_timestamp = EXCLUDED.run_timestamp,
                    metrics = EXCLUDED.metrics,
                    updated_at = NOW()
                """;
        jdbcTemplate.update(
                sql,
                new MapSqlParameterSource()
                        .addValue("runId", runId)
                        .addValue("snapshotId", snapshotId)
                        .addValue("generationStrategy", generationStrategy.toString())
                        .addValue("gatingConfig", gatingConfig.toString())
                        .addValue("memorySize", memorySize == null ? 0 : memorySize)
                        .addValue("retrievalConfig", retrievalConfig.toString())
                        .addValue("rewriteConfig", rewriteConfig.toString())
                        .addValue("datasetVersion", datasetVersion == null ? "" : datasetVersion)
                        .addValue("runTimestamp", Timestamp.from(runTimestamp == null ? Instant.now() : runTimestamp))
                        .addValue("metrics", metrics.toString())
        );
    }

    @Transactional
    public void completeRagTestRun(UUID runId, JsonNode metricsJson, UUID sourceExperimentRunId) {
        String sql = """
                UPDATE rag_test_run
                SET status = 'completed',
                    started_at = COALESCE(started_at, NOW()),
                    finished_at = NOW(),
                    metrics_json = CAST(:metricsJson AS jsonb),
                    source_experiment_run_id = :sourceExperimentRunId
                WHERE rag_test_run_id = :runId
                """;
        jdbcTemplate.update(
                sql,
                new MapSqlParameterSource()
                        .addValue("runId", runId)
                        .addValue("metricsJson", metricsJson.toString())
                        .addValue("sourceExperimentRunId", sourceExperimentRunId)
        );
    }

    @Transactional
    public void failRagTestRun(UUID runId, String errorMessage) {
        String sql = """
                UPDATE rag_test_run
                SET status = 'failed',
                    started_at = COALESCE(started_at, NOW()),
                    finished_at = NOW(),
                    metrics_json = CAST(:metricsJson AS jsonb)
                WHERE rag_test_run_id = :runId
                """;
        JsonNode payload = objectMapper.valueToTree(Map.of("error", errorMessage == null ? "" : errorMessage));
        jdbcTemplate.update(
                sql,
                new MapSqlParameterSource()
                        .addValue("runId", runId)
                        .addValue("metricsJson", payload.toString())
        );
    }

    @Transactional
    public void markRagTestRunRunning(UUID runId) {
        String sql = """
                UPDATE rag_test_run
                SET status = 'running',
                    started_at = COALESCE(started_at, NOW())
                WHERE rag_test_run_id = :runId
                  AND status IN ('planned', 'queued', 'failed')
                """;
        jdbcTemplate.update(sql, new MapSqlParameterSource("runId", runId));
    }

    @Transactional
    public void cancelRagTestRun(UUID runId, String reason) {
        String sql = """
                UPDATE rag_test_run
                SET status = 'cancelled',
                    finished_at = NOW(),
                    metrics_json = jsonb_build_object('cancel_reason', :reason)
                WHERE rag_test_run_id = :runId
                """;
        jdbcTemplate.update(
                sql,
                new MapSqlParameterSource()
                        .addValue("runId", runId)
                        .addValue("reason", reason == null ? "" : reason)
        );
    }

    @Transactional
    public void upsertRagSummary(
            UUID runId,
            Double recallAt5,
            Double hitAt5,
            Double mrrAt10,
            Double ndcgAt10,
            Double latencyAvgMs,
            Double rewriteAcceptanceRate,
            Double rewriteRejectionRate,
            Double averageConfidenceDelta,
            JsonNode answerMetrics,
            JsonNode metricsJson
    ) {
        String sql = """
                INSERT INTO rag_test_result_summary (
                    rag_test_run_id,
                    recall_at_5,
                    hit_at_5,
                    mrr_at_10,
                    ndcg_at_10,
                    latency_avg_ms,
                    rewrite_acceptance_rate,
                    rewrite_rejection_rate,
                    average_confidence_delta,
                    answer_metrics,
                    metrics_json
                ) VALUES (
                    :runId,
                    :recallAt5,
                    :hitAt5,
                    :mrrAt10,
                    :ndcgAt10,
                    :latencyAvgMs,
                    :rewriteAcceptanceRate,
                    :rewriteRejectionRate,
                    :averageConfidenceDelta,
                    CAST(:answerMetrics AS jsonb),
                    CAST(:metricsJson AS jsonb)
                )
                ON CONFLICT (rag_test_run_id) DO UPDATE
                SET recall_at_5 = EXCLUDED.recall_at_5,
                    hit_at_5 = EXCLUDED.hit_at_5,
                    mrr_at_10 = EXCLUDED.mrr_at_10,
                    ndcg_at_10 = EXCLUDED.ndcg_at_10,
                    latency_avg_ms = EXCLUDED.latency_avg_ms,
                    rewrite_acceptance_rate = EXCLUDED.rewrite_acceptance_rate,
                    rewrite_rejection_rate = EXCLUDED.rewrite_rejection_rate,
                    average_confidence_delta = EXCLUDED.average_confidence_delta,
                    answer_metrics = EXCLUDED.answer_metrics,
                    metrics_json = EXCLUDED.metrics_json
                """;
        jdbcTemplate.update(
                sql,
                new MapSqlParameterSource()
                        .addValue("runId", runId)
                        .addValue("recallAt5", recallAt5)
                        .addValue("hitAt5", hitAt5)
                        .addValue("mrrAt10", mrrAt10)
                        .addValue("ndcgAt10", ndcgAt10)
                        .addValue("latencyAvgMs", latencyAvgMs)
                        .addValue("rewriteAcceptanceRate", rewriteAcceptanceRate)
                        .addValue("rewriteRejectionRate", rewriteRejectionRate)
                        .addValue("averageConfidenceDelta", averageConfidenceDelta)
                        .addValue("answerMetrics", answerMetrics.toString())
                        .addValue("metricsJson", metricsJson.toString())
        );
    }

    @Transactional
    public void replaceRagDetailRows(UUID runId, List<RagTestDtos.RagTestResultDetailRow> rows) {
        jdbcTemplate.update(
                "DELETE FROM rag_test_result_detail WHERE rag_test_run_id = :runId",
                new MapSqlParameterSource("runId", runId)
        );
        if (rows == null || rows.isEmpty()) {
            return;
        }
        String sql = """
                INSERT INTO rag_test_result_detail (
                    detail_id,
                    rag_test_run_id,
                    sample_id,
                    query_category,
                    raw_query,
                    rewrite_query,
                    rewrite_applied,
                    memory_candidates,
                    rewrite_candidates,
                    retrieved_chunks,
                    metric_contribution,
                    hit_target
                ) VALUES (
                    :detailId,
                    :runId,
                    :sampleId,
                    :queryCategory,
                    :rawQuery,
                    :rewriteQuery,
                    :rewriteApplied,
                    CAST(:memoryCandidates AS jsonb),
                    CAST(:rewriteCandidates AS jsonb),
                    CAST(:retrievedChunks AS jsonb),
                    CAST(:metricContribution AS jsonb),
                    :hitTarget
                )
                """;
        for (RagTestDtos.RagTestResultDetailRow row : rows) {
            jdbcTemplate.update(
                sql,
                new MapSqlParameterSource()
                        .addValue("detailId", row.detailId())
                        .addValue("runId", row.ragTestRunId())
                        .addValue("sampleId", row.sampleId())
                        .addValue("queryCategory", row.queryCategory())
                        .addValue("rawQuery", row.rawQuery())
                        .addValue("rewriteQuery", row.rewriteQuery())
                        .addValue("rewriteApplied", row.rewriteApplied())
                        .addValue("memoryCandidates", row.memoryCandidates().toString())
                        .addValue("rewriteCandidates", row.rewriteCandidates().toString())
                        .addValue("retrievedChunks", row.retrievedChunks().toString())
                        .addValue("metricContribution", row.metricContribution().toString())
                        .addValue("hitTarget", row.hitTarget())
            );
        }
    }

    public Optional<RagTestDtos.RagTestRunRow> findRagTestRun(UUID runId) {
        String sql = """
                SELECT r.rag_test_run_id,
                       r.run_label,
                       r.status,
                       r.dataset_id,
                       d.dataset_name,
                       r.generation_method_codes::text AS generation_method_codes,
                       r.generation_batch_ids::text AS generation_batch_ids,
                       r.gating_applied,
                       r.gating_preset,
                       COALESCE((rc.config_json ->> 'stage_cutoff_enabled')::boolean, FALSE) AS stage_cutoff_enabled,
                       NULLIF(rc.config_json ->> 'stage_cutoff_level', '') AS stage_cutoff_level,
                       r.rewrite_enabled,
                       r.selective_rewrite,
                       r.use_session_context,
                       r.rewrite_anchor_injection_enabled,
                       r.multi_source_anchor_expansion_enabled,
                       r.retrieval_top_k,
                       r.threshold,
                       COALESCE(job.total_items, 3) AS total_stage_count,
                       COALESCE(job.processed_items, 0) AS completed_stage_count,
                       rate.estimated_seconds_per_stage,
                       CASE
                           WHEN lower(COALESCE(r.status, '')) IN ('completed', 'failed', 'cancelled')
                               THEN 0
                           WHEN COALESCE(job.total_items, 3) <= COALESCE(job.processed_items, 0)
                               THEN 0
                           WHEN rate.estimated_seconds_per_stage IS NULL
                               THEN NULL
                           ELSE GREATEST(
                                   0,
                                   ROUND(
                                           (COALESCE(job.total_items, 3) - COALESCE(job.processed_items, 0))
                                           * rate.estimated_seconds_per_stage
                                   )
                                )::bigint
                       END AS estimated_remaining_seconds,
                       r.started_at,
                       r.finished_at,
                       r.metrics_json::text AS metrics_json
                FROM rag_test_run r
                LEFT JOIN eval_dataset d
                  ON d.dataset_id = r.dataset_id
                LEFT JOIN rag_test_run_config rc
                  ON rc.rag_test_run_id = r.rag_test_run_id
                LEFT JOIN LATERAL (
                    SELECT lj.total_items,
                           lj.processed_items,
                           lj.job_status,
                           lj.started_at
                    FROM llm_job lj
                    WHERE lj.rag_test_run_id = r.rag_test_run_id
                    ORDER BY lj.created_at DESC
                    LIMIT 1
                ) job ON TRUE
                LEFT JOIN LATERAL (
                    SELECT AVG(sample.seconds_per_stage) AS historical_seconds_per_stage
                    FROM (
                        SELECT EXTRACT(EPOCH FROM (history.finished_at - history.started_at))
                               / history.total_items::double precision AS seconds_per_stage
                        FROM llm_job history
                        WHERE history.job_type = 'RUN_RAG_TEST'
                          AND history.job_status = 'completed'
                          AND history.total_items > 0
                          AND history.started_at IS NOT NULL
                          AND history.finished_at IS NOT NULL
                        ORDER BY history.finished_at DESC
                        LIMIT 20
                    ) sample
                ) hist ON TRUE
                LEFT JOIN LATERAL (
                    SELECT CASE
                               WHEN COALESCE(job.processed_items, 0) > 0
                                    AND job.started_at IS NOT NULL
                                    AND lower(COALESCE(job.job_status, '')) IN ('queued', 'running', 'pause_requested', 'paused', 'cancel_requested')
                                   THEN GREATEST(EXTRACT(EPOCH FROM (NOW() - job.started_at)), 1.0)
                                        / job.processed_items::double precision
                               ELSE hist.historical_seconds_per_stage
                           END AS estimated_seconds_per_stage
                ) rate ON TRUE
                WHERE r.rag_test_run_id = :runId
                """;
        List<RagTestDtos.RagTestRunRow> rows = jdbcTemplate.query(
                sql,
                new MapSqlParameterSource("runId", runId),
                (rs, rowNum) -> mapRagTestRunRow(rs)
        );
        return rows.stream().findFirst();
    }

    @Transactional
    public int deleteRagTestRun(UUID runId) {
        MapSqlParameterSource params = new MapSqlParameterSource("runId", runId);
        Long existing = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM rag_test_run WHERE rag_test_run_id = :runId",
                params,
                Long.class
        );
        if (existing == null || existing <= 0L) {
            return 0;
        }

        List<UUID> experimentRunIds = findLinkedExperimentRunIds(runId);

        jdbcTemplate.update(
                "DELETE FROM online_query_rewrite_log WHERE run_id = :runId",
                params
        );
        jdbcTemplate.update(
                "DELETE FROM llm_job WHERE rag_test_run_id = :runId",
                params
        );
        int removed = jdbcTemplate.update(
                "DELETE FROM rag_test_run WHERE rag_test_run_id = :runId",
                params
        );
        if (removed > 0) {
            deleteExperimentArtifacts(experimentRunIds);
        }
        return removed;
    }

    private List<UUID> findLinkedExperimentRunIds(UUID runId) {
        MapSqlParameterSource params = new MapSqlParameterSource("runId", runId);
        LinkedHashSet<UUID> experimentRunIds = new LinkedHashSet<>();
        List<UUID> directIds = jdbcTemplate.query(
                """
                SELECT source_experiment_run_id
                FROM rag_test_run
                WHERE rag_test_run_id = :runId
                """,
                params,
                (rs, rowNum) -> readUuid(rs, "source_experiment_run_id")
        );
        for (UUID directId : directIds) {
            if (directId != null) {
                experimentRunIds.add(directId);
            }
        }

        List<String> payloads = new ArrayList<>(jdbcTemplate.query(
                """
                SELECT metrics_json::text AS payload
                FROM rag_test_run
                WHERE rag_test_run_id = :runId
                UNION ALL
                SELECT metrics_json::text AS payload
                FROM rag_test_result_summary
                WHERE rag_test_run_id = :runId
                UNION ALL
                SELECT metrics::text AS payload
                FROM rag_eval_experiment_record
                WHERE rag_test_run_id = :runId
                UNION ALL
                SELECT result_json::text AS payload
                FROM llm_job
                WHERE rag_test_run_id = :runId
                """,
                params,
                (rs, rowNum) -> rs.getString("payload")
        ));
        for (String payload : payloads) {
            collectExperimentRunIds(readJson(objectMapper, payload), experimentRunIds);
        }
        return new ArrayList<>(experimentRunIds);
    }

    private void deleteExperimentArtifacts(List<UUID> experimentRunIds) {
        if (experimentRunIds == null || experimentRunIds.isEmpty()) {
            return;
        }
        List<String> experimentRunIdTexts = experimentRunIds.stream()
                .map(UUID::toString)
                .toList();
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("experimentRunIds", experimentRunIds)
                .addValue("experimentRunIdTexts", experimentRunIdTexts);
        jdbcTemplate.update(
                """
                DELETE FROM retrieval_results
                WHERE metadata ->> 'experiment_run_id' IN (:experimentRunIdTexts)
                """,
                params
        );
        jdbcTemplate.update(
                """
                DELETE FROM rerank_results
                WHERE metadata ->> 'experiment_run_id' IN (:experimentRunIdTexts)
                """,
                params
        );
        jdbcTemplate.update(
                """
                DELETE FROM eval_judgments
                WHERE experiment_run_id IN (:experimentRunIds)
                """,
                params
        );
        jdbcTemplate.update(
                """
                DELETE FROM online_queries
                WHERE experiment_run_id IN (:experimentRunIds)
                """,
                params
        );
        jdbcTemplate.update(
                """
                DELETE FROM experiment_runs
                WHERE experiment_run_id IN (:experimentRunIds)
                """,
                params
        );
    }

    private void collectExperimentRunIds(JsonNode node, Set<UUID> output) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return;
        }
        if (node.isObject()) {
            UUID experimentRunId = parseUuid(node.path("experiment_run_id").asText(""));
            if (experimentRunId != null) {
                output.add(experimentRunId);
            }
            node.fields().forEachRemaining(entry -> collectExperimentRunIds(entry.getValue(), output));
            return;
        }
        if (node.isArray()) {
            node.forEach(child -> collectExperimentRunIds(child, output));
        }
    }

    private UUID parseUuid(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(raw.trim());
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    public List<RagTestDtos.RagTestRunRow> findRagTestRuns(Integer limit) {
        return findRagTestRuns(limit, null);
    }

    public List<RagTestDtos.RagTestRunRow> findRagTestRuns(Integer limit, UUID domainId) {
        String domainWhereClause = domainId == null ? "" : "WHERE r.domain_id = :domainId";
        boolean bounded = limit != null && limit > 0;
        String limitClause = bounded ? "LIMIT :limit" : "";
        String sql = """
                SELECT r.rag_test_run_id,
                       r.run_label,
                       r.status,
                       r.dataset_id,
                       d.dataset_name,
                       r.generation_method_codes::text AS generation_method_codes,
                       r.generation_batch_ids::text AS generation_batch_ids,
                       r.gating_applied,
                       r.gating_preset,
                       COALESCE((rc.config_json ->> 'stage_cutoff_enabled')::boolean, FALSE) AS stage_cutoff_enabled,
                       NULLIF(rc.config_json ->> 'stage_cutoff_level', '') AS stage_cutoff_level,
                       r.rewrite_enabled,
                       r.selective_rewrite,
                       r.use_session_context,
                       r.rewrite_anchor_injection_enabled,
                       r.multi_source_anchor_expansion_enabled,
                       r.retrieval_top_k,
                       r.threshold,
                       COALESCE(job.total_items, 3) AS total_stage_count,
                       COALESCE(job.processed_items, 0) AS completed_stage_count,
                       rate.estimated_seconds_per_stage,
                       CASE
                           WHEN lower(COALESCE(r.status, '')) IN ('completed', 'failed', 'cancelled')
                               THEN 0
                           WHEN COALESCE(job.total_items, 3) <= COALESCE(job.processed_items, 0)
                               THEN 0
                           WHEN rate.estimated_seconds_per_stage IS NULL
                               THEN NULL
                           ELSE GREATEST(
                                   0,
                                   ROUND(
                                           (COALESCE(job.total_items, 3) - COALESCE(job.processed_items, 0))
                                           * rate.estimated_seconds_per_stage
                                   )
                                )::bigint
                       END AS estimated_remaining_seconds,
                       r.started_at,
                       r.finished_at,
                       r.metrics_json::text AS metrics_json
                FROM rag_test_run r
                LEFT JOIN eval_dataset d
                  ON d.dataset_id = r.dataset_id
                LEFT JOIN rag_test_run_config rc
                  ON rc.rag_test_run_id = r.rag_test_run_id
                LEFT JOIN LATERAL (
                    SELECT lj.total_items,
                           lj.processed_items,
                           lj.job_status,
                           lj.started_at
                    FROM llm_job lj
                    WHERE lj.rag_test_run_id = r.rag_test_run_id
                    ORDER BY lj.created_at DESC
                    LIMIT 1
                ) job ON TRUE
                LEFT JOIN LATERAL (
                    SELECT AVG(sample.seconds_per_stage) AS historical_seconds_per_stage
                    FROM (
                        SELECT EXTRACT(EPOCH FROM (history.finished_at - history.started_at))
                               / history.total_items::double precision AS seconds_per_stage
                        FROM llm_job history
                        WHERE history.job_type = 'RUN_RAG_TEST'
                          AND history.job_status = 'completed'
                          AND history.total_items > 0
                          AND history.started_at IS NOT NULL
                          AND history.finished_at IS NOT NULL
                        ORDER BY history.finished_at DESC
                        LIMIT 20
                    ) sample
                ) hist ON TRUE
                LEFT JOIN LATERAL (
                    SELECT CASE
                               WHEN COALESCE(job.processed_items, 0) > 0
                                    AND job.started_at IS NOT NULL
                                    AND lower(COALESCE(job.job_status, '')) IN ('queued', 'running', 'pause_requested', 'paused', 'cancel_requested')
                                   THEN GREATEST(EXTRACT(EPOCH FROM (NOW() - job.started_at)), 1.0)
                                        / job.processed_items::double precision
                               ELSE hist.historical_seconds_per_stage
                           END AS estimated_seconds_per_stage
                ) rate ON TRUE
                %s
                ORDER BY r.created_at DESC
                %s
                """.formatted(domainWhereClause, limitClause);
        MapSqlParameterSource params = new MapSqlParameterSource();
        if (bounded) {
            params.addValue("limit", normalizeLimit(limit, 1000));
        }
        if (domainId != null) {
            params.addValue("domainId", domainId);
        }
        return jdbcTemplate.query(
                sql,
                params,
                (rs, rowNum) -> mapRagTestRunRow(rs)
        );
    }

    public List<RagTestDtos.RagTestResultDetailRow> findRagTestDetails(UUID runId, Integer limit) {
        String sql = """
                WITH ranked AS (
                    SELECT DISTINCT ON (COALESCE(sample_id, detail_id::text))
                           detail_id,
                           rag_test_run_id,
                           sample_id,
                           query_category,
                           raw_query,
                           rewrite_query,
                           rewrite_applied,
                           memory_candidates,
                           rewrite_candidates,
                           retrieved_chunks,
                           metric_contribution,
                           hit_target,
                           created_at
                    FROM rag_test_result_detail
                    WHERE rag_test_run_id = :runId
                    ORDER BY
                        COALESCE(sample_id, detail_id::text),
                        CASE
                            WHEN jsonb_typeof(rewrite_candidates) = 'array' AND jsonb_array_length(rewrite_candidates) > 0 THEN 0
                            WHEN jsonb_typeof(rewrite_candidates) = 'object' AND rewrite_candidates <> '{}'::jsonb THEN 0
                            ELSE 1
                        END,
                        CASE
                            WHEN jsonb_typeof(memory_candidates) = 'array' AND jsonb_array_length(memory_candidates) > 0 THEN 0
                            WHEN jsonb_typeof(memory_candidates) = 'object' AND memory_candidates <> '{}'::jsonb THEN 0
                            ELSE 1
                        END,
                        CASE WHEN rewrite_applied IS TRUE THEN 0 ELSE 1 END,
                        created_at
                )
                SELECT detail_id,
                       rag_test_run_id,
                       sample_id,
                       query_category,
                       raw_query,
                       rewrite_query,
                       rewrite_applied,
                       memory_candidates::text AS memory_candidates,
                       rewrite_candidates::text AS rewrite_candidates,
                       retrieved_chunks::text AS retrieved_chunks,
                       metric_contribution::text AS metric_contribution,
                       hit_target
                FROM ranked
                ORDER BY
                    NULLIF(substring(sample_id from '([0-9]+)$'), '')::numeric NULLS LAST,
                    sample_id NULLS LAST,
                    created_at
                LIMIT :limit
                """;
        List<RagTestDtos.RagTestResultDetailRow> details = jdbcTemplate.query(
                sql,
                new MapSqlParameterSource()
                        .addValue("runId", runId)
                        .addValue("limit", normalizeLimit(limit, 500)),
                (rs, rowNum) -> new RagTestDtos.RagTestResultDetailRow(
                        readUuid(rs, "detail_id"),
                        readUuid(rs, "rag_test_run_id"),
                        rs.getString("sample_id"),
                        rs.getString("query_category"),
                        rs.getString("raw_query"),
                        rs.getString("rewrite_query"),
                        rs.getObject("rewrite_applied", Boolean.class),
                        readJson(objectMapper, rs, "memory_candidates"),
                        readJson(objectMapper, rs, "rewrite_candidates"),
                        readJson(objectMapper, rs, "retrieved_chunks"),
                        readJson(objectMapper, rs, "metric_contribution"),
                        rs.getObject("hit_target", Boolean.class),
                        List.of()
                )
        );
        Map<UUID, List<RagTestDtos.RagRewriteAnchorEvalRow>> anchorsByDetailId =
                anchorRepository.findRagRewriteAnchorEvalByDetailIds(details.stream()
                        .map(RagTestDtos.RagTestResultDetailRow::detailId)
                        .toList());
        return details.stream()
                .map(row -> row.withAnchorEvaluations(anchorsByDetailId.getOrDefault(row.detailId(), List.of())))
                .toList();
    }

    public Optional<JsonNode> findRagSummaryMetrics(UUID runId) {
        String sql = """
                SELECT jsonb_build_object(
                    'recall_at_5', recall_at_5,
                    'hit_at_5', hit_at_5,
                    'mrr_at_10', mrr_at_10,
                    'ndcg_at_10', ndcg_at_10,
                    'answer_metrics', answer_metrics,
                    'metrics_json', metrics_json,
                    'experiment_record',
                    (
                        SELECT jsonb_build_object(
                            'snapshot_id', er.snapshot_id,
                            'generation_strategy', er.generation_strategy,
                            'gating_config', er.gating_config,
                            'memory_size', er.memory_size,
                            'retrieval_config', er.retrieval_config,
                            'rewrite_config', er.rewrite_config,
                            'dataset_version', er.dataset_version,
                            'run_timestamp', er.run_timestamp,
                            'metrics', er.metrics
                        )
                        FROM rag_eval_experiment_record er
                        WHERE er.rag_test_run_id = :runId
                    )
                )::text AS payload
                FROM rag_test_result_summary
                WHERE rag_test_run_id = :runId
                """;
        List<String> rows = jdbcTemplate.query(
                sql,
                new MapSqlParameterSource("runId", runId),
                (rs, rowNum) -> rs.getString("payload")
        );
        return rows.stream().findFirst().map(raw -> readJson(objectMapper, raw));
    }

    public List<RagTestDtos.RagTestRunRow> findRagTestRunsByDataset(UUID datasetId) {
        String sql = """
                SELECT r.rag_test_run_id,
                       r.run_label,
                       r.status,
                       r.dataset_id,
                       d.dataset_name,
                       r.generation_method_codes::text AS generation_method_codes,
                       r.generation_batch_ids::text AS generation_batch_ids,
                       r.gating_applied,
                       r.gating_preset,
                       COALESCE((rc.config_json ->> 'stage_cutoff_enabled')::boolean, FALSE) AS stage_cutoff_enabled,
                       NULLIF(rc.config_json ->> 'stage_cutoff_level', '') AS stage_cutoff_level,
                       r.rewrite_enabled,
                       r.selective_rewrite,
                       r.use_session_context,
                       r.rewrite_anchor_injection_enabled,
                       r.multi_source_anchor_expansion_enabled,
                       r.retrieval_top_k,
                       r.threshold,
                       COALESCE(job.total_items, 3) AS total_stage_count,
                       COALESCE(job.processed_items, 0) AS completed_stage_count,
                       rate.estimated_seconds_per_stage,
                       CASE
                           WHEN lower(COALESCE(r.status, '')) IN ('completed', 'failed', 'cancelled')
                               THEN 0
                           WHEN COALESCE(job.total_items, 3) <= COALESCE(job.processed_items, 0)
                               THEN 0
                           WHEN rate.estimated_seconds_per_stage IS NULL
                               THEN NULL
                           ELSE GREATEST(
                                   0,
                                   ROUND(
                                           (COALESCE(job.total_items, 3) - COALESCE(job.processed_items, 0))
                                           * rate.estimated_seconds_per_stage
                                   )
                                )::bigint
                       END AS estimated_remaining_seconds,
                       r.started_at,
                       r.finished_at,
                       r.metrics_json::text AS metrics_json
                FROM rag_test_run r
                LEFT JOIN eval_dataset d
                  ON d.dataset_id = r.dataset_id
                LEFT JOIN rag_test_run_config rc
                  ON rc.rag_test_run_id = r.rag_test_run_id
                LEFT JOIN LATERAL (
                    SELECT lj.total_items,
                           lj.processed_items,
                           lj.job_status,
                           lj.started_at
                    FROM llm_job lj
                    WHERE lj.rag_test_run_id = r.rag_test_run_id
                    ORDER BY lj.created_at DESC
                    LIMIT 1
                ) job ON TRUE
                LEFT JOIN LATERAL (
                    SELECT AVG(sample.seconds_per_stage) AS historical_seconds_per_stage
                    FROM (
                        SELECT EXTRACT(EPOCH FROM (history.finished_at - history.started_at))
                               / history.total_items::double precision AS seconds_per_stage
                        FROM llm_job history
                        WHERE history.job_type = 'RUN_RAG_TEST'
                          AND history.job_status = 'completed'
                          AND history.total_items > 0
                          AND history.started_at IS NOT NULL
                          AND history.finished_at IS NOT NULL
                        ORDER BY history.finished_at DESC
                        LIMIT 20
                    ) sample
                ) hist ON TRUE
                LEFT JOIN LATERAL (
                    SELECT CASE
                               WHEN COALESCE(job.processed_items, 0) > 0
                                    AND job.started_at IS NOT NULL
                                    AND lower(COALESCE(job.job_status, '')) IN ('queued', 'running', 'pause_requested', 'paused', 'cancel_requested')
                                   THEN GREATEST(EXTRACT(EPOCH FROM (NOW() - job.started_at)), 1.0)
                                        / job.processed_items::double precision
                               ELSE hist.historical_seconds_per_stage
                           END AS estimated_seconds_per_stage
                ) rate ON TRUE
                WHERE r.dataset_id = :datasetId
                ORDER BY r.created_at DESC
                """;
        return jdbcTemplate.query(
                sql,
                new MapSqlParameterSource("datasetId", datasetId),
                (rs, rowNum) -> mapRagTestRunRow(rs)
        );
    }

    private RagTestDtos.RagTestRunRow mapRagTestRunRow(ResultSet rs) throws SQLException {
        return new RagTestDtos.RagTestRunRow(
                readUuid(rs, "rag_test_run_id"),
                rs.getString("run_label"),
                rs.getString("status"),
                readUuid(rs, "dataset_id"),
                rs.getString("dataset_name"),
                readJson(objectMapper, rs, "generation_method_codes"),
                readJson(objectMapper, rs, "generation_batch_ids"),
                rs.getObject("gating_applied", Boolean.class),
                rs.getString("gating_preset"),
                rs.getObject("stage_cutoff_enabled", Boolean.class),
                rs.getString("stage_cutoff_level"),
                rs.getObject("rewrite_enabled", Boolean.class),
                rs.getObject("selective_rewrite", Boolean.class),
                rs.getObject("use_session_context", Boolean.class),
                rs.getObject("rewrite_anchor_injection_enabled", Boolean.class),
                rs.getObject("multi_source_anchor_expansion_enabled", Boolean.class),
                rs.getObject("retrieval_top_k", Integer.class),
                rs.getObject("threshold", Double.class),
                rs.getObject("total_stage_count", Integer.class),
                rs.getObject("completed_stage_count", Integer.class),
                rs.getObject("estimated_seconds_per_stage", Double.class),
                rs.getObject("estimated_remaining_seconds", Long.class),
                readInstant(rs, "started_at"),
                readInstant(rs, "finished_at"),
                readJson(objectMapper, rs, "metrics_json")
        );
    }
}
