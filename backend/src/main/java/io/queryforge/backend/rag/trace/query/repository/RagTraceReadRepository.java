package io.queryforge.backend.rag.trace.query.repository;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.queryforge.backend.rag.trace.query.dto.RagTraceDtos.OnlineQueryRow;
import io.queryforge.backend.rag.trace.query.dto.RagTraceDtos.RewriteCandidateRow;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static io.queryforge.backend.rag.persistence.support.RagJdbc.readJson;
import static io.queryforge.backend.rag.persistence.support.RagJdbc.readUuid;

@Repository
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RagTraceReadRepository {

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public Optional<OnlineQueryRow> findOnlineQuery(UUID onlineQueryId) {
        String sql = """
                SELECT online_query_id,
                       raw_query,
                       final_query_used,
                       rewrite_applied,
                       rewrite_strategy,
                       session_context_snapshot::text AS session_context_snapshot,
                       memory_top_n::text AS memory_top_n,
                       raw_score,
                       selected_rewrite_candidate_id,
                       selected_reason,
                       rejected_reason,
                       threshold,
                       latency_breakdown::text AS latency_breakdown
                FROM online_queries
                WHERE online_query_id = :onlineQueryId
                """;
        List<OnlineQueryRow> rows = jdbcTemplate.query(
                sql,
                new MapSqlParameterSource("onlineQueryId", onlineQueryId),
                (rs, rowNum) -> new OnlineQueryRow(
                        readUuid(rs, "online_query_id"),
                        rs.getString("raw_query"),
                        rs.getString("final_query_used"),
                        rs.getBoolean("rewrite_applied"),
                        rs.getString("rewrite_strategy"),
                        readJson(objectMapper, rs, "session_context_snapshot"),
                        readJson(objectMapper, rs, "memory_top_n"),
                        rs.getObject("raw_score", Double.class),
                        readUuid(rs, "selected_rewrite_candidate_id"),
                        rs.getString("selected_reason"),
                        rs.getString("rejected_reason"),
                        rs.getObject("threshold", Double.class),
                        readJson(objectMapper, rs, "latency_breakdown")
                )
        );
        return rows.stream().findFirst();
    }

    public List<RewriteCandidateRow> findRewriteCandidates(UUID onlineQueryId) {
        String sql = """
                SELECT rewrite_candidate_id,
                       candidate_rank,
                       candidate_label,
                       candidate_query,
                       retrieval_top_k_docs::text AS retrieval_top_k_docs,
                       confidence_score,
                       adopted,
                       rejected_reason,
                       score_breakdown::text AS score_breakdown
                FROM rewrite_candidates
                WHERE online_query_id = :onlineQueryId
                ORDER BY candidate_rank
                """;
        return jdbcTemplate.query(
                sql,
                new MapSqlParameterSource("onlineQueryId", onlineQueryId),
                (rs, rowNum) -> new RewriteCandidateRow(
                        readUuid(rs, "rewrite_candidate_id"),
                        rs.getInt("candidate_rank"),
                        rs.getString("candidate_label"),
                        rs.getString("candidate_query"),
                        readJson(objectMapper, rs, "retrieval_top_k_docs"),
                        rs.getObject("confidence_score", Double.class),
                        rs.getBoolean("adopted"),
                        rs.getString("rejected_reason"),
                        readJson(objectMapper, rs, "score_breakdown")
                )
        );
    }

    public JsonNode findRetrievalResults(UUID onlineQueryId) {
        String sql = """
                SELECT COALESCE(
                    jsonb_agg(
                        jsonb_build_object(
                            'retrieval_result_id', retrieval_result_id,
                            'rewrite_candidate_id', rewrite_candidate_id,
                            'result_scope', result_scope,
                            'rank', rank,
                            'document_id', document_id,
                            'chunk_id', chunk_id,
                            'score', score,
                            'metadata', metadata
                        )
                        ORDER BY created_at, rank
                    ),
                    '[]'::jsonb
                )::text AS payload
                FROM retrieval_results
                WHERE online_query_id = :onlineQueryId
                """;
        String payload = jdbcTemplate.queryForObject(sql, new MapSqlParameterSource("onlineQueryId", onlineQueryId), String.class);
        return readJson(objectMapper, payload);
    }

    public JsonNode findRerankResults(UUID onlineQueryId) {
        String sql = """
                SELECT COALESCE(
                    jsonb_agg(
                        jsonb_build_object(
                            'rerank_result_id', rerank_result_id,
                            'rewrite_candidate_id', rewrite_candidate_id,
                            'rank', rank,
                            'document_id', document_id,
                            'chunk_id', chunk_id,
                            'relevance_score', relevance_score,
                            'metadata', metadata
                        )
                        ORDER BY created_at, rank
                    ),
                    '[]'::jsonb
                )::text AS payload
                FROM rerank_results
                WHERE online_query_id = :onlineQueryId
                """;
        String payload = jdbcTemplate.queryForObject(sql, new MapSqlParameterSource("onlineQueryId", onlineQueryId), String.class);
        return readJson(objectMapper, payload);
    }

    public JsonNode findAnswer(UUID onlineQueryId) {
        String sql = """
                SELECT COALESCE(
                    jsonb_build_object(
                        'answer_id', answer_id,
                        'answer_text', answer_text,
                        'cited_document_ids', cited_document_ids,
                        'cited_chunk_ids', cited_chunk_ids,
                        'generation_model', generation_model,
                        'metadata', metadata,
                        'created_at', created_at
                    ),
                    '{}'::jsonb
                )::text AS payload
                FROM answers
                WHERE online_query_id = :onlineQueryId
                """;
        List<String> rows = jdbcTemplate.query(
                sql,
                new MapSqlParameterSource("onlineQueryId", onlineQueryId),
                (rs, rowNum) -> rs.getString("payload")
        );
        if (rows.isEmpty()) {
            return objectMapper.createObjectNode();
        }
        return readJson(objectMapper, rows.getFirst());
    }
}
