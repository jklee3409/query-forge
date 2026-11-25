package io.queryforge.backend.rag.runtime.snapshot.repository;

import io.queryforge.backend.rag.runtime.snapshot.dto.GatingSnapshotDtos.GatingSnapshot;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class GatingSnapshotRepository {

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public Optional<GatingSnapshot> findGatingSnapshot(UUID gatingBatchId) {
        String sql = """
                SELECT qb.gating_batch_id,
                       qb.source_gating_run_id,
                       qb.domain_id,
                       qb.gating_preset,
                       m.method_code,
                       qb.status
                FROM quality_gating_batch qb
                LEFT JOIN synthetic_query_generation_method m
                  ON m.generation_method_id = qb.generation_method_id
                WHERE qb.gating_batch_id = :gatingBatchId
                """;
        return jdbcTemplate.query(
                        sql,
                        new MapSqlParameterSource("gatingBatchId", gatingBatchId),
                        (rs, rowNum) -> new GatingSnapshot(
                                readUuid(rs, "gating_batch_id"),
                                readUuid(rs, "source_gating_run_id"),
                                readUuid(rs, "domain_id"),
                                rs.getString("gating_preset"),
                                rs.getString("method_code"),
                                rs.getString("status")
                        )
                )
                .stream()
                .findFirst();
    }

    private UUID readUuid(ResultSet rs, String column) throws SQLException {
        Object value = rs.getObject(column);
        if (value instanceof UUID uuid) {
            return uuid;
        }
        return value == null ? null : UUID.fromString(value.toString());
    }
}
