package io.queryforge.backend.rag.experiment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static io.queryforge.backend.rag.persistence.RagJdbc.readInstant;
import static io.queryforge.backend.rag.persistence.RagJdbc.readJson;
import static io.queryforge.backend.rag.persistence.RagJdbc.readUuid;

@Repository
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RagExperimentRepository {

    private final ObjectMapper objectMapper;
    private final NamedParameterJdbcTemplate jdbcTemplate;

    public record ExperimentRunSummary(
            UUID experimentRunId,
            String experimentKey,
            String status,
            Instant startedAt,
            Instant finishedAt,
            JsonNode parameters,
            JsonNode metrics,
            String notes
    ) {
    }

    public Optional<ExperimentRunSummary> findExperimentRunSummary(UUID experimentRunId) {
        String sql = """
                SELECT er.experiment_run_id,
                       e.experiment_key,
                       er.status,
                       er.started_at,
                       er.finished_at,
                       er.parameters::text AS parameters,
                       er.metrics::text AS metrics,
                       er.notes
                FROM experiment_runs er
                JOIN experiments e ON e.experiment_id = er.experiment_id
                WHERE er.experiment_run_id = :experimentRunId
                """;
        List<ExperimentRunSummary> rows = jdbcTemplate.query(
                sql,
                new MapSqlParameterSource("experimentRunId", experimentRunId),
                (rs, rowNum) -> new ExperimentRunSummary(
                        readUuid(rs, "experiment_run_id"),
                        rs.getString("experiment_key"),
                        rs.getString("status"),
                        readInstant(rs, "started_at"),
                        readInstant(rs, "finished_at"),
                        readJson(objectMapper, rs, "parameters"),
                        readJson(objectMapper, rs, "metrics"),
                        rs.getString("notes")
                )
        );
        return rows.stream().findFirst();
    }

    public List<ExperimentRunSummary> listRecentExperimentRuns(int limit) {
        String sql = """
                SELECT er.experiment_run_id,
                       e.experiment_key,
                       er.status,
                       er.started_at,
                       er.finished_at,
                       er.parameters::text AS parameters,
                       er.metrics::text AS metrics,
                       er.notes
                FROM experiment_runs er
                JOIN experiments e ON e.experiment_id = er.experiment_id
                ORDER BY er.created_at DESC
                LIMIT :limit
                """;
        return jdbcTemplate.query(
                sql,
                new MapSqlParameterSource("limit", Math.max(1, Math.min(limit, 100))),
                (rs, rowNum) -> new ExperimentRunSummary(
                        readUuid(rs, "experiment_run_id"),
                        rs.getString("experiment_key"),
                        rs.getString("status"),
                        readInstant(rs, "started_at"),
                        readInstant(rs, "finished_at"),
                        readJson(objectMapper, rs, "parameters"),
                        readJson(objectMapper, rs, "metrics"),
                        rs.getString("notes")
                )
        );
    }
}
