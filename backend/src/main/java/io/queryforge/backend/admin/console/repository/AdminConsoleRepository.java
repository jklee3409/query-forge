package io.queryforge.backend.admin.console.repository;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.queryforge.backend.admin.console.model.AdminConsoleDtos;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
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
public class AdminConsoleRepository {

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public record EvalSampleMeta(
            String sampleId,
            String userQueryKo,
            String queryCategory
    ) {
    }

    public List<AdminConsoleDtos.SyntheticGenerationMethod> findGenerationMethods() {
        String sql = """
                SELECT generation_method_id,
                       method_code,
                       method_name,
                       description,
                       active,
                       prompt_template_version,
                       summary_strategy,
                       translation_strategy,
                       query_language_strategy,
                       terminology_preservation_rule,
                       metadata::text AS metadata
                FROM synthetic_query_generation_method
                ORDER BY method_code
                """;
        return jdbcTemplate.query(
                sql,
                (rs, rowNum) -> new AdminConsoleDtos.SyntheticGenerationMethod(
                        readUuid(rs, "generation_method_id"),
                        rs.getString("method_code"),
                        rs.getString("method_name"),
                        rs.getString("description"),
                        rs.getBoolean("active"),
                        rs.getString("prompt_template_version"),
                        rs.getString("summary_strategy"),
                        rs.getString("translation_strategy"),
                        rs.getString("query_language_strategy"),
                        rs.getString("terminology_preservation_rule"),
                        readJson(rs, "metadata")
                )
        );
    }

    public Optional<AdminConsoleDtos.SyntheticGenerationMethod> findGenerationMethodByCode(String methodCode) {
        String sql = """
                SELECT generation_method_id,
                       method_code,
                       method_name,
                       description,
                       active,
                       prompt_template_version,
                       summary_strategy,
                       translation_strategy,
                       query_language_strategy,
                       terminology_preservation_rule,
                       metadata::text AS metadata
                FROM synthetic_query_generation_method
                WHERE method_code = :methodCode
                """;
        List<AdminConsoleDtos.SyntheticGenerationMethod> rows = jdbcTemplate.query(
                sql,
                new MapSqlParameterSource("methodCode", methodCode),
                (rs, rowNum) -> new AdminConsoleDtos.SyntheticGenerationMethod(
                        readUuid(rs, "generation_method_id"),
                        rs.getString("method_code"),
                        rs.getString("method_name"),
                        rs.getString("description"),
                        rs.getBoolean("active"),
                        rs.getString("prompt_template_version"),
                        rs.getString("summary_strategy"),
                        rs.getString("translation_strategy"),
                        rs.getString("query_language_strategy"),
                        rs.getString("terminology_preservation_rule"),
                        readJson(rs, "metadata")
                )
        );
        return rows.stream().findFirst();
    }

    @Transactional
    public UUID createGenerationBatch(
            UUID generationMethodId,
            String versionName,
            String sourceDocumentVersion,
            String createdBy,
            JsonNode configJson
    ) {
        UUID batchId = UUID.randomUUID();
        String sql = """
                INSERT INTO synthetic_query_generation_batch (
                    batch_id,
                    generation_method_id,
                    version_name,
                    source_document_version,
                    status,
                    started_at,
                    created_by,
                    config_json
                ) VALUES (
                    :batchId,
                    :generationMethodId,
                    :versionName,
                    :sourceDocumentVersion,
                    'running',
                    NOW(),
                    :createdBy,
                    CAST(:configJson AS jsonb)
                )
                """;
        jdbcTemplate.update(
                sql,
                new MapSqlParameterSource()
                        .addValue("batchId", batchId)
                        .addValue("generationMethodId", generationMethodId)
                        .addValue("versionName", versionName)
                        .addValue("sourceDocumentVersion", sourceDocumentVersion)
                        .addValue("createdBy", createdBy)
                        .addValue("configJson", configJson.toString())
        );
        return batchId;
    }

    @Transactional
    public void completeGenerationBatch(
            UUID batchId,
            UUID sourceGenerationRunId,
            int totalGeneratedCount,
            JsonNode metricsJson
    ) {
        String sql = """
                UPDATE synthetic_query_generation_batch
                SET status = 'completed',
                    source_generation_run_id = :sourceGenerationRunId,
                    total_generated_count = :totalGeneratedCount,
                    metrics_json = CAST(:metricsJson AS jsonb),
                    finished_at = NOW()
                WHERE batch_id = :batchId
                """;
        jdbcTemplate.update(
                sql,
                new MapSqlParameterSource()
                        .addValue("batchId", batchId)
                        .addValue("sourceGenerationRunId", sourceGenerationRunId)
                        .addValue("totalGeneratedCount", totalGeneratedCount)
                        .addValue("metricsJson", metricsJson.toString())
        );
    }

    @Transactional
    public void failGenerationBatch(UUID batchId, String errorMessage, JsonNode resultPayload) {
        String sql = """
                UPDATE synthetic_query_generation_batch
                SET status = 'failed',
                    finished_at = NOW(),
                    metrics_json = CAST(:metricsJson AS jsonb)
                WHERE batch_id = :batchId
                """;
        JsonNode payload = objectMapper.valueToTree(Map.of(
                "error", errorMessage == null ? "" : errorMessage,
                "result", resultPayload
        ));
        jdbcTemplate.update(
                sql,
                new MapSqlParameterSource()
                        .addValue("batchId", batchId)
                        .addValue("metricsJson", payload.toString())
        );
    }

    public List<AdminConsoleDtos.SyntheticGenerationBatchRow> findGenerationBatches(Integer limit) {
        String sql = """
                SELECT b.batch_id,
                       m.method_code,
                       m.method_name,
                       b.version_name,
                       b.source_document_version,
                       b.source_generation_run_id,
                       b.status,
                       b.started_at,
                       b.finished_at,
                       b.total_generated_count,
                       b.created_by,
                       b.metrics_json::text AS metrics_json
                FROM synthetic_query_generation_batch b
                JOIN synthetic_query_generation_method m
                  ON m.generation_method_id = b.generation_method_id
                ORDER BY b.created_at DESC
                LIMIT :limit
                """;
        return jdbcTemplate.query(
                sql,
                new MapSqlParameterSource("limit", normalizeLimit(limit, 100)),
                (rs, rowNum) -> new AdminConsoleDtos.SyntheticGenerationBatchRow(
                        readUuid(rs, "batch_id"),
                        rs.getString("method_code"),
                        rs.getString("method_name"),
                        rs.getString("version_name"),
                        rs.getString("source_document_version"),
                        readUuid(rs, "source_generation_run_id"),
                        rs.getString("status"),
                        readInstant(rs, "started_at"),
                        readInstant(rs, "finished_at"),
                        rs.getInt("total_generated_count"),
                        rs.getString("created_by"),
                        readJson(rs, "metrics_json")
                )
        );
    }

