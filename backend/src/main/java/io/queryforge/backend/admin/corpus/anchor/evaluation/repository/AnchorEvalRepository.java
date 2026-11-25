package io.queryforge.backend.admin.corpus.anchor.evaluation.repository;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.queryforge.backend.admin.corpus.anchor.evaluation.dto.AnchorEvalDtos;
import io.queryforge.backend.admin.corpus.document.dto.CorpusDocumentDtos;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.queryforge.backend.admin.corpus.persistence.support.CorpusJdbc.normalizeLimit;
import static io.queryforge.backend.admin.corpus.persistence.support.CorpusJdbc.normalizeOffset;
import static io.queryforge.backend.admin.corpus.persistence.support.CorpusJdbc.readInstant;
import static io.queryforge.backend.admin.corpus.persistence.support.CorpusJdbc.readJson;
import static io.queryforge.backend.admin.corpus.persistence.support.CorpusJdbc.readUuid;

@Repository
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AnchorEvalRepository {

    private final NamedParameterJdbcTemplate jdbcTemplate;

    private final ObjectMapper objectMapper;

    @Transactional
    public void createAnchorEvalRun(
            UUID runId,
            String runName,
            String productName,
            String sourceId,
            int sampleSize,
            int candidateLimit,
            String createdBy
    ) {
        String sql = """
                INSERT INTO anchor_eval_run (
                    run_id, run_name, status, product_name, source_id, sample_size, candidate_limit, created_by, summary_json
                ) VALUES (
                    :runId, :runName, 'running', :productName, :sourceId, :sampleSize, :candidateLimit, :createdBy, '{}'::jsonb
                )
                """;
        jdbcTemplate.update(sql, new MapSqlParameterSource()
                .addValue("runId", runId)
                .addValue("runName", runName)
                .addValue("productName", productName)
                .addValue("sourceId", sourceId)
                .addValue("sampleSize", sampleSize)
                .addValue("candidateLimit", candidateLimit)
                .addValue("createdBy", createdBy));
    }

    public List<CorpusDocumentDtos.ChunkDetail> findAnchorEvalTargetChunks(
            String productName,
            String sourceId,
            List<String> documentIds,
            List<String> chunkIds,
            int sampleSize
    ) {
        StringBuilder sql = new StringBuilder("""
                SELECT c.chunk_id,
                       c.document_id,
                       c.section_id,
                       c.chunk_index_in_document,
                       c.chunk_index_in_section,
                       c.section_path_text,
                       c.chunk_text,
                       c.char_len,
                       c.token_len,
                       c.overlap_from_prev_chars,
                       c.previous_chunk_id,
                       c.next_chunk_id,
                       c.code_presence,
                       c.table_presence,
                       c.list_presence,
                       c.product_name,
                       c.version_label,
                       c.content_checksum,
                       c.import_run_id,
                       c.metadata_json::text AS metadata_json,
                       c.created_at,
                       c.updated_at
                FROM corpus_chunks c
                JOIN corpus_documents d ON d.document_id = c.document_id
                WHERE d.is_active = TRUE
                """);
        MapSqlParameterSource params = new MapSqlParameterSource();
        if (productName != null && !productName.isBlank()) {
            sql.append(" AND c.product_name = :productName");
            params.addValue("productName", productName);
        }
        if (sourceId != null && !sourceId.isBlank()) {
            sql.append(" AND d.source_id = :sourceId");
            params.addValue("sourceId", sourceId);
        }
        if (documentIds != null && !documentIds.isEmpty()) {
            sql.append(" AND c.document_id = ANY(:documentIds)");
            params.addValue("documentIds", documentIds.toArray(String[]::new));
        }
        if (chunkIds != null && !chunkIds.isEmpty()) {
            sql.append(" AND c.chunk_id = ANY(:chunkIds)");
            params.addValue("chunkIds", chunkIds.toArray(String[]::new));
        }
        sql.append(" ORDER BY c.updated_at DESC, c.chunk_id LIMIT :limit");
        params.addValue("limit", sampleSize);
        return jdbcTemplate.query(sql.toString(), params, chunkDetailRowMapper());
    }

    public List<AnchorEvalDtos.AnchorEvalCandidateDto> findAnchorEvalChunkCandidates(String chunkId, int limit) {
        String sql = """
                SELECT DISTINCT t.term_id,
                       t.canonical_form,
                       t.term_type,
                       t.source_confidence,
                       t.evidence_count
                FROM corpus_glossary_evidence e
                JOIN corpus_glossary_terms t ON t.term_id = e.term_id
                WHERE e.chunk_id = :chunkId
                  AND t.is_active = TRUE
                ORDER BY t.evidence_count DESC, t.source_confidence DESC, t.canonical_form
                LIMIT :limit
                """;
        return jdbcTemplate.query(sql, new MapSqlParameterSource()
                        .addValue("chunkId", chunkId)
                        .addValue("limit", limit),
                (rs, rowNum) -> new AnchorEvalDtos.AnchorEvalCandidateDto(
                        UUID.randomUUID(),
                        readUuid(rs, "term_id"),
                        rs.getString("canonical_form"),
                        rs.getString("term_type"),
                        rs.getDouble("source_confidence"),
                        rowNum + 1,
                        null,
                        null,
                        null
                )
        );
    }

    @Transactional
    public void insertAnchorEvalSample(UUID sampleId, UUID runId, String documentId, String chunkId, String chunkText) {
        String sql = """
                INSERT INTO anchor_eval_sample (sample_id, run_id, document_id, chunk_id, chunk_text)
                VALUES (:sampleId, :runId, :documentId, :chunkId, :chunkText)
                """;
        jdbcTemplate.update(sql, new MapSqlParameterSource()
                .addValue("sampleId", sampleId)
                .addValue("runId", runId)
                .addValue("documentId", documentId)
                .addValue("chunkId", chunkId)
                .addValue("chunkText", chunkText));
    }

    @Transactional
    public void insertAnchorEvalCandidate(UUID sampleId, AnchorEvalDtos.AnchorEvalCandidateDto candidate) {
        String sql = """
                INSERT INTO anchor_eval_candidate (
                    candidate_id, sample_id, term_id, canonical_form, term_type, score, rank_index, is_selected
                ) VALUES (
                    :candidateId, :sampleId, :termId, :canonicalForm, :termType, :score, :rankIndex, TRUE
                )
                """;
        jdbcTemplate.update(sql, new MapSqlParameterSource()
                .addValue("candidateId", candidate.candidateId())
                .addValue("sampleId", sampleId)
                .addValue("termId", candidate.termId())
                .addValue("canonicalForm", candidate.canonicalForm())
                .addValue("termType", candidate.termType())
                .addValue("score", candidate.score())
                .addValue("rankIndex", candidate.rankIndex()));
    }

    @Transactional
    public void completeAnchorEvalRun(UUID runId, JsonNode summaryJson) {
        String sql = """
                UPDATE anchor_eval_run
                SET status = 'completed',
                    summary_json = CAST(:summaryJson AS jsonb),
                    updated_at = NOW()
                WHERE run_id = :runId
                """;
        jdbcTemplate.update(sql, new MapSqlParameterSource()
                .addValue("runId", runId)
                .addValue("summaryJson", summaryJson.toString()));
    }

    public List<AnchorEvalDtos.AnchorEvalRunSummary> findAnchorEvalRuns(Integer limit, Integer offset) {
        String sql = """
                SELECT run_id, run_name, status, product_name, source_id, sample_size, candidate_limit, created_by,
                       summary_json::text AS summary_json, created_at, updated_at
                FROM anchor_eval_run
                ORDER BY created_at DESC
                LIMIT :limit OFFSET :offset
                """;
        return jdbcTemplate.query(sql, new MapSqlParameterSource()
                .addValue("limit", normalizeLimit(limit))
                .addValue("offset", normalizeOffset(offset)), anchorEvalRunRowMapper());
    }

    public AnchorEvalDtos.AnchorEvalRunSummary findAnchorEvalRun(UUID runId) {
        String sql = """
                SELECT run_id, run_name, status, product_name, source_id, sample_size, candidate_limit, created_by,
                       summary_json::text AS summary_json, created_at, updated_at
                FROM anchor_eval_run
                WHERE run_id = :runId
                """;
        return jdbcTemplate.queryForObject(sql, new MapSqlParameterSource("runId", runId), anchorEvalRunRowMapper());
    }

    public List<AnchorEvalDtos.AnchorEvalSampleDto> findAnchorEvalSamples(UUID runId) {
        String sampleSql = """
                SELECT sample_id, document_id, chunk_id, chunk_text
                FROM anchor_eval_sample
                WHERE run_id = :runId
                ORDER BY created_at, sample_id
                """;
        List<AnchorEvalDtos.AnchorEvalSampleDto> samples = jdbcTemplate.query(
                sampleSql,
                new MapSqlParameterSource("runId", runId),
                (rs, rowNum) -> new AnchorEvalDtos.AnchorEvalSampleDto(
                        readUuid(rs, "sample_id"),
                        rs.getString("document_id"),
                        rs.getString("chunk_id"),
                        rs.getString("chunk_text"),
                        new ArrayList<>()
                )
        );
        if (samples.isEmpty()) {
            return samples;
        }
        String candidateSql = """
                SELECT c.sample_id, c.candidate_id, c.term_id, c.canonical_form, c.term_type, c.score, c.rank_index,
                       l.label_value, l.confidence, l.note
                FROM anchor_eval_candidate c
                LEFT JOIN anchor_eval_label l
                  ON l.run_id = :runId
                 AND l.candidate_id = c.candidate_id
                WHERE c.sample_id = ANY(:sampleIds)
                ORDER BY c.sample_id, c.rank_index
                """;
        Map<UUID, List<AnchorEvalDtos.AnchorEvalCandidateDto>> candidateMap = new LinkedHashMap<>();
        for (AnchorEvalDtos.AnchorEvalSampleDto sample : samples) {
            candidateMap.put(sample.sampleId(), new ArrayList<>());
        }
        jdbcTemplate.query(candidateSql, new MapSqlParameterSource()
                        .addValue("runId", runId)
                        .addValue("sampleIds", samples.stream().map(AnchorEvalDtos.AnchorEvalSampleDto::sampleId).toArray(UUID[]::new)),
                (rs) -> {
                    UUID sampleId = readUuid(rs, "sample_id");
                    List<AnchorEvalDtos.AnchorEvalCandidateDto> bucket = candidateMap.get(sampleId);
                    if (bucket != null) {
                        bucket.add(new AnchorEvalDtos.AnchorEvalCandidateDto(
                                readUuid(rs, "candidate_id"),
                                readUuid(rs, "term_id"),
                                rs.getString("canonical_form"),
                                rs.getString("term_type"),
                                rs.getDouble("score"),
                                rs.getInt("rank_index"),
                                rs.getString("label_value"),
                                rs.getObject("confidence", Double.class),
                                rs.getString("note")
                        ));
                    }
                });
        return samples.stream().map(sample -> new AnchorEvalDtos.AnchorEvalSampleDto(
                sample.sampleId(), sample.documentId(), sample.chunkId(), sample.chunkText(), candidateMap.get(sample.sampleId())
        )).toList();
    }

    @Transactional
    public void upsertAnchorEvalLabel(
            UUID runId,
            UUID candidateId,
            String labelValue,
            Double confidence,
            String note,
            String labeledBy
    ) {
        String sql = """
                INSERT INTO anchor_eval_label (
                    label_id, run_id, candidate_id, label_value, confidence, note, labeled_by
                ) VALUES (
                    :labelId, :runId, :candidateId, :labelValue, :confidence, :note, :labeledBy
                )
                ON CONFLICT (run_id, candidate_id) DO UPDATE
                SET label_value = EXCLUDED.label_value,
                    confidence = EXCLUDED.confidence,
                    note = EXCLUDED.note,
                    labeled_by = EXCLUDED.labeled_by,
                    updated_at = NOW()
                """;
        jdbcTemplate.update(sql, new MapSqlParameterSource()
                .addValue("labelId", UUID.randomUUID())
                .addValue("runId", runId)
                .addValue("candidateId", candidateId)
                .addValue("labelValue", labelValue)
                .addValue("confidence", confidence)
                .addValue("note", note)
                .addValue("labeledBy", labeledBy));
    }

    public JsonNode computeAnchorEvalSummary(UUID runId) {
        String sql = """
                WITH candidate_base AS (
                    SELECT c.candidate_id, c.rank_index, s.sample_id
                    FROM anchor_eval_candidate c
                    JOIN anchor_eval_sample s ON s.sample_id = c.sample_id
                    WHERE s.run_id = :runId
                ),
                labeled AS (
                    SELECT b.sample_id,
                           b.candidate_id,
                           b.rank_index,
                           l.label_value
                    FROM candidate_base b
                    LEFT JOIN anchor_eval_label l
                      ON l.run_id = :runId
                     AND l.candidate_id = b.candidate_id
                )
                SELECT
                    (SELECT COUNT(*) FROM anchor_eval_sample WHERE run_id = :runId) AS sample_count,
                    (SELECT COUNT(*) FROM candidate_base) AS candidate_count,
                    (SELECT COUNT(*) FROM labeled WHERE label_value IS NOT NULL) AS labeled_count,
                    (SELECT COUNT(*) FROM labeled WHERE label_value = 'valid') AS valid_count,
                    (SELECT COUNT(*) FROM labeled WHERE label_value = 'invalid') AS invalid_count,
                    (SELECT COUNT(*) FROM labeled WHERE label_value = 'partial') AS partial_count
                """;
        Map<String, Object> result = jdbcTemplate.queryForMap(sql, new MapSqlParameterSource("runId", runId));
        int sampleCount = ((Number) result.get("sample_count")).intValue();
        int candidateCount = ((Number) result.get("candidate_count")).intValue();
        int labeledCount = ((Number) result.get("labeled_count")).intValue();
        int validCount = ((Number) result.get("valid_count")).intValue();
        int invalidCount = ((Number) result.get("invalid_count")).intValue();
        int partialCount = ((Number) result.get("partial_count")).intValue();
        double precision = labeledCount == 0 ? 0.0 : (double) validCount / labeledCount;
        double noiseRatio = labeledCount == 0 ? 0.0 : (double) invalidCount / labeledCount;
        Map<String, Object> summary = Map.of(
                "sample_count", sampleCount,
                "candidate_count", candidateCount,
                "labeled_count", labeledCount,
                "valid_count", validCount,
                "invalid_count", invalidCount,
                "partial_count", partialCount,
                "precision", precision,
                "noise_ratio", noiseRatio
        );
        return objectMapper.valueToTree(summary);
    }

    private RowMapper<CorpusDocumentDtos.ChunkDetail> chunkDetailRowMapper() {
        return (rs, rowNum) -> new CorpusDocumentDtos.ChunkDetail(
                rs.getString("chunk_id"),
                rs.getString("document_id"),
                rs.getString("section_id"),
                rs.getInt("chunk_index_in_document"),
                rs.getInt("chunk_index_in_section"),
                rs.getString("section_path_text"),
                rs.getString("chunk_text"),
                rs.getInt("char_len"),
                rs.getInt("token_len"),
                rs.getInt("overlap_from_prev_chars"),
                rs.getString("previous_chunk_id"),
                rs.getString("next_chunk_id"),
                rs.getBoolean("code_presence"),
                rs.getBoolean("table_presence"),
                rs.getBoolean("list_presence"),
                rs.getString("product_name"),
                rs.getString("version_label"),
                rs.getString("content_checksum"),
                readUuid(rs, "import_run_id"),
                readJson(objectMapper, rs, "metadata_json"),
                readInstant(rs, "created_at"),
                readInstant(rs, "updated_at")
        );
    }

    private RowMapper<AnchorEvalDtos.AnchorEvalRunSummary> anchorEvalRunRowMapper() {
        return (rs, rowNum) -> new AnchorEvalDtos.AnchorEvalRunSummary(
                readUuid(rs, "run_id"),
                rs.getString("run_name"),
                rs.getString("status"),
                rs.getString("product_name"),
                rs.getString("source_id"),
                rs.getInt("sample_size"),
                rs.getInt("candidate_limit"),
                rs.getString("created_by"),
                readJson(objectMapper, rs, "summary_json"),
                readInstant(rs, "created_at"),
                readInstant(rs, "updated_at")
        );
    }
}
