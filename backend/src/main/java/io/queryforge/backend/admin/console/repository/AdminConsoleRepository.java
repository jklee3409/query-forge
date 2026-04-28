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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AdminConsoleRepository {

    private static final List<String> STRATEGY_RAW_TABLES = List.of(
            "synthetic_queries_raw_a",
            "synthetic_queries_raw_b",
            "synthetic_queries_raw_c",
            "synthetic_queries_raw_d",
            "synthetic_queries_raw_e"
    );

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public record EvalSampleMeta(
            String sampleId,
            String userQueryKo,
            String userQueryEn,
            String queryLanguage,
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
                    created_by,
                    config_json
                ) VALUES (
                    :batchId,
                    :generationMethodId,
                    :versionName,
                    :sourceDocumentVersion,
                    'planned',
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
                    started_at = COALESCE(started_at, NOW()),
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
    public void syncSyntheticQueryBatchProvenance(UUID batchId, UUID sourceGenerationRunId) {
        for (String tableName : STRATEGY_RAW_TABLES) {
            updateStrategyRawBatchProvenance(tableName, batchId, sourceGenerationRunId);
        }

        String linkSql = """
                INSERT INTO synthetic_query_source_link (
                    synthetic_query_id,
                    source_doc_id,
                    source_chunk_id,
                    source_role,
                    metadata_json
                )
                SELECT r.synthetic_query_id,
                       r.target_doc_id,
                       r.chunk_id_source,
                       'primary',
                       jsonb_build_object(
                           'generation_batch_id', :batchId::text,
                           'source_generation_run_id', :sourceGenerationRunId::text
                       )
                FROM synthetic_queries_raw_all r
                WHERE r.experiment_run_id = :sourceGenerationRunId
                ON CONFLICT (synthetic_query_id, source_chunk_id, source_role) DO UPDATE
                SET source_doc_id = EXCLUDED.source_doc_id,
                    metadata_json = EXCLUDED.metadata_json
                """;
        jdbcTemplate.update(
                linkSql,
                new MapSqlParameterSource()
                        .addValue("batchId", batchId)
                        .addValue("sourceGenerationRunId", sourceGenerationRunId)
        );
    }

    @Transactional
    public void failGenerationBatch(UUID batchId, String errorMessage, JsonNode resultPayload) {
        String sql = """
                UPDATE synthetic_query_generation_batch
                SET status = 'failed',
                    started_at = COALESCE(started_at, NOW()),
                    finished_at = NOW(),
                    total_generated_count = 0,
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

    @Transactional
    public int deleteSyntheticQueriesByGenerationBatch(UUID batchId) {
        if (batchId == null) {
            return 0;
        }
        MapSqlParameterSource params = new MapSqlParameterSource("batchId", batchId);
        int totalDeleted = 0;
        for (String tableName : STRATEGY_RAW_TABLES) {
            String sql = "DELETE FROM " + tableName + " WHERE generation_batch_id = :batchId";
            totalDeleted += jdbcTemplate.update(sql, params);
        }
        return totalDeleted;
    }

    @Transactional
    public void markGenerationBatchRunning(UUID batchId) {
        String sql = """
                UPDATE synthetic_query_generation_batch
                SET status = 'running',
                    started_at = COALESCE(started_at, NOW())
                WHERE batch_id = :batchId
                  AND status IN ('planned', 'queued', 'failed')
                """;
        jdbcTemplate.update(sql, new MapSqlParameterSource("batchId", batchId));
    }

    @Transactional
    public void cancelGenerationBatch(UUID batchId, String reason) {
        String sql = """
                UPDATE synthetic_query_generation_batch
                SET status = 'cancelled',
                    finished_at = NOW(),
                    metrics_json = jsonb_build_object('cancel_reason', :reason)
                WHERE batch_id = :batchId
                """;
        jdbcTemplate.update(
                sql,
                new MapSqlParameterSource()
                        .addValue("batchId", batchId)
                        .addValue("reason", reason == null ? "" : reason)
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
                FROM synthetic_queries_raw_all r
                LEFT JOIN synthetic_query_generation_batch b
                  ON b.batch_id = r.generation_batch_id
                  OR (r.generation_batch_id IS NULL AND b.source_generation_run_id = r.experiment_run_id)
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
        sql.append(" ORDER BY r.created_at DESC LIMIT :limit OFFSET :offset");
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
                       r.generation_batch_id,
                       r.language_profile,
                       jsonb_build_object(
                           'chunk_id', c.chunk_id,
                           'document_id', c.document_id,
                           'chunk_text', c.chunk_text,
                           'chunk_index_in_document', c.chunk_index_in_document,
                           'section_path_text', c.section_path_text
                       )::text AS source_chunk,
                       COALESCE(
                           (
                               SELECT jsonb_agg(
                                   jsonb_build_object(
                                       'source_doc_id', l.source_doc_id,
                                       'source_chunk_id', l.source_chunk_id,
                                       'source_role', l.source_role,
                                       'source_chunk_group_id', l.source_chunk_group_id,
                                       'metadata', l.metadata_json
                                   )
                                   ORDER BY l.created_at
                               )
                               FROM synthetic_query_source_link l
                               WHERE l.synthetic_query_id = r.synthetic_query_id
                           ),
                           '[]'::jsonb
                       )::text AS source_links,
                       r.source_summary,
                       r.glossary_terms::text AS glossary_terms,
                       r.prompt_template_version,
                       r.prompt_version,
                       r.prompt_hash,
                       r.llm_output::text AS raw_output,
                       r.metadata::text AS metadata
                FROM synthetic_queries_raw_all r
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
                        readUuid(rs, "generation_batch_id"),
                        rs.getString("language_profile"),
                        readJson(rs, "source_chunk"),
                        readJson(rs, "source_links"),
                        rs.getString("source_summary"),
                        readJson(rs, "glossary_terms"),
                        rs.getString("prompt_template_version"),
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
                    FROM synthetic_queries_raw_all r
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
                    LEFT JOIN synthetic_queries_raw_all r
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
                    FROM synthetic_queries_raw_all r
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
                    FROM synthetic_queries_raw_all r
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

    public AdminConsoleDtos.AdminDashboardStats findAdminDashboardStats() {
        long sourceCount = queryLong("SELECT COUNT(*) FROM corpus_sources");
        long activeDocumentCount = queryLong("SELECT COUNT(*) FROM corpus_documents WHERE is_active = TRUE");
        long chunkCount = queryLong(
                """
                SELECT COUNT(*)
                FROM corpus_chunks c
                JOIN corpus_documents d ON d.document_id = c.document_id
                WHERE d.is_active = TRUE
                """
        );
        long glossaryCount = queryLong("SELECT COUNT(*) FROM corpus_glossary_terms WHERE is_active = TRUE");
        long syntheticCount = queryLong("SELECT COUNT(*) FROM synthetic_queries_raw_all");
        long gatedAcceptedCount = queryLong("SELECT COUNT(*) FROM synthetic_queries_gated WHERE final_decision = TRUE");
        long memoryCount = queryLong("SELECT COUNT(*) FROM memory_entries");
        long ragRunCount = queryLong("SELECT COUNT(*) FROM rag_test_run");
        return new AdminConsoleDtos.AdminDashboardStats(
                sourceCount,
                activeDocumentCount,
                chunkCount,
                glossaryCount,
                syntheticCount,
                gatedAcceptedCount,
                memoryCount,
                ragRunCount
        );
    }

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
                    created_by
                ) VALUES (
                    :gatingBatchId,
                    :gatingPreset,
                    :generationMethodId,
                    :generationBatchId,
                    :sourceGenerationRunId,
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
                               WHEN r.generation_strategy = 'E' THEN 'en'
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
                       qb.rejection_summary::text AS rejection_summary,
                       qb.stage_config_json::text AS stage_config
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
                       CASE
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
                       END AS rejected_count,
                       lb.rejection_summary::text AS rejection_summary,
                       lb.stage_config_json::text AS stage_config
                FROM limited_batch lb
                LEFT JOIN synthetic_query_generation_method m
                  ON m.generation_method_id = lb.generation_method_id
                LEFT JOIN live_count lc
                  ON lc.gating_batch_id = lb.gating_batch_id
                ORDER BY lb.created_at DESC
                """;
        return jdbcTemplate.query(
                sql,
                new MapSqlParameterSource("limit", normalizeLimit(limit, 100)),
                (rs, rowNum) -> mapGatingBatchRow(rs)
        );
    }

    public AdminConsoleDtos.GatingFunnelResponse findGatingFunnel(UUID gatingBatchId, String methodCode) {
        AdminConsoleDtos.GatingBatchRow batch = findGatingBatch(gatingBatchId)
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
        return new AdminConsoleDtos.GatingFunnelResponse(
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

    public List<AdminConsoleDtos.GatingResultRow> findGatingResults(
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
                (rs, rowNum) -> new AdminConsoleDtos.GatingResultRow(
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
                        readJson(rs, "llm_scores"),
                        readJson(rs, "rejection_reasons")
                )
        );
    }

    public long countEvalSamples() {
        Long value = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM eval_samples", new MapSqlParameterSource(), Long.class);
        return value == null ? 0L : value;
    }

    public long countEvalSamplesForDefaultDataset() {
        String sql = """
                SELECT COUNT(*)
                FROM eval_samples s
                WHERE COALESCE(s.metadata ->> 'builder', '') = 'build-eval-dataset'
                   OR s.sample_id LIKE 'dev-human-%%'
                   OR s.sample_id LIKE 'test-human-%%'
                """;
        Long value = jdbcTemplate.queryForObject(sql, new MapSqlParameterSource(), Long.class);
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

    @Transactional
    public void refreshDefaultEvalDatasetItems(UUID datasetId) {
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
                WHERE COALESCE(s.metadata ->> 'builder', '') = 'build-eval-dataset'
                   OR s.sample_id LIKE 'dev-human-%%'
                   OR s.sample_id LIKE 'test-human-%%'
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
                       s.user_query_en,
                       COALESCE(NULLIF(s.query_language, ''), COALESCE(i.metadata ->> 'query_language', d.metadata ->> 'query_language', 'ko')) AS query_language,
                       s.dialog_context::text AS dialog_context,
                       s.metadata ->> 'target_method' AS target_method,
                       COALESCE(s.metadata -> 'evaluation_focus', '[]'::jsonb)::text AS evaluation_focus
                FROM eval_dataset_item i
                JOIN eval_samples s
                  ON s.sample_id = i.sample_id
                JOIN eval_dataset d
                  ON d.dataset_id = i.dataset_id
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
                        rs.getString("user_query_en"),
                        rs.getString("query_language"),
                        readJson(rs, "dialog_context"),
                        rs.getString("target_method"),
                        readJson(rs, "evaluation_focus")
                )
        );
    }

    public Optional<String> findEvalDatasetQueryLanguage(UUID datasetId) {
        String sql = """
                SELECT COALESCE(
                           NULLIF(d.metadata ->> 'query_language', ''),
                           NULLIF(
                               (
                                   SELECT CASE
                                              WHEN COUNT(*) FILTER (WHERE COALESCE(NULLIF(s.query_language, ''), 'ko') = 'en') > 0
                                                  THEN 'en'
                                              ELSE 'ko'
                                          END
                                   FROM eval_dataset_item i
                                   JOIN eval_samples s
                                     ON s.sample_id = i.sample_id
                                   WHERE i.dataset_id = d.dataset_id
                                     AND i.active = TRUE
                               ),
                               ''
                           ),
                           'ko'
                       ) AS query_language
                FROM eval_dataset d
                WHERE d.dataset_id = :datasetId
                """;
        List<String> rows = jdbcTemplate.query(
                sql,
                new MapSqlParameterSource("datasetId", datasetId),
                (rs, rowNum) -> rs.getString("query_language")
        );
        return rows.stream().findFirst();
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
                    created_by
                ) VALUES (
                    :runId,
                    :runLabel,
                    'planned',
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
                    :createdBy
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

    public Optional<JsonNode> findRagTestRunConfig(UUID runId) {
        String sql = """
                SELECT config_json::text AS config_json
                FROM rag_test_run_config
                WHERE rag_test_run_id = :runId
                """;
        List<String> rows = jdbcTemplate.query(
                sql,
                new MapSqlParameterSource("runId", runId),
                (rs, rowNum) -> rs.getString("config_json")
        );
        return rows.stream().findFirst().map(this::readJson);
    }

    public String findRagDatasetVersion(UUID runId) {
        String sql = """
                SELECT COALESCE(d.version, '')
                FROM rag_test_run r
                LEFT JOIN eval_dataset d
                  ON d.dataset_id = r.dataset_id
                WHERE r.rag_test_run_id = :runId
                """;
        List<String> rows = jdbcTemplate.query(
                sql,
                new MapSqlParameterSource("runId", runId),
                (rs, rowNum) -> rs.getString(1)
        );
        return rows.stream().findFirst().orElse("");
    }

    @Transactional
    public void upsertRagExperimentRecord(
            UUID runId,
            String snapshotId,
            JsonNode generationStrategy,
            JsonNode gatingConfig,
            Integer memorySize,
            JsonNode retrievalConfig,
            JsonNode rewriteConfig,
            String datasetVersion,
            Instant runTimestamp,
            JsonNode metrics
    ) {
        String sql = """
                INSERT INTO rag_eval_experiment_record (
                    record_id,
                    rag_test_run_id,
                    snapshot_id,
                    generation_strategy,
                    gating_config,
                    memory_size,
                    retrieval_config,
                    rewrite_config,
                    dataset_version,
                    run_timestamp,
                    metrics
                ) VALUES (
                    gen_random_uuid(),
                    :runId,
                    :snapshotId,
                    CAST(:generationStrategy AS jsonb),
                    CAST(:gatingConfig AS jsonb),
                    :memorySize,
                    CAST(:retrievalConfig AS jsonb),
                    CAST(:rewriteConfig AS jsonb),
                    :datasetVersion,
                    :runTimestamp,
                    CAST(:metrics AS jsonb)
                )
                ON CONFLICT (rag_test_run_id) DO UPDATE
                SET snapshot_id = EXCLUDED.snapshot_id,
                    generation_strategy = EXCLUDED.generation_strategy,
                    gating_config = EXCLUDED.gating_config,
                    memory_size = EXCLUDED.memory_size,
                    retrieval_config = EXCLUDED.retrieval_config,
                    rewrite_config = EXCLUDED.rewrite_config,
                    dataset_version = EXCLUDED.dataset_version,
                    run_timestamp = EXCLUDED.run_timestamp,
                    metrics = EXCLUDED.metrics,
                    updated_at = NOW()
                """;
        jdbcTemplate.update(
                sql,
                new MapSqlParameterSource()
                        .addValue("runId", runId)
                        .addValue("snapshotId", snapshotId)
                        .addValue("generationStrategy", generationStrategy.toString())
                        .addValue("gatingConfig", gatingConfig.toString())
                        .addValue("memorySize", memorySize == null ? 0 : memorySize)
                        .addValue("retrievalConfig", retrievalConfig.toString())
                        .addValue("rewriteConfig", rewriteConfig.toString())
                        .addValue("datasetVersion", datasetVersion == null ? "" : datasetVersion)
                        .addValue("runTimestamp", Timestamp.from(runTimestamp == null ? Instant.now() : runTimestamp))
                        .addValue("metrics", metrics.toString())
        );
    }

    @Transactional
    public void completeRagTestRun(UUID runId, JsonNode metricsJson, UUID sourceExperimentRunId) {
        String sql = """
                UPDATE rag_test_run
                SET status = 'completed',
                    started_at = COALESCE(started_at, NOW()),
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
                    started_at = COALESCE(started_at, NOW()),
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
    public void markRagTestRunRunning(UUID runId) {
        String sql = """
                UPDATE rag_test_run
                SET status = 'running',
                    started_at = COALESCE(started_at, NOW())
                WHERE rag_test_run_id = :runId
                  AND status IN ('planned', 'queued', 'failed')
                """;
        jdbcTemplate.update(sql, new MapSqlParameterSource("runId", runId));
    }

    @Transactional
    public void cancelRagTestRun(UUID runId, String reason) {
        String sql = """
                UPDATE rag_test_run
                SET status = 'cancelled',
                    finished_at = NOW(),
                    metrics_json = jsonb_build_object('cancel_reason', :reason)
                WHERE rag_test_run_id = :runId
                """;
        jdbcTemplate.update(
                sql,
                new MapSqlParameterSource()
                        .addValue("runId", runId)
                        .addValue("reason", reason == null ? "" : reason)
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
            Double rewriteRejectionRate,
            Double averageConfidenceDelta,
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
                    rewrite_rejection_rate,
                    average_confidence_delta,
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
                    :rewriteRejectionRate,
                    :averageConfidenceDelta,
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
                    rewrite_rejection_rate = EXCLUDED.rewrite_rejection_rate,
                    average_confidence_delta = EXCLUDED.average_confidence_delta,
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
                        .addValue("rewriteRejectionRate", rewriteRejectionRate)
                        .addValue("averageConfidenceDelta", averageConfidenceDelta)
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
                SELECT sample_id,
                       user_query_ko,
                       user_query_en,
                       COALESCE(NULLIF(query_language, ''), 'ko') AS query_language,
                       query_category
                FROM eval_samples
                WHERE sample_id IN (:sampleIds)
                """;
        return jdbcTemplate.query(
                sql,
                new MapSqlParameterSource("sampleIds", sampleIds),
                (rs, rowNum) -> new EvalSampleMeta(
                        rs.getString("sample_id"),
                        rs.getString("user_query_ko"),
                        rs.getString("user_query_en"),
                        rs.getString("query_language"),
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
                       COALESCE((rc.config_json ->> 'stage_cutoff_enabled')::boolean, FALSE) AS stage_cutoff_enabled,
                       NULLIF(rc.config_json ->> 'stage_cutoff_level', '') AS stage_cutoff_level,
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
                LEFT JOIN rag_test_run_config rc
                  ON rc.rag_test_run_id = r.rag_test_run_id
                WHERE r.rag_test_run_id = :runId
                """;
        List<AdminConsoleDtos.RagTestRunRow> rows = jdbcTemplate.query(
                sql,
                new MapSqlParameterSource("runId", runId),
                (rs, rowNum) -> mapRagTestRunRow(rs)
        );
        return rows.stream().findFirst();
    }

    @Transactional
    public int deleteRagTestRun(UUID runId) {
        MapSqlParameterSource params = new MapSqlParameterSource("runId", runId);
        Long existing = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM rag_test_run WHERE rag_test_run_id = :runId",
                params,
                Long.class
        );
        if (existing == null || existing <= 0L) {
            return 0;
        }

        List<UUID> experimentRunIds = findLinkedExperimentRunIds(runId);

        jdbcTemplate.update(
                "DELETE FROM online_query_rewrite_log WHERE run_id = :runId",
                params
        );
        jdbcTemplate.update(
                "DELETE FROM llm_job WHERE rag_test_run_id = :runId",
                params
        );
        int removed = jdbcTemplate.update(
                "DELETE FROM rag_test_run WHERE rag_test_run_id = :runId",
                params
        );
        if (removed > 0) {
            deleteExperimentArtifacts(experimentRunIds);
        }
        return removed;
    }

    private List<UUID> findLinkedExperimentRunIds(UUID runId) {
        MapSqlParameterSource params = new MapSqlParameterSource("runId", runId);
        LinkedHashSet<UUID> experimentRunIds = new LinkedHashSet<>();
        List<UUID> directIds = jdbcTemplate.query(
                """
                SELECT source_experiment_run_id
                FROM rag_test_run
                WHERE rag_test_run_id = :runId
                """,
                params,
                (rs, rowNum) -> readUuid(rs, "source_experiment_run_id")
        );
        for (UUID directId : directIds) {
            if (directId != null) {
                experimentRunIds.add(directId);
            }
        }

        List<String> payloads = new ArrayList<>(jdbcTemplate.query(
                """
                SELECT metrics_json::text AS payload
                FROM rag_test_run
                WHERE rag_test_run_id = :runId
                UNION ALL
                SELECT metrics_json::text AS payload
                FROM rag_test_result_summary
                WHERE rag_test_run_id = :runId
                UNION ALL
                SELECT metrics::text AS payload
                FROM rag_eval_experiment_record
                WHERE rag_test_run_id = :runId
                UNION ALL
                SELECT result_json::text AS payload
                FROM llm_job
                WHERE rag_test_run_id = :runId
                """,
                params,
                (rs, rowNum) -> rs.getString("payload")
        ));
        for (String payload : payloads) {
            collectExperimentRunIds(readJson(payload), experimentRunIds);
        }
        return new ArrayList<>(experimentRunIds);
    }

    private void deleteExperimentArtifacts(List<UUID> experimentRunIds) {
        if (experimentRunIds == null || experimentRunIds.isEmpty()) {
            return;
        }
        List<String> experimentRunIdTexts = experimentRunIds.stream()
                .map(UUID::toString)
                .toList();
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("experimentRunIds", experimentRunIds)
                .addValue("experimentRunIdTexts", experimentRunIdTexts);
        jdbcTemplate.update(
                """
                DELETE FROM retrieval_results
                WHERE metadata ->> 'experiment_run_id' IN (:experimentRunIdTexts)
                """,
                params
        );
        jdbcTemplate.update(
                """
                DELETE FROM rerank_results
                WHERE metadata ->> 'experiment_run_id' IN (:experimentRunIdTexts)
                """,
                params
        );
        jdbcTemplate.update(
                """
                DELETE FROM eval_judgments
                WHERE experiment_run_id IN (:experimentRunIds)
                """,
                params
        );
        jdbcTemplate.update(
                """
                DELETE FROM online_queries
                WHERE experiment_run_id IN (:experimentRunIds)
                """,
                params
        );
        jdbcTemplate.update(
                """
                DELETE FROM experiment_runs
                WHERE experiment_run_id IN (:experimentRunIds)
                """,
                params
        );
    }

    private void collectExperimentRunIds(JsonNode node, Set<UUID> output) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return;
        }
        if (node.isObject()) {
            UUID experimentRunId = parseUuid(node.path("experiment_run_id").asText(""));
            if (experimentRunId != null) {
                output.add(experimentRunId);
            }
            node.fields().forEachRemaining(entry -> collectExperimentRunIds(entry.getValue(), output));
            return;
        }
        if (node.isArray()) {
            node.forEach(child -> collectExperimentRunIds(child, output));
        }
    }

    private UUID parseUuid(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(raw.trim());
        } catch (IllegalArgumentException exception) {
            return null;
        }
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
                       COALESCE((rc.config_json ->> 'stage_cutoff_enabled')::boolean, FALSE) AS stage_cutoff_enabled,
                       NULLIF(rc.config_json ->> 'stage_cutoff_level', '') AS stage_cutoff_level,
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
                LEFT JOIN rag_test_run_config rc
                  ON rc.rag_test_run_id = r.rag_test_run_id
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
                    'rewrite_rejection_rate', rewrite_rejection_rate,
                    'average_confidence_delta', average_confidence_delta,
                    'answer_metrics', answer_metrics,
                    'metrics_json', metrics_json,
                    'experiment_record',
                    (
                        SELECT jsonb_build_object(
                            'snapshot_id', er.snapshot_id,
                            'generation_strategy', er.generation_strategy,
                            'gating_config', er.gating_config,
                            'memory_size', er.memory_size,
                            'retrieval_config', er.retrieval_config,
                            'rewrite_config', er.rewrite_config,
                            'dataset_version', er.dataset_version,
                            'run_timestamp', er.run_timestamp,
                            'metrics', er.metrics
                        )
                        FROM rag_eval_experiment_record er
                        WHERE er.rag_test_run_id = :runId
                    )
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
                       COALESCE((rc.config_json ->> 'stage_cutoff_enabled')::boolean, FALSE) AS stage_cutoff_enabled,
                       NULLIF(rc.config_json ->> 'stage_cutoff_level', '') AS stage_cutoff_level,
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
                LEFT JOIN rag_test_run_config rc
                  ON rc.rag_test_run_id = r.rag_test_run_id
                WHERE r.dataset_id = :datasetId
                ORDER BY r.created_at DESC
                """;
        return jdbcTemplate.query(
                sql,
                new MapSqlParameterSource("datasetId", datasetId),
                (rs, rowNum) -> mapRagTestRunRow(rs)
        );
    }

    public List<AdminConsoleDtos.RewriteDebugRow> findRewriteDebugRows(Integer limit, Integer offset) {
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
                (rs, rowNum) -> new AdminConsoleDtos.RewriteDebugRow(
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

    public Optional<AdminConsoleDtos.RewriteDebugDetail> findRewriteDebugDetail(UUID rewriteLogId) {
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
        List<AdminConsoleDtos.RewriteDebugRow> rows = jdbcTemplate.query(
                rowSql,
                new MapSqlParameterSource("rewriteLogId", rewriteLogId),
                (rs, rowNum) -> new AdminConsoleDtos.RewriteDebugRow(
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
        JsonNode memory = readJson(jdbcTemplate.queryForObject(memorySql, new MapSqlParameterSource("rewriteLogId", rewriteLogId), String.class));
        JsonNode candidates = readJson(jdbcTemplate.queryForObject(candidateSql, new MapSqlParameterSource("rewriteLogId", rewriteLogId), String.class));
        return Optional.of(new AdminConsoleDtos.RewriteDebugDetail(rows.getFirst(), memory, candidates));
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

    public JsonNode aggregateCategoryDistributionFromDefaultSamples() {
        String sql = """
                SELECT COALESCE(
                    jsonb_object_agg(x.query_category, x.cnt),
                    '{}'::jsonb
                )::text AS payload
                FROM (
                    SELECT s.query_category, COUNT(*) AS cnt
                    FROM eval_samples s
                    WHERE COALESCE(s.metadata ->> 'builder', '') = 'build-eval-dataset'
                       OR s.sample_id LIKE 'dev-human-%%'
                       OR s.sample_id LIKE 'test-human-%%'
                    GROUP BY s.query_category
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

    public JsonNode aggregateSingleMultiDistributionFromDefaultSamples() {
        String sql = """
                SELECT COALESCE(
                    jsonb_object_agg(x.single_or_multi_chunk, x.cnt),
                    '{}'::jsonb
                )::text AS payload
                FROM (
                    SELECT s.single_or_multi_chunk, COUNT(*) AS cnt
                    FROM eval_samples s
                    WHERE COALESCE(s.metadata ->> 'builder', '') = 'build-eval-dataset'
                       OR s.sample_id LIKE 'dev-human-%%'
                       OR s.sample_id LIKE 'test-human-%%'
                    GROUP BY s.single_or_multi_chunk
                ) x
                """;
        return readJson(jdbcTemplate.queryForObject(sql, new MapSqlParameterSource(), String.class));
    }

    private AdminConsoleDtos.GatingBatchRow mapGatingBatchRow(ResultSet rs) throws SQLException {
        JsonNode stageConfig = readJson(rs, "stage_config");
        String retrieverMode = stageConfig.path("retriever_config").path("retriever_mode").asText(null);
        return new AdminConsoleDtos.GatingBatchRow(
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
                readJson(rs, "rejection_summary"),
                stageConfig
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
                rs.getObject("stage_cutoff_enabled", Boolean.class),
                rs.getString("stage_cutoff_level"),
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

    private void updateStrategyRawBatchProvenance(String tableName, UUID batchId, UUID sourceGenerationRunId) {
        String sql = """
                UPDATE %s r
                SET generation_batch_id = :batchId,
                    generation_method_id = b.generation_method_id,
                    prompt_template_version = COALESCE(r.prompt_template_version, r.prompt_version),
                    language_profile = COALESCE(
                        r.language_profile,
                        CASE
                            WHEN r.generation_strategy = 'E' THEN 'en'
                            WHEN r.query_type = 'code_mixed' THEN 'code_mixed'
                            ELSE 'ko'
                        END
                    ),
                    normalized_query_text = COALESCE(r.normalized_query_text, lower(regexp_replace(trim(r.query_text), '\\s+', ' ', 'g')))
                FROM synthetic_query_generation_batch b
                WHERE b.batch_id = :batchId
                  AND r.experiment_run_id = :sourceGenerationRunId
                """.formatted(tableName);
        jdbcTemplate.update(
                sql,
                new MapSqlParameterSource()
                        .addValue("batchId", batchId)
                        .addValue("sourceGenerationRunId", sourceGenerationRunId)
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

    private long queryLong(String sql) {
        Long value = jdbcTemplate.queryForObject(sql, new MapSqlParameterSource(), Long.class);
        return value == null ? 0L : value;
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
