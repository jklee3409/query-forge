package io.queryforge.backend.admin.console.evaluation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static io.queryforge.backend.admin.console.ConsoleJdbc.normalizeLimit;
import static io.queryforge.backend.admin.console.ConsoleJdbc.normalizeOffset;
import static io.queryforge.backend.admin.console.ConsoleJdbc.readInstant;
import static io.queryforge.backend.admin.console.ConsoleJdbc.readJson;
import static io.queryforge.backend.admin.console.ConsoleJdbc.readUuid;

@Repository
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RewriteDebugRepository {

    private final ObjectMapper objectMapper;

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public List<RewriteDebugDtos.RewriteDebugRow> findRewriteDebugRows(Integer limit, Integer offset) {
        String sql = """
                SELECT rewrite_log_id,
                       online_query_id,
                       raw_query,
                       final_query,
                       rewrite_strategy,
                       rewrite_applied,
                       gating_preset,
                       raw_confidence,
                       selected_confidence,
                       confidence_delta,
                       decision_reason,
                       rejection_reason,
                       created_at
                FROM online_query_rewrite_log
                ORDER BY created_at DESC
                LIMIT :limit OFFSET :offset
                """;
        return jdbcTemplate.query(
                sql,
                new MapSqlParameterSource()
                        .addValue("limit", normalizeLimit(limit, 200))
                        .addValue("offset", normalizeOffset(offset)),
                (rs, rowNum) -> new RewriteDebugDtos.RewriteDebugRow(
                        readUuid(rs, "rewrite_log_id"),
                        readUuid(rs, "online_query_id"),
                        rs.getString("raw_query"),
                        rs.getString("final_query"),
                        rs.getString("rewrite_strategy"),
                        rs.getBoolean("rewrite_applied"),
                        rs.getString("gating_preset"),
                        rs.getObject("raw_confidence", Double.class),
                        rs.getObject("selected_confidence", Double.class),
                        rs.getObject("confidence_delta", Double.class),
                        rs.getString("decision_reason"),
                        rs.getString("rejection_reason"),
                        readInstant(rs, "created_at")
                )
        );
    }

    public Optional<RewriteDebugDtos.RewriteDebugDetail> findRewriteDebugDetail(UUID rewriteLogId) {
        String rowSql = """
                SELECT rewrite_log_id,
                       online_query_id,
                       raw_query,
                       final_query,
                       rewrite_strategy,
                       rewrite_applied,
                       gating_preset,
                       raw_confidence,
                       selected_confidence,
                       confidence_delta,
                       decision_reason,
                       rejection_reason,
                       created_at
                FROM online_query_rewrite_log
                WHERE rewrite_log_id = :rewriteLogId
                """;
        List<RewriteDebugDtos.RewriteDebugRow> rows = jdbcTemplate.query(
                rowSql,
                new MapSqlParameterSource("rewriteLogId", rewriteLogId),
                (rs, rowNum) -> new RewriteDebugDtos.RewriteDebugRow(
                        readUuid(rs, "rewrite_log_id"),
                        readUuid(rs, "online_query_id"),
                        rs.getString("raw_query"),
                        rs.getString("final_query"),
                        rs.getString("rewrite_strategy"),
                        rs.getBoolean("rewrite_applied"),
                        rs.getString("gating_preset"),
                        rs.getObject("raw_confidence", Double.class),
                        rs.getObject("selected_confidence", Double.class),
                        rs.getObject("confidence_delta", Double.class),
                        rs.getString("decision_reason"),
                        rs.getString("rejection_reason"),
                        readInstant(rs, "created_at")
                )
        );
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        String memorySql = """
                SELECT COALESCE(
                    jsonb_agg(
                        jsonb_build_object(
                            'memory_retrieval_log_id', memory_retrieval_log_id,
                            'memory_id', memory_id,
                            'retrieval_rank', retrieval_rank,
                            'similarity', similarity,
                            'query_text', query_text,
                            'target_doc_id', target_doc_id,
                            'target_chunk_ids', target_chunk_ids,
                            'generation_strategy', generation_strategy,
                            'metadata', metadata_json
                        )
                        ORDER BY retrieval_rank
                    ),
                    '[]'::jsonb
                )::text AS payload
                FROM memory_retrieval_log
                WHERE rewrite_log_id = :rewriteLogId
                """;
        String candidateSql = """
                SELECT COALESCE(
                    jsonb_agg(
                        jsonb_build_object(
                            'candidate_log_id', candidate_log_id,
                            'rewrite_candidate_id', rewrite_candidate_id,
                            'candidate_rank', candidate_rank,
                            'candidate_label', candidate_label,
                            'candidate_query', candidate_query,
                            'confidence_score', confidence_score,
                            'selected', selected,
                            'rejection_reason', rejection_reason,
                            'retrieval_top_k_docs', retrieval_top_k_docs,
                            'score_breakdown', score_breakdown,
                            'metadata', metadata_json
                        )
                        ORDER BY candidate_rank
                    ),
                    '[]'::jsonb
                )::text AS payload
                FROM rewrite_candidate_log
                WHERE rewrite_log_id = :rewriteLogId
                """;
        JsonNode memory = readJson(objectMapper, jdbcTemplate.queryForObject(memorySql, new MapSqlParameterSource("rewriteLogId", rewriteLogId), String.class));
        JsonNode candidates = readJson(objectMapper, jdbcTemplate.queryForObject(candidateSql, new MapSqlParameterSource("rewriteLogId", rewriteLogId), String.class));
        return Optional.of(new RewriteDebugDtos.RewriteDebugDetail(rows.getFirst(), memory, candidates));
    }
}
