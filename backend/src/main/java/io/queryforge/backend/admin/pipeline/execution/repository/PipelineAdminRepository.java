package io.queryforge.backend.admin.pipeline.execution.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PipelineAdminRepository {

    private static final long PIPELINE_START_LOCK_KEY = 937_511_042L;
    private final ObjectMapper objectMapper;
    private final NamedParameterJdbcTemplate jdbcTemplate;

    public Optional<UUID> findActiveRunId() {
        String sql = """
                SELECT run_id
                FROM corpus_runs
                WHERE run_status IN (:statuses)
                ORDER BY created_at DESC
                LIMIT 1
                """;
        List<UUID> rows = jdbcTemplate.query(
                sql,
                new MapSqlParameterSource("statuses", List.of("queued", "running")),
                (rs, rowNum) -> readUuid(rs, "run_id")
        );
        return rows.stream().findFirst();
    }

    @Transactional
    public void acquirePipelineStartLock() {
        jdbcTemplate.queryForObject(
                "SELECT pg_advisory_xact_lock(:lockKey)",
                new MapSqlParameterSource("lockKey", PIPELINE_START_LOCK_KEY),
                Object.class
        );
    }

    @Transactional
    public void createRun(
            UUID runId,
            String runType,
            UUID domainId,
            String triggerType,
            Map<String, Object> sourceScope,
            Map<String, Object> configSnapshot,
            String createdBy
    ) {
        executeUpdate(
                """
                INSERT INTO corpus_runs (
                    run_id,
                    domain_id,
                    run_type,
                    run_status,
                    trigger_type,
                    source_scope,
                    config_snapshot,
                    summary_json,
                    error_message,
                    created_by,
                    started_at,
                    finished_at,
                    duration_ms,
                    cancel_requested_at,
                    created_at,
                    updated_at
                ) VALUES (
                    :runId,
                    :domainId,
                    :runType,
                    'queued',
                    :triggerType,
                    CAST(:sourceScope AS jsonb),
                    CAST(:configSnapshot AS jsonb),
                    '{}'::jsonb,
                    NULL,
                    :createdBy,
                    NULL,
                    NULL,
                    NULL,
                    NULL,
                    NOW(),
                    NOW()
                )
                """,
                params(
                        "runId", runId,
                        "domainId", domainId,
                        "runType", runType,
                        "triggerType", triggerType,
                        "sourceScope", writeJson(sourceScope),
                        "configSnapshot", writeJson(configSnapshot),
                        "createdBy", createdBy
                )
        );
    }

    @Transactional
    public void createStep(
            UUID stepId,
            UUID runId,
            String stepName,
            int stepOrder,
            String inputArtifactPath,
            String outputArtifactPath
    ) {
        executeUpdate(
                """
                INSERT INTO corpus_run_steps (
                    step_id,
                    run_id,
                    step_name,
                    step_order,
                    step_status,
                    input_artifact_path,
                    output_artifact_path,
                    command_line,
                    stdout_log_path,
                    stderr_log_path,
                    stdout_excerpt,
                    stderr_excerpt,
                    metrics_json,
                    started_at,
                    finished_at,
                    error_message,
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
                    NULL,
                    NULL,
                    NULL,
                    NULL,
                    NULL,
                    '{}'::jsonb,
                    NULL,
                    NULL,
                    NULL,
                    NOW(),
                    NOW()
                )
                """,
                params(
                        "stepId", stepId,
                        "runId", runId,
                        "stepName", stepName,
                        "stepOrder", stepOrder,
                        "inputArtifactPath", inputArtifactPath,
                        "outputArtifactPath", outputArtifactPath
                )
        );
    }

    @Transactional
    public void propagateImportedRunDomain(UUID runId, UUID domainId) {
        Map<String, Object> parameters = params("runId", runId, "domainId", domainId);
        executeUpdate(
                """
                UPDATE corpus_runs
                SET domain_id = :domainId,
                    updated_at = NOW()
                WHERE run_id = :runId
                """,
                parameters
        );
        executeUpdate(
                """
                UPDATE corpus_documents
                SET domain_id = :domainId,
                    updated_at = NOW()
                WHERE import_run_id = :runId
                """,
                parameters
        );
        executeUpdate(
                """
                UPDATE corpus_sections s
                SET domain_id = :domainId,
                    updated_at = NOW()
                WHERE import_run_id = :runId
                   OR EXISTS (
                       SELECT 1
                       FROM corpus_documents d
                       WHERE d.document_id = s.document_id
                         AND d.import_run_id = :runId
                   )
                """,
                parameters
        );
        executeUpdate(
                """
                UPDATE corpus_chunks c
                SET domain_id = :domainId,
                    updated_at = NOW()
                WHERE import_run_id = :runId
                   OR EXISTS (
                       SELECT 1
                       FROM corpus_documents d
                       WHERE d.document_id = c.document_id
                         AND d.import_run_id = :runId
                   )
                """,
                parameters
        );
        executeUpdate(
                """
                UPDATE corpus_chunk_relations r
                SET domain_id = :domainId
                WHERE import_run_id = :runId
                   OR EXISTS (
                       SELECT 1
                       FROM corpus_chunks c
                       WHERE c.chunk_id IN (r.source_chunk_id, r.target_chunk_id)
                         AND c.import_run_id = :runId
                   )
                """,
                parameters
        );
        executeUpdate(
                """
                UPDATE corpus_glossary_terms t
                SET domain_id = :domainId,
                    updated_at = NOW()
                WHERE import_run_id = :runId
                   OR EXISTS (
                       SELECT 1
                       FROM corpus_chunks c
                       WHERE c.chunk_id = t.first_seen_chunk_id
                         AND c.import_run_id = :runId
                   )
                """,
                parameters
        );
        executeUpdate(
                """
                UPDATE corpus_glossary_aliases a
                SET domain_id = :domainId
                WHERE import_run_id = :runId
                   OR EXISTS (
                       SELECT 1
                       FROM corpus_glossary_terms t
                       WHERE t.term_id = a.term_id
                         AND t.domain_id = :domainId
                   )
                """,
                parameters
        );
        executeUpdate(
                """
                UPDATE corpus_glossary_evidence e
                SET domain_id = :domainId
                WHERE import_run_id = :runId
                   OR EXISTS (
                       SELECT 1
                       FROM corpus_chunks c
                       WHERE c.chunk_id = e.chunk_id
                         AND c.import_run_id = :runId
                   )
                """,
                parameters
        );
    }

    @Transactional
    public void markRunRunning(UUID runId) {
        executeUpdate(
                """
                UPDATE corpus_runs
                SET run_status = 'running',
                    started_at = COALESCE(started_at, NOW()),
                    updated_at = NOW()
                WHERE run_id = :runId
                """,
                params("runId", runId)
        );
    }

    @Transactional
    public void finishRun(
            UUID runId,
            String runStatus,
            Map<String, Object> summaryJson,
            String errorMessage
    ) {
        executeUpdate(
                """
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
                """,
                params(
                        "runId", runId,
                        "runStatus", runStatus,
                        "summaryJson", writeJson(summaryJson),
                        "errorMessage", errorMessage
                )
        );
    }

    @Transactional
    public void requestRunCancellation(UUID runId) {
        executeUpdate(
                """
                UPDATE corpus_runs
                SET cancel_requested_at = NOW(),
                    updated_at = NOW()
                WHERE run_id = :runId
                """,
                params("runId", runId)
        );
    }

    @Transactional
    public void markStepRunning(
            UUID stepId,
            String commandLine,
            String stdoutLogPath,
            String stderrLogPath
    ) {
        executeUpdate(
                """
                UPDATE corpus_run_steps
                SET step_status = 'running',
                    command_line = :commandLine,
                    stdout_log_path = :stdoutLogPath,
                    stderr_log_path = :stderrLogPath,
                    started_at = COALESCE(started_at, NOW()),
                    updated_at = NOW()
                WHERE step_id = :stepId
                """,
                params(
                        "stepId", stepId,
                        "commandLine", commandLine,
                        "stdoutLogPath", stdoutLogPath,
                        "stderrLogPath", stderrLogPath
                )
        );
    }

    @Transactional
    public void finishStep(
            UUID stepId,
            String stepStatus,
            Map<String, Object> metricsJson,
            String errorMessage,
            String stdoutExcerpt,
            String stderrExcerpt,
            String outputArtifactPath
    ) {
        executeUpdate(
                """
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
                """,
                params(
                        "stepId", stepId,
                        "stepStatus", stepStatus,
                        "metricsJson", writeJson(metricsJson),
                        "errorMessage", errorMessage,
                        "stdoutExcerpt", stdoutExcerpt,
                        "stderrExcerpt", stderrExcerpt,
                        "outputArtifactPath", outputArtifactPath
                )
        );
    }

    @Transactional
    public void cancelQueuedSteps(UUID runId) {
        executeUpdate(
                """
                UPDATE corpus_run_steps
                SET step_status = 'cancelled',
                    error_message = COALESCE(error_message, 'Cancellation requested by user.'),
                    finished_at = COALESCE(finished_at, NOW()),
                    updated_at = NOW()
                WHERE run_id = :runId
                  AND step_status = 'queued'
                """,
                params("runId", runId)
        );
    }

    @Transactional
    public void markStaleRunsFailed() {
        executeUpdate(
                """
                UPDATE corpus_runs
                SET run_status = 'failed',
                    finished_at = NOW(),
                    error_message = COALESCE(error_message, 'Application restarted before pipeline completion.'),
                    updated_at = NOW()
                WHERE run_status IN ('queued', 'running')
                """,
                Map.of()
        );
        executeUpdate(
                """
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
                """,
                Map.of()
        );
    }

    private void executeUpdate(String sql, Map<String, Object> parameters) {
        jdbcTemplate.update(sql, new MapSqlParameterSource(parameters));
    }

    private Map<String, Object> params(Object... keyValuePairs) {
        Map<String, Object> parameters = new LinkedHashMap<>();
        for (int index = 0; index < keyValuePairs.length; index += 2) {
            parameters.put(String.valueOf(keyValuePairs[index]), keyValuePairs[index + 1]);
        }
        return parameters;
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
        return UUID.fromString(String.valueOf(value));
    }
}
