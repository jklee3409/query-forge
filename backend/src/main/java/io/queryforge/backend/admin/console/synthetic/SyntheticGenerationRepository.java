package io.queryforge.backend.admin.console.synthetic;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

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
public class SyntheticGenerationRepository {

    private final ObjectMapper objectMapper;

    private final NamedParameterJdbcTemplate jdbcTemplate;

    @Transactional
    public UUID createGenerationBatch(
            UUID generationMethodId,
            UUID domainId,
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
                    domain_id,
                    version_name,
                    source_document_version,
                    status,
                    created_by,
                    config_json
                ) VALUES (
                    :batchId,
                    :generationMethodId,
                    :domainId,
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
                        .addValue("domainId", domainId)
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
        for (String tableName : AdminConsoleStrategyTables.RAW_TABLES) {
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
        for (String tableName : AdminConsoleStrategyTables.RAW_TABLES) {
            String sql = "DELETE FROM " + tableName + " WHERE generation_batch_id = :batchId";
            totalDeleted += jdbcTemplate.update(sql, params);
        }
        return totalDeleted;
    }

    @Transactional
    public int deleteLlmJobsByGenerationBatch(UUID batchId) {
        if (batchId == null) {
            return 0;
        }
        return jdbcTemplate.update(
                "DELETE FROM llm_job WHERE generation_batch_id = :batchId",
                new MapSqlParameterSource("batchId", batchId)
        );
    }

    @Transactional
    public int deleteGenerationBatch(UUID batchId) {
        if (batchId == null) {
            return 0;
        }
        MapSqlParameterSource params = new MapSqlParameterSource("batchId", batchId);
        jdbcTemplate.update(
                "UPDATE quality_gating_batch SET generation_batch_id = NULL WHERE generation_batch_id = :batchId",
                params
        );
        return jdbcTemplate.update(
                "DELETE FROM synthetic_query_generation_batch WHERE batch_id = :batchId",
                params
        );
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
                    total_generated_count = 0,
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

    public List<SyntheticGenerationDtos.SyntheticGenerationBatchRow> findGenerationBatches(Integer limit) {
        return findGenerationBatches(limit, null);
    }

    public List<SyntheticGenerationDtos.SyntheticGenerationBatchRow> findGenerationBatches(Integer limit, UUID domainId) {
        String domainWhereClause = domainId == null ? "" : "WHERE b.domain_id = :domainId";
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
                       stats.generated_count AS total_generated_count,
                       stats.target_query_count,
                       rate.estimated_seconds_per_query,
                       CASE
                           WHEN stats.target_query_count IS NULL OR stats.target_query_count <= 0 THEN NULL
                           WHEN stats.generated_count >= stats.target_query_count THEN 0
                           WHEN rate.estimated_seconds_per_query IS NULL THEN NULL
                           ELSE GREATEST(
                                   0,
                                   ROUND(
                                           (stats.target_query_count - stats.generated_count)
                                           * rate.estimated_seconds_per_query
                                   )
                                )::bigint
                       END AS estimated_remaining_seconds,
                       job.job_status AS llm_job_status,
                       job.item_status AS llm_job_item_status,
                       job.retry_count AS llm_retry_count,
                       job.max_retries AS llm_max_retries,
                       b.created_by,
                       b.metrics_json::text AS metrics_json
                FROM synthetic_query_generation_batch b
                JOIN synthetic_query_generation_method m
                  ON m.generation_method_id = b.generation_method_id
                LEFT JOIN LATERAL (
                    SELECT COUNT(*)::int AS live_generated_count
                    FROM synthetic_queries_raw_all r
                    WHERE r.generation_batch_id = b.batch_id
                ) live ON TRUE
                LEFT JOIN LATERAL (
                    SELECT GREATEST(COALESCE(b.total_generated_count, 0), COALESCE(live.live_generated_count, 0)) AS generated_count,
                           CASE
                               WHEN COALESCE(b.config_json ->> 'max_total_queries', '') ~ '^[0-9]+$'
                                   THEN (b.config_json ->> 'max_total_queries')::int
                               ELSE NULL
                           END AS target_query_count
                ) stats ON TRUE
                LEFT JOIN LATERAL (
                    SELECT AVG(sample.seconds_per_query) AS historical_seconds_per_query
                    FROM (
                        SELECT EXTRACT(EPOCH FROM (cb.finished_at - cb.started_at))
                               / cb.total_generated_count::double precision AS seconds_per_query
                        FROM synthetic_query_generation_batch cb
                        WHERE cb.generation_method_id = b.generation_method_id
                          AND cb.status = 'completed'
                          AND cb.total_generated_count > 0
                          AND cb.started_at IS NOT NULL
                          AND cb.finished_at IS NOT NULL
                        ORDER BY cb.finished_at DESC
                        LIMIT 20
                    ) sample
                ) hist ON TRUE
                LEFT JOIN LATERAL (
                    SELECT CASE
                               WHEN stats.generated_count > 0
                                    AND b.started_at IS NOT NULL
                                    AND LOWER(COALESCE(b.status, '')) IN ('planned', 'queued', 'running')
                                   THEN GREATEST(EXTRACT(EPOCH FROM (NOW() - b.started_at)), 1.0)
                                        / stats.generated_count::double precision
                               ELSE hist.historical_seconds_per_query
                           END AS estimated_seconds_per_query
                ) rate ON TRUE
                LEFT JOIN LATERAL (
                    SELECT lj.job_status,
                           lj.retry_count,
                           lj.max_retries,
                           li.item_status
                    FROM llm_job lj
                    LEFT JOIN LATERAL (
                        SELECT item_status
                        FROM llm_job_item lji
                        WHERE lji.job_id = lj.job_id
                        ORDER BY lji.item_order DESC
                        LIMIT 1
                    ) li ON TRUE
                    WHERE lj.generation_batch_id = b.batch_id
                    ORDER BY lj.created_at DESC
                    LIMIT 1
                ) job ON TRUE
                %s
                ORDER BY b.created_at DESC
                LIMIT :limit
                """.formatted(domainWhereClause);
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("limit", normalizeLimit(limit, 100));
        if (domainId != null) {
            params.addValue("domainId", domainId);
        }
        return jdbcTemplate.query(
                sql,
                params,
                (rs, rowNum) -> new SyntheticGenerationDtos.SyntheticGenerationBatchRow(
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
                        rs.getObject("target_query_count", Integer.class),
                        rs.getObject("estimated_seconds_per_query", Double.class),
                        rs.getObject("estimated_remaining_seconds", Long.class),
                        rs.getString("llm_job_status"),
                        rs.getString("llm_job_item_status"),
                        rs.getObject("llm_retry_count", Integer.class),
                        rs.getObject("llm_max_retries", Integer.class),
                        rs.getString("created_by"),
                        readJson(objectMapper, rs, "metrics_json")
                )
        );
    }

    public Optional<SyntheticGenerationDtos.SyntheticGenerationBatchRow> findGenerationBatch(UUID batchId) {
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
                       stats.generated_count AS total_generated_count,
                       stats.target_query_count,
                       rate.estimated_seconds_per_query,
                       CASE
                           WHEN stats.target_query_count IS NULL OR stats.target_query_count <= 0 THEN NULL
                           WHEN stats.generated_count >= stats.target_query_count THEN 0
                           WHEN rate.estimated_seconds_per_query IS NULL THEN NULL
                           ELSE GREATEST(
                                   0,
                                   ROUND(
                                           (stats.target_query_count - stats.generated_count)
                                           * rate.estimated_seconds_per_query
                                   )
                                )::bigint
                       END AS estimated_remaining_seconds,
                       job.job_status AS llm_job_status,
                       job.item_status AS llm_job_item_status,
                       job.retry_count AS llm_retry_count,
                       job.max_retries AS llm_max_retries,
                       b.created_by,
                       b.metrics_json::text AS metrics_json
                FROM synthetic_query_generation_batch b
                JOIN synthetic_query_generation_method m
                  ON m.generation_method_id = b.generation_method_id
                LEFT JOIN LATERAL (
                    SELECT COUNT(*)::int AS live_generated_count
                    FROM synthetic_queries_raw_all r
                    WHERE r.generation_batch_id = b.batch_id
                ) live ON TRUE
                LEFT JOIN LATERAL (
                    SELECT GREATEST(COALESCE(b.total_generated_count, 0), COALESCE(live.live_generated_count, 0)) AS generated_count,
                           CASE
                               WHEN COALESCE(b.config_json ->> 'max_total_queries', '') ~ '^[0-9]+$'
                                   THEN (b.config_json ->> 'max_total_queries')::int
                               ELSE NULL
                           END AS target_query_count
                ) stats ON TRUE
                LEFT JOIN LATERAL (
                    SELECT AVG(sample.seconds_per_query) AS historical_seconds_per_query
                    FROM (
                        SELECT EXTRACT(EPOCH FROM (cb.finished_at - cb.started_at))
                               / cb.total_generated_count::double precision AS seconds_per_query
                        FROM synthetic_query_generation_batch cb
                        WHERE cb.generation_method_id = b.generation_method_id
                          AND cb.status = 'completed'
                          AND cb.total_generated_count > 0
                          AND cb.started_at IS NOT NULL
                          AND cb.finished_at IS NOT NULL
                        ORDER BY cb.finished_at DESC
                        LIMIT 20
                    ) sample
                ) hist ON TRUE
                LEFT JOIN LATERAL (
                    SELECT CASE
                               WHEN stats.generated_count > 0
                                    AND b.started_at IS NOT NULL
                                    AND LOWER(COALESCE(b.status, '')) IN ('planned', 'queued', 'running')
                                   THEN GREATEST(EXTRACT(EPOCH FROM (NOW() - b.started_at)), 1.0)
                                        / stats.generated_count::double precision
                               ELSE hist.historical_seconds_per_query
                           END AS estimated_seconds_per_query
                ) rate ON TRUE
                LEFT JOIN LATERAL (
                    SELECT lj.job_status,
                           lj.retry_count,
                           lj.max_retries,
                           li.item_status
                    FROM llm_job lj
                    LEFT JOIN LATERAL (
                        SELECT item_status
                        FROM llm_job_item lji
                        WHERE lji.job_id = lj.job_id
                        ORDER BY lji.item_order DESC
                        LIMIT 1
                    ) li ON TRUE
                    WHERE lj.generation_batch_id = b.batch_id
                    ORDER BY lj.created_at DESC
                    LIMIT 1
                ) job ON TRUE
                WHERE b.batch_id = :batchId
                """;
        List<SyntheticGenerationDtos.SyntheticGenerationBatchRow> rows = jdbcTemplate.query(
                sql,
                new MapSqlParameterSource("batchId", batchId),
                (rs, rowNum) -> new SyntheticGenerationDtos.SyntheticGenerationBatchRow(
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
                        rs.getObject("target_query_count", Integer.class),
                        rs.getObject("estimated_seconds_per_query", Double.class),
                        rs.getObject("estimated_remaining_seconds", Long.class),
                        rs.getString("llm_job_status"),
                        rs.getString("llm_job_item_status"),
                        rs.getObject("llm_retry_count", Integer.class),
                        rs.getObject("llm_max_retries", Integer.class),
                        rs.getString("created_by"),
                        readJson(objectMapper, rs, "metrics_json")
                )
        );
        return rows.stream().findFirst();
    }

    public List<SyntheticGenerationDtos.SyntheticQueryRow> findSyntheticQueries(
            String methodCode,
            UUID batchId,
            String queryType,
            Boolean gated,
            Integer limit,
            Integer offset
    ) {
        return findSyntheticQueries(methodCode, batchId, queryType, gated, limit, offset, null);
    }

    public List<SyntheticGenerationDtos.SyntheticQueryRow> findSyntheticQueries(
            String methodCode,
            UUID batchId,
            String queryType,
            Boolean gated,
            Integer limit,
            Integer offset,
            UUID domainId
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
        if (domainId != null) {
            sql.append(" AND COALESCE(r.domain_id, b.domain_id) = :domainId");
            params.addValue("domainId", domainId);
        }
        sql.append(" ORDER BY r.created_at DESC LIMIT :limit OFFSET :offset");
        params.addValue("limit", normalizeLimit(limit, 200));
        params.addValue("offset", normalizeOffset(offset));
        return jdbcTemplate.query(
                sql.toString(),
                params,
                (rs, rowNum) -> new SyntheticGenerationDtos.SyntheticQueryRow(
                        rs.getString("synthetic_query_id"),
                        rs.getString("query_text"),
                        rs.getString("query_type"),
                        rs.getString("generation_strategy"),
                        readUuid(rs, "batch_id"),
                        rs.getString("version_name"),
                        rs.getString("chunk_id_source"),
                        readJson(objectMapper, rs, "target_chunk_ids"),
                        readInstant(rs, "created_at"),
                        rs.getBoolean("gated")
                )
        );
    }

    public Optional<SyntheticGenerationDtos.SyntheticQueryDetailResponse> findSyntheticQueryDetail(String queryId) {
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
                       COALESCE(
                           (
                               SELECT jsonb_agg(
                                   jsonb_build_object(
                                       'term_id', t.term_id,
                                       'canonical_form', t.canonical_form,
                                       'term_type', t.term_type,
                                       'chunk_id', l.source_chunk_id
                                   )
                                   ORDER BY t.canonical_form, l.source_chunk_id
                               )
                               FROM synthetic_query_anchor_link l
                               JOIN corpus_glossary_terms t
                                 ON t.term_id = l.term_id
                                AND t.is_active = TRUE
                               WHERE l.synthetic_query_id = r.synthetic_query_id
                                 AND l.is_active = TRUE
                           ),
                           '[]'::jsonb
                       )::text AS mapped_anchors,
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
        List<SyntheticGenerationDtos.SyntheticQueryDetailResponse> rows = jdbcTemplate.query(
                sql,
                new MapSqlParameterSource("queryId", queryId),
                (rs, rowNum) -> new SyntheticGenerationDtos.SyntheticQueryDetailResponse(
                        rs.getString("synthetic_query_id"),
                        rs.getString("query_text"),
                        rs.getString("query_type"),
                        rs.getString("generation_strategy"),
                        readUuid(rs, "generation_batch_id"),
                        rs.getString("language_profile"),
                        readJson(objectMapper, rs, "source_chunk"),
                        readJson(objectMapper, rs, "source_links"),
                        rs.getString("source_summary"),
                        readJson(objectMapper, rs, "glossary_terms"),
                        readJson(objectMapper, rs, "mapped_anchors"),
                        rs.getString("prompt_template_version"),
                        rs.getString("prompt_version"),
                        rs.getString("prompt_hash"),
                        readJson(objectMapper, rs, "raw_output"),
                        readJson(objectMapper, rs, "metadata")
                )
        );
        return rows.stream().findFirst();
    }

    public SyntheticGenerationDtos.SyntheticStatsResponse findSyntheticStats(String methodCode, UUID batchId) {
        return findSyntheticStats(methodCode, batchId, null);
    }

    public SyntheticGenerationDtos.SyntheticStatsResponse findSyntheticStats(String methodCode, UUID batchId, UUID domainId) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        String syntheticWhere = buildSyntheticFilterClause(methodCode, batchId, domainId, params);
        String batchWhere = buildBatchFilterClause(methodCode, batchId, domainId, params);
        String methodMetadataWhere = buildMethodMetadataFilterClause(methodCode, domainId, params);

        String byMethodSql = """
                SELECT COALESCE(
                    jsonb_agg(
                        jsonb_build_object('method_code', x.method_code, 'count', x.cnt)
                        ORDER BY x.method_code
                    ),
                    '[]'::jsonb
                )::text AS payload
                FROM (
                    SELECT m.method_code,
                           COALESCE(cnts.cnt, 0) AS cnt
                    FROM synthetic_query_generation_method m
                    LEFT JOIN (
                        SELECT r.generation_strategy,
                               COUNT(*) AS cnt
                        FROM synthetic_queries_raw_all r
                        LEFT JOIN synthetic_query_generation_batch b
                          ON b.source_generation_run_id = r.experiment_run_id
                        WHERE 1=1
                    """ + syntheticWhere + """
                        GROUP BY r.generation_strategy
                    ) cnts
                      ON cnts.generation_strategy = m.method_code
                    WHERE 1=1
                """ + methodMetadataWhere + """
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
        JsonNode byMethod = readJson(objectMapper, jdbcTemplate.queryForObject(byMethodSql, params, String.class));
        JsonNode byBatch = readJson(objectMapper, jdbcTemplate.queryForObject(byBatchSql, params, String.class));
        JsonNode byType = readJson(objectMapper, jdbcTemplate.queryForObject(byTypeSql, params, String.class));
        JsonNode byVersion = readJson(objectMapper, jdbcTemplate.queryForObject(byVersionSql, params, String.class));
        return new SyntheticGenerationDtos.SyntheticStatsResponse(byMethod, byBatch, byType, byVersion);
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
                            WHEN r.generation_strategy IN ('E', 'F') THEN 'en'
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

    private String buildSyntheticFilterClause(String methodCode, UUID batchId, UUID domainId, MapSqlParameterSource params) {
        StringBuilder where = new StringBuilder();
        if (methodCode != null && !methodCode.isBlank()) {
            where.append(" AND r.generation_strategy = :methodCode");
            params.addValue("methodCode", methodCode.trim().toUpperCase());
        }
        if (batchId != null) {
            where.append(" AND b.batch_id = :batchId");
            params.addValue("batchId", batchId);
        }
        if (domainId != null) {
            where.append(" AND COALESCE(r.domain_id, b.domain_id) = :domainId");
            params.addValue("domainId", domainId);
        }
        return where.toString();
    }

    private String buildBatchFilterClause(String methodCode, UUID batchId, UUID domainId, MapSqlParameterSource params) {
        StringBuilder where = new StringBuilder();
        if (methodCode != null && !methodCode.isBlank()) {
            where.append(" AND m.method_code = :methodCode");
            params.addValue("methodCode", methodCode.trim().toUpperCase());
        }
        if (batchId != null) {
            where.append(" AND b.batch_id = :batchId");
            params.addValue("batchId", batchId);
        }
        if (domainId != null) {
            where.append(" AND b.domain_id = :domainId");
            params.addValue("domainId", domainId);
        }
        return where.toString();
    }

    private String buildMethodMetadataFilterClause(String methodCode, UUID domainId, MapSqlParameterSource params) {
        StringBuilder where = new StringBuilder();
        if (methodCode != null && !methodCode.isBlank()) {
            where.append(" AND m.method_code = :methodCode");
            params.addValue("methodCode", methodCode.trim().toUpperCase());
        }
        if (domainId != null) {
            where.append("""
                     AND EXISTS (
                        SELECT 1
                        FROM tech_doc_domain_method_policy p
                        WHERE p.domain_id = :domainId
                          AND p.method_code = m.method_code
                          AND p.enabled IS TRUE
                     )
                    """);
            params.addValue("domainId", domainId);
        }
        return where.toString();
    }
}
