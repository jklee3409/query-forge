package io.queryforge.backend.admin.pipeline.dashboard;

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
import java.util.UUID;

@Repository
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PipelineDashboardRepository {

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public PipelineDashboardDtos.DashboardStats fetchDashboardStats(UUID domainId) {
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
        List<PipelineDashboardDtos.ProductDocumentStat> productStats = productRows.stream()
                .map(row -> new PipelineDashboardDtos.ProductDocumentStat(
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
        List<PipelineDashboardDtos.RecentRunStat> recentRuns = recentRunRows.stream()
                .map(row -> new PipelineDashboardDtos.RecentRunStat(
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
        List<PipelineDashboardDtos.FailedStepStat> failedSteps = failedStepRows.stream()
                .map(row -> new PipelineDashboardDtos.FailedStepStat(
                        uuidValue(row[0]),
                        uuidValue(row[1]),
                        stringValue(row[2]),
                        stringValue(row[3]),
                        instantValue(row[4])
                ))
                .toList();

        return new PipelineDashboardDtos.DashboardStats(
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
        Object value = jdbcTemplate.queryForObject(sql, new MapSqlParameterSource(), Object.class);
        return longValue(value);
    }

    private long queryForLong(String sql, UUID domainId) {
        MapSqlParameterSource parameters = new MapSqlParameterSource();
        if (domainId != null) {
            parameters.addValue("domainId", domainId);
        }
        Object value = jdbcTemplate.queryForObject(sql, parameters, Object.class);
        return longValue(value);
    }

    private List<Object[]> queryRows(String sql) {
        return jdbcTemplate.query(sql, new MapSqlParameterSource(), this::readObjectArray);
    }

    private List<Object[]> queryRows(String sql, UUID domainId) {
        MapSqlParameterSource parameters = new MapSqlParameterSource();
        if (domainId != null) {
            parameters.addValue("domainId", domainId);
        }
        return jdbcTemplate.query(sql, parameters, this::readObjectArray);
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

    private Object[] readObjectArray(ResultSet rs, int rowNum) throws SQLException {
        int columnCount = rs.getMetaData().getColumnCount();
        Object[] values = new Object[columnCount];
        for (int index = 1; index <= columnCount; index++) {
            values[index - 1] = rs.getObject(index);
        }
        return values;
    }
}