    public Optional<AdminConsoleDtos.SyntheticGenerationBatchRow> findGenerationBatch(UUID batchId) {
        String sql = """
                SELECT b.batch_id,
                       m.method_code,
                       m.method_name,
                       b.version_name,
                       b.source_document_version,
                       b.source_generation_run_id,
                       b.status,
                       b.started_at,
                       b.finished_at,
                       b.total_generated_count,
                       b.created_by,
                       b.metrics_json::text AS metrics_json
                FROM synthetic_query_generation_batch b
                JOIN synthetic_query_generation_method m
                  ON m.generation_method_id = b.generation_method_id
                WHERE b.batch_id = :batchId
                """;
        List<AdminConsoleDtos.SyntheticGenerationBatchRow> rows = jdbcTemplate.query(
                sql,
                new MapSqlParameterSource("batchId", batchId),
                (rs, rowNum) -> new AdminConsoleDtos.SyntheticGenerationBatchRow(
                        readUuid(rs, "batch_id"),
                        rs.getString("method_code"),
                        rs.getString("method_name"),
                        rs.getString("version_name"),
                        rs.getString("source_document_version"),
                        readUuid(rs, "source_generation_run_id"),
                        rs.getString("status"),
                        readInstant(rs, "started_at"),
                        readInstant(rs, "finished_at"),
                        rs.getInt("total_generated_count"),
                        rs.getString("created_by"),
                        readJson(rs, "metrics_json")
                )
        );
        return rows.stream().findFirst();
    }

    public List<AdminConsoleDtos.SyntheticQueryRow> findSyntheticQueries(
            String methodCode,
            UUID batchId,
            String queryType,
            Boolean gated,
            Integer limit,
            Integer offset
    ) {
        StringBuilder sql = new StringBuilder(
                """
                SELECT r.synthetic_query_id,
                       r.query_text,
                       r.query_type,
                       r.generation_strategy,
                       b.batch_id,
                       b.version_name,
                       r.chunk_id_source,
                       r.target_chunk_ids::text AS target_chunk_ids,
                       r.created_at,
                       EXISTS (
                           SELECT 1
                           FROM synthetic_queries_gated g
                           WHERE g.synthetic_query_id = r.synthetic_query_id
                             AND g.final_decision = TRUE
                       ) AS gated
                FROM synthetic_queries_raw r
                LEFT JOIN synthetic_query_generation_batch b
                  ON b.source_generation_run_id = r.experiment_run_id
                WHERE 1=1
                """
        );
        MapSqlParameterSource params = new MapSqlParameterSource();
        if (methodCode != null && !methodCode.isBlank()) {
            sql.append(" AND r.generation_strategy = :methodCode");
            params.addValue("methodCode", methodCode.trim().toUpperCase());
        }
        if (batchId != null) {
            sql.append(" AND b.batch_id = :batchId");
            params.addValue("batchId", batchId);
        }
        if (queryType != null && !queryType.isBlank()) {
            sql.append(" AND r.query_type = :queryType");
            params.addValue("queryType", queryType.trim());
        }
        if (gated != null) {
            if (gated) {
                sql.append(" AND EXISTS (SELECT 1 FROM synthetic_queries_gated g2 WHERE g2.synthetic_query_id = r.synthetic_query_id AND g2.final_decision = TRUE)");
            } else {
                sql.append(" AND NOT EXISTS (SELECT 1 FROM synthetic_queries_gated g2 WHERE g2.synthetic_query_id = r.synthetic_query_id AND g2.final_decision = TRUE)");
            }
        }
        sql.append("""
                ORDER BY r.created_at DESC
                LIMIT :limit OFFSET :offset
                """);
        params.addValue("limit", normalizeLimit(limit, 200));
        params.addValue("offset", normalizeOffset(offset));
        return jdbcTemplate.query(
                sql.toString(),
                params,
                (rs, rowNum) -> new AdminConsoleDtos.SyntheticQueryRow(
                        rs.getString("synthetic_query_id"),
                        rs.getString("query_text"),
                        rs.getString("query_type"),
                        rs.getString("generation_strategy"),
                        readUuid(rs, "batch_id"),
                        rs.getString("version_name"),
                        rs.getString("chunk_id_source"),
                        readJson(rs, "target_chunk_ids"),
                        readInstant(rs, "created_at"),
                        rs.getBoolean("gated")
                )
        );
    }

    public Optional<AdminConsoleDtos.SyntheticQueryDetailResponse> findSyntheticQueryDetail(String queryId) {
        String sql = """
                SELECT r.synthetic_query_id,
                       r.query_text,
                       r.query_type,
                       r.generation_strategy,
                       jsonb_build_object(
                           'chunk_id', c.chunk_id,
                           'document_id', c.document_id,
                           'chunk_text', c.chunk_text,
                           'chunk_index_in_document', c.chunk_index_in_document,
                           'section_path_text', c.section_path_text
                       )::text AS source_chunk,
                       r.source_summary,
                       r.glossary_terms::text AS glossary_terms,
                       r.prompt_version,
                       r.prompt_hash,
                       r.llm_output::text AS raw_output,
                       r.metadata::text AS metadata
                FROM synthetic_queries_raw r
                LEFT JOIN corpus_chunks c
                  ON c.chunk_id = r.chunk_id_source
                WHERE r.synthetic_query_id = :queryId
                """;
        List<AdminConsoleDtos.SyntheticQueryDetailResponse> rows = jdbcTemplate.query(
                sql,
                new MapSqlParameterSource("queryId", queryId),
                (rs, rowNum) -> new AdminConsoleDtos.SyntheticQueryDetailResponse(
                        rs.getString("synthetic_query_id"),
                        rs.getString("query_text"),
                        rs.getString("query_type"),
                        rs.getString("generation_strategy"),
                        readJson(rs, "source_chunk"),
                        rs.getString("source_summary"),
                        readJson(rs, "glossary_terms"),
                        rs.getString("prompt_version"),
                        rs.getString("prompt_hash"),
                        readJson(rs, "raw_output"),
                        readJson(rs, "metadata")
                )
        );
        return rows.stream().findFirst();
    }

