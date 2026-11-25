package io.queryforge.backend.admin.corpus.run;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

import static io.queryforge.backend.admin.corpus.CorpusJdbc.normalizeLimit;
import static io.queryforge.backend.admin.corpus.CorpusJdbc.normalizeOffset;
import static io.queryforge.backend.admin.corpus.CorpusJdbc.readInstant;
import static io.queryforge.backend.admin.corpus.CorpusJdbc.readJson;
import static io.queryforge.backend.admin.corpus.CorpusJdbc.readNullableLong;
import static io.queryforge.backend.admin.corpus.CorpusJdbc.readUuid;

@Repository
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CorpusRunRepository {

    private final ObjectMapper objectMapper;

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public List<CorpusRunDtos.RunSummary> findRuns(
            UUID runId,
            String runStatus,
            String runType,
            UUID domainId,
            Integer limit,
            Integer offset
    ) {
        StringBuilder sql = new StringBuilder("""
                SELECT run_id,
                       domain_id,
                       run_type,
                       run_status,
                       trigger_type,
                       source_scope::text AS source_scope,
                       config_snapshot::text AS config_snapshot,
                       started_at,
                       finished_at,
                       duration_ms,
                       summary_json::text AS summary_json,
                       error_message,
                       created_by,
                       created_at,
                       cancel_requested_at,
                       updated_at
                FROM corpus_runs
                WHERE 1=1
                """);
        MapSqlParameterSource params = new MapSqlParameterSource();
        if (runId != null) {
            sql.append(" AND run_id = :runId");
            params.addValue("runId", runId);
        }
        if (runStatus != null && !runStatus.isBlank()) {
            sql.append(" AND run_status = :runStatus");
            params.addValue("runStatus", runStatus);
        }
        if (runType != null && !runType.isBlank()) {
            sql.append(" AND run_type = :runType");
            params.addValue("runType", runType);
        }
        if (domainId != null) {
            sql.append(" AND domain_id = :domainId");
            params.addValue("domainId", domainId);
        }
        sql.append(" ORDER BY created_at DESC LIMIT :limit OFFSET :offset");
        params.addValue("limit", normalizeLimit(limit));
        params.addValue("offset", normalizeOffset(offset));
        return jdbcTemplate.query(sql.toString(), params, runRowMapper());
    }

    public CorpusRunDtos.RunSummary findRun(UUID runId) {
        String sql = """
                SELECT run_id,
                       domain_id,
                       run_type,
                       run_status,
                       trigger_type,
                       source_scope::text AS source_scope,
                       config_snapshot::text AS config_snapshot,
                       started_at,
                       finished_at,
                       duration_ms,
                       summary_json::text AS summary_json,
                       error_message,
                       created_by,
                       created_at,
                       cancel_requested_at,
                       updated_at
                FROM corpus_runs
                WHERE run_id = :runId
                """;
        return jdbcTemplate.queryForObject(sql, new MapSqlParameterSource("runId", runId), runRowMapper());
    }

    public List<CorpusRunDtos.RunStep> findRunSteps(UUID runId) {
        String sql = """
                SELECT step_id,
                       step_name,
                       step_order,
                       step_status,
                       input_artifact_path,
                       output_artifact_path,
                       command_line,
                       metrics_json::text AS metrics_json,
                       started_at,
                       finished_at,
                       error_message,
                       stdout_log_path,
                       stderr_log_path,
                       stdout_excerpt,
                       stderr_excerpt,
                       updated_at
                FROM corpus_run_steps
                WHERE run_id = :runId
                ORDER BY step_order
                """;
        return jdbcTemplate.query(sql, new MapSqlParameterSource("runId", runId), runStepRowMapper());
    }

    private RowMapper<CorpusRunDtos.RunSummary> runRowMapper() {
        return (rs, rowNum) -> new CorpusRunDtos.RunSummary(
                readUuid(rs, "run_id"),
                readUuid(rs, "domain_id"),
                rs.getString("run_type"),
                rs.getString("run_status"),
                rs.getString("trigger_type"),
                readJson(objectMapper, rs, "source_scope"),
                readJson(objectMapper, rs, "config_snapshot"),
                readInstant(rs, "started_at"),
                readInstant(rs, "finished_at"),
                readNullableLong(rs, "duration_ms"),
                readJson(objectMapper, rs, "summary_json"),
                rs.getString("error_message"),
                rs.getString("created_by"),
                readInstant(rs, "created_at"),
                readInstant(rs, "cancel_requested_at"),
                readInstant(rs, "updated_at")
        );
    }

    private RowMapper<CorpusRunDtos.RunStep> runStepRowMapper() {
        return (rs, rowNum) -> new CorpusRunDtos.RunStep(
                readUuid(rs, "step_id"),
                rs.getString("step_name"),
                rs.getInt("step_order"),
                rs.getString("step_status"),
                rs.getString("input_artifact_path"),
                rs.getString("output_artifact_path"),
                rs.getString("command_line"),
                readJson(objectMapper, rs, "metrics_json"),
                readInstant(rs, "started_at"),
                readInstant(rs, "finished_at"),
                rs.getString("error_message"),
                rs.getString("stdout_log_path"),
                rs.getString("stderr_log_path"),
                rs.getString("stdout_excerpt"),
                rs.getString("stderr_excerpt"),
                readInstant(rs, "updated_at")
        );
    }
}
