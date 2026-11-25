package io.queryforge.backend.admin.console.evaluation.anchor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.queryforge.backend.admin.console.persistence.ConsoleJdbc.readInstant;
import static io.queryforge.backend.admin.console.persistence.ConsoleJdbc.readJson;
import static io.queryforge.backend.admin.console.persistence.ConsoleJdbc.readUuid;

@Repository
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RagRewriteAnchorRepository {

    private final ObjectMapper objectMapper;

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public record EvalDatasetAnchorMeta(
            String sampleId,
            UUID datasetItemId,
            JsonNode expectedChunkIds,
            JsonNode expectedDocIds
    ) {
    }

    public record MemoryAnchorSource(
            UUID memoryId,
            String sourceGatedQueryId,
            String queryText,
            JsonNode glossaryTerms,
            JsonNode targetChunkIds,
            String targetDocId
    ) {
    }

    @Transactional
    public void replaceRagRewriteAnchorEvalRows(
            UUID runId,
            List<RagRewriteAnchorDtos.RagRewriteAnchorEvalRow> rows
    ) {
        jdbcTemplate.update(
                "DELETE FROM rag_rewrite_anchor_eval WHERE rag_test_run_id = :runId",
                new MapSqlParameterSource("runId", runId)
        );
        if (rows == null || rows.isEmpty()) {
            return;
        }
        String sql = """
                INSERT INTO rag_rewrite_anchor_eval (
                    id,
                    rag_test_run_id,
                    rag_test_result_detail_id,
                    sample_id,
                    dataset_item_id,
                    mode,
                    original_query,
                    final_rewrite_query,
                    rewrite_applied,
                    source_memory_index,
                    anchor_text,
                    normalized_anchor_text,
                    canonical_anchor_text,
                    anchor_source,
                    source_tags,
                    appears_in_raw_query,
                    appears_in_final_rewrite,
                    appears_in_expected_chunk,
                    appears_in_expected_doc,
                    appears_in_retrieved_chunk,
                    grounded_by_expected_chunk,
                    grounded_by_expected_doc,
                    grounded_by_retrieved_chunk,
                    grounded_by_memory,
                    grounded_by_glossary,
                    grounding_score,
                    intent_relevance_score,
                    drift_risk_score,
                    overall_anchor_score,
                    label,
                    evidence_summary,
                    expected_chunk_ids,
                    expected_doc_ids,
                    retrieved_chunk_ids,
                    source_memory_entry_id,
                    source_memory_query_id
                ) VALUES (
                    :id,
                    :runId,
                    :detailId,
                    :sampleId,
                    :datasetItemId,
                    :mode,
                    :originalQuery,
                    :finalRewriteQuery,
                    :rewriteApplied,
                    :sourceMemoryIndex,
                    :anchorText,
                    :normalizedAnchorText,
                    :canonicalAnchorText,
                    :anchorSource,
                    CAST(:sourceTags AS jsonb),
                    :appearsInRawQuery,
                    :appearsInFinalRewrite,
                    :appearsInExpectedChunk,
                    :appearsInExpectedDoc,
                    :appearsInRetrievedChunk,
                    :groundedByExpectedChunk,
                    :groundedByExpectedDoc,
                    :groundedByRetrievedChunk,
                    :groundedByMemory,
                    :groundedByGlossary,
                    :groundingScore,
                    :intentRelevanceScore,
                    :driftRiskScore,
                    :overallAnchorScore,
                    :label,
                    :evidenceSummary,
                    CAST(:expectedChunkIds AS jsonb),
                    CAST(:expectedDocIds AS jsonb),
                    CAST(:retrievedChunkIds AS jsonb),
                    :sourceMemoryEntryId,
                    :sourceMemoryQueryId
                )
                ON CONFLICT (rag_test_result_detail_id, normalized_anchor_text) DO UPDATE SET
                    anchor_text = EXCLUDED.anchor_text,
                    canonical_anchor_text = EXCLUDED.canonical_anchor_text,
                    anchor_source = EXCLUDED.anchor_source,
                    source_tags = EXCLUDED.source_tags,
                    appears_in_raw_query = EXCLUDED.appears_in_raw_query,
                    appears_in_final_rewrite = EXCLUDED.appears_in_final_rewrite,
                    appears_in_expected_chunk = EXCLUDED.appears_in_expected_chunk,
                    appears_in_expected_doc = EXCLUDED.appears_in_expected_doc,
                    appears_in_retrieved_chunk = EXCLUDED.appears_in_retrieved_chunk,
                    grounded_by_expected_chunk = EXCLUDED.grounded_by_expected_chunk,
                    grounded_by_expected_doc = EXCLUDED.grounded_by_expected_doc,
                    grounded_by_retrieved_chunk = EXCLUDED.grounded_by_retrieved_chunk,
                    grounded_by_memory = EXCLUDED.grounded_by_memory,
                    grounded_by_glossary = EXCLUDED.grounded_by_glossary,
                    grounding_score = EXCLUDED.grounding_score,
                    intent_relevance_score = EXCLUDED.intent_relevance_score,
                    drift_risk_score = EXCLUDED.drift_risk_score,
                    overall_anchor_score = EXCLUDED.overall_anchor_score,
                    label = EXCLUDED.label,
                    evidence_summary = EXCLUDED.evidence_summary,
                    expected_chunk_ids = EXCLUDED.expected_chunk_ids,
                    expected_doc_ids = EXCLUDED.expected_doc_ids,
                    retrieved_chunk_ids = EXCLUDED.retrieved_chunk_ids,
                    source_memory_entry_id = EXCLUDED.source_memory_entry_id,
                    source_memory_query_id = EXCLUDED.source_memory_query_id
                """;
        for (RagRewriteAnchorDtos.RagRewriteAnchorEvalRow row : rows) {
            jdbcTemplate.update(
                    sql,
                    new MapSqlParameterSource()
                            .addValue("id", row.id() == null ? UUID.randomUUID() : row.id())
                            .addValue("runId", row.ragTestRunId())
                            .addValue("detailId", row.ragTestResultDetailId())
                            .addValue("sampleId", row.sampleId())
                            .addValue("datasetItemId", row.datasetItemId())
                            .addValue("mode", row.mode())
                            .addValue("originalQuery", row.originalQuery())
                            .addValue("finalRewriteQuery", row.finalRewriteQuery())
                            .addValue("rewriteApplied", row.rewriteApplied())
                            .addValue("sourceMemoryIndex", row.sourceMemoryIndex())
                            .addValue("anchorText", row.anchorText())
                            .addValue("normalizedAnchorText", row.normalizedAnchorText())
                            .addValue("canonicalAnchorText", row.canonicalAnchorText())
                            .addValue("anchorSource", row.anchorSource())
                            .addValue("sourceTags", jsonText(row.sourceTags()))
                            .addValue("appearsInRawQuery", row.appearsInRawQuery())
                            .addValue("appearsInFinalRewrite", row.appearsInFinalRewrite())
                            .addValue("appearsInExpectedChunk", row.appearsInExpectedChunk())
                            .addValue("appearsInExpectedDoc", row.appearsInExpectedDoc())
                            .addValue("appearsInRetrievedChunk", row.appearsInRetrievedChunk())
                            .addValue("groundedByExpectedChunk", row.groundedByExpectedChunk())
                            .addValue("groundedByExpectedDoc", row.groundedByExpectedDoc())
                            .addValue("groundedByRetrievedChunk", row.groundedByRetrievedChunk())
                            .addValue("groundedByMemory", row.groundedByMemory())
                            .addValue("groundedByGlossary", row.groundedByGlossary())
                            .addValue("groundingScore", row.groundingScore())
                            .addValue("intentRelevanceScore", row.intentRelevanceScore())
                            .addValue("driftRiskScore", row.driftRiskScore())
                            .addValue("overallAnchorScore", row.overallAnchorScore())
                            .addValue("label", row.label())
                            .addValue("evidenceSummary", row.evidenceSummary())
                            .addValue("expectedChunkIds", jsonText(row.expectedChunkIds()))
                            .addValue("expectedDocIds", jsonText(row.expectedDocIds()))
                            .addValue("retrievedChunkIds", jsonText(row.retrievedChunkIds()))
                            .addValue("sourceMemoryEntryId", row.sourceMemoryEntryId())
                            .addValue("sourceMemoryQueryId", row.sourceMemoryQueryId())
            );
        }
    }

    public List<EvalDatasetAnchorMeta> findEvalDatasetAnchorMeta(UUID runId, List<String> sampleIds) {
        if (runId == null || sampleIds == null || sampleIds.isEmpty()) {
            return List.of();
        }
        String sql = """
                SELECT s.sample_id,
                       di.dataset_item_id,
                       s.expected_chunk_ids::text AS expected_chunk_ids,
                       s.expected_doc_ids::text AS expected_doc_ids
                FROM eval_samples s
                JOIN rag_test_run r
                  ON r.rag_test_run_id = :runId
                LEFT JOIN eval_dataset_item di
                  ON di.dataset_id = r.dataset_id
                 AND di.sample_id = s.sample_id
                 AND di.active IS TRUE
                WHERE s.sample_id IN (:sampleIds)
                """;
        return jdbcTemplate.query(
                sql,
                new MapSqlParameterSource()
                        .addValue("runId", runId)
                        .addValue("sampleIds", sampleIds),
                (rs, rowNum) -> new EvalDatasetAnchorMeta(
                        rs.getString("sample_id"),
                        readUuid(rs, "dataset_item_id"),
                        readJson(objectMapper, rs, "expected_chunk_ids"),
                        readJson(objectMapper, rs, "expected_doc_ids")
                )
        );
    }

    public Map<String, String> findChunkTexts(List<String> chunkIds) {
        if (chunkIds == null || chunkIds.isEmpty()) {
            return Map.of();
        }
        String sql = """
                SELECT chunk_id, text_value
                FROM (
                    SELECT chunk_id, chunk_text AS text_value
                    FROM corpus_chunks
                    WHERE chunk_id IN (:chunkIds)
                    UNION ALL
                    SELECT chunk_id, content AS text_value
                    FROM chunks
                    WHERE chunk_id IN (:chunkIds)
                ) source_rows
                """;
        Map<String, String> result = new LinkedHashMap<>();
        jdbcTemplate.query(
                sql,
                new MapSqlParameterSource("chunkIds", chunkIds),
                rs -> {
                    String chunkId = rs.getString("chunk_id");
                    if (chunkId != null && !result.containsKey(chunkId)) {
                        result.put(chunkId, rs.getString("text_value"));
                    }
                }
        );
        return result;
    }

    public Map<String, String> findDocumentTexts(List<String> docIds) {
        if (docIds == null || docIds.isEmpty()) {
            return Map.of();
        }
        String sql = """
                SELECT document_id, text_value
                FROM (
                    SELECT document_id,
                           CONCAT_WS(E'\n', title, section_path_text, cleaned_text, raw_text) AS text_value
                    FROM corpus_documents
                    WHERE document_id IN (:docIds)
                    UNION ALL
                    SELECT document_id,
                           CONCAT_WS(E'\n', title, cleaned_text, raw_text) AS text_value
                    FROM documents
                    WHERE document_id IN (:docIds)
                ) source_rows
                """;
        Map<String, String> result = new LinkedHashMap<>();
        jdbcTemplate.query(
                sql,
                new MapSqlParameterSource("docIds", docIds),
                rs -> {
                    String docId = rs.getString("document_id");
                    if (docId != null && !result.containsKey(docId)) {
                        result.put(docId, rs.getString("text_value"));
                    }
                }
        );
        return result;
    }

    public Map<UUID, MemoryAnchorSource> findMemoryAnchorSources(List<UUID> memoryIds) {
        if (memoryIds == null || memoryIds.isEmpty()) {
            return Map.of();
        }
        String sql = """
                SELECT memory_id,
                       source_gated_query_id,
                       query_text,
                       glossary_terms::text AS glossary_terms,
                       target_chunk_ids::text AS target_chunk_ids,
                       target_doc_id
                FROM memory_entries
                WHERE memory_id IN (:memoryIds)
                """;
        Map<UUID, MemoryAnchorSource> result = new LinkedHashMap<>();
        jdbcTemplate.query(
                sql,
                new MapSqlParameterSource("memoryIds", memoryIds),
                rs -> {
                    UUID memoryId = readUuid(rs, "memory_id");
                    result.put(
                            memoryId,
                            new MemoryAnchorSource(
                                    memoryId,
                                    rs.getString("source_gated_query_id"),
                                    rs.getString("query_text"),
                                    readJson(objectMapper, rs, "glossary_terms"),
                                    readJson(objectMapper, rs, "target_chunk_ids"),
                                    rs.getString("target_doc_id")
                            )
                    );
                }
        );
        return result;
    }

    public List<RagRewriteAnchorDtos.RagRewriteAnchorEvalRow> findRagRewriteAnchorEvalByDetailId(UUID detailId) {
        if (detailId == null) {
            return List.of();
        }
        return findRagRewriteAnchorEvalByDetailIds(List.of(detailId)).getOrDefault(detailId, List.of());
    }

    public Map<UUID, List<RagRewriteAnchorDtos.RagRewriteAnchorEvalRow>> findRagRewriteAnchorEvalByDetailIds(List<UUID> detailIds) {
        if (detailIds == null || detailIds.isEmpty()) {
            return Map.of();
        }
        String sql = """
                SELECT id,
                       rag_test_run_id,
                       rag_test_result_detail_id,
                       sample_id,
                       dataset_item_id,
                       mode,
                       original_query,
                       final_rewrite_query,
                       rewrite_applied,
                       source_memory_index,
                       anchor_text,
                       normalized_anchor_text,
                       canonical_anchor_text,
                       anchor_source,
                       source_tags::text AS source_tags,
                       appears_in_raw_query,
                       appears_in_final_rewrite,
                       appears_in_expected_chunk,
                       appears_in_expected_doc,
                       appears_in_retrieved_chunk,
                       grounded_by_expected_chunk,
                       grounded_by_expected_doc,
                       grounded_by_retrieved_chunk,
                       grounded_by_memory,
                       grounded_by_glossary,
                       grounding_score,
                       intent_relevance_score,
                       drift_risk_score,
                       overall_anchor_score,
                       label,
                       evidence_summary,
                       expected_chunk_ids::text AS expected_chunk_ids,
                       expected_doc_ids::text AS expected_doc_ids,
                       retrieved_chunk_ids::text AS retrieved_chunk_ids,
                       source_memory_entry_id,
                       source_memory_query_id,
                       created_at
                FROM rag_rewrite_anchor_eval
                WHERE rag_test_result_detail_id IN (:detailIds)
                ORDER BY rag_test_result_detail_id, overall_anchor_score DESC NULLS LAST, anchor_text
                """;
        Map<UUID, List<RagRewriteAnchorDtos.RagRewriteAnchorEvalRow>> result = new LinkedHashMap<>();
        jdbcTemplate.query(
                sql,
                new MapSqlParameterSource("detailIds", detailIds),
                rs -> {
                    RagRewriteAnchorDtos.RagRewriteAnchorEvalRow row = mapRagRewriteAnchorEvalRow(rs);
                    result.computeIfAbsent(row.ragTestResultDetailId(), ignored -> new ArrayList<>()).add(row);
                }
        );
        return result;
    }

    public JsonNode findRagAnchorSummary(UUID runId) {
        if (runId == null) {
            return emptyRagAnchorSummary();
        }
        return findRagAnchorSummaries(List.of(runId)).getOrDefault(runId, emptyRagAnchorSummary());
    }

    public Map<UUID, JsonNode> findRagAnchorSummaries(List<UUID> runIds) {
        if (runIds == null || runIds.isEmpty()) {
            return Map.of();
        }
        String sql = """
                WITH detail_scope AS (
                    SELECT rag_test_run_id,
                           COUNT(DISTINCT detail_id) FILTER (WHERE rewrite_applied IS TRUE) AS rewrite_applied_sample_count
                    FROM rag_test_result_detail
                    WHERE rag_test_run_id IN (:runIds)
                    GROUP BY rag_test_run_id
                ),
                anchor_scope AS (
                    SELECT rag_test_run_id,
                           COUNT(*) AS total_anchor_count,
                           COUNT(DISTINCT rag_test_result_detail_id) AS anchor_evaluated_sample_count,
                           COUNT(*) FILTER (WHERE label = 'useful') AS useful_anchor_count,
                           COUNT(*) FILTER (WHERE label = 'risky') AS risky_anchor_count,
                           COUNT(*) FILTER (WHERE label = 'unsupported') AS unsupported_anchor_count,
                           COUNT(*) FILTER (
                               WHERE grounded_by_expected_chunk IS TRUE
                                  OR grounded_by_expected_doc IS TRUE
                           ) AS grounded_anchor_count,
                           COUNT(*) FILTER (WHERE anchor_source = 'added_by_rewrite') AS added_anchor_count,
                           COUNT(*) FILTER (
                               WHERE anchor_source = 'added_by_rewrite'
                                 AND (
                                     grounded_by_expected_chunk IS TRUE
                                     OR grounded_by_expected_doc IS TRUE
                                 )
                           ) AS added_anchor_grounded_count,
                           COUNT(DISTINCT rag_test_result_detail_id) FILTER (
                               WHERE label = 'useful'
                                  OR grounded_by_expected_chunk IS TRUE
                                  OR grounded_by_expected_doc IS TRUE
                           ) AS supported_rewrite_sample_count,
                           AVG(overall_anchor_score) AS avg_anchor_relevance_score
                    FROM rag_rewrite_anchor_eval
                    WHERE rag_test_run_id IN (:runIds)
                    GROUP BY rag_test_run_id
                )
                SELECT r.rag_test_run_id,
                       COALESCE(a.total_anchor_count, 0) AS total_anchor_count,
                       COALESCE(d.rewrite_applied_sample_count, 0) AS rewrite_applied_sample_count,
                       COALESCE(a.anchor_evaluated_sample_count, 0) AS anchor_evaluated_sample_count,
                       COALESCE(a.useful_anchor_count, 0) AS useful_anchor_count,
                       COALESCE(a.risky_anchor_count, 0) AS risky_anchor_count,
                       COALESCE(a.unsupported_anchor_count, 0) AS unsupported_anchor_count,
                       CASE
                           WHEN COALESCE(a.total_anchor_count, 0) = 0 THEN NULL
                           ELSE a.useful_anchor_count::double precision / a.total_anchor_count::double precision
                       END AS anchor_precision,
                       CASE
                           WHEN COALESCE(a.total_anchor_count, 0) = 0 THEN NULL
                           ELSE a.grounded_anchor_count::double precision / a.total_anchor_count::double precision
                       END AS grounded_anchor_rate,
                       CASE
                           WHEN COALESCE(a.added_anchor_count, 0) = 0 THEN NULL
                           ELSE a.added_anchor_grounded_count::double precision / a.added_anchor_count::double precision
                       END AS added_anchor_grounded_rate,
                       CASE
                           WHEN COALESCE(a.total_anchor_count, 0) = 0 THEN NULL
                           ELSE a.risky_anchor_count::double precision / a.total_anchor_count::double precision
                       END AS risky_anchor_rate,
                       a.avg_anchor_relevance_score,
                       CASE
                           WHEN COALESCE(d.rewrite_applied_sample_count, 0) = 0 THEN NULL
                           ELSE COALESCE(a.supported_rewrite_sample_count, 0)::double precision
                                / d.rewrite_applied_sample_count::double precision
                       END AS anchor_supported_rewrite_rate
                FROM rag_test_run r
                LEFT JOIN detail_scope d
                  ON d.rag_test_run_id = r.rag_test_run_id
                LEFT JOIN anchor_scope a
                  ON a.rag_test_run_id = r.rag_test_run_id
                WHERE r.rag_test_run_id IN (:runIds)
                """;
        Map<UUID, JsonNode> result = new LinkedHashMap<>();
        jdbcTemplate.query(
                sql,
                new MapSqlParameterSource("runIds", runIds),
                rs -> {
                    UUID runId = readUuid(rs, "rag_test_run_id");
                    result.put(runId, mapRagAnchorSummary(rs));
                }
        );
        for (UUID runId : runIds) {
            result.putIfAbsent(runId, emptyRagAnchorSummary());
        }
        return result;
    }

    private RagRewriteAnchorDtos.RagRewriteAnchorEvalRow mapRagRewriteAnchorEvalRow(ResultSet rs) throws SQLException {
        return new RagRewriteAnchorDtos.RagRewriteAnchorEvalRow(
                readUuid(rs, "id"),
                readUuid(rs, "rag_test_run_id"),
                readUuid(rs, "rag_test_result_detail_id"),
                rs.getString("sample_id"),
                readUuid(rs, "dataset_item_id"),
                rs.getString("mode"),
                rs.getString("original_query"),
                rs.getString("final_rewrite_query"),
                rs.getObject("rewrite_applied", Boolean.class),
                rs.getObject("source_memory_index", Integer.class),
                rs.getString("anchor_text"),
                rs.getString("normalized_anchor_text"),
                rs.getString("canonical_anchor_text"),
                rs.getString("anchor_source"),
                readJson(objectMapper, rs, "source_tags"),
                rs.getObject("appears_in_raw_query", Boolean.class),
                rs.getObject("appears_in_final_rewrite", Boolean.class),
                rs.getObject("appears_in_expected_chunk", Boolean.class),
                rs.getObject("appears_in_expected_doc", Boolean.class),
                rs.getObject("appears_in_retrieved_chunk", Boolean.class),
                rs.getObject("grounded_by_expected_chunk", Boolean.class),
                rs.getObject("grounded_by_expected_doc", Boolean.class),
                rs.getObject("grounded_by_retrieved_chunk", Boolean.class),
                rs.getObject("grounded_by_memory", Boolean.class),
                rs.getObject("grounded_by_glossary", Boolean.class),
                rs.getObject("grounding_score", Double.class),
                rs.getObject("intent_relevance_score", Double.class),
                rs.getObject("drift_risk_score", Double.class),
                rs.getObject("overall_anchor_score", Double.class),
                rs.getString("label"),
                rs.getString("evidence_summary"),
                readJson(objectMapper, rs, "expected_chunk_ids"),
                readJson(objectMapper, rs, "expected_doc_ids"),
                readJson(objectMapper, rs, "retrieved_chunk_ids"),
                readUuid(rs, "source_memory_entry_id"),
                rs.getString("source_memory_query_id"),
                readInstant(rs, "created_at")
        );
    }

    private JsonNode mapRagAnchorSummary(ResultSet rs) throws SQLException {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("total_anchor_count", rs.getLong("total_anchor_count"));
        node.put("rewrite_applied_sample_count", rs.getLong("rewrite_applied_sample_count"));
        node.put("anchor_evaluated_sample_count", rs.getLong("anchor_evaluated_sample_count"));
        node.put("useful_anchor_count", rs.getLong("useful_anchor_count"));
        node.put("risky_anchor_count", rs.getLong("risky_anchor_count"));
        node.put("unsupported_anchor_count", rs.getLong("unsupported_anchor_count"));
        putNullableDouble(node, "anchor_precision", rs.getObject("anchor_precision", Double.class));
        putNullableDouble(node, "grounded_anchor_rate", rs.getObject("grounded_anchor_rate", Double.class));
        putNullableDouble(node, "added_anchor_grounded_rate", rs.getObject("added_anchor_grounded_rate", Double.class));
        putNullableDouble(node, "risky_anchor_rate", rs.getObject("risky_anchor_rate", Double.class));
        putNullableDouble(node, "avg_anchor_relevance_score", rs.getObject("avg_anchor_relevance_score", Double.class));
        putNullableDouble(node, "anchor_supported_rewrite_rate", rs.getObject("anchor_supported_rewrite_rate", Double.class));
        return node;
    }

    private JsonNode emptyRagAnchorSummary() {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("total_anchor_count", 0);
        node.put("rewrite_applied_sample_count", 0);
        node.put("anchor_evaluated_sample_count", 0);
        node.put("useful_anchor_count", 0);
        node.put("risky_anchor_count", 0);
        node.put("unsupported_anchor_count", 0);
        node.putNull("anchor_precision");
        node.putNull("grounded_anchor_rate");
        node.putNull("added_anchor_grounded_rate");
        node.putNull("risky_anchor_rate");
        node.putNull("avg_anchor_relevance_score");
        node.putNull("anchor_supported_rewrite_rate");
        return node;
    }

    private void putNullableDouble(ObjectNode node, String field, Double value) {
        if (value == null || value.isNaN() || value.isInfinite()) {
            node.putNull(field);
            return;
        }
        node.put(field, value);
    }

    private String jsonText(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return "null";
        }
        return node.toString();
    }
}