    public AdminConsoleDtos.SyntheticStatsResponse findSyntheticStats(String methodCode, UUID batchId) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        String syntheticWhere = buildSyntheticFilterClause(methodCode, batchId, params);
        String batchWhere = buildBatchFilterClause(methodCode, batchId, params);

        String byMethodSql = """
                SELECT COALESCE(
                    jsonb_agg(
                        jsonb_build_object('method_code', x.generation_strategy, 'count', x.cnt)
                        ORDER BY x.generation_strategy
                    ),
                    '[]'::jsonb
                )::text AS payload
                FROM (
                    SELECT r.generation_strategy, COUNT(*) AS cnt
                    FROM synthetic_queries_raw r
                    LEFT JOIN synthetic_query_generation_batch b
                      ON b.source_generation_run_id = r.experiment_run_id
                    WHERE 1=1
                """ + syntheticWhere + """
                    GROUP BY r.generation_strategy
                ) x
                """;
        String byBatchSql = """
                SELECT COALESCE(
                    jsonb_agg(
                        jsonb_build_object(
                            'batch_id', x.batch_id,
                            'version_name', x.version_name,
                            'method_code', x.method_code,
                            'count', x.cnt
                        )
                        ORDER BY x.created_at DESC
                    ),
                    '[]'::jsonb
                )::text AS payload
                FROM (
                    SELECT b.batch_id,
                           b.version_name,
                           m.method_code,
                           b.created_at,
                           COUNT(r.synthetic_query_id) AS cnt
                    FROM synthetic_query_generation_batch b
                    JOIN synthetic_query_generation_method m
                      ON m.generation_method_id = b.generation_method_id
                    LEFT JOIN synthetic_queries_raw r
                      ON r.experiment_run_id = b.source_generation_run_id
                    WHERE 1=1
                """ + batchWhere + """
                    GROUP BY b.batch_id, b.version_name, m.method_code, b.created_at
                ) x
                """;
        String byTypeSql = """
                SELECT COALESCE(
                    jsonb_agg(
                        jsonb_build_object('query_type', x.query_type, 'count', x.cnt)
                        ORDER BY x.query_type
                    ),
                    '[]'::jsonb
                )::text AS payload
                FROM (
                    SELECT r.query_type, COUNT(*) AS cnt
                    FROM synthetic_queries_raw r
                    LEFT JOIN synthetic_query_generation_batch b
                      ON b.source_generation_run_id = r.experiment_run_id
                    WHERE 1=1
                """ + syntheticWhere + """
                    GROUP BY r.query_type
                ) x
                """;
        String byVersionSql = """
                SELECT COALESCE(
                    jsonb_agg(
                        jsonb_build_object('version_label', x.version_label, 'count', x.cnt)
                        ORDER BY x.version_label
                    ),
                    '[]'::jsonb
                )::text AS payload
                FROM (
                    SELECT COALESCE(c.version_label, 'unknown') AS version_label, COUNT(*) AS cnt
                    FROM synthetic_queries_raw r
                    LEFT JOIN synthetic_query_generation_batch b
                      ON b.source_generation_run_id = r.experiment_run_id
                    LEFT JOIN corpus_chunks c
                      ON c.chunk_id = r.chunk_id_source
                    WHERE 1=1
                """ + syntheticWhere + """
                    GROUP BY COALESCE(c.version_label, 'unknown')
                ) x
                """;
        JsonNode byMethod = readJson(jdbcTemplate.queryForObject(byMethodSql, params, String.class));
        JsonNode byBatch = readJson(jdbcTemplate.queryForObject(byBatchSql, params, String.class));
        JsonNode byType = readJson(jdbcTemplate.queryForObject(byTypeSql, params, String.class));
        JsonNode byVersion = readJson(jdbcTemplate.queryForObject(byVersionSql, params, String.class));
        return new AdminConsoleDtos.SyntheticStatsResponse(byMethod, byBatch, byType, byVersion);
    }

    @Transactional
    public UUID createGatingBatch(
            String gatingPreset,
            UUID generationMethodId,
            UUID generationBatchId,
            UUID sourceGenerationRunId,
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
                    stage_config_json,
                    status,
                    started_at,
                    created_by
                ) VALUES (
                    :gatingBatchId,
                    :gatingPreset,
                    :generationMethodId,
                    :generationBatchId,
                    :sourceGenerationRunId,
                    CAST(:stageConfig AS jsonb),
                    'running',
                    NOW(),
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
    public void failGatingBatch(UUID gatingBatchId, String errorMessage) {
        String sql = """
                UPDATE quality_gating_batch
                SET status = 'failed',
                    finished_at = NOW(),
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
    }

    public Optional<AdminConsoleDtos.GatingBatchRow> findGatingBatch(UUID gatingBatchId) {
        String sql = """
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
                       qb.processed_count,
                       qb.accepted_count,
                       qb.rejected_count,
                       qb.rejection_summary::text AS rejection_summary
                FROM quality_gating_batch qb
                LEFT JOIN synthetic_query_generation_method m
                  ON m.generation_method_id = qb.generation_method_id
                WHERE qb.gating_batch_id = :gatingBatchId
                """;
        List<AdminConsoleDtos.GatingBatchRow> rows = jdbcTemplate.query(
                sql,
                new MapSqlParameterSource("gatingBatchId", gatingBatchId),
                (rs, rowNum) -> mapGatingBatchRow(rs)
        );
        return rows.stream().findFirst();
    }

    public List<AdminConsoleDtos.GatingBatchRow> findGatingBatches(Integer limit) {
        String sql = """
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
                       qb.processed_count,
                       qb.accepted_count,
                       qb.rejected_count,
                       qb.rejection_summary::text AS rejection_summary
                FROM quality_gating_batch qb
                LEFT JOIN synthetic_query_generation_method m
                  ON m.generation_method_id = qb.generation_method_id
                ORDER BY qb.created_at DESC
                LIMIT :limit
                """;
        return jdbcTemplate.query(
                sql,
                new MapSqlParameterSource("limit", normalizeLimit(limit, 100)),
                (rs, rowNum) -> mapGatingBatchRow(rs)
        );
    }

    public AdminConsoleDtos.GatingFunnelResponse findGatingFunnel(UUID gatingBatchId) {
        AdminConsoleDtos.GatingBatchRow batch = findGatingBatch(gatingBatchId)
                .orElseThrow(() -> new IllegalArgumentException("gating batch not found: " + gatingBatchId));
        if (batch.sourceGatingRunId() == null) {
            return new AdminConsoleDtos.GatingFunnelResponse(
                    batch.gatingBatchId(),
                    batch.methodCode(),
                    batch.gatingPreset(),
                    0,
                    0,
                    0,
                    0,
                    0,
                    0
            );
        }
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("sourceGatingRunId", batch.sourceGatingRunId().toString())
                .addValue("sourceGenerationRunId", batch.sourceGenerationRunId())
                .addValue("methodCode", batch.methodCode());
        String methodFilter = batch.methodCode() == null ? "" : " AND r.generation_strategy = :methodCode ";

        Long generatedTotal = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM synthetic_queries_raw r
                WHERE (:sourceGenerationRunId IS NULL OR r.experiment_run_id = :sourceGenerationRunId)
                """ + methodFilter,
                params,
                Long.class
        );
        String baseWhere = """
                FROM synthetic_queries_gated g
                JOIN synthetic_queries_raw r
                  ON r.synthetic_query_id = g.synthetic_query_id
                WHERE g.metadata ->> 'experiment_run_id' = :sourceGatingRunId
                """ + methodFilter;
        int passedRule = intValue(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) " + baseWhere + " AND COALESCE(g.passed_rule_filter, TRUE)",
                params,
                Integer.class
        ));
        int passedLlm = intValue(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) " + baseWhere + " AND COALESCE(g.passed_rule_filter, TRUE) AND COALESCE(g.passed_llm_self_eval, TRUE)",
                params,
                Integer.class
        ));
        int passedUtility = intValue(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) " + baseWhere + " AND COALESCE(g.passed_rule_filter, TRUE) AND COALESCE(g.passed_llm_self_eval, TRUE) AND COALESCE(g.passed_retrieval_utility, TRUE)",
                params,
                Integer.class
        ));
        int passedDiversity = intValue(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) " + baseWhere + " AND COALESCE(g.passed_rule_filter, TRUE) AND COALESCE(g.passed_llm_self_eval, TRUE) AND COALESCE(g.passed_retrieval_utility, TRUE) AND COALESCE(g.passed_diversity, TRUE)",
                params,
                Integer.class
        ));
        int finalAccepted = intValue(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) " + baseWhere + " AND g.final_decision = TRUE",
                params,
                Integer.class
        ));
        return new AdminConsoleDtos.GatingFunnelResponse(
                batch.gatingBatchId(),
                batch.methodCode(),
                batch.gatingPreset(),
                generatedTotal == null ? 0 : generatedTotal.intValue(),
                passedRule,
                passedLlm,
                passedUtility,
                passedDiversity,
                finalAccepted
        );
    }

    public List<AdminConsoleDtos.GatingResultRow> findGatingResults(
            UUID gatingBatchId,
            String queryType,
            Integer limit,
            Integer offset
    ) {
        AdminConsoleDtos.GatingBatchRow batch = findGatingBatch(gatingBatchId)
                .orElseThrow(() -> new IllegalArgumentException("gating batch not found: " + gatingBatchId));
        if (batch.sourceGatingRunId() == null) {
            return List.of();
        }
        StringBuilder sql = new StringBuilder(
                """
                SELECT r.synthetic_query_id,
                       r.query_text,
                       r.query_type,
                       r.generation_strategy,
                       g.passed_rule_filter,
                       g.passed_llm_self_eval,
                       g.passed_retrieval_utility,
                       g.passed_diversity,
                       g.utility_score,
                       g.novelty_score,
                       g.final_score,
                       g.final_decision,
                       g.llm_scores::text AS llm_scores,
                       g.rejection_reasons::text AS rejection_reasons
                FROM synthetic_queries_gated g
                JOIN synthetic_queries_raw r
                  ON r.synthetic_query_id = g.synthetic_query_id
                WHERE g.metadata ->> 'experiment_run_id' = :sourceGatingRunId
                """
        );
        MapSqlParameterSource params = new MapSqlParameterSource("sourceGatingRunId", batch.sourceGatingRunId().toString());
        if (batch.methodCode() != null) {
            sql.append(" AND r.generation_strategy = :methodCode");
            params.addValue("methodCode", batch.methodCode());
        }
        if (queryType != null && !queryType.isBlank()) {
            sql.append(" AND r.query_type = :queryType");
            params.addValue("queryType", queryType.trim());
        }
        sql.append("""
                ORDER BY g.created_at DESC
                LIMIT :limit OFFSET :offset
                """);
        params.addValue("limit", normalizeLimit(limit, 300));
        params.addValue("offset", normalizeOffset(offset));
        return jdbcTemplate.query(
                sql.toString(),
                params,
                (rs, rowNum) -> new AdminConsoleDtos.GatingResultRow(
                        rs.getString("synthetic_query_id"),
                        rs.getString("query_text"),
                        rs.getString("query_type"),
                        rs.getString("generation_strategy"),
                        rs.getObject("passed_rule_filter", Boolean.class),
                        rs.getObject("passed_llm_self_eval", Boolean.class),
                        rs.getObject("passed_retrieval_utility", Boolean.class),
                        rs.getObject("passed_diversity", Boolean.class),
                        rs.getObject("utility_score", Double.class),
                        rs.getObject("novelty_score", Double.class),
                        rs.getObject("final_score", Double.class),
                        rs.getBoolean("final_decision"),
                        readJson(rs, "llm_scores"),
                        readJson(rs, "rejection_reasons")
                )
        );
    }

    public long countEvalSamples() {
        Long value = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM eval_samples", new MapSqlParameterSource(), Long.class);
        return value == null ? 0L : value;
    }

    public Optional<UUID> findEvalDatasetIdByKey(String datasetKey) {
        String sql = "SELECT dataset_id FROM eval_dataset WHERE dataset_key = :datasetKey";
        List<UUID> rows = jdbcTemplate.query(
                sql,
                new MapSqlParameterSource("datasetKey", datasetKey),
                (rs, rowNum) -> readUuid(rs, "dataset_id")
        );
        return rows.stream().findFirst();
    }

    @Transactional
    public UUID upsertEvalDataset(
            String datasetKey,
            String datasetName,
            String description,
            String version,
            int totalItems,
            JsonNode categoryDistribution,
            JsonNode singleMultiDistribution
    ) {
        UUID datasetId = findEvalDatasetIdByKey(datasetKey).orElse(UUID.randomUUID());
        String sql = """
                INSERT INTO eval_dataset (
                    dataset_id,
                    dataset_key,
                    dataset_name,
                    description,
                    version,
                    total_items,
                    category_distribution,
                    single_multi_distribution,
                    updated_at
                ) VALUES (
                    :datasetId,
                    :datasetKey,
                    :datasetName,
                    :description,
                    :version,
                    :totalItems,
                    CAST(:categoryDistribution AS jsonb),
                    CAST(:singleMultiDistribution AS jsonb),
                    NOW()
                )
                ON CONFLICT (dataset_key) DO UPDATE
                SET dataset_name = EXCLUDED.dataset_name,
                    description = EXCLUDED.description,
                    version = EXCLUDED.version,
                    total_items = EXCLUDED.total_items,
                    category_distribution = EXCLUDED.category_distribution,
                    single_multi_distribution = EXCLUDED.single_multi_distribution,
                    updated_at = NOW()
                """;
        jdbcTemplate.update(
                sql,
                new MapSqlParameterSource()
                        .addValue("datasetId", datasetId)
                        .addValue("datasetKey", datasetKey)
                        .addValue("datasetName", datasetName)
                        .addValue("description", description)
                        .addValue("version", version)
                        .addValue("totalItems", totalItems)
                        .addValue("categoryDistribution", categoryDistribution.toString())
                        .addValue("singleMultiDistribution", singleMultiDistribution.toString())
        );
        return datasetId;
    }

    @Transactional
    public void refreshEvalDatasetItems(UUID datasetId) {
        jdbcTemplate.update(
                "DELETE FROM eval_dataset_item WHERE dataset_id = :datasetId",
                new MapSqlParameterSource("datasetId", datasetId)
        );
        jdbcTemplate.update(
                """
                INSERT INTO eval_dataset_item (
                    dataset_item_id,
                    dataset_id,
                    sample_id,
                    query_category,
                    single_or_multi_chunk,
                    active
                )
                SELECT gen_random_uuid(),
                       :datasetId,
                       s.sample_id,
                       s.query_category,
                       s.single_or_multi_chunk,
                       TRUE
                FROM eval_samples s
                ORDER BY s.sample_id
                """,
                new MapSqlParameterSource("datasetId", datasetId)
        );
    }

    public List<AdminConsoleDtos.EvalDatasetRow> findEvalDatasets() {
        String sql = """
                SELECT d.dataset_id,
                       d.dataset_key,
                       d.dataset_name,
                       d.version,
                       COALESCE(items.total_items, d.total_items, 0) AS total_items,
                       d.category_distribution::text AS category_distribution,
                       d.single_multi_distribution::text AS single_multi_distribution,
                       d.created_at
                FROM eval_dataset d
                LEFT JOIN LATERAL (
                    SELECT COUNT(*) AS total_items
                    FROM eval_dataset_item i
                    WHERE i.dataset_id = d.dataset_id
                      AND i.active = TRUE
                ) items ON TRUE
                ORDER BY d.created_at DESC
                """;
        return jdbcTemplate.query(
                sql,
                (rs, rowNum) -> new AdminConsoleDtos.EvalDatasetRow(
                        readUuid(rs, "dataset_id"),
                        rs.getString("dataset_key"),
                        rs.getString("dataset_name"),
                        rs.getString("version"),
                        rs.getInt("total_items"),
                        readJson(rs, "category_distribution"),
                        readJson(rs, "single_multi_distribution"),
                        readInstant(rs, "created_at")
                )
        );
    }

    public List<AdminConsoleDtos.EvalDatasetItemRow> findEvalDatasetItems(UUID datasetId, Integer limit, Integer offset) {
        String sql = """
                SELECT i.dataset_id,
                       s.sample_id,
                       s.split,
                       s.query_category,
                       s.single_or_multi_chunk,
                       s.user_query_ko,
                       s.dialog_context::text AS dialog_context
                FROM eval_dataset_item i
                JOIN eval_samples s
                  ON s.sample_id = i.sample_id
                WHERE i.dataset_id = :datasetId
                  AND i.active = TRUE
                ORDER BY s.sample_id
                LIMIT :limit OFFSET :offset
                """;
        return jdbcTemplate.query(
                sql,
                new MapSqlParameterSource()
                        .addValue("datasetId", datasetId)
                        .addValue("limit", normalizeLimit(limit, 500))
                        .addValue("offset", normalizeOffset(offset)),
                (rs, rowNum) -> new AdminConsoleDtos.EvalDatasetItemRow(
                        readUuid(rs, "dataset_id"),
                        rs.getString("sample_id"),
                        rs.getString("split"),
                        rs.getString("query_category"),
                        rs.getString("single_or_multi_chunk"),
                        rs.getString("user_query_ko"),
                        readJson(rs, "dialog_context")
                )
        );
    }

    @Transactional
    public UUID createRagTestRun(
            String runLabel,
            UUID datasetId,
            JsonNode generationMethodCodes,
            JsonNode generationBatchIds,
            Boolean gatingApplied,
            String gatingPreset,
            Boolean rewriteEnabled,
            Boolean selectiveRewrite,
            Boolean useSessionContext,
            Integer topK,
            Double threshold,
            Integer retrievalTopK,
            Integer rerankTopN,
            String experimentConfigName,
            String createdBy
    ) {
        UUID runId = UUID.randomUUID();
        String sql = """
                INSERT INTO rag_test_run (
                    rag_test_run_id,
                    run_label,
                    status,
                    dataset_id,
                    generation_method_codes,
                    generation_batch_ids,
                    gating_applied,
                    gating_preset,
                    rewrite_enabled,
                    selective_rewrite,
                    use_session_context,
                    top_k,
                    threshold,
                    retrieval_top_k,
                    rerank_top_n,
                    experiment_config_name,
                    created_by,
                    started_at
                ) VALUES (
                    :runId,
                    :runLabel,
                    'running',
                    :datasetId,
                    CAST(:generationMethodCodes AS jsonb),
                    CAST(:generationBatchIds AS jsonb),
                    :gatingApplied,
                    :gatingPreset,
                    :rewriteEnabled,
                    :selectiveRewrite,
                    :useSessionContext,
                    :topK,
                    :threshold,
                    :retrievalTopK,
                    :rerankTopN,
                    :experimentConfigName,
                    :createdBy,
                    NOW()
                )
                """;
        jdbcTemplate.update(
                sql,
                new MapSqlParameterSource()
                        .addValue("runId", runId)
                        .addValue("runLabel", runLabel)
                        .addValue("datasetId", datasetId)
                        .addValue("generationMethodCodes", generationMethodCodes.toString())
                        .addValue("generationBatchIds", generationBatchIds.toString())
                        .addValue("gatingApplied", gatingApplied)
                        .addValue("gatingPreset", gatingPreset)
                        .addValue("rewriteEnabled", rewriteEnabled)
                        .addValue("selectiveRewrite", selectiveRewrite)
                        .addValue("useSessionContext", useSessionContext)
                        .addValue("topK", topK)
                        .addValue("threshold", threshold)
                        .addValue("retrievalTopK", retrievalTopK)
                        .addValue("rerankTopN", rerankTopN)
                        .addValue("experimentConfigName", experimentConfigName)
                        .addValue("createdBy", createdBy)
        );
        return runId;
    }

    @Transactional
    public void upsertRagTestRunConfig(UUID runId, JsonNode configJson) {
        String sql = """
                INSERT INTO rag_test_run_config (
                    config_id,
                    rag_test_run_id,
                    config_json
                ) VALUES (
                    gen_random_uuid(),
                    :runId,
                    CAST(:configJson AS jsonb)
                )
                ON CONFLICT (rag_test_run_id) DO UPDATE
                SET config_json = EXCLUDED.config_json
                """;
        jdbcTemplate.update(
                sql,
                new MapSqlParameterSource()
                        .addValue("runId", runId)
                        .addValue("configJson", configJson.toString())
        );
    }

    @Transactional
    public void completeRagTestRun(UUID runId, JsonNode metricsJson, UUID sourceExperimentRunId) {
        String sql = """
                UPDATE rag_test_run
                SET status = 'completed',
                    finished_at = NOW(),
                    metrics_json = CAST(:metricsJson AS jsonb),
                    source_experiment_run_id = :sourceExperimentRunId
                WHERE rag_test_run_id = :runId
                """;
        jdbcTemplate.update(
                sql,
                new MapSqlParameterSource()
                        .addValue("runId", runId)
                        .addValue("metricsJson", metricsJson.toString())
                        .addValue("sourceExperimentRunId", sourceExperimentRunId)
        );
    }

    @Transactional
    public void failRagTestRun(UUID runId, String errorMessage) {
        String sql = """
                UPDATE rag_test_run
                SET status = 'failed',
                    finished_at = NOW(),
                    metrics_json = CAST(:metricsJson AS jsonb)
                WHERE rag_test_run_id = :runId
                """;
        JsonNode payload = objectMapper.valueToTree(Map.of("error", errorMessage == null ? "" : errorMessage));
        jdbcTemplate.update(
                sql,
                new MapSqlParameterSource()
                        .addValue("runId", runId)
                        .addValue("metricsJson", payload.toString())
        );
    }

    @Transactional
    public void upsertRagSummary(
            UUID runId,
            Double recallAt5,
            Double hitAt5,
            Double mrrAt10,
            Double ndcgAt10,
            Double latencyAvgMs,
            Double rewriteAcceptanceRate,
            JsonNode answerMetrics,
            JsonNode metricsJson
    ) {
        String sql = """
                INSERT INTO rag_test_result_summary (
                    rag_test_run_id,
                    recall_at_5,
                    hit_at_5,
                    mrr_at_10,
                    ndcg_at_10,
                    latency_avg_ms,
                    rewrite_acceptance_rate,
                    answer_metrics,
                    metrics_json
                ) VALUES (
                    :runId,
                    :recallAt5,
                    :hitAt5,
                    :mrrAt10,
                    :ndcgAt10,
                    :latencyAvgMs,
                    :rewriteAcceptanceRate,
                    CAST(:answerMetrics AS jsonb),
                    CAST(:metricsJson AS jsonb)
                )
                ON CONFLICT (rag_test_run_id) DO UPDATE
                SET recall_at_5 = EXCLUDED.recall_at_5,
                    hit_at_5 = EXCLUDED.hit_at_5,
                    mrr_at_10 = EXCLUDED.mrr_at_10,
                    ndcg_at_10 = EXCLUDED.ndcg_at_10,
                    latency_avg_ms = EXCLUDED.latency_avg_ms,
                    rewrite_acceptance_rate = EXCLUDED.rewrite_acceptance_rate,
                    answer_metrics = EXCLUDED.answer_metrics,
                    metrics_json = EXCLUDED.metrics_json
                """;
        jdbcTemplate.update(
                sql,
                new MapSqlParameterSource()
                        .addValue("runId", runId)
                        .addValue("recallAt5", recallAt5)
                        .addValue("hitAt5", hitAt5)
                        .addValue("mrrAt10", mrrAt10)
                        .addValue("ndcgAt10", ndcgAt10)
                        .addValue("latencyAvgMs", latencyAvgMs)
                        .addValue("rewriteAcceptanceRate", rewriteAcceptanceRate)
                        .addValue("answerMetrics", answerMetrics.toString())
                        .addValue("metricsJson", metricsJson.toString())
        );
    }

    @Transactional
    public void replaceRagDetailRows(UUID runId, List<AdminConsoleDtos.RagTestResultDetailRow> rows) {
        jdbcTemplate.update(
                "DELETE FROM rag_test_result_detail WHERE rag_test_run_id = :runId",
                new MapSqlParameterSource("runId", runId)
        );
        if (rows == null || rows.isEmpty()) {
            return;
        }
        String sql = """
                INSERT INTO rag_test_result_detail (
                    detail_id,
                    rag_test_run_id,
                    sample_id,
                    query_category,
                    raw_query,
                    rewrite_query,
                    rewrite_applied,
                    memory_candidates,
                    rewrite_candidates,
                    retrieved_chunks,
                    metric_contribution,
                    hit_target
                ) VALUES (
                    :detailId,
                    :runId,
                    :sampleId,
                    :queryCategory,
                    :rawQuery,
                    :rewriteQuery,
                    :rewriteApplied,
                    CAST(:memoryCandidates AS jsonb),
                    CAST(:rewriteCandidates AS jsonb),
                    CAST(:retrievedChunks AS jsonb),
                    CAST(:metricContribution AS jsonb),
                    :hitTarget
                )
                """;
        for (AdminConsoleDtos.RagTestResultDetailRow row : rows) {
            jdbcTemplate.update(
                sql,
                new MapSqlParameterSource()
                        .addValue("detailId", row.detailId())
                        .addValue("runId", row.ragTestRunId())
                        .addValue("sampleId", row.sampleId())
                        .addValue("queryCategory", row.queryCategory())
                        .addValue("rawQuery", row.rawQuery())
                        .addValue("rewriteQuery", row.rewriteQuery())
                        .addValue("rewriteApplied", row.rewriteApplied())
                        .addValue("memoryCandidates", row.memoryCandidates().toString())
                        .addValue("rewriteCandidates", row.rewriteCandidates().toString())
                        .addValue("retrievedChunks", row.retrievedChunks().toString())
                        .addValue("metricContribution", row.metricContribution().toString())
                        .addValue("hitTarget", row.hitTarget())
            );
        }
    }

    public List<EvalSampleMeta> findEvalSampleMeta(List<String> sampleIds) {
        if (sampleIds == null || sampleIds.isEmpty()) {
            return List.of();
        }
        String sql = """
                SELECT sample_id, user_query_ko, query_category
                FROM eval_samples
                WHERE sample_id IN (:sampleIds)
                """;
        return jdbcTemplate.query(
                sql,
                new MapSqlParameterSource("sampleIds", sampleIds),
                (rs, rowNum) -> new EvalSampleMeta(
                        rs.getString("sample_id"),
                        rs.getString("user_query_ko"),
                        rs.getString("query_category")
                )
        );
    }

    public Optional<AdminConsoleDtos.RagTestRunRow> findRagTestRun(UUID runId) {
        String sql = """
                SELECT r.rag_test_run_id,
                       r.run_label,
                       r.status,
                       r.dataset_id,
                       d.dataset_name,
                       r.generation_method_codes::text AS generation_method_codes,
                       r.generation_batch_ids::text AS generation_batch_ids,
                       r.gating_applied,
                       r.gating_preset,
                       r.rewrite_enabled,
                       r.selective_rewrite,
                       r.use_session_context,
                       r.retrieval_top_k,
                       r.threshold,
                       r.started_at,
                       r.finished_at,
                       r.metrics_json::text AS metrics_json
                FROM rag_test_run r
                LEFT JOIN eval_dataset d
                  ON d.dataset_id = r.dataset_id
                WHERE r.rag_test_run_id = :runId
                """;
        List<AdminConsoleDtos.RagTestRunRow> rows = jdbcTemplate.query(
                sql,
                new MapSqlParameterSource("runId", runId),
                (rs, rowNum) -> mapRagTestRunRow(rs)
        );
        return rows.stream().findFirst();
    }

    public List<AdminConsoleDtos.RagTestRunRow> findRagTestRuns(Integer limit) {
        String sql = """
                SELECT r.rag_test_run_id,
                       r.run_label,
                       r.status,
                       r.dataset_id,
                       d.dataset_name,
                       r.generation_method_codes::text AS generation_method_codes,
                       r.generation_batch_ids::text AS generation_batch_ids,
                       r.gating_applied,
                       r.gating_preset,
                       r.rewrite_enabled,
                       r.selective_rewrite,
                       r.use_session_context,
                       r.retrieval_top_k,
                       r.threshold,
                       r.started_at,
                       r.finished_at,
                       r.metrics_json::text AS metrics_json
                FROM rag_test_run r
                LEFT JOIN eval_dataset d
                  ON d.dataset_id = r.dataset_id
                ORDER BY r.created_at DESC
                LIMIT :limit
                """;
        return jdbcTemplate.query(
                sql,
                new MapSqlParameterSource("limit", normalizeLimit(limit, 100)),
                (rs, rowNum) -> mapRagTestRunRow(rs)
        );
    }

    public List<AdminConsoleDtos.RagTestResultDetailRow> findRagTestDetails(UUID runId, Integer limit) {
        String sql = """
                SELECT detail_id,
                       rag_test_run_id,
                       sample_id,
                       query_category,
                       raw_query,
                       rewrite_query,
                       rewrite_applied,
                       memory_candidates::text AS memory_candidates,
                       rewrite_candidates::text AS rewrite_candidates,
                       retrieved_chunks::text AS retrieved_chunks,
                       metric_contribution::text AS metric_contribution,
                       hit_target
                FROM rag_test_result_detail
                WHERE rag_test_run_id = :runId
                ORDER BY created_at DESC
                LIMIT :limit
                """;
        return jdbcTemplate.query(
                sql,
                new MapSqlParameterSource()
                        .addValue("runId", runId)
                        .addValue("limit", normalizeLimit(limit, 500)),
                (rs, rowNum) -> new AdminConsoleDtos.RagTestResultDetailRow(
                        readUuid(rs, "detail_id"),
                        readUuid(rs, "rag_test_run_id"),
                        rs.getString("sample_id"),
                        rs.getString("query_category"),
                        rs.getString("raw_query"),
                        rs.getString("rewrite_query"),
                        rs.getObject("rewrite_applied", Boolean.class),
                        readJson(rs, "memory_candidates"),
                        readJson(rs, "rewrite_candidates"),
                        readJson(rs, "retrieved_chunks"),
                        readJson(rs, "metric_contribution"),
                        rs.getObject("hit_target", Boolean.class)
                )
        );
    }

    public Optional<JsonNode> findRagSummaryMetrics(UUID runId) {
        String sql = """
                SELECT jsonb_build_object(
                    'recall_at_5', recall_at_5,
                    'hit_at_5', hit_at_5,
                    'mrr_at_10', mrr_at_10,
                    'ndcg_at_10', ndcg_at_10,
                    'latency_avg_ms', latency_avg_ms,
                    'rewrite_acceptance_rate', rewrite_acceptance_rate,
                    'answer_metrics', answer_metrics,
                    'metrics_json', metrics_json
                )::text AS payload
                FROM rag_test_result_summary
                WHERE rag_test_run_id = :runId
                """;
        List<String> rows = jdbcTemplate.query(
                sql,
                new MapSqlParameterSource("runId", runId),
                (rs, rowNum) -> rs.getString("payload")
        );
        return rows.stream().findFirst().map(this::readJson);
    }

    public List<AdminConsoleDtos.RagTestRunRow> findRagTestRunsByDataset(UUID datasetId) {
        String sql = """
                SELECT r.rag_test_run_id,
                       r.run_label,
                       r.status,
                       r.dataset_id,
                       d.dataset_name,
                       r.generation_method_codes::text AS generation_method_codes,
                       r.generation_batch_ids::text AS generation_batch_ids,
                       r.gating_applied,
                       r.gating_preset,
                       r.rewrite_enabled,
                       r.selective_rewrite,
                       r.use_session_context,
                       r.retrieval_top_k,
                       r.threshold,
                       r.started_at,
                       r.finished_at,
                       r.metrics_json::text AS metrics_json
                FROM rag_test_run r
                LEFT JOIN eval_dataset d
                  ON d.dataset_id = r.dataset_id
                WHERE r.dataset_id = :datasetId
                ORDER BY r.created_at DESC
                """;
        return jdbcTemplate.query(
                sql,
                new MapSqlParameterSource("datasetId", datasetId),
                (rs, rowNum) -> mapRagTestRunRow(rs)
        );
    }

    public JsonNode aggregateCategoryDistributionFromSamples() {
        String sql = """
                SELECT COALESCE(
                    jsonb_object_agg(x.query_category, x.cnt),
                    '{}'::jsonb
                )::text AS payload
                FROM (
                    SELECT query_category, COUNT(*) AS cnt
                    FROM eval_samples
                    GROUP BY query_category
                ) x
                """;
        return readJson(jdbcTemplate.queryForObject(sql, new MapSqlParameterSource(), String.class));
    }

    public JsonNode aggregateSingleMultiDistributionFromSamples() {
        String sql = """
                SELECT COALESCE(
                    jsonb_object_agg(x.single_or_multi_chunk, x.cnt),
                    '{}'::jsonb
                )::text AS payload
                FROM (
                    SELECT single_or_multi_chunk, COUNT(*) AS cnt
                    FROM eval_samples
                    GROUP BY single_or_multi_chunk
                ) x
                """;
        return readJson(jdbcTemplate.queryForObject(sql, new MapSqlParameterSource(), String.class));
    }

    private AdminConsoleDtos.GatingBatchRow mapGatingBatchRow(ResultSet rs) throws SQLException {
        return new AdminConsoleDtos.GatingBatchRow(
                readUuid(rs, "gating_batch_id"),
                rs.getString("gating_preset"),
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
                readJson(rs, "rejection_summary")
        );
    }

    private AdminConsoleDtos.RagTestRunRow mapRagTestRunRow(ResultSet rs) throws SQLException {
        return new AdminConsoleDtos.RagTestRunRow(
                readUuid(rs, "rag_test_run_id"),
                rs.getString("run_label"),
                rs.getString("status"),
                readUuid(rs, "dataset_id"),
                rs.getString("dataset_name"),
                readJson(rs, "generation_method_codes"),
                readJson(rs, "generation_batch_ids"),
                rs.getObject("gating_applied", Boolean.class),
                rs.getString("gating_preset"),
                rs.getObject("rewrite_enabled", Boolean.class),
                rs.getObject("selective_rewrite", Boolean.class),
                rs.getObject("use_session_context", Boolean.class),
                rs.getObject("retrieval_top_k", Integer.class),
                rs.getObject("threshold", Double.class),
                readInstant(rs, "started_at"),
                readInstant(rs, "finished_at"),
                readJson(rs, "metrics_json")
        );
    }

    private String buildSyntheticFilterClause(String methodCode, UUID batchId, MapSqlParameterSource params) {
        StringBuilder where = new StringBuilder();
        if (methodCode != null && !methodCode.isBlank()) {
            where.append(" AND r.generation_strategy = :methodCode");
            params.addValue("methodCode", methodCode.trim().toUpperCase());
        }
        if (batchId != null) {
            where.append(" AND b.batch_id = :batchId");
            params.addValue("batchId", batchId);
        }
        return where.toString();
    }

    private String buildBatchFilterClause(String methodCode, UUID batchId, MapSqlParameterSource params) {
        StringBuilder where = new StringBuilder();
        if (methodCode != null && !methodCode.isBlank()) {
            where.append(" AND m.method_code = :methodCode");
            params.addValue("methodCode", methodCode.trim().toUpperCase());
        }
        if (batchId != null) {
            where.append(" AND b.batch_id = :batchId");
            params.addValue("batchId", batchId);
        }
        return where.toString();
    }

    private int normalizeLimit(Integer value, int defaultLimit) {
        if (value == null || value <= 0) {
            return defaultLimit;
        }
        return Math.min(value, 1000);
    }

    private int normalizeOffset(Integer value) {
        if (value == null || value < 0) {
            return 0;
        }
        return value;
    }

    private int intValue(Integer value) {
        return value == null ? 0 : value;
    }

    private UUID readUuid(ResultSet rs, String column) throws SQLException {
        Object value = rs.getObject(column);
        if (value == null) {
            return null;
        }
        if (value instanceof UUID uuid) {
            return uuid;
        }
        return UUID.fromString(String.valueOf(value));
    }

    private Instant readInstant(ResultSet rs, String column) throws SQLException {
        Timestamp timestamp = rs.getTimestamp(column);
        return timestamp != null ? timestamp.toInstant() : null;
    }

    private JsonNode readJson(ResultSet rs, String column) throws SQLException {
        return readJson(rs.getString(column));
    }

    private JsonNode readJson(String raw) {
        if (raw == null || raw.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            return objectMapper.readTree(raw);
        } catch (Exception exception) {
            return objectMapper.valueToTree(Map.of("raw", raw));
        }
    }
}
