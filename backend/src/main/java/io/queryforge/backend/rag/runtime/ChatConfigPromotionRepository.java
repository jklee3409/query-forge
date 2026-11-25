package io.queryforge.backend.rag.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
public class ChatConfigPromotionRepository {

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public record RagRunApplySnapshot(
            UUID ragTestRunId,
            UUID domainId,
            String status,
            JsonNode generationMethodCodes,
            String gatingPreset,
            Boolean rewriteEnabled,
            Boolean selectiveRewrite,
            Boolean useSessionContext,
            Boolean rewriteAnchorInjectionEnabled,
            Integer retrievalTopK,
            Integer rerankTopN,
            Double threshold,
            JsonNode configJson
    ) {
    }

    public Optional<RagRunApplySnapshot> findRagRunApplySnapshot(UUID ragTestRunId) {
        String sql = """
                SELECT r.rag_test_run_id,
                       r.domain_id,
                       r.status,
                       r.generation_method_codes::text AS generation_method_codes,
                       r.gating_preset,
                       r.rewrite_enabled,
                       r.selective_rewrite,
                       r.use_session_context,
                       r.rewrite_anchor_injection_enabled,
                       r.retrieval_top_k,
                       r.rerank_top_n,
                       r.threshold,
                       COALESCE(rc.config_json, '{}'::jsonb)::text AS config_json
                FROM rag_test_run r
                LEFT JOIN rag_test_run_config rc
                  ON rc.rag_test_run_id = r.rag_test_run_id
                WHERE r.rag_test_run_id = :ragTestRunId
                """;
        return jdbcTemplate.query(
                        sql,
                        new MapSqlParameterSource("ragTestRunId", ragTestRunId),
                        (rs, rowNum) -> new RagRunApplySnapshot(
                                readUuid(rs, "rag_test_run_id"),
                                readUuid(rs, "domain_id"),
                                rs.getString("status"),
                                readJson(rs.getString("generation_method_codes")),
                                rs.getString("gating_preset"),
                                rs.getObject("rewrite_enabled", Boolean.class),
                                rs.getObject("selective_rewrite", Boolean.class),
                                rs.getObject("use_session_context", Boolean.class),
                                rs.getObject("rewrite_anchor_injection_enabled", Boolean.class),
                                rs.getObject("retrieval_top_k", Integer.class),
                                rs.getObject("rerank_top_n", Integer.class),
                                rs.getObject("threshold", Double.class),
                                readJson(rs.getString("config_json"))
                        )
                )
                .stream()
                .findFirst();
    }

    private JsonNode readJson(String raw) {
        if (raw == null || raw.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            return objectMapper.readTree(raw);
        } catch (Exception ignored) {
            return objectMapper.createObjectNode();
        }
    }

    private UUID readUuid(ResultSet rs, String column) throws SQLException {
        Object value = rs.getObject(column);
        if (value instanceof UUID uuid) {
            return uuid;
        }
        return value == null ? null : UUID.fromString(value.toString());
    }
}
