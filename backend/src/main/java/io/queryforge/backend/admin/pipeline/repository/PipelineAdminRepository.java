package io.queryforge.backend.admin.pipeline.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.queryforge.backend.admin.persistence.entity.CorpusRunEntity;
import io.queryforge.backend.admin.persistence.entity.CorpusRunStepEntity;
import io.queryforge.backend.admin.persistence.entity.CorpusSourceEntity;
import io.queryforge.backend.admin.persistence.repository.CorpusRunJpaRepository;
import io.queryforge.backend.admin.persistence.repository.CorpusRunStepJpaRepository;
import io.queryforge.backend.admin.persistence.repository.CorpusSourceJpaRepository;
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

    private final ObjectMapper objectMapper;
    private final CorpusRunJpaRepository runRepository;
    private final CorpusRunStepJpaRepository runStepRepository;
    private final CorpusSourceJpaRepository sourceRepository;

    @PersistenceContext
    private EntityManager entityManager;

    public Optional<UUID> findActiveRunId() {
        return runRepository.findFirstByRunStatusInOrderByCreatedAtDesc(List.of("queued", "running"))
                .map(CorpusRunEntity::getRunId);
    }

    @Transactional
    public void createRun(
            UUID runId,
            String runType,
            String triggerType,
            Map<String, Object> sourceScope,
            Map<String, Object> configSnapshot,
            String createdBy
    ) {
        CorpusRunEntity run = CorpusRunEntity.builder()
                .runId(runId)
                .runType(runType)
                .runStatus("queued")
                .triggerType(triggerType)
                .sourceScope(writeJson(sourceScope))
                .configSnapshot(writeJson(configSnapshot))
                .summaryJson("{}")
                .createdBy(createdBy)
                .build();
        Instant now = Instant.now();
        run.setCreatedAt(now);
        run.setUpdatedAt(now);
        runRepository.save(run);
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
        CorpusRunEntity runRef = entityManager.getReference(CorpusRunEntity.class, runId);
        CorpusRunStepEntity step = CorpusRunStepEntity.builder()
                .stepId(stepId)
                .run(runRef)
                .stepName(stepName)
                .stepOrder(stepOrder)
                .stepStatus("queued")
                .inputArtifactPath(inputArtifactPath)
                .outputArtifactPath(outputArtifactPath)
                .metricsJson("{}")
                .build();
        Instant now = Instant.now();
        step.setCreatedAt(now);
        step.setUpdatedAt(now);
        runStepRepository.save(step);
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
        CorpusSourceEntity source = sourceRepository.findById(sourceId).orElseGet(() -> {
            CorpusSourceEntity entity = new CorpusSourceEntity();
            entity.setSourceId(sourceId);
            entity.setCreatedAt(Instant.now());
            return entity;
        });
        source.setSourceType(sourceType);
        source.setProductName(productName);
        source.setSourceName(sourceName);
        source.setBaseUrl(baseUrl);
        source.setIncludePatterns(writeJson(includePatterns));
        source.setExcludePatterns(writeJson(excludePatterns));
        if (source.getDefaultVersion() == null) {
            source.setDefaultVersion(defaultVersion);
        }
        source.setEnabled(enabled);
        source.setUpdatedAt(Instant.now());
        sourceRepository.save(source);
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

    public PipelineAdminDtos.DashboardStats fetchDashboardStats() {
        long sourceCount = queryForLong("SELECT COUNT(*) FROM corpus_sources");
        long activeDocumentCount = queryForLong("SELECT COUNT(*) FROM corpus_documents WHERE is_active = TRUE");
        long activeChunkCount = queryForLong(
                """
                SELECT COUNT(*)
                FROM corpus_chunks c
                JOIN corpus_documents d ON d.document_id = c.document_id
                WHERE d.is_active = TRUE
                """
        );
        long glossaryTermCount = queryForLong("SELECT COUNT(*) FROM corpus_glossary_terms WHERE is_active = TRUE");
        long recentRunSuccessCount = queryForLong(
                """
                SELECT COUNT(*)
                FROM corpus_runs
                WHERE created_at >= NOW() - INTERVAL '7 days'
                  AND run_status = 'success'
                """
        );
        long recentRunFailureCount = queryForLong(
                """
                SELECT COUNT(*)
                FROM corpus_runs
                WHERE created_at >= NOW() - INTERVAL '7 days'
                  AND run_status = 'failed'
                """
        );

        List<Object[]> productRows = queryRows(
                """
                SELECT product_name, COUNT(*) AS document_count
                FROM corpus_documents
                WHERE is_active = TRUE
                GROUP BY product_name
                ORDER BY COUNT(*) DESC, product_name
                """
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
                ORDER BY created_at DESC
                LIMIT 10
                """
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
                WHERE rs.step_status = 'failed'
                ORDER BY COALESCE(rs.finished_at, r.updated_at) DESC
                LIMIT 10
                """
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

    private long queryForLong(String sql) {
        Object value = entityManager.createNativeQuery(sql).getSingleResult();
        return longValue(value);
    }

    @SuppressWarnings("unchecked")
    private List<Object[]> queryRows(String sql) {
        return entityManager.createNativeQuery(sql).getResultList();
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
