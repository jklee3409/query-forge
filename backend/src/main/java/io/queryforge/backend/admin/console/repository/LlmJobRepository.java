package io.queryforge.backend.admin.console.repository;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.queryforge.backend.admin.console.model.AdminConsoleDtos;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class LlmJobRepository {

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    @Transactional
    public UUID createJob(
            String jobType,
            String commandName,
            String experimentName,
            JsonNode commandArgs,
            UUID generationBatchId,
            UUID gatingBatchId,
            UUID ragTestRunId,
            int totalItems,
            int maxRetries,
            String createdBy
    ) {
        UUID jobId = UUID.randomUUID();
        String sql = """
                INSERT INTO llm_job (
                    job_id,
                    job_type,
                    job_status,
                    generation_batch_id,
                    gating_batch_id,
                    rag_test_run_id,
                    experiment_name,
                    command_name,
                    command_args,
                    total_items,
                    max_retries,
                    created_by
                ) VALUES (
                    :jobId,
                    :jobType,
                    'queued',
                    :generationBatchId,
                    :gatingBatchId,
                    :ragTestRunId,
                    :experimentName,
                    :commandName,
                    CAST(:commandArgs AS jsonb),
                    :totalItems,
                    :maxRetries,
                    :createdBy
                )
                """;
        jdbcTemplate.update(
                sql,
                new MapSqlParameterSource()
                        .addValue("jobId", jobId)
                        .addValue("jobType", jobType)
                        .addValue("generationBatchId", generationBatchId)
                        .addValue("gatingBatchId", gatingBatchId)
                        .addValue("ragTestRunId", ragTestRunId)
                        .addValue("experimentName", experimentName)
                        .addValue("commandName", commandName)
                        .addValue("commandArgs", commandArgs.toString())
                        .addValue("totalItems", Math.max(totalItems, 1))
                        .addValue("maxRetries", Math.max(maxRetries, 0))
                        .addValue("createdBy", createdBy)
        );
        return jobId;
    }

    @Transactional
    public UUID createJobItem(UUID jobId, int itemOrder, String itemType, JsonNode payloadJson, int maxRetries) {
        UUID jobItemId = UUID.randomUUID();
        String sql = """
                INSERT INTO llm_job_item (
                    job_item_id,
                    job_id,
                    item_order,
                    item_type,
                    item_status,
                    payload_json,
                    max_retries
                ) VALUES (
                    :jobItemId,
                    :jobId,
                    :itemOrder,
                    :itemType,
                    'queued',
                    CAST(:payloadJson AS jsonb),
                    :maxRetries
                )
                """;
        jdbcTemplate.update(
                sql,
                new MapSqlParameterSource()
                        .addValue("jobItemId", jobItemId)
                        .addValue("jobId", jobId)
                        .addValue("itemOrder", itemOrder)
                        .addValue("itemType", itemType)
                        .addValue("payloadJson", payloadJson.toString())
                        .addValue("maxRetries", Math.max(maxRetries, 0))
        );
        return jobItemId;
    }

    public Optional<AdminConsoleDtos.LlmJobRow> findJob(UUID jobId) {
        String sql = """
                WITH target_job AS (
                    SELECT job_id,
                           job_type,
                           job_status,
                           priority,
                           generation_batch_id,
                           gating_batch_id,
                           rag_test_run_id,
                           experiment_name,
                           command_name,
                           command_args,
                           total_items,
                           processed_items,
                           progress_pct,
                           retry_count,
                           max_retries,
                           next_run_at,
                           started_at,
                           finished_at,
                           error_message,
                           result_json,
                           created_at
                    FROM llm_job
                    WHERE job_id = :jobId
                ),
                gating_live AS (
                    SELECT COUNT(*) AS processed_count
                    FROM synthetic_query_gating_result gr
                    WHERE gr.gating_batch_id = (SELECT tj.gating_batch_id FROM target_job tj)
                )
                SELECT tj.job_id,
                       tj.job_type,
                       tj.job_status,
                       tj.priority,
                       tj.generation_batch_id,
                       tj.gating_batch_id,
                       tj.rag_test_run_id,
                       tj.experiment_name,
                       tj.command_name,
                       tj.command_args::text AS command_args,
                       CASE
                           WHEN tj.job_type = 'RUN_LLM_SELF_EVAL'
                                AND lower(COALESCE(tj.job_status, '')) IN ('queued', 'running', 'pause_requested', 'paused', 'cancel_requested')
                               THEN COALESCE(gl.processed_count, 0)
                           ELSE tj.processed_items
                       END AS processed_items,
                       CASE
                           WHEN tj.job_type = 'RUN_LLM_SELF_EVAL'
                                AND lower(COALESCE(tj.job_status, '')) IN ('queued', 'running', 'pause_requested', 'paused', 'cancel_requested')
                               THEN COALESCE(NULLIF(gb.total_generated_count, 0), NULLIF(tj.total_items, 0), 1)
                           ELSE tj.total_items
                       END AS total_items,
                       CASE
                           WHEN tj.job_type = 'RUN_LLM_SELF_EVAL'
                                AND lower(COALESCE(tj.job_status, '')) IN ('queued', 'running', 'pause_requested', 'paused', 'cancel_requested')
                               THEN LEAST(
                                       100.0,
                                       ROUND(
                                               (
                                                   COALESCE(gl.processed_count, 0)::numeric * 100.0
                                               ) / COALESCE(NULLIF(gb.total_generated_count, 0), NULLIF(tj.total_items, 0), 1),
                                               1
                                       )::double precision
                               )
                           ELSE tj.progress_pct
                       END AS progress_pct,
                       tj.retry_count,
                       tj.max_retries,
                       tj.next_run_at,
                       tj.started_at,
                       tj.finished_at,
                       tj.error_message,
                       tj.result_json::text AS result_json,
                       tj.created_at
                FROM target_job tj
                LEFT JOIN gating_live gl ON true
                LEFT JOIN quality_gating_batch qb
                  ON qb.gating_batch_id = tj.gating_batch_id
                LEFT JOIN synthetic_query_generation_batch gb
                  ON gb.batch_id = qb.generation_batch_id
                """;
        List<AdminConsoleDtos.LlmJobRow> rows = jdbcTemplate.query(
                sql,
                new MapSqlParameterSource("jobId", jobId),
                (rs, rowNum) -> mapJobRow(rs)
        );
        return rows.stream().findFirst();
    }

    public List<AdminConsoleDtos.LlmJobRow> findJobs(Integer limit) {
        String sql = """
                WITH limited_job AS (
                    SELECT job_id,
                           job_type,
                           job_status,
                           priority,
                           generation_batch_id,
                           gating_batch_id,
                           rag_test_run_id,
                           experiment_name,
                           command_name,
                           command_args,
                           total_items,
                           processed_items,
                           progress_pct,
                           retry_count,
                           max_retries,
                           next_run_at,
                           started_at,
                           finished_at,
                           error_message,
                           result_json,
                           created_at
                    FROM llm_job
                    ORDER BY created_at DESC
                    LIMIT :limit
                ),
                gating_live AS (
                    SELECT gr.gating_batch_id,
                           COUNT(*) AS processed_count
                    FROM synthetic_query_gating_result gr
                    WHERE gr.gating_batch_id IN (
                        SELECT lj.gating_batch_id
                        FROM limited_job lj
                        WHERE lj.gating_batch_id IS NOT NULL
                    )
                    GROUP BY gr.gating_batch_id
                )
                SELECT lj.job_id,
                       lj.job_type,
                       lj.job_status,
                       lj.priority,
                       lj.generation_batch_id,
                       lj.gating_batch_id,
                       lj.rag_test_run_id,
                       lj.experiment_name,
                       lj.command_name,
                       lj.command_args::text AS command_args,
                       CASE
                           WHEN lj.job_type = 'RUN_LLM_SELF_EVAL'
                                AND lower(COALESCE(lj.job_status, '')) IN ('queued', 'running', 'pause_requested', 'paused', 'cancel_requested')
                               THEN COALESCE(gl.processed_count, 0)
                           ELSE lj.processed_items
                       END AS processed_items,
                       CASE
                           WHEN lj.job_type = 'RUN_LLM_SELF_EVAL'
                                AND lower(COALESCE(lj.job_status, '')) IN ('queued', 'running', 'pause_requested', 'paused', 'cancel_requested')
                               THEN COALESCE(NULLIF(gb.total_generated_count, 0), NULLIF(lj.total_items, 0), 1)
                           ELSE lj.total_items
                       END AS total_items,
                       CASE
                           WHEN lj.job_type = 'RUN_LLM_SELF_EVAL'
                                AND lower(COALESCE(lj.job_status, '')) IN ('queued', 'running', 'pause_requested', 'paused', 'cancel_requested')
                               THEN LEAST(
                                       100.0,
                                       ROUND(
                                               (
                                                   COALESCE(gl.processed_count, 0)::numeric * 100.0
                                               ) / COALESCE(NULLIF(gb.total_generated_count, 0), NULLIF(lj.total_items, 0), 1),
                                               1
                                       )::double precision
                               )
                           ELSE lj.progress_pct
                       END AS progress_pct,
                       lj.retry_count,
                       lj.max_retries,
                       lj.next_run_at,
                       lj.started_at,
                       lj.finished_at,
                       lj.error_message,
                       lj.result_json::text AS result_json,
                       lj.created_at
                FROM limited_job lj
                LEFT JOIN gating_live gl
                  ON gl.gating_batch_id = lj.gating_batch_id
                LEFT JOIN quality_gating_batch qb
                  ON qb.gating_batch_id = lj.gating_batch_id
                LEFT JOIN synthetic_query_generation_batch gb
                  ON gb.batch_id = qb.generation_batch_id
                ORDER BY lj.created_at DESC
                """;
        return jdbcTemplate.query(
                sql,
                new MapSqlParameterSource("limit", normalizeLimit(limit, 100)),
                (rs, rowNum) -> mapJobRow(rs)
        );
    }

    public List<AdminConsoleDtos.LlmJobItemRow> findJobItems(UUID jobId) {
        String sql = """
                SELECT job_item_id,
                       job_id,
                       item_order,
                       item_type,
                       item_status,
                       retry_count,
                       max_retries,
                       payload_json::text AS payload_json,
                       checkpoint_json::text AS checkpoint_json,
                       result_json::text AS result_json,
                       error_message,
                       started_at,
                       finished_at,
                       created_at
                FROM llm_job_item
                WHERE job_id = :jobId
                ORDER BY item_order
                """;
        return jdbcTemplate.query(
                sql,
                new MapSqlParameterSource("jobId", jobId),
                (rs, rowNum) -> new AdminConsoleDtos.LlmJobItemRow(
                        readUuid(rs, "job_item_id"),
                        readUuid(rs, "job_id"),
                        rs.getInt("item_order"),
                        rs.getString("item_type"),
                        rs.getString("item_status"),
                        rs.getInt("retry_count"),
                        rs.getInt("max_retries"),
                        readJson(rs, "payload_json"),
                        readJson(rs, "checkpoint_json"),
                        readJson(rs, "result_json"),
                        rs.getString("error_message"),
                        readInstant(rs, "started_at"),
                        readInstant(rs, "finished_at"),
                        readInstant(rs, "created_at")
                )
        );
    }

    public List<UUID> findQueuedJobIds(int limit) {
        String sql = """
                SELECT job_id
                FROM llm_job
                WHERE job_status = 'queued'
                ORDER BY next_run_at ASC, priority ASC, created_at ASC
                LIMIT :limit
                """;
        return jdbcTemplate.query(
                sql,
                new MapSqlParameterSource("limit", Math.max(1, limit)),
                (rs, rowNum) -> readUuid(rs, "job_id")
        );
    }

    @Transactional
    public List<UUID> recoverInterruptedJobs(int limit) {
        String sql = """
                WITH target AS (
                    SELECT job_id
                    FROM llm_job
                    WHERE job_status IN ('running', 'pause_requested')
                    ORDER BY updated_at ASC
                    LIMIT :limit
                    FOR UPDATE SKIP LOCKED
                )
                UPDATE llm_job job
                SET job_status = 'queued',
                    next_run_at = NOW(),
                    finished_at = NULL,
                    error_message = COALESCE(job.error_message, 'Recovered after backend restart.'),
                    updated_at = NOW()
                FROM target
                WHERE job.job_id = target.job_id
                RETURNING job.job_id
                """;
        return jdbcTemplate.query(
                sql,
                new MapSqlParameterSource("limit", Math.max(1, limit)),
                (rs, rowNum) -> readUuid(rs, "job_id")
        );
    }

    @Transactional
    public List<UUID> finalizeCancelRequestedJobs(int limit) {
        String sql = """
                WITH target AS (
                    SELECT job_id
                    FROM llm_job
                    WHERE job_status = 'cancel_requested'
                    ORDER BY updated_at ASC
                    LIMIT :limit
                    FOR UPDATE SKIP LOCKED
                )
                UPDATE llm_job job
                SET job_status = 'cancelled',
                    finished_at = COALESCE(job.finished_at, NOW()),
                    updated_at = NOW()
                FROM target
                WHERE job.job_id = target.job_id
                RETURNING job.job_id
                """;
        return jdbcTemplate.query(
                sql,
                new MapSqlParameterSource("limit", Math.max(1, limit)),
                (rs, rowNum) -> readUuid(rs, "job_id")
        );
    }

    @Transactional
    public void markJobRunning(UUID jobId) {
        String sql = """
                UPDATE llm_job
                SET job_status = 'running',
                    started_at = COALESCE(started_at, NOW()),
                    updated_at = NOW(),
                    error_message = NULL
                WHERE job_id = :jobId
                """;
        jdbcTemplate.update(sql, new MapSqlParameterSource("jobId", jobId));
    }

    @Transactional
    public void markJobCompleted(UUID jobId, JsonNode resultJson) {
        String sql = """
                UPDATE llm_job
                SET job_status = 'completed',
                    progress_pct = 100.0,
                    processed_items = total_items,
                    result_json = CAST(:resultJson AS jsonb),
                    error_message = NULL,
                    finished_at = NOW(),
                    updated_at = NOW()
                WHERE job_id = :jobId
                """;
        jdbcTemplate.update(
                sql,
                new MapSqlParameterSource()
                        .addValue("jobId", jobId)
                        .addValue("resultJson", resultJson.toString())
        );
    }

    @Transactional
    public void markJobFailed(UUID jobId, String errorMessage, JsonNode resultJson) {
        String sql = """
                UPDATE llm_job
                SET job_status = 'failed',
                    error_message = :errorMessage,
                    result_json = CAST(:resultJson AS jsonb),
                    finished_at = NOW(),
                    updated_at = NOW()
                WHERE job_id = :jobId
                """;
        jdbcTemplate.update(
                sql,
                new MapSqlParameterSource()
                        .addValue("jobId", jobId)
                        .addValue("errorMessage", errorMessage)
                        .addValue("resultJson", resultJson.toString())
        );
    }

    @Transactional
    public void queueJobWithBackoff(UUID jobId, int retryCount, Instant nextRunAt, String errorMessage) {
        String sql = """
                UPDATE llm_job
                SET job_status = 'queued',
                    retry_count = :retryCount,
                    next_run_at = :nextRunAt,
                    error_message = :errorMessage,
                    updated_at = NOW()
                WHERE job_id = :jobId
                """;
        jdbcTemplate.update(
                sql,
                new MapSqlParameterSource()
                        .addValue("jobId", jobId)
                        .addValue("retryCount", retryCount)
                        .addValue("nextRunAt", Timestamp.from(nextRunAt))
                        .addValue("errorMessage", errorMessage)
        );
    }

    @Transactional
    public void markJobProgress(UUID jobId, int processedItems, int totalItems, double progressPct, JsonNode checkpointJson) {
        String sql = """
                UPDATE llm_job
                SET processed_items = :processedItems,
                    total_items = :totalItems,
                    progress_pct = :progressPct,
                    last_checkpoint = CAST(:checkpointJson AS jsonb),
                    updated_at = NOW()
                WHERE job_id = :jobId
                """;
        jdbcTemplate.update(
                sql,
                new MapSqlParameterSource()
                        .addValue("jobId", jobId)
                        .addValue("processedItems", Math.max(0, processedItems))
                        .addValue("totalItems", Math.max(1, totalItems))
                        .addValue("progressPct", progressPct)
                        .addValue("checkpointJson", checkpointJson.toString())
        );
    }

    @Transactional
    public void requestPause(UUID jobId) {
        String sql = """
                UPDATE llm_job
                SET job_status = CASE WHEN job_status = 'running' THEN 'pause_requested' ELSE 'paused' END,
                    updated_at = NOW()
                WHERE job_id = :jobId
                  AND job_status IN ('queued', 'running')
                """;
        jdbcTemplate.update(sql, new MapSqlParameterSource("jobId", jobId));
    }

    @Transactional
    public void requestCancel(UUID jobId) {
        String sql = """
                UPDATE llm_job
                SET job_status = CASE WHEN job_status = 'running' THEN 'cancel_requested' ELSE 'cancelled' END,
                    finished_at = CASE WHEN job_status = 'running' THEN finished_at ELSE NOW() END,
                    updated_at = NOW()
                WHERE job_id = :jobId
                  AND job_status IN ('queued', 'running', 'paused')
                """;
        jdbcTemplate.update(sql, new MapSqlParameterSource("jobId", jobId));
    }

    @Transactional
    public void resumePaused(UUID jobId) {
        String sql = """
                UPDATE llm_job
                SET job_status = 'queued',
                    next_run_at = NOW(),
                    updated_at = NOW()
                WHERE job_id = :jobId
                  AND job_status = 'paused'
                """;
        jdbcTemplate.update(sql, new MapSqlParameterSource("jobId", jobId));
    }

    @Transactional
    public void retryFailed(UUID jobId) {
        String sql = """
                UPDATE llm_job
                SET job_status = 'queued',
                    next_run_at = NOW(),
                    error_message = NULL,
                    finished_at = NULL,
                    updated_at = NOW()
                WHERE job_id = :jobId
                  AND job_status = 'failed'
                """;
        jdbcTemplate.update(sql, new MapSqlParameterSource("jobId", jobId));
    }

    @Transactional
    public void markPaused(UUID jobId, JsonNode resultJson) {
        String sql = """
                UPDATE llm_job
                SET job_status = 'paused',
                    result_json = CAST(:resultJson AS jsonb),
                    updated_at = NOW()
                WHERE job_id = :jobId
                """;
        jdbcTemplate.update(
                sql,
                new MapSqlParameterSource()
                        .addValue("jobId", jobId)
                        .addValue("resultJson", resultJson.toString())
        );
    }

    @Transactional
    public void markCancelled(UUID jobId, JsonNode resultJson) {
        String sql = """
                UPDATE llm_job
                SET job_status = 'cancelled',
                    result_json = CAST(:resultJson AS jsonb),
                    finished_at = NOW(),
                    updated_at = NOW()
                WHERE job_id = :jobId
                """;
        jdbcTemplate.update(
                sql,
                new MapSqlParameterSource()
                        .addValue("jobId", jobId)
                        .addValue("resultJson", resultJson.toString())
        );
    }

    @Transactional
    public void markItemRunning(UUID jobItemId) {
        String sql = """
                UPDATE llm_job_item
                SET item_status = 'running',
                    started_at = NOW(),
                    finished_at = NULL,
                    error_message = NULL,
                    result_json = '{}'::jsonb
                WHERE job_item_id = :jobItemId
                """;
        jdbcTemplate.update(sql, new MapSqlParameterSource("jobItemId", jobItemId));
    }

    @Transactional
    public void resetRunningItemsToQueued(UUID jobId) {
        String sql = """
                UPDATE llm_job_item
                SET item_status = 'queued',
                    started_at = NULL,
                    finished_at = NULL,
                    error_message = NULL
                WHERE job_id = :jobId
                  AND item_status = 'running'
                """;
        jdbcTemplate.update(sql, new MapSqlParameterSource("jobId", jobId));
    }

    @Transactional
    public void prepareItemsForRetry(UUID jobId) {
        String sql = """
                UPDATE llm_job_item
                SET item_status = CASE WHEN item_status = 'completed' THEN 'completed' ELSE 'queued' END,
                    started_at = CASE WHEN item_status = 'completed' THEN started_at ELSE NULL END,
                    finished_at = CASE WHEN item_status = 'completed' THEN finished_at ELSE NULL END,
                    error_message = CASE WHEN item_status = 'completed' THEN error_message ELSE NULL END,
                    result_json = CASE WHEN item_status = 'completed' THEN result_json ELSE '{}'::jsonb END
                WHERE job_id = :jobId
                """;
        jdbcTemplate.update(sql, new MapSqlParameterSource("jobId", jobId));
    }

    @Transactional
    public void markItemCompleted(UUID jobItemId, JsonNode resultJson) {
        String sql = """
                UPDATE llm_job_item
                SET item_status = 'completed',
                    result_json = CAST(:resultJson AS jsonb),
                    finished_at = NOW()
                WHERE job_item_id = :jobItemId
                """;
        jdbcTemplate.update(
                sql,
                new MapSqlParameterSource()
                        .addValue("jobItemId", jobItemId)
                        .addValue("resultJson", resultJson.toString())
        );
    }

    @Transactional
    public void markItemFailed(UUID jobItemId, String errorMessage, JsonNode resultJson) {
        String sql = """
                UPDATE llm_job_item
                SET item_status = 'failed',
                    error_message = :errorMessage,
                    result_json = CAST(:resultJson AS jsonb),
                    finished_at = NOW()
                WHERE job_item_id = :jobItemId
                """;
        jdbcTemplate.update(
                sql,
                new MapSqlParameterSource()
                        .addValue("jobItemId", jobItemId)
                        .addValue("errorMessage", errorMessage)
                        .addValue("resultJson", resultJson.toString())
        );
    }

    @Transactional
    public void markRemainingItemsCancelled(UUID jobId) {
        String sql = """
                UPDATE llm_job_item
                SET item_status = 'cancelled',
                    finished_at = NOW()
                WHERE job_id = :jobId
                  AND item_status IN ('queued', 'running')
                """;
        jdbcTemplate.update(sql, new MapSqlParameterSource("jobId", jobId));
    }

    private AdminConsoleDtos.LlmJobRow mapJobRow(ResultSet rs) throws SQLException {
        return new AdminConsoleDtos.LlmJobRow(
                readUuid(rs, "job_id"),
                rs.getString("job_type"),
                rs.getString("job_status"),
                rs.getInt("priority"),
                readUuid(rs, "generation_batch_id"),
                readUuid(rs, "gating_batch_id"),
                readUuid(rs, "rag_test_run_id"),
                rs.getString("experiment_name"),
                rs.getString("command_name"),
                readJson(rs, "command_args"),
                rs.getInt("total_items"),
                rs.getInt("processed_items"),
                rs.getObject("progress_pct", Double.class),
                rs.getInt("retry_count"),
                rs.getInt("max_retries"),
                readInstant(rs, "next_run_at"),
                readInstant(rs, "started_at"),
                readInstant(rs, "finished_at"),
                rs.getString("error_message"),
                readJson(rs, "result_json"),
                readInstant(rs, "created_at")
        );
    }

    private int normalizeLimit(Integer value, int fallback) {
        if (value == null || value <= 0) {
            return fallback;
        }
        return Math.min(value, 500);
    }

    private UUID readUuid(ResultSet rs, String column) throws SQLException {
        Object value = rs.getObject(column);
        if (value == null) {
            return null;
        }
        if (value instanceof UUID uuid) {
            return uuid;
        }
        return UUID.fromString(String.valueOf(value));
    }

    private Instant readInstant(ResultSet rs, String column) throws SQLException {
        Timestamp timestamp = rs.getTimestamp(column);
        return timestamp != null ? timestamp.toInstant() : null;
    }

    private JsonNode readJson(ResultSet rs, String column) throws SQLException {
        return readJson(rs.getString(column));
    }

    private JsonNode readJson(String raw) {
        if (raw == null || raw.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            return objectMapper.readTree(raw);
        } catch (Exception exception) {
            return objectMapper.createObjectNode().put("raw", raw);
        }
    }
}
