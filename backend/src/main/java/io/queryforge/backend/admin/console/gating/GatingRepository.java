package io.queryforge.backend.admin.console.gating;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static io.queryforge.backend.admin.console.persistence.ConsoleJdbc.normalizeLimit;
import static io.queryforge.backend.admin.console.persistence.ConsoleJdbc.normalizeOffset;
import static io.queryforge.backend.admin.console.persistence.ConsoleJdbc.readInstant;
import static io.queryforge.backend.admin.console.persistence.ConsoleJdbc.readJson;
import static io.queryforge.backend.admin.console.persistence.ConsoleJdbc.readUuid;

@Repository
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class GatingRepository {

    private final ObjectMapper objectMapper;

    private final NamedParameterJdbcTemplate jdbcTemplate;

    @Transactional
    public int clearCompletedGatingResults(UUID generationMethodId, UUID generationBatchId) {
        StringBuilder targetSql = new StringBuilder(
                """
                SELECT gating_batch_id
                FROM quality_gating_batch
                WHERE generation_method_id = :generationMethodId
                  AND status IN ('completed', 'failed', 'cancelled')
                """
        );
        MapSqlParameterSource targetParams = new MapSqlParameterSource("generationMethodId", generationMethodId);
        if (generationBatchId != null) {
            targetSql.append(" AND generation_batch_id = :generationBatchId");
            targetParams.addValue("generationBatchId", generationBatchId);
        }
        List<UUID> gatingBatchIds = jdbcTemplate.query(
                targetSql.toString(),
                targetParams,
                (rs, rowNum) -> readUuid(rs, "gating_batch_id")
        );
        if (gatingBatchIds.isEmpty()) {
            return 0;
        }

        MapSqlParameterSource cleanupParams = new MapSqlParameterSource("gatingBatchIds", gatingBatchIds);
        jdbcTemplate.update(
                "UPDATE synthetic_queries_gated SET gating_batch_id = NULL WHERE gating_batch_id IN (:gatingBatchIds)",
                cleanupParams
        );
        jdbcTemplate.update(
                "DELETE FROM quality_gating_batch WHERE gating_batch_id IN (:gatingBatchIds)",
                cleanupParams
        );
        return gatingBatchIds.size();
    }

    @Transactional
    public UUID createGatingBatch(
            String gatingPreset,
            UUID generationMethodId,
            UUID generationBatchId,
            UUID sourceGenerationRunId,
            UUID domainId,
            String createdBy,
            JsonNode stageConfig
    ) {
        UUID gatingBatchId = UUID.randomUUID();
        String sql = """
                INSERT INTO quality_gating_batch (
                    gating_batch_id,
                    gating_preset,
                    generation_method_id,
                    generation_batch_id,
                    source_generation_run_id,
                    domain_id,
                    stage_config_json,
                    status,
                    created_by
                ) VALUES (
                    :gatingBatchId,
                    :gatingPreset,
                    :generationMethodId,
                    :generationBatchId,
                    :sourceGenerationRunId,
                    :domainId,
                    CAST(:stageConfig AS jsonb),
                    'planned',
                    :createdBy
                )
                """;
        jdbcTemplate.update(
                sql,
                new MapSqlParameterSource()
                        .addValue("gatingBatchId", gatingBatchId)
                        .addValue("gatingPreset", gatingPreset)
                        .addValue("generationMethodId", generationMethodId)
                        .addValue("generationBatchId", generationBatchId)
                        .addValue("sourceGenerationRunId", sourceGenerationRunId)
                        .addValue("domainId", domainId)
                        .addValue("stageConfig", stageConfig.toString())
                        .addValue("createdBy", createdBy)
        );
        return gatingBatchId;
    }

    @Transactional
    public void completeGatingBatch(
            UUID gatingBatchId,
            UUID sourceGatingRunId,
            int processedCount,
            int acceptedCount,
            int rejectedCount,
            JsonNode rejectionSummary
    ) {
        String sql = """
                UPDATE quality_gating_batch
                SET status = 'completed',
                    started_at = COALESCE(started_at, NOW()),
                    source_gating_run_id = :sourceGatingRunId,
                    processed_count = :processedCount,
                    accepted_count = :acceptedCount,
                    rejected_count = :rejectedCount,
                    rejection_summary = CAST(:rejectionSummary AS jsonb),
                    finished_at = NOW()
                WHERE gating_batch_id = :gatingBatchId
                """;
        jdbcTemplate.update(
                sql,
                new MapSqlParameterSource()
                        .addValue("gatingBatchId", gatingBatchId)
                        .addValue("sourceGatingRunId", sourceGatingRunId)
                        .addValue("processedCount", processedCount)
                        .addValue("acceptedCount", acceptedCount)
                        .addValue("rejectedCount", rejectedCount)
                        .addValue("rejectionSummary", rejectionSummary.toString())
        );
    }

    @Transactional
    public void syncGatingBatchResults(UUID gatingBatchId, UUID sourceGatingRunId) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("gatingBatchId", gatingBatchId)
                .addValue("sourceGatingRunId", sourceGatingRunId.toString());

        String cleanupResultSql = "DELETE FROM synthetic_query_gating_result WHERE gating_batch_id = :gatingBatchId";
        jdbcTemplate.update(cleanupResultSql, params);
        String cleanupHistorySql = "DELETE FROM synthetic_query_gating_history WHERE gating_batch_id = :gatingBatchId";
        jdbcTemplate.update(cleanupHistorySql, params);
        String cleanupStageSql = "DELETE FROM quality_gating_stage_result WHERE gating_batch_id = :gatingBatchId";
        jdbcTemplate.update(cleanupStageSql, params);

        String insertResultSql = """
                INSERT INTO synthetic_query_gating_result (
                    gating_batch_id,
                    synthetic_query_id,
                    query_text,
                    query_type,
                    language_profile,
                    generation_strategy,
                    rule_pass,
                    llm_eval_score,
                    utility_score,
                    diversity_pass,
                    novelty_score,
                    final_score,
                    accepted,
                    rejected_stage,
                    rejected_reason,
                    llm_scores,
                    stage_payload_json,
                    created_at
                )
                SELECT :gatingBatchId,
                       r.synthetic_query_id,
                       r.query_text,
                       r.query_type,
                       COALESCE(
                           r.language_profile,
                           CASE
                               WHEN r.generation_strategy IN ('E', 'F') THEN 'en'
                               WHEN r.query_type = 'code_mixed' THEN 'code_mixed'
                               ELSE 'ko'
                           END
                       ),
                       r.generation_strategy,
                       g.passed_rule_filter,
                       COALESCE(
                           (
                               SELECT AVG((value)::double precision)
                               FROM jsonb_each_text(COALESCE(g.llm_scores -> 'scores', g.llm_scores)) llm(value_key, value)
                               WHERE value ~ '^[0-9]+(\\.[0-9]+)?$'
                           ) / 5.0,
                           NULL
                       ) AS llm_eval_score,
                       g.utility_score,
                       g.passed_diversity,
                       g.novelty_score,
                       g.final_score,
                       g.final_decision,
                       COALESCE(
                           g.rejected_stage,
                           CASE
                               WHEN COALESCE(g.passed_rule_filter, TRUE) = FALSE THEN 'rule_filter'
                               WHEN COALESCE(g.passed_llm_self_eval, TRUE) = FALSE THEN 'llm_self_eval'
                               WHEN COALESCE(g.passed_retrieval_utility, TRUE) = FALSE THEN 'retrieval_utility'
                               WHEN COALESCE(g.passed_diversity, TRUE) = FALSE THEN 'diversity_dedup'
                               WHEN COALESCE(g.final_decision, FALSE) = FALSE THEN 'final_score'
                               ELSE 'approved'
                           END
                       ) AS rejected_stage,
                       COALESCE(g.rejected_reason, NULLIF(g.rejection_reasons ->> 0, '')) AS rejected_reason,
                       COALESCE(g.llm_scores, '{}'::jsonb),
                       jsonb_build_object(
                           'passed_rule_filter', g.passed_rule_filter,
                           'passed_llm_self_eval', g.passed_llm_self_eval,
                           'passed_retrieval_utility', g.passed_retrieval_utility,
                           'passed_diversity', g.passed_diversity,
                           'rejection_reasons', g.rejection_reasons
                       ),
                       g.created_at
                FROM synthetic_queries_gated g
                JOIN synthetic_queries_raw_all r
                  ON r.synthetic_query_id = g.synthetic_query_id
                WHERE g.gating_batch_id = :gatingBatchId
                   OR g.metadata ->> 'experiment_run_id' = :sourceGatingRunId
                """;
        jdbcTemplate.update(insertResultSql, params);

        String updateRawGatedSql = """
                UPDATE synthetic_queries_gated g
                SET gating_batch_id = :gatingBatchId,
                    rejected_stage = COALESCE(
                        g.rejected_stage,
                        CASE
                            WHEN COALESCE(g.passed_rule_filter, TRUE) = FALSE THEN 'rule_filter'
                            WHEN COALESCE(g.passed_llm_self_eval, TRUE) = FALSE THEN 'llm_self_eval'
                            WHEN COALESCE(g.passed_retrieval_utility, TRUE) = FALSE THEN 'retrieval_utility'
                            WHEN COALESCE(g.passed_diversity, TRUE) = FALSE THEN 'diversity_dedup'
                            WHEN COALESCE(g.final_decision, FALSE) = FALSE THEN 'final_score'
                            ELSE 'approved'
                        END
                    ),
                    rejected_reason = COALESCE(g.rejected_reason, NULLIF(g.rejection_reasons ->> 0, ''))
                WHERE g.gating_batch_id = :gatingBatchId
                   OR g.metadata ->> 'experiment_run_id' = :sourceGatingRunId
                """;
        jdbcTemplate.update(updateRawGatedSql, params);

        String historySql = """
                INSERT INTO synthetic_query_gating_history (
                    gating_batch_id,
                    synthetic_query_id,
                    stage_name,
                    stage_order,
                    passed,
                    score,
                    reason,
                    payload_json
                )
                SELECT :gatingBatchId,
                       result.synthetic_query_id,
                       stage.stage_name,
                       stage.stage_order,
                       stage.passed,
                       stage.score,
                       stage.reason,
                       stage.payload
                FROM synthetic_query_gating_result result
                CROSS JOIN LATERAL (
                    VALUES
                        ('rule_filter', 1, COALESCE(result.rule_pass, TRUE), NULL::double precision, CASE WHEN COALESCE(result.rule_pass, TRUE) THEN NULL ELSE result.rejected_reason END, jsonb_build_object('stage', 'rule_filter')),
                        ('llm_self_eval', 2, COALESCE((result.stage_payload_json ->> 'passed_llm_self_eval')::boolean, TRUE), result.llm_eval_score, CASE WHEN COALESCE((result.stage_payload_json ->> 'passed_llm_self_eval')::boolean, TRUE) THEN NULL ELSE result.rejected_reason END, jsonb_build_object('stage', 'llm_self_eval')),
                        ('retrieval_utility', 3, COALESCE((result.stage_payload_json ->> 'passed_retrieval_utility')::boolean, TRUE), result.utility_score, CASE WHEN COALESCE((result.stage_payload_json ->> 'passed_retrieval_utility')::boolean, TRUE) THEN NULL ELSE result.rejected_reason END, jsonb_build_object('stage', 'retrieval_utility')),
                        ('diversity_dedup', 4, COALESCE(result.diversity_pass, TRUE), result.novelty_score, CASE WHEN COALESCE(result.diversity_pass, TRUE) THEN NULL ELSE result.rejected_reason END, jsonb_build_object('stage', 'diversity_dedup')),
                        ('final_score', 5, result.accepted, result.final_score, CASE WHEN result.accepted THEN NULL ELSE result.rejected_reason END, jsonb_build_object('stage', 'final_score'))
                ) AS stage(stage_name, stage_order, passed, score, reason, payload)
                WHERE result.gating_batch_id = :gatingBatchId
                """;
        jdbcTemplate.update(historySql, params);

        String stageResultSql = """
                INSERT INTO quality_gating_stage_result (
                    gating_batch_id,
                    stage_name,
                    stage_order,
                    input_count,
                    passed_count,
                    rejected_count,
                    metrics_json
                )
                VALUES
                    (
                        :gatingBatchId, 'generated', 0,
                        (SELECT COUNT(*) FROM synthetic_query_gating_result WHERE gating_batch_id = :gatingBatchId),
                        (SELECT COUNT(*) FROM synthetic_query_gating_result WHERE gating_batch_id = :gatingBatchId),
                        0,
                        '{}'::jsonb
                    ),
                    (
                        :gatingBatchId, 'rule_filter', 1,
                        (SELECT COUNT(*) FROM synthetic_query_gating_result WHERE gating_batch_id = :gatingBatchId),
                        (SELECT COUNT(*) FROM synthetic_query_gating_result WHERE gating_batch_id = :gatingBatchId AND COALESCE(rule_pass, TRUE)),
                        (SELECT COUNT(*) FROM synthetic_query_gating_result WHERE gating_batch_id = :gatingBatchId AND COALESCE(rule_pass, TRUE) = FALSE),
                        '{}'::jsonb
                    ),
                    (
                        :gatingBatchId, 'llm_self_eval', 2,
                        (SELECT COUNT(*) FROM synthetic_query_gating_result WHERE gating_batch_id = :gatingBatchId AND COALESCE(rule_pass, TRUE)),
                        (SELECT COUNT(*) FROM synthetic_query_gating_result WHERE gating_batch_id = :gatingBatchId AND COALESCE(rule_pass, TRUE) AND COALESCE((stage_payload_json ->> 'passed_llm_self_eval')::boolean, TRUE)),
                        (SELECT COUNT(*) FROM synthetic_query_gating_result WHERE gating_batch_id = :gatingBatchId AND COALESCE(rule_pass, TRUE) AND COALESCE((stage_payload_json ->> 'passed_llm_self_eval')::boolean, TRUE) = FALSE),
                        '{}'::jsonb
                    ),
                    (
                        :gatingBatchId, 'retrieval_utility', 3,
                        (SELECT COUNT(*) FROM synthetic_query_gating_result WHERE gating_batch_id = :gatingBatchId AND COALESCE(rule_pass, TRUE) AND COALESCE((stage_payload_json ->> 'passed_llm_self_eval')::boolean, TRUE)),
                        (SELECT COUNT(*) FROM synthetic_query_gating_result WHERE gating_batch_id = :gatingBatchId AND COALESCE(rule_pass, TRUE) AND COALESCE((stage_payload_json ->> 'passed_llm_self_eval')::boolean, TRUE) AND COALESCE((stage_payload_json ->> 'passed_retrieval_utility')::boolean, TRUE)),
                        (SELECT COUNT(*) FROM synthetic_query_gating_result WHERE gating_batch_id = :gatingBatchId AND COALESCE(rule_pass, TRUE) AND COALESCE((stage_payload_json ->> 'passed_llm_self_eval')::boolean, TRUE) AND COALESCE((stage_payload_json ->> 'passed_retrieval_utility')::boolean, TRUE) = FALSE),
                        '{}'::jsonb
                    ),
                    (
                        :gatingBatchId, 'diversity_dedup', 4,
                        (SELECT COUNT(*) FROM synthetic_query_gating_result WHERE gating_batch_id = :gatingBatchId AND COALESCE(rule_pass, TRUE) AND COALESCE((stage_payload_json ->> 'passed_llm_self_eval')::boolean, TRUE) AND COALESCE((stage_payload_json ->> 'passed_retrieval_utility')::boolean, TRUE)),
                        (SELECT COUNT(*) FROM synthetic_query_gating_result WHERE gating_batch_id = :gatingBatchId AND COALESCE(rule_pass, TRUE) AND COALESCE((stage_payload_json ->> 'passed_llm_self_eval')::boolean, TRUE) AND COALESCE((stage_payload_json ->> 'passed_retrieval_utility')::boolean, TRUE) AND COALESCE(diversity_pass, TRUE)),
                        (SELECT COUNT(*) FROM synthetic_query_gating_result WHERE gating_batch_id = :gatingBatchId AND COALESCE(rule_pass, TRUE) AND COALESCE((stage_payload_json ->> 'passed_llm_self_eval')::boolean, TRUE) AND COALESCE((stage_payload_json ->> 'passed_retrieval_utility')::boolean, TRUE) AND COALESCE(diversity_pass, TRUE) = FALSE),
                        '{}'::jsonb
                    ),
                    (
                        :gatingBatchId, 'final_approved', 5,
                        (SELECT COUNT(*) FROM synthetic_query_gating_result WHERE gating_batch_id = :gatingBatchId AND COALESCE(rule_pass, TRUE) AND COALESCE((stage_payload_json ->> 'passed_llm_self_eval')::boolean, TRUE) AND COALESCE((stage_payload_json ->> 'passed_retrieval_utility')::boolean, TRUE) AND COALESCE(diversity_pass, TRUE)),
                        (SELECT COUNT(*) FROM synthetic_query_gating_result WHERE gating_batch_id = :gatingBatchId AND accepted),
                        (SELECT COUNT(*) FROM synthetic_query_gating_result WHERE gating_batch_id = :gatingBatchId AND NOT accepted),
                        '{}'::jsonb
                    )
                ON CONFLICT (gating_batch_id, stage_name) DO UPDATE
                SET stage_order = EXCLUDED.stage_order,
                    input_count = EXCLUDED.input_count,
                    passed_count = EXCLUDED.passed_count,
                    rejected_count = EXCLUDED.rejected_count,
                    metrics_json = EXCLUDED.metrics_json
                """;
        jdbcTemplate.update(stageResultSql, params);
    }

    @Transactional
    public void failGatingBatch(UUID gatingBatchId, String errorMessage) {
        String sql = """
                UPDATE quality_gating_batch
                SET status = 'failed',
                    started_at = COALESCE(started_at, NOW()),
                    finished_at = NOW(),
                    processed_count = 0,
                    accepted_count = 0,
                    rejected_count = 0,
                    rejection_summary = CAST(:rejectionSummary AS jsonb)
                WHERE gating_batch_id = :gatingBatchId
                """;
        JsonNode rejection = objectMapper.valueToTree(Map.of("error", errorMessage == null ? "" : errorMessage));
        jdbcTemplate.update(
                sql,
                new MapSqlParameterSource()
                        .addValue("gatingBatchId", gatingBatchId)
                        .addValue("rejectionSummary", rejection.toString())
        );
        MapSqlParameterSource cleanupParams = new MapSqlParameterSource("gatingBatchId", gatingBatchId);
        jdbcTemplate.update(
                "UPDATE synthetic_queries_gated SET gating_batch_id = NULL WHERE gating_batch_id = :gatingBatchId",
                cleanupParams
        );
        jdbcTemplate.update("DELETE FROM synthetic_query_gating_history WHERE gating_batch_id = :gatingBatchId", cleanupParams);
        jdbcTemplate.update("DELETE FROM quality_gating_stage_result WHERE gating_batch_id = :gatingBatchId", cleanupParams);
        jdbcTemplate.update("DELETE FROM synthetic_query_gating_result WHERE gating_batch_id = :gatingBatchId", cleanupParams);
    }

    @Transactional
    public void markGatingBatchRunning(UUID gatingBatchId) {
        String sql = """
                UPDATE quality_gating_batch
                SET status = 'running',
                    started_at = COALESCE(started_at, NOW())
                WHERE gating_batch_id = :gatingBatchId
                  AND status IN ('planned', 'queued', 'failed')
                """;
        jdbcTemplate.update(sql, new MapSqlParameterSource("gatingBatchId", gatingBatchId));
    }

    @Transactional
    public void cancelGatingBatch(UUID gatingBatchId, String reason) {
        String sql = """
                UPDATE quality_gating_batch
                SET status = 'cancelled',
                    finished_at = NOW(),
                    rejection_summary = jsonb_build_object('cancel_reason', :reason)
                WHERE gating_batch_id = :gatingBatchId
                """;
        jdbcTemplate.update(
                sql,
                new MapSqlParameterSource()
                        .addValue("gatingBatchId", gatingBatchId)
                        .addValue("reason", reason == null ? "" : reason)
        );
    }

    public Optional<GatingDtos.GatingBatchRow> findGatingBatch(UUID gatingBatchId) {
        String sql = """
                WITH live_count AS (
                    SELECT gr.gating_batch_id,
                           COUNT(*) AS processed_count,
                           COUNT(*) FILTER (WHERE gr.accepted) AS accepted_count
                    FROM synthetic_query_gating_result gr
                    WHERE gr.gating_batch_id = :gatingBatchId
                    GROUP BY gr.gating_batch_id
                )
                SELECT qb.gating_batch_id,
                       qb.gating_preset,
                       qb.generation_batch_id,
                       m.method_code,
                       m.method_name,
                       qb.source_generation_run_id,
                       qb.source_gating_run_id,
                       qb.status,
                       qb.started_at,
                       qb.finished_at,
                       progress.processed_count,
                       progress.accepted_count,
                       progress.rejected_count,
                       target.target_query_count,
                       rate.estimated_seconds_per_query,
                       CASE
                           WHEN lower(COALESCE(qb.status, '')) IN ('completed', 'failed', 'cancelled') THEN 0
                           WHEN lower(COALESCE(qb.status, '')) NOT IN ('planned', 'queued', 'running') THEN NULL
                           WHEN target.target_query_count IS NULL OR target.target_query_count <= 0 THEN NULL
                           WHEN progress.processed_count >= target.target_query_count THEN 0
                           WHEN rate.estimated_seconds_per_query IS NULL THEN NULL
                           ELSE GREATEST(
                                   0,
                                   ROUND(
                                           (target.target_query_count - progress.processed_count)
                                           * rate.estimated_seconds_per_query
                                   )
                                )::bigint
                       END AS estimated_remaining_seconds,
                       qb.rejection_summary::text AS rejection_summary,
                       qb.stage_config_json::text AS stage_config
                FROM quality_gating_batch qb
                LEFT JOIN synthetic_query_generation_method m
                  ON m.generation_method_id = qb.generation_method_id
                LEFT JOIN live_count lc
                  ON lc.gating_batch_id = qb.gating_batch_id
                LEFT JOIN LATERAL (
                    SELECT CASE
                               WHEN lower(COALESCE(qb.status, '')) IN ('planned', 'queued', 'running')
                                   THEN COALESCE(lc.processed_count, 0)
                               ELSE qb.processed_count
                           END AS processed_count,
                           CASE
                               WHEN lower(COALESCE(qb.status, '')) IN ('planned', 'queued', 'running')
                                   THEN COALESCE(lc.accepted_count, 0)
                               ELSE qb.accepted_count
                           END AS accepted_count,
                           CASE
                               WHEN lower(COALESCE(qb.status, '')) IN ('planned', 'queued', 'running')
                                   THEN GREATEST(COALESCE(lc.processed_count, 0) - COALESCE(lc.accepted_count, 0), 0)
                               ELSE qb.rejected_count
                           END AS rejected_count
                ) progress ON TRUE
                LEFT JOIN LATERAL (
                    SELECT SUM(batch_stats.generated_count)::int AS target_query_count
                    FROM (
                        SELECT DISTINCT source_batch.batch_id,
                               GREATEST(COALESCE(gb.total_generated_count, 0), COALESCE(live.live_generated_count, 0)) AS generated_count
                        FROM (
                            SELECT qb.generation_batch_id AS batch_id
                            WHERE qb.generation_batch_id IS NOT NULL
                            UNION
                            SELECT arr.value::uuid AS batch_id
                            FROM jsonb_array_elements_text(COALESCE(qb.stage_config_json -> 'source_generation_batch_ids', '[]'::jsonb)) arr(value)
                            WHERE arr.value ~* '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
                        ) source_batch
                        JOIN synthetic_query_generation_batch gb
                          ON gb.batch_id = source_batch.batch_id
                        LEFT JOIN LATERAL (
                            SELECT COUNT(*)::int AS live_generated_count
                            FROM synthetic_queries_raw_all r
                            WHERE r.generation_batch_id = source_batch.batch_id
                        ) live ON TRUE
                    ) batch_stats
                ) target ON TRUE
                LEFT JOIN LATERAL (
                    SELECT AVG(sample.seconds_per_query) AS historical_seconds_per_query
                    FROM (
                        SELECT EXTRACT(EPOCH FROM (history.finished_at - history.started_at))
                               / history.processed_count::double precision AS seconds_per_query
                        FROM quality_gating_batch history
                        WHERE history.generation_method_id = qb.generation_method_id
                          AND history.gating_preset = qb.gating_preset
                          AND history.status = 'completed'
                          AND history.processed_count > 0
                          AND history.started_at IS NOT NULL
                          AND history.finished_at IS NOT NULL
                        ORDER BY history.finished_at DESC
                        LIMIT 20
                    ) sample
                ) hist ON TRUE
                LEFT JOIN LATERAL (
                    SELECT CASE
                               WHEN progress.processed_count > 0
                                    AND qb.started_at IS NOT NULL
                                    AND lower(COALESCE(qb.status, '')) IN ('planned', 'queued', 'running')
                                   THEN GREATEST(EXTRACT(EPOCH FROM (NOW() - qb.started_at)), 1.0)
                                        / progress.processed_count::double precision
                               ELSE hist.historical_seconds_per_query
                           END AS estimated_seconds_per_query
                ) rate ON TRUE
                WHERE qb.gating_batch_id = :gatingBatchId
                """;
        List<GatingDtos.GatingBatchRow> rows = jdbcTemplate.query(
                sql,
                new MapSqlParameterSource("gatingBatchId", gatingBatchId),
                (rs, rowNum) -> mapGatingBatchRow(rs)
        );
        return rows.stream().findFirst();
    }

    public List<GatingDtos.GatingBatchRow> findGatingBatches(Integer limit) {
        return findGatingBatches(limit, null);
    }

    public List<GatingDtos.GatingBatchRow> findGatingBatches(Integer limit, UUID domainId) {
        String domainWhereClause = domainId == null ? "" : "WHERE qb.domain_id = :domainId";
        String sql = """
                WITH limited_batch AS (
                    SELECT qb.gating_batch_id,
                           qb.gating_preset,
                           qb.generation_batch_id,
                           qb.generation_method_id,
                           qb.source_generation_run_id,
                           qb.source_gating_run_id,
                           qb.status,
                           qb.started_at,
                           qb.finished_at,
                           qb.processed_count,
                           qb.accepted_count,
                           qb.rejected_count,
                           qb.rejection_summary,
                           qb.stage_config_json,
                           qb.created_at
                    FROM quality_gating_batch qb
                    %s
                    ORDER BY qb.created_at DESC
                    LIMIT :limit
                ),
                live_count AS (
                    SELECT gr.gating_batch_id,
                           COUNT(*) AS processed_count,
                           COUNT(*) FILTER (WHERE gr.accepted) AS accepted_count
                    FROM synthetic_query_gating_result gr
                    WHERE gr.gating_batch_id IN (SELECT lb.gating_batch_id FROM limited_batch lb)
                    GROUP BY gr.gating_batch_id
                )
                SELECT lb.gating_batch_id,
                       lb.gating_preset,
                       lb.generation_batch_id,
                       m.method_code,
                       m.method_name,
                       lb.source_generation_run_id,
                       lb.source_gating_run_id,
                       lb.status,
                       lb.started_at,
                       lb.finished_at,
                       progress.processed_count,
                       progress.accepted_count,
                       progress.rejected_count,
                       target.target_query_count,
                       rate.estimated_seconds_per_query,
                       CASE
                           WHEN lower(COALESCE(lb.status, '')) IN ('completed', 'failed', 'cancelled') THEN 0
                           WHEN lower(COALESCE(lb.status, '')) NOT IN ('planned', 'queued', 'running') THEN NULL
                           WHEN target.target_query_count IS NULL OR target.target_query_count <= 0 THEN NULL
                           WHEN progress.processed_count >= target.target_query_count THEN 0
                           WHEN rate.estimated_seconds_per_query IS NULL THEN NULL
                           ELSE GREATEST(
                                   0,
                                   ROUND(
                                           (target.target_query_count - progress.processed_count)
                                           * rate.estimated_seconds_per_query
                                   )
                                )::bigint
                       END AS estimated_remaining_seconds,
                       lb.rejection_summary::text AS rejection_summary,
                       lb.stage_config_json::text AS stage_config
                FROM limited_batch lb
                LEFT JOIN synthetic_query_generation_method m
                  ON m.generation_method_id = lb.generation_method_id
                LEFT JOIN live_count lc
                  ON lc.gating_batch_id = lb.gating_batch_id
                LEFT JOIN LATERAL (
                    SELECT CASE
                               WHEN lower(COALESCE(lb.status, '')) IN ('planned', 'queued', 'running')
                                   THEN COALESCE(lc.processed_count, 0)
                               ELSE lb.processed_count
                           END AS processed_count,
                           CASE
                               WHEN lower(COALESCE(lb.status, '')) IN ('planned', 'queued', 'running')
                                   THEN COALESCE(lc.accepted_count, 0)
                               ELSE lb.accepted_count
                           END AS accepted_count,
                           CASE
                               WHEN lower(COALESCE(lb.status, '')) IN ('planned', 'queued', 'running')
                                   THEN GREATEST(COALESCE(lc.processed_count, 0) - COALESCE(lc.accepted_count, 0), 0)
                               ELSE lb.rejected_count
                           END AS rejected_count
                ) progress ON TRUE
                LEFT JOIN LATERAL (
                    SELECT SUM(batch_stats.generated_count)::int AS target_query_count
                    FROM (
                        SELECT DISTINCT source_batch.batch_id,
                               GREATEST(COALESCE(gb.total_generated_count, 0), COALESCE(live.live_generated_count, 0)) AS generated_count
                        FROM (
                            SELECT lb.generation_batch_id AS batch_id
                            WHERE lb.generation_batch_id IS NOT NULL
                            UNION
                            SELECT arr.value::uuid AS batch_id
                            FROM jsonb_array_elements_text(COALESCE(lb.stage_config_json -> 'source_generation_batch_ids', '[]'::jsonb)) arr(value)
                            WHERE arr.value ~* '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
                        ) source_batch
                        JOIN synthetic_query_generation_batch gb
                          ON gb.batch_id = source_batch.batch_id
                        LEFT JOIN LATERAL (
                            SELECT COUNT(*)::int AS live_generated_count
                            FROM synthetic_queries_raw_all r
                            WHERE r.generation_batch_id = source_batch.batch_id
                        ) live ON TRUE
                    ) batch_stats
                ) target ON TRUE
                LEFT JOIN LATERAL (
                    SELECT AVG(sample.seconds_per_query) AS historical_seconds_per_query
                    FROM (
                        SELECT EXTRACT(EPOCH FROM (history.finished_at - history.started_at))
                               / history.processed_count::double precision AS seconds_per_query
                        FROM quality_gating_batch history
                        WHERE history.generation_method_id = lb.generation_method_id
                          AND history.gating_preset = lb.gating_preset
                          AND history.status = 'completed'
                          AND history.processed_count > 0
                          AND history.started_at IS NOT NULL
                          AND history.finished_at IS NOT NULL
                        ORDER BY history.finished_at DESC
                        LIMIT 20
                    ) sample
                ) hist ON TRUE
                LEFT JOIN LATERAL (
                    SELECT CASE
                               WHEN progress.processed_count > 0
                                    AND lb.started_at IS NOT NULL
                                    AND lower(COALESCE(lb.status, '')) IN ('planned', 'queued', 'running')
                                   THEN GREATEST(EXTRACT(EPOCH FROM (NOW() - lb.started_at)), 1.0)
                                        / progress.processed_count::double precision
                               ELSE hist.historical_seconds_per_query
                           END AS estimated_seconds_per_query
                ) rate ON TRUE
                ORDER BY lb.created_at DESC
                """.formatted(domainWhereClause);
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("limit", normalizeLimit(limit, 100));
        if (domainId != null) {
            params.addValue("domainId", domainId);
        }
        return jdbcTemplate.query(
                sql,
                params,
                (rs, rowNum) -> mapGatingBatchRow(rs)
        );
    }

    public GatingDtos.GatingFunnelResponse findGatingFunnel(UUID gatingBatchId, String methodCode) {
        GatingDtos.GatingBatchRow batch = findGatingBatch(gatingBatchId)
                .orElseThrow(() -> new IllegalArgumentException("gating batch not found: " + gatingBatchId));
        StringBuilder sql = new StringBuilder(
                """
                SELECT COUNT(*) AS generated_total,
                       COUNT(*) FILTER (WHERE COALESCE(rule_pass, TRUE)) AS passed_rule,
                       COUNT(*) FILTER (
                           WHERE COALESCE(rule_pass, TRUE)
                             AND COALESCE((stage_payload_json ->> 'passed_llm_self_eval')::boolean, TRUE)
                       ) AS passed_llm,
                       COUNT(*) FILTER (
                           WHERE COALESCE(rule_pass, TRUE)
                             AND COALESCE((stage_payload_json ->> 'passed_llm_self_eval')::boolean, TRUE)
                             AND COALESCE((stage_payload_json ->> 'passed_retrieval_utility')::boolean, TRUE)
                       ) AS passed_utility,
                       COUNT(*) FILTER (
                           WHERE COALESCE(rule_pass, TRUE)
                             AND COALESCE((stage_payload_json ->> 'passed_llm_self_eval')::boolean, TRUE)
                             AND COALESCE((stage_payload_json ->> 'passed_retrieval_utility')::boolean, TRUE)
                             AND COALESCE(diversity_pass, TRUE)
                       ) AS passed_diversity,
                       COUNT(*) FILTER (WHERE accepted) AS final_accepted
                FROM synthetic_query_gating_result
                WHERE gating_batch_id = :gatingBatchId
                """
        );
        MapSqlParameterSource params = new MapSqlParameterSource("gatingBatchId", gatingBatchId);
        if (methodCode != null && !methodCode.isBlank()) {
            sql.append(" AND generation_strategy = :methodCode");
            params.addValue("methodCode", methodCode.trim().toUpperCase());
        }
        Integer[] counts = jdbcTemplate.queryForObject(
                sql.toString(),
                params,
                (rs, rowNum) -> new Integer[]{
                        rs.getInt("generated_total"),
                        rs.getInt("passed_rule"),
                        rs.getInt("passed_llm"),
                        rs.getInt("passed_utility"),
                        rs.getInt("passed_diversity"),
                        rs.getInt("final_accepted")
                }
        );
        int generatedTotal = counts == null ? 0 : intValue(counts[0]);
        int passedRule = counts == null ? 0 : intValue(counts[1]);
        int passedLlm = counts == null ? 0 : intValue(counts[2]);
        int passedUtility = counts == null ? 0 : intValue(counts[3]);
        int passedDiversity = counts == null ? 0 : intValue(counts[4]);
        int finalAccepted = counts == null ? 0 : intValue(counts[5]);
        return new GatingDtos.GatingFunnelResponse(
                batch.gatingBatchId(),
                methodCode == null || methodCode.isBlank() ? batch.methodCode() : methodCode.trim().toUpperCase(),
                batch.gatingPreset(),
                generatedTotal,
                passedRule,
                passedLlm,
                passedUtility,
                passedDiversity,
                finalAccepted
        );
    }

    public List<GatingDtos.GatingResultRow> findGatingResults(
            UUID gatingBatchId,
            String methodCode,
            String passStage,
            String queryType,
            Integer limit,
            Integer offset
    ) {
        findGatingBatch(gatingBatchId)
                .orElseThrow(() -> new IllegalArgumentException("gating batch not found: " + gatingBatchId));
        StringBuilder sql = new StringBuilder(
                """
                SELECT gr.synthetic_query_id,
                       gr.query_text,
                       gr.query_type,
                       gr.generation_strategy,
                       gr.rule_pass,
                       (gr.stage_payload_json ->> 'passed_llm_self_eval')::boolean AS llm_pass,
                       (gr.stage_payload_json ->> 'passed_retrieval_utility')::boolean AS utility_pass,
                       gr.diversity_pass,
                       gr.utility_score,
                       gr.novelty_score,
                       gr.final_score,
                       gr.accepted,
                       gr.rejected_stage,
                       gr.rejected_reason,
                       gr.llm_scores::text AS llm_scores,
                       gr.stage_payload_json -> 'rejection_reasons' AS rejection_reasons
                FROM synthetic_query_gating_result gr
                WHERE gr.gating_batch_id = :gatingBatchId
                """
        );
        MapSqlParameterSource params = new MapSqlParameterSource("gatingBatchId", gatingBatchId);
        if (methodCode != null && !methodCode.isBlank()) {
            sql.append(" AND gr.generation_strategy = :methodCode");
            params.addValue("methodCode", methodCode.trim().toUpperCase());
        }
        if (queryType != null && !queryType.isBlank()) {
            sql.append(" AND gr.query_type = :queryType");
            params.addValue("queryType", queryType.trim());
        }
        if (passStage != null && !passStage.isBlank()) {
            switch (passStage) {
                case "failed_rule", "rejected" -> sql.append(" AND COALESCE(gr.rule_pass, TRUE) = FALSE");
                case "passed_rule" -> sql.append("""
                         AND COALESCE(gr.rule_pass, TRUE)
                         AND COALESCE((gr.stage_payload_json ->> 'passed_llm_self_eval')::boolean, FALSE) = FALSE
                        """);
                case "passed_llm" -> sql.append("""
                         AND COALESCE(gr.rule_pass, TRUE)
                         AND COALESCE((gr.stage_payload_json ->> 'passed_llm_self_eval')::boolean, TRUE)
                         AND COALESCE((gr.stage_payload_json ->> 'passed_retrieval_utility')::boolean, FALSE) = FALSE
                        """);
                case "passed_utility" -> sql.append("""
                         AND COALESCE(gr.rule_pass, TRUE)
                         AND COALESCE((gr.stage_payload_json ->> 'passed_llm_self_eval')::boolean, TRUE)
                         AND COALESCE((gr.stage_payload_json ->> 'passed_retrieval_utility')::boolean, TRUE)
                         AND COALESCE(gr.diversity_pass, FALSE) = FALSE
                        """);
                case "passed_diversity" -> sql.append("""
                         AND COALESCE(gr.rule_pass, TRUE)
                         AND COALESCE((gr.stage_payload_json ->> 'passed_llm_self_eval')::boolean, TRUE)
                         AND COALESCE((gr.stage_payload_json ->> 'passed_retrieval_utility')::boolean, TRUE)
                         AND COALESCE(gr.diversity_pass, TRUE)
                         AND COALESCE(gr.accepted, FALSE) = FALSE
                        """);
                case "passed_all" -> sql.append("""
                         AND COALESCE(gr.rule_pass, TRUE)
                         AND COALESCE((gr.stage_payload_json ->> 'passed_llm_self_eval')::boolean, TRUE)
                         AND COALESCE((gr.stage_payload_json ->> 'passed_retrieval_utility')::boolean, TRUE)
                         AND COALESCE(gr.diversity_pass, TRUE)
                         AND gr.accepted
                        """);
                default -> {
                    // validated in service layer
                }
            }
        }
        sql.append(" ORDER BY gr.created_at DESC LIMIT :limit OFFSET :offset");
        params.addValue("limit", normalizeLimit(limit, 300));
        params.addValue("offset", normalizeOffset(offset));
        return jdbcTemplate.query(
                sql.toString(),
                params,
                (rs, rowNum) -> new GatingDtos.GatingResultRow(
                        rs.getString("synthetic_query_id"),
                        rs.getString("query_text"),
                        rs.getString("query_type"),
                        rs.getString("generation_strategy"),
                        rs.getObject("rule_pass", Boolean.class),
                        rs.getObject("llm_pass", Boolean.class),
                        rs.getObject("utility_pass", Boolean.class),
                        rs.getObject("diversity_pass", Boolean.class),
                        rs.getObject("utility_score", Double.class),
                        rs.getObject("novelty_score", Double.class),
                        rs.getObject("final_score", Double.class),
                        rs.getBoolean("accepted"),
                        rs.getString("rejected_stage"),
                        rs.getString("rejected_reason"),
                        readJson(objectMapper, rs, "llm_scores"),
                        readJson(objectMapper, rs, "rejection_reasons")
                )
        );
    }

    private GatingDtos.GatingBatchRow mapGatingBatchRow(ResultSet rs) throws SQLException {
        JsonNode stageConfig = readJson(objectMapper, rs, "stage_config");
        String retrieverMode = stageConfig.path("retriever_config").path("retriever_mode").asText(null);
        return new GatingDtos.GatingBatchRow(
                readUuid(rs, "gating_batch_id"),
                rs.getString("gating_preset"),
                retrieverMode,
                readUuid(rs, "generation_batch_id"),
                rs.getString("method_code"),
                rs.getString("method_name"),
                readUuid(rs, "source_generation_run_id"),
                readUuid(rs, "source_gating_run_id"),
                rs.getString("status"),
                readInstant(rs, "started_at"),
                readInstant(rs, "finished_at"),
                rs.getInt("processed_count"),
                rs.getInt("accepted_count"),
                rs.getInt("rejected_count"),
                rs.getObject("target_query_count", Integer.class),
                rs.getObject("estimated_seconds_per_query", Double.class),
                rs.getObject("estimated_remaining_seconds", Long.class),
                readJson(objectMapper, rs, "rejection_summary"),
                stageConfig
        );
    }

    private int intValue(Integer value) {
        return value == null ? 0 : value;
    }
}
