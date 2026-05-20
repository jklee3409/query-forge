package io.queryforge.backend.admin.pipeline.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.queryforge.backend.admin.persistence.entity.CorpusRunEntity;
import io.queryforge.backend.admin.persistence.repository.CorpusRunJpaRepository;
import io.queryforge.backend.admin.pipeline.model.PipelineAdminDtos;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
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
    private final CorpusRunJpaRepository runRepository;

    @PersistenceContext
    private EntityManager entityManager;

    public Optional<UUID> findActiveRunId() {
        return runRepository.findFirstByRunStatusInOrderByCreatedAtDesc(List.of("queued", "running"))
                .map(CorpusRunEntity::getRunId);
    }

    @Transactional
    public void acquirePipelineStartLock() {
        entityManager.createNativeQuery("SELECT pg_advisory_xact_lock(:lockKey)")
                .setParameter("lockKey", PIPELINE_START_LOCK_KEY)
                .getSingleResult();
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
        executeUpdate(
                """
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
                    enabled = EXCLUDED.enabled,
                    updated_at = NOW()
                """,
                params(
                        "sourceId", sourceId,
                        "sourceType", sourceType,
                        "productName", productName,
                        "sourceName", sourceName,
                        "baseUrl", baseUrl,
                        "includePatterns", writeJson(includePatterns),
                        "excludePatterns", writeJson(excludePatterns),
                        "defaultVersion", defaultVersion,
                        "enabled", enabled
                )
        );
    }

    @Transactional
    public void assignConfiguredSourceDomain(String sourceId, String domainKey) {
        Map<String, Object> parameters = params("sourceId", sourceId, "domainKey", domainKey);
        executeUpdate(
                """
                UPDATE corpus_sources cs
                SET domain_id = d.domain_id,
                    updated_at = NOW()
                FROM tech_doc_domain d
                WHERE cs.source_id = :sourceId
                  AND d.domain_key = :domainKey
                  AND cs.domain_id IS DISTINCT FROM d.domain_id
                """,
                parameters
        );
        executeUpdate(
                """
                INSERT INTO tech_doc_domain_source (domain_id, source_id, source_role, active)
                SELECT d.domain_id, cs.source_id, 'primary', TRUE
                FROM tech_doc_domain d
                JOIN corpus_sources cs ON cs.source_id = :sourceId
                WHERE d.domain_key = :domainKey
                ON CONFLICT (source_id) DO UPDATE
                SET domain_id = EXCLUDED.domain_id,
                    source_role = EXCLUDED.source_role,
                    active = EXCLUDED.active
                """,
                parameters
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

    public PipelineAdminDtos.DashboardStats fetchDashboardStats(UUID domainId) {
        long sourceCount = queryForLong(
                "SELECT COUNT(*) FROM corpus_sources" + domainWhere(domainId, "corpus_sources"),
                domainId
        );
        long activeDocumentCount = queryForLong(
                "SELECT COUNT(*) FROM corpus_documents WHERE is_active = TRUE" + domainAnd(domainId, "corpus_documents"),
                domainId
        );
        long activeChunkCount = queryForLong(
                """
                SELECT COUNT(*)
                FROM corpus_chunks c
                JOIN corpus_documents d ON d.document_id = c.document_id
                WHERE d.is_active = TRUE
                """ + domainAnd(domainId, "d"),
                domainId
        );
        long glossaryTermCount = queryForLong(
                "SELECT COUNT(*) FROM corpus_glossary_terms WHERE is_active = TRUE" + domainAnd(domainId, "corpus_glossary_terms"),
                domainId
        );
        long duplicateUrlSkippedCount = queryForLong(importMetricSql("duplicate_url_skipped", domainId), domainId);
        long sameHashSkippedCount = queryForLong(importMetricSql("same_title_hash_skipped", domainId), domainId);
        long unchangedSkippedCount = queryForLong(importMetricSql("unchanged_content_skipped", domainId), domainId);
        long recentRunSuccessCount = queryForLong(
                """
                SELECT COUNT(*)
                FROM corpus_runs
                WHERE created_at >= NOW() - INTERVAL '7 days'
                  AND run_status = 'success'
                """ + domainAnd(domainId, "corpus_runs"),
                domainId
        );
        long recentRunFailureCount = queryForLong(
                """
                SELECT COUNT(*)
                FROM corpus_runs
                WHERE created_at >= NOW() - INTERVAL '7 days'
                  AND run_status = 'failed'
                """ + domainAnd(domainId, "corpus_runs"),
                domainId
        );

        List<Object[]> productRows = queryRows(
                """
                SELECT product_name, COUNT(*) AS document_count
                FROM corpus_documents d
                WHERE is_active = TRUE
                """ + domainAnd(domainId, "d") + """
                GROUP BY product_name
                ORDER BY COUNT(*) DESC, product_name
                """,
                domainId
        );
        List<PipelineAdminDtos.ProductDocumentStat> productStats = productRows.stream()
                .map(row -> new PipelineAdminDtos.ProductDocumentStat(
                        stringValue(row[0]),
                        longValue(row[1])
                ))
                .toList();

        List<Object[]> recentRunRows = queryRows(
                """
                SELECT run_id, run_type, run_status, started_at, finished_at, created_by
                FROM corpus_runs
                """ + domainWhere(domainId, "corpus_runs") + """
                ORDER BY created_at DESC
                LIMIT 10
                """,
                domainId
        );
        List<PipelineAdminDtos.RecentRunStat> recentRuns = recentRunRows.stream()
                .map(row -> new PipelineAdminDtos.RecentRunStat(
                        uuidValue(row[0]),
                        stringValue(row[1]),
                        stringValue(row[2]),
                        instantValue(row[3]),
                        instantValue(row[4]),
                        stringValue(row[5])
                ))
                .toList();

        List<Object[]> failedStepRows = queryRows(
                """
                SELECT rs.run_id, rs.step_id, rs.step_name, rs.error_message, rs.finished_at
                FROM corpus_run_steps rs
                JOIN corpus_runs r ON r.run_id = rs.run_id
                WHERE rs.step_status IN ('failed', 'warning')
                """ + domainAnd(domainId, "r") + """
                ORDER BY COALESCE(rs.finished_at, r.updated_at) DESC
                LIMIT 10
                """,
                domainId
        );
        List<PipelineAdminDtos.FailedStepStat> failedSteps = failedStepRows.stream()
                .map(row -> new PipelineAdminDtos.FailedStepStat(
                        uuidValue(row[0]),
                        uuidValue(row[1]),
                        stringValue(row[2]),
                        stringValue(row[3]),
                        instantValue(row[4])
                ))
                .toList();

        return new PipelineAdminDtos.DashboardStats(
                sourceCount,
                activeDocumentCount,
                activeChunkCount,
                glossaryTermCount,
                duplicateUrlSkippedCount,
                sameHashSkippedCount,
                unchangedSkippedCount,
                recentRunSuccessCount,
                recentRunFailureCount,
                productStats,
                recentRuns,
                failedSteps
        );
    }

    private void executeUpdate(String sql, Map<String, Object> parameters) {
        Query query = entityManager.createNativeQuery(sql);
        parameters.forEach(query::setParameter);
        query.executeUpdate();
    }

    private Map<String, Object> params(Object... keyValuePairs) {
        Map<String, Object> parameters = new LinkedHashMap<>();
        for (int index = 0; index < keyValuePairs.length; index += 2) {
            parameters.put(String.valueOf(keyValuePairs[index]), keyValuePairs[index + 1]);
        }
        return parameters;
    }

    private String importMetricSql(String metricKey, UUID domainId) {
        return """
                SELECT COALESCE(
                    SUM(
                        COALESCE(
                            NULLIF(metrics_json ->> '%1$s', '')::BIGINT,
                            NULLIF(metrics_json -> 'dedupe_summary' ->> '%1$s', '')::BIGINT,
                            0
                        )
                    ),
                    0
                )
                FROM corpus_run_steps rs
                JOIN corpus_runs r ON r.run_id = rs.run_id
                WHERE rs.step_name = 'import_docs'
                  AND r.created_at >= NOW() - INTERVAL '30 days'
                """.formatted(metricKey) + domainAnd(domainId, "r");
    }

    private String domainWhere(UUID domainId, String alias) {
        return domainId == null ? "" : " WHERE " + alias + ".domain_id = :domainId ";
    }

    private String domainAnd(UUID domainId, String alias) {
        return domainId == null ? "" : " AND " + alias + ".domain_id = :domainId ";
    }

    private long queryForLong(String sql) {
        Object value = entityManager.createNativeQuery(sql).getSingleResult();
        return longValue(value);
    }

    private long queryForLong(String sql, UUID domainId) {
        Query query = entityManager.createNativeQuery(sql);
        if (domainId != null) {
            query.setParameter("domainId", domainId);
        }
        return longValue(query.getSingleResult());
    }

    @SuppressWarnings("unchecked")
    private List<Object[]> queryRows(String sql) {
        return entityManager.createNativeQuery(sql).getResultList();
    }

    @SuppressWarnings("unchecked")
    private List<Object[]> queryRows(String sql, UUID domainId) {
        Query query = entityManager.createNativeQuery(sql);
        if (domainId != null) {
            query.setParameter("domainId", domainId);
        }
        return query.getResultList();
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Failed to serialize JSON payload.", exception);
        }
    }

    private UUID uuidValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof UUID uuid) {
            return uuid;
        }
        return UUID.fromString(String.valueOf(value));
    }

    private Instant instantValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Instant instant) {
            return instant;
        }
        if (value instanceof Timestamp timestamp) {
            return timestamp.toInstant();
        }
        if (value instanceof java.time.OffsetDateTime offsetDateTime) {
            return offsetDateTime.toInstant();
        }
        throw new IllegalArgumentException("Unsupported timestamp value: " + value.getClass());
    }

    private long longValue(Object value) {
        if (value == null) {
            return 0L;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        return Long.parseLong(String.valueOf(value));
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
