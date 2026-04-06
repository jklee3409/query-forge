package io.queryforge.backend.admin.pipeline.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.queryforge.backend.admin.pipeline.model.PipelineAdminDtos;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Repository
public class PipelineAdminRepository {

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public PipelineAdminRepository(
            NamedParameterJdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public Optional<UUID> findActiveRunId() {
        String sql = """
                SELECT run_id
                FROM corpus_runs
                WHERE run_status IN ('queued', 'running')
                ORDER BY created_at DESC
                LIMIT 1
                """;
        List<UUID> runIds = jdbcTemplate.query(sql, (rs, rowNum) -> readUuid(rs, "run_id"));
        return runIds.stream().findFirst();
    }

    public void createRun(
            UUID runId,
            String runType,
            String triggerType,
            Map<String, Object> sourceScope,
            Map<String, Object> configSnapshot,
            String createdBy
    ) {
        String sql = """
                INSERT INTO corpus_runs (
                    run_id,
                    run_type,
                    run_status,
                    trigger_type,
                    source_scope,
                    config_snapshot,
                    summary_json,
                    created_by,
                    created_at,
                    updated_at
                ) VALUES (
                    :runId,
                    :runType,
                    'queued',
                    :triggerType,
                    CAST(:sourceScope AS jsonb),
                    CAST(:configSnapshot AS jsonb),
                    '{}'::jsonb,
                    :createdBy,
                    NOW(),
                    NOW()
                )
                """;
        jdbcTemplate.update(
                sql,
                new MapSqlParameterSource()
                        .addValue("runId", runId)
                        .addValue("runType", runType)
                        .addValue("triggerType", triggerType)
                        .addValue("sourceScope", writeJson(sourceScope))
                        .addValue("configSnapshot", writeJson(configSnapshot))
                        .addValue("createdBy", createdBy)
        );
    }

    public void createStep(
            UUID stepId,
            UUID runId,
            String stepName,
            int stepOrder,
            String inputArtifactPath,
            String outputArtifactPath
    ) {
        String sql = """
                INSERT INTO corpus_run_steps (
                    step_id,
                    run_id,
                    step_name,
                    step_order,
                    step_status,
                    input_artifact_path,
                    output_artifact_path,
                    metrics_json,
                    created_at,
                    updated_at
                ) VALUES (
                    :stepId,
                    :runId,
                    :stepName,
                    :stepOrder,
                    'queued',
                    :inputArtifactPath,
                    :outputArtifactPath,
                    '{}'::jsonb,
                    NOW(),
                    NOW()
                )
                """;
        jdbcTemplate.update(
                sql,
                new MapSqlParameterSource()
                        .addValue("stepId", stepId)
                        .addValue("runId", runId)
                        .addValue("stepName", stepName)
                        .addValue("stepOrder", stepOrder)
                        .addValue("inputArtifactPath", inputArtifactPath)
                        .addValue("outputArtifactPath", outputArtifactPath)
        );
    }

    public void markRunRunning(UUID runId) {
        String sql = """
                UPDATE corpus_runs
                SET run_status = 'running',
                    started_at = COALESCE(started_at, NOW()),
                    updated_at = NOW()
                WHERE run_id = :runId
                """;
        jdbcTemplate.update(sql, new MapSqlParameterSource("runId", runId));
    }

    public void finishRun(
            UUID runId,
            String runStatus,
            Map<String, Object> summaryJson,
            String errorMessage
    ) {
        String sql = """
                UPDATE corpus_runs
                SET run_status = :runStatus,
                    summary_json = CAST(:summaryJson AS jsonb),
                    error_message = :errorMessage,
                    finished_at = COALESCE(finished_at, NOW()),
                    duration_ms = CASE
                        WHEN started_at IS NULL THEN duration_ms
                        ELSE GREATEST(0, FLOOR(EXTRACT(EPOCH FROM (COALESCE(finished_at, NOW()) - started_at)) * 1000))::BIGINT
                    END,
                    updated_at = NOW()
                WHERE run_id = :runId
                """;
        jdbcTemplate.update(
                sql,
                new MapSqlParameterSource()
                        .addValue("runId", runId)
                        .addValue("runStatus", runStatus)
                        .addValue("summaryJson", writeJson(summaryJson))
                        .addValue("errorMessage", errorMessage)
        );
    }

    public void requestRunCancellation(UUID runId) {
        String sql = """
                UPDATE corpus_runs
                SET cancel_requested_at = NOW(),
                    updated_at = NOW()
                WHERE run_id = :runId
                """;
        jdbcTemplate.update(sql, new MapSqlParameterSource("runId", runId));
    }

    public void markStepRunning(
            UUID stepId,
            String commandLine,
            String stdoutLogPath,
            String stderrLogPath
    ) {
        String sql = """
                UPDATE corpus_run_steps
                SET step_status = 'running',
                    command_line = :commandLine,
                    stdout_log_path = :stdoutLogPath,
                    stderr_log_path = :stderrLogPath,
                    started_at = COALESCE(started_at, NOW()),
                    updated_at = NOW()
                WHERE step_id = :stepId
                """;
        jdbcTemplate.update(
                sql,
                new MapSqlParameterSource()
                        .addValue("stepId", stepId)
                        .addValue("commandLine", commandLine)
                        .addValue("stdoutLogPath", stdoutLogPath)
                        .addValue("stderrLogPath", stderrLogPath)
        );
    }

    public void finishStep(
            UUID stepId,
            String stepStatus,
            Map<String, Object> metricsJson,
            String errorMessage,
            String stdoutExcerpt,
            String stderrExcerpt,
            String outputArtifactPath
    ) {
        String sql = """
                UPDATE corpus_run_steps
                SET step_status = :stepStatus,
                    metrics_json = CAST(:metricsJson AS jsonb),
                    error_message = :errorMessage,
                    stdout_excerpt = :stdoutExcerpt,
                    stderr_excerpt = :stderrExcerpt,
                    output_artifact_path = COALESCE(:outputArtifactPath, output_artifact_path),
                    finished_at = COALESCE(finished_at, NOW()),
                    updated_at = NOW()
                WHERE step_id = :stepId
                """;
        jdbcTemplate.update(
                sql,
                new MapSqlParameterSource()
                        .addValue("stepId", stepId)
                        .addValue("stepStatus", stepStatus)
                        .addValue("metricsJson", writeJson(metricsJson))
                        .addValue("errorMessage", errorMessage)
                        .addValue("stdoutExcerpt", stdoutExcerpt)
                        .addValue("stderrExcerpt", stderrExcerpt)
                        .addValue("outputArtifactPath", outputArtifactPath)
        );
    }

    public void cancelQueuedSteps(UUID runId) {
        String sql = """
                UPDATE corpus_run_steps
                SET step_status = 'cancelled',
                    error_message = COALESCE(error_message, 'Cancellation requested by user.'),
                    finished_at = COALESCE(finished_at, NOW()),
                    updated_at = NOW()
                WHERE run_id = :runId
                  AND step_status = 'queued'
                """;
        jdbcTemplate.update(sql, new MapSqlParameterSource("runId", runId));
    }

    public void upsertSourceDefinition(
            String sourceId,
            String sourceType,
            String productName,
            String sourceName,
            String baseUrl,
            List<String> includePatterns,
            List<String> excludePatterns,
            String defaultVersion,
            boolean enabled
    ) {
        String sql = """
                INSERT INTO corpus_sources (
                    source_id,
                    source_type,
                    product_name,
                    source_name,
                    base_url,
                    include_patterns,
                    exclude_patterns,
                    default_version,
                    enabled,
                    created_at,
                    updated_at
                ) VALUES (
                    :sourceId,
                    :sourceType,
                    :productName,
                    :sourceName,
                    :baseUrl,
                    CAST(:includePatterns AS jsonb),
                    CAST(:excludePatterns AS jsonb),
                    :defaultVersion,
                    :enabled,
                    NOW(),
                    NOW()
                )
                ON CONFLICT (source_id) DO UPDATE
                SET source_type = EXCLUDED.source_type,
                    product_name = EXCLUDED.product_name,
                    source_name = EXCLUDED.source_name,
                    base_url = EXCLUDED.base_url,
                    include_patterns = EXCLUDED.include_patterns,
                    exclude_patterns = EXCLUDED.exclude_patterns,
                    default_version = COALESCE(corpus_sources.default_version, EXCLUDED.default_version),
                    updated_at = NOW()
                """;
        jdbcTemplate.update(
                sql,
                new MapSqlParameterSource()
                        .addValue("sourceId", sourceId)
                        .addValue("sourceType", sourceType)
                        .addValue("productName", productName)
                        .addValue("sourceName", sourceName)
                        .addValue("baseUrl", baseUrl)
                        .addValue("includePatterns", writeJson(includePatterns))
                        .addValue("excludePatterns", writeJson(excludePatterns))
                        .addValue("defaultVersion", defaultVersion)
                        .addValue("enabled", enabled)
        );
    }

    public void markStaleRunsFailed() {
        String runSql = """
                UPDATE corpus_runs
                SET run_status = 'failed',
                    finished_at = NOW(),
                    error_message = COALESCE(error_message, 'Application restarted before pipeline completion.'),
                    updated_at = NOW()
                WHERE run_status IN ('queued', 'running')
                """;
        String stepSql = """
                UPDATE corpus_run_steps
                SET step_status = CASE
                        WHEN step_status = 'success' THEN step_status
                        ELSE 'failed'
                    END,
                    error_message = COALESCE(error_message, 'Application restarted before step completion.'),
                    finished_at = COALESCE(finished_at, NOW()),
                    updated_at = NOW()
                WHERE run_id IN (
                    SELECT run_id
                    FROM corpus_runs
                    WHERE run_status = 'failed'
                      AND error_message = 'Application restarted before pipeline completion.'
                )
                  AND step_status IN ('queued', 'running')
                """;
        jdbcTemplate.update(runSql, new MapSqlParameterSource());
        jdbcTemplate.update(stepSql, new MapSqlParameterSource());
    }

    public PipelineAdminDtos.DashboardStats fetchDashboardStats() {
        long sourceCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM corpus_sources",
                new MapSqlParameterSource(),
                Long.class
        );
        long activeDocumentCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM corpus_documents WHERE is_active = TRUE",
                new MapSqlParameterSource(),
                Long.class
        );
        long activeChunkCount = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM corpus_chunks c
                JOIN corpus_documents d ON d.document_id = c.document_id
                WHERE d.is_active = TRUE
                """,
                new MapSqlParameterSource(),
                Long.class
        );
        long glossaryTermCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM corpus_glossary_terms WHERE is_active = TRUE",
                new MapSqlParameterSource(),
                Long.class
        );
        long recentRunSuccessCount = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM corpus_runs
                WHERE created_at >= NOW() - INTERVAL '7 days'
                  AND run_status = 'success'
                """,
                new MapSqlParameterSource(),
                Long.class
        );
        long recentRunFailureCount = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM corpus_runs
                WHERE created_at >= NOW() - INTERVAL '7 days'
                  AND run_status = 'failed'
                """,
                new MapSqlParameterSource(),
                Long.class
        );

        List<PipelineAdminDtos.ProductDocumentStat> productStats = jdbcTemplate.query(
                """
                SELECT product_name, COUNT(*) AS document_count
                FROM corpus_documents
                WHERE is_active = TRUE
                GROUP BY product_name
                ORDER BY COUNT(*) DESC, product_name
                """,
                (rs, rowNum) -> new PipelineAdminDtos.ProductDocumentStat(
                        rs.getString("product_name"),
                        rs.getLong("document_count")
                )
        );

        List<PipelineAdminDtos.RecentRunStat> recentRuns = jdbcTemplate.query(
                """
                SELECT run_id, run_type, run_status, started_at, finished_at, created_by
                FROM corpus_runs
                ORDER BY created_at DESC
                LIMIT 10
                """,
                recentRunRowMapper()
        );

        List<PipelineAdminDtos.FailedStepStat> failedSteps = jdbcTemplate.query(
                """
                SELECT rs.run_id, rs.step_id, rs.step_name, rs.error_message, rs.finished_at
                FROM corpus_run_steps rs
                JOIN corpus_runs r ON r.run_id = rs.run_id
                WHERE rs.step_status = 'failed'
                ORDER BY COALESCE(rs.finished_at, r.updated_at) DESC
                LIMIT 10
                """,
                failedStepRowMapper()
        );

        return new PipelineAdminDtos.DashboardStats(
                sourceCount,
                activeDocumentCount,
                activeChunkCount,
                glossaryTermCount,
                recentRunSuccessCount,
                recentRunFailureCount,
                productStats,
                recentRuns,
                failedSteps
        );
    }

    private RowMapper<PipelineAdminDtos.RecentRunStat> recentRunRowMapper() {
        return (rs, rowNum) -> new PipelineAdminDtos.RecentRunStat(
                readUuid(rs, "run_id"),
                rs.getString("run_type"),
                rs.getString("run_status"),
                readInstant(rs, "started_at"),
                readInstant(rs, "finished_at"),
                rs.getString("created_by")
        );
    }

    private RowMapper<PipelineAdminDtos.FailedStepStat> failedStepRowMapper() {
        return (rs, rowNum) -> new PipelineAdminDtos.FailedStepStat(
                readUuid(rs, "run_id"),
                readUuid(rs, "step_id"),
                rs.getString("step_name"),
                rs.getString("error_message"),
                readInstant(rs, "finished_at")
        );
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Failed to serialize JSON payload.", exception);
        }
    }

    private UUID readUuid(ResultSet rs, String column) throws SQLException {
        Object value = rs.getObject(column);
        if (value == null) {
            return null;
        }
        if (value instanceof UUID uuid) {
            return uuid;
        }
        return UUID.fromString(value.toString());
    }

    private Instant readInstant(ResultSet rs, String column) throws SQLException {
        Timestamp timestamp = rs.getTimestamp(column);
        return timestamp != null ? timestamp.toInstant() : null;
    }
}
