package io.queryforge.backend.admin.corpus.repository;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.queryforge.backend.admin.corpus.model.CorpusAdminDtos;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.RowMapper;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CorpusAdminRepository {

    private static final String OVERLAP_LABEL = "Overlap context from previous chunk:";

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public List<CorpusAdminDtos.SourceSummary> findSources() {
        String sql = """
                SELECT cs.source_id,
                       cs.source_type,
                       cs.product_name,
                       cs.source_name,
                       cs.base_url,
                       cs.include_patterns::text AS include_patterns,
                       cs.exclude_patterns::text AS exclude_patterns,
                       cs.default_version,
                       cs.enabled,
                       cs.created_at,
                       cs.updated_at,
                       COALESCE(doc_stats.total_documents, 0) AS total_documents,
                       COALESCE(doc_stats.active_documents, 0) AS active_documents,
                       COALESCE(version_stats.version_stats, '[]'::jsonb)::text AS version_stats
                FROM corpus_sources cs
                LEFT JOIN LATERAL (
                    SELECT COUNT(*) AS total_documents,
                           COUNT(*) FILTER (WHERE is_active) AS active_documents
                    FROM corpus_documents cd
                    WHERE cd.source_id = cs.source_id
                ) doc_stats ON TRUE
                LEFT JOIN LATERAL (
                    SELECT jsonb_agg(
                               jsonb_build_object(
                                   'version_label', version_counts.version_label,
                                   'document_count', version_counts.document_count,
                                   'active_count', version_counts.active_count
                               )
                               ORDER BY version_counts.version_label
                           ) AS version_stats
                    FROM (
                        SELECT cd.version_label,
                               COUNT(*) AS document_count,
                               COUNT(*) FILTER (WHERE cd.is_active) AS active_count
                        FROM corpus_documents cd
                        WHERE cd.source_id = cs.source_id
                        GROUP BY cd.version_label
                    ) version_counts
                ) version_stats ON TRUE
                ORDER BY cs.product_name, cs.source_id
                """;
        return jdbcTemplate.query(sql, sourceRowMapper());
    }

    public CorpusAdminDtos.SourceSummary findSourceById(String sourceId) {
        String sql = """
                SELECT cs.source_id,
                       cs.source_type,
                       cs.product_name,
                       cs.source_name,
                       cs.base_url,
                       cs.include_patterns::text AS include_patterns,
                       cs.exclude_patterns::text AS exclude_patterns,
                       cs.default_version,
                       cs.enabled,
                       cs.created_at,
                       cs.updated_at,
                       COALESCE(doc_stats.total_documents, 0) AS total_documents,
                       COALESCE(doc_stats.active_documents, 0) AS active_documents,
                       COALESCE(version_stats.version_stats, '[]'::jsonb)::text AS version_stats
                FROM corpus_sources cs
                LEFT JOIN LATERAL (
                    SELECT COUNT(*) AS total_documents,
                           COUNT(*) FILTER (WHERE is_active) AS active_documents
                    FROM corpus_documents cd
                    WHERE cd.source_id = cs.source_id
                ) doc_stats ON TRUE
                LEFT JOIN LATERAL (
                    SELECT jsonb_agg(
                               jsonb_build_object(
                                   'version_label', version_counts.version_label,
                                   'document_count', version_counts.document_count,
                                   'active_count', version_counts.active_count
                               )
                               ORDER BY version_counts.version_label
                           ) AS version_stats
                    FROM (
                        SELECT cd.version_label,
                               COUNT(*) AS document_count,
                               COUNT(*) FILTER (WHERE cd.is_active) AS active_count
                        FROM corpus_documents cd
                        WHERE cd.source_id = cs.source_id
                        GROUP BY cd.version_label
                    ) version_counts
                ) version_stats ON TRUE
                WHERE cs.source_id = :sourceId
                """;
        return jdbcTemplate.queryForObject(
                sql,
                new MapSqlParameterSource("sourceId", sourceId),
                sourceRowMapper()
        );
    }

    public List<CorpusAdminDtos.RunSummary> findRuns(
            UUID runId,
            String runStatus,
            String runType,
            Integer limit,
            Integer offset
    ) {
        StringBuilder sql = new StringBuilder("""
                SELECT run_id,
                       run_type,
                       run_status,
                       trigger_type,
                       source_scope::text AS source_scope,
                       config_snapshot::text AS config_snapshot,
                       started_at,
                       finished_at,
                       duration_ms,
                       summary_json::text AS summary_json,
                       error_message,
                       created_by,
                       created_at,
                       cancel_requested_at,
                       updated_at
                FROM corpus_runs
                WHERE 1=1
                """);
        MapSqlParameterSource params = new MapSqlParameterSource();
        if (runId != null) {
            sql.append(" AND run_id = :runId");
            params.addValue("runId", runId);
        }
        if (runStatus != null && !runStatus.isBlank()) {
            sql.append(" AND run_status = :runStatus");
            params.addValue("runStatus", runStatus);
        }
        if (runType != null && !runType.isBlank()) {
            sql.append(" AND run_type = :runType");
            params.addValue("runType", runType);
        }
        sql.append(" ORDER BY created_at DESC LIMIT :limit OFFSET :offset");
        params.addValue("limit", normalizeLimit(limit));
        params.addValue("offset", normalizeOffset(offset));
        return jdbcTemplate.query(sql.toString(), params, runRowMapper());
    }

    public CorpusAdminDtos.RunSummary findRun(UUID runId) {
        String sql = """
                SELECT run_id,
                       run_type,
                       run_status,
                       trigger_type,
                       source_scope::text AS source_scope,
                       config_snapshot::text AS config_snapshot,
                       started_at,
                       finished_at,
                       duration_ms,
                       summary_json::text AS summary_json,
                       error_message,
                       created_by,
                       created_at,
                       cancel_requested_at,
                       updated_at
                FROM corpus_runs
                WHERE run_id = :runId
                """;
        return jdbcTemplate.queryForObject(sql, new MapSqlParameterSource("runId", runId), runRowMapper());
    }

    public List<CorpusAdminDtos.RunStep> findRunSteps(UUID runId) {
        String sql = """
                SELECT step_id,
                       step_name,
                       step_order,
                       step_status,
                       input_artifact_path,
                       output_artifact_path,
                       command_line,
                       metrics_json::text AS metrics_json,
                       started_at,
                       finished_at,
                       error_message,
                       stdout_log_path,
                       stderr_log_path,
                       stdout_excerpt,
                       stderr_excerpt,
                       updated_at
                FROM corpus_run_steps
                WHERE run_id = :runId
                ORDER BY step_order
                """;
        return jdbcTemplate.query(sql, new MapSqlParameterSource("runId", runId), runStepRowMapper());
    }

    public List<CorpusAdminDtos.DocumentSummary> findDocuments(
            String productName,
            String versionLabel,
            String sourceId,
            String documentId,
            String headingKeyword,
            String chunkKeyword,
            String search,
            UUID runId,
            boolean activeOnly,
            Integer limit,
            Integer offset
    ) {
        StringBuilder sql = new StringBuilder("""
                SELECT d.document_id,
                       d.source_id,
                       d.product_name,
                       d.version_label,
                       d.canonical_url,
                       d.title,
                       d.section_path_text,
                       d.language_code,
                       d.content_type,
                       d.is_active,
                       (to_jsonb(d) ->> 'import_run_id')::uuid AS import_run_id,
                       d.collected_at,
                       d.normalized_at,
                       d.updated_at,
                       COUNT(DISTINCT s.section_id) AS section_count,
                       COUNT(DISTINCT c.chunk_id) AS chunk_count
                FROM corpus_documents d
                LEFT JOIN corpus_sections s ON s.document_id = d.document_id
                LEFT JOIN corpus_chunks c ON c.document_id = d.document_id
                WHERE 1=1
                """);
        MapSqlParameterSource params = new MapSqlParameterSource();
        appendDocumentFilters(sql, params, productName, versionLabel, sourceId, documentId, headingKeyword, chunkKeyword, search, runId, activeOnly);
        sql.append("""

                GROUP BY d.document_id, d.source_id, d.product_name, d.version_label, d.canonical_url, d.title,
                         d.section_path_text, d.language_code, d.content_type, d.is_active,
                         d.collected_at, d.normalized_at, d.updated_at
                ORDER BY d.updated_at DESC, d.document_id
                LIMIT :limit OFFSET :offset
                """);
        params.addValue("limit", normalizeLimit(limit));
        params.addValue("offset", normalizeOffset(offset));
        return jdbcTemplate.query(sql.toString(), params, documentSummaryRowMapper());
    }

    public CorpusAdminDtos.DocumentDetail findDocument(String documentId) {
        String sql = """
                SELECT document_id,
                       source_id,
                       product_name,
                       version_label,
                       canonical_url,
                       title,
                       section_path_text,
                       heading_hierarchy_json::text AS heading_hierarchy_json,
                       raw_checksum,
                       cleaned_checksum,
                       raw_text,
                       cleaned_text,
                       language_code,
                       content_type,
                       collected_at,
                       normalized_at,
                       is_active,
                       to_jsonb(corpus_documents) ->> 'superseded_by_document_id' AS superseded_by_document_id,
                       (to_jsonb(corpus_documents) ->> 'import_run_id')::uuid AS import_run_id,
                       COALESCE(to_jsonb(corpus_documents) -> 'metadata_json', '{}'::jsonb)::text AS metadata_json,
                       created_at,
                       updated_at
                FROM corpus_documents
                WHERE document_id = :documentId
                """;
        return jdbcTemplate.queryForObject(sql, new MapSqlParameterSource("documentId", documentId), documentDetailRowMapper());
    }

    public List<CorpusAdminDtos.SectionDto> findSections(
            String documentId,
            String headingKeyword,
            UUID runId
    ) {
        StringBuilder sql = new StringBuilder("""
                SELECT section_id,
                       document_id,
                       parent_section_id,
                       heading_level,
                       heading_text,
                       section_order,
                       section_path_text,
                       content_text,
                       code_block_count,
                       table_count,
                       list_count,
                       import_run_id,
                       structural_blocks_json::text AS structural_blocks_json,
                       created_at,
                       updated_at
                FROM corpus_sections
                WHERE document_id = :documentId
                """);
        MapSqlParameterSource params = new MapSqlParameterSource("documentId", documentId);
        if (headingKeyword != null && !headingKeyword.isBlank()) {
            sql.append(" AND heading_text ILIKE :headingKeyword");
            params.addValue("headingKeyword", like(headingKeyword));
        }
        if (runId != null) {
            sql.append(" AND import_run_id = :runId");
            params.addValue("runId", runId);
        }
        sql.append(" ORDER BY section_order");
        return jdbcTemplate.query(sql.toString(), params, sectionRowMapper());
    }

    public List<CorpusAdminDtos.ChunkSummary> findChunks(
            String productName,
            String versionLabel,
            String sourceId,
            String documentId,
            String chunkKeyword,
            String search,
            Boolean codePresence,
            Integer minTokenLen,
            Integer maxTokenLen,
            UUID runId,
            boolean activeOnly,
            Integer limit,
            Integer offset
    ) {
        StringBuilder sql = new StringBuilder("""
                SELECT c.chunk_id,
                       c.document_id,
                       c.section_id,
                       c.chunk_index_in_document,
                       c.chunk_index_in_section,
                       c.section_path_text,
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
                       c.import_run_id,
                       c.created_at,
                       c.updated_at
                FROM corpus_chunks c
                JOIN corpus_documents d ON d.document_id = c.document_id
                WHERE 1=1
                """);
        MapSqlParameterSource params = new MapSqlParameterSource();
        appendChunkFilters(
                sql,
                params,
                productName,
                versionLabel,
                sourceId,
                documentId,
                chunkKeyword,
                search,
                codePresence,
                minTokenLen,
                maxTokenLen,
                runId,
                activeOnly
        );
        sql.append("""

                ORDER BY c.document_id, c.chunk_index_in_document
                LIMIT :limit OFFSET :offset
                """);
        params.addValue("limit", normalizeLimit(limit));
        params.addValue("offset", normalizeOffset(offset));
        return jdbcTemplate.query(sql.toString(), params, chunkSummaryRowMapper());
    }

    public CorpusAdminDtos.ChunkDetail findChunk(String chunkId) {
        String sql = """
                SELECT chunk_id,
                       document_id,
                       section_id,
                       chunk_index_in_document,
                       chunk_index_in_section,
                       section_path_text,
                       chunk_text,
                       char_len,
                       token_len,
                       overlap_from_prev_chars,
                       previous_chunk_id,
                       next_chunk_id,
                       code_presence,
                       table_presence,
                       list_presence,
                       product_name,
                       version_label,
                       content_checksum,
                       import_run_id,
                       metadata_json::text AS metadata_json,
                       created_at,
                       updated_at
                FROM corpus_chunks
                WHERE chunk_id = :chunkId
                """;
        return jdbcTemplate.queryForObject(sql, new MapSqlParameterSource("chunkId", chunkId), chunkDetailRowMapper());
    }

    public List<CorpusAdminDtos.ChunkDetail> findChunkDetailsByDocumentId(String documentId, Integer limit) {
        String sql = """
                SELECT chunk_id,
                       document_id,
                       section_id,
                       chunk_index_in_document,
                       chunk_index_in_section,
                       section_path_text,
                       chunk_text,
                       char_len,
                       token_len,
                       overlap_from_prev_chars,
                       previous_chunk_id,
                       next_chunk_id,
                       code_presence,
                       table_presence,
                       list_presence,
                       product_name,
                       version_label,
                       content_checksum,
                       import_run_id,
                       metadata_json::text AS metadata_json,
                       created_at,
                       updated_at
                FROM corpus_chunks
                WHERE document_id = :documentId
                ORDER BY chunk_index_in_document
                LIMIT :limit
                """;
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("documentId", documentId)
                .addValue("limit", normalizeLimit(limit));
        return jdbcTemplate.query(sql, params, chunkDetailRowMapper());
    }

    public List<CorpusAdminDtos.ChunkNeighborDto> findChunkNeighbors(String chunkId) {
        String sql = """
                SELECT r.relation_id,
                       r.source_chunk_id,
                       r.target_chunk_id,
                       r.relation_type,
                       r.distance_in_doc,
                       tc.document_id AS target_document_id,
                       tc.chunk_index_in_document AS target_chunk_index_in_document,
                       tc.section_path_text AS target_section_path_text
                FROM corpus_chunk_relations r
                JOIN corpus_chunks tc ON tc.chunk_id = r.target_chunk_id
                WHERE r.source_chunk_id = :chunkId
                ORDER BY CASE r.relation_type
                             WHEN 'near' THEN 1
                             WHEN 'far' THEN 2
                             WHEN 'same_section' THEN 3
                             ELSE 4
                         END,
                         r.distance_in_doc,
                         tc.chunk_index_in_document
                """;
        return jdbcTemplate.query(sql, new MapSqlParameterSource("chunkId", chunkId), chunkNeighborRowMapper());
    }

    public List<CorpusAdminDtos.GlossaryTermSummary> findGlossaryTerms(
            String productName,
            String versionLabel,
            String sourceId,
            String termType,
            Boolean keepInEnglish,
            UUID runId,
            boolean activeOnly,
            String keyword,
            Integer limit,
            Integer offset
    ) {
        StringBuilder sql = new StringBuilder("""
                SELECT DISTINCT gt.term_id,
                       gt.canonical_form,
                       gt.normalized_form,
                       gt.term_type,
                       gt.keep_in_english,
                       gt.description_short,
                       gt.source_confidence,
                       gt.first_seen_document_id,
                       gt.first_seen_chunk_id,
                       gt.evidence_count,
                       gt.is_active,
                       gt.import_run_id,
                       gt.metadata_json::text AS metadata_json,
                       gt.created_at,
                       gt.updated_at
                FROM corpus_glossary_terms gt
                LEFT JOIN corpus_glossary_aliases ga ON ga.term_id = gt.term_id
                LEFT JOIN corpus_documents d ON d.document_id = gt.first_seen_document_id
                WHERE 1=1
                """);
        MapSqlParameterSource params = new MapSqlParameterSource();
        appendGlossaryFilters(sql, params, productName, versionLabel, sourceId, termType, keepInEnglish, runId, activeOnly, keyword);
        sql.append("""

                ORDER BY gt.evidence_count DESC, gt.canonical_form
                LIMIT :limit OFFSET :offset
                """);
        params.addValue("limit", normalizeLimit(limit));
        params.addValue("offset", normalizeOffset(offset));
        return jdbcTemplate.query(sql.toString(), params, glossaryTermRowMapper());
    }

    public CorpusAdminDtos.GlossaryTermSummary findGlossaryTerm(UUID termId) {
        String sql = """
                SELECT term_id,
                       canonical_form,
                       normalized_form,
                       term_type,
                       keep_in_english,
                       description_short,
                       source_confidence,
                       first_seen_document_id,
                       first_seen_chunk_id,
                       evidence_count,
                       is_active,
                       import_run_id,
                       metadata_json::text AS metadata_json,
                       created_at,
                       updated_at
                FROM corpus_glossary_terms
                WHERE term_id = :termId
                """;
        return jdbcTemplate.queryForObject(sql, new MapSqlParameterSource("termId", termId), glossaryTermRowMapper());
    }

    public List<CorpusAdminDtos.GlossaryAliasDto> findGlossaryAliases(UUID termId) {
        String sql = """
                SELECT alias_id,
                       term_id,
                       alias_text,
                       alias_language,
                       alias_type,
                       import_run_id,
                       created_at
                FROM corpus_glossary_aliases
                WHERE term_id = :termId
                ORDER BY alias_text
                """;
        return jdbcTemplate.query(sql, new MapSqlParameterSource("termId", termId), glossaryAliasRowMapper());
    }

    public List<CorpusAdminDtos.GlossaryEvidenceDto> findGlossaryEvidence(UUID termId) {
        String sql = """
                SELECT evidence_id,
                       term_id,
                       document_id,
                       chunk_id,
                       matched_text,
                       line_or_offset_info::text AS line_or_offset_info,
                       import_run_id,
                       created_at
                FROM corpus_glossary_evidence
                WHERE term_id = :termId
                ORDER BY created_at, document_id, chunk_id
                """;
        return jdbcTemplate.query(sql, new MapSqlParameterSource("termId", termId), glossaryEvidenceRowMapper());
    }

    public List<CorpusAdminDtos.AnchorSummary> findAnchors(
            String documentId,
            String chunkId,
            String keyword,
            boolean activeOnly,
            Integer limit,
            Integer offset
    ) {
        StringBuilder sql = new StringBuilder("""
                SELECT gt.term_id,
                       gt.canonical_form,
                       gt.term_type,
                       gt.keep_in_english,
                       gt.source_confidence,
                       gt.evidence_count,
                       COALESCE(scope_stats.scoped_evidence_count, 0) AS scoped_evidence_count,
                       gt.first_seen_document_id,
                       gt.first_seen_chunk_id,
                       gt.updated_at
                FROM corpus_glossary_terms gt
                LEFT JOIN LATERAL (
                    SELECT COUNT(*) AS scoped_evidence_count
                    FROM corpus_glossary_evidence e
                    WHERE e.term_id = gt.term_id
                """);
        MapSqlParameterSource params = new MapSqlParameterSource();
        appendAnchorEvidenceScopeFilters(sql, params, "e", documentId, chunkId);
        sql.append("""
                ) scope_stats ON TRUE
                WHERE 1=1
                """);

        if (activeOnly) {
            sql.append(" AND gt.is_active = TRUE");
        }
        if (keyword != null && !keyword.isBlank()) {
            sql.append(" AND gt.canonical_form ILIKE :keyword");
            params.addValue("keyword", like(keyword));
        }
        if ((documentId != null && !documentId.isBlank()) || (chunkId != null && !chunkId.isBlank())) {
            sql.append("""
                     AND EXISTS (
                         SELECT 1
                         FROM corpus_glossary_evidence e2
                         WHERE e2.term_id = gt.term_id
                    """);
            appendAnchorEvidenceScopeFilters(sql, params, "e2", documentId, chunkId);
            sql.append(")");
        }

        sql.append("""

                ORDER BY COALESCE(scope_stats.scoped_evidence_count, 0) DESC,
                         gt.evidence_count DESC,
                         gt.canonical_form
                LIMIT :limit OFFSET :offset
                """);
        params.addValue("limit", normalizeLimit(limit));
        params.addValue("offset", normalizeOffset(offset));
        return jdbcTemplate.query(sql.toString(), params, anchorSummaryRowMapper());
    }

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

    public List<CorpusAdminDtos.ChunkDetail> findAnchorEvalTargetChunks(
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

    public List<CorpusAdminDtos.AnchorEvalCandidateDto> findAnchorEvalChunkCandidates(String chunkId, int limit) {
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
                (rs, rowNum) -> new CorpusAdminDtos.AnchorEvalCandidateDto(
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
    public void insertAnchorEvalCandidate(UUID sampleId, CorpusAdminDtos.AnchorEvalCandidateDto candidate) {
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

    public List<CorpusAdminDtos.AnchorEvalRunSummary> findAnchorEvalRuns(Integer limit, Integer offset) {
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

    public CorpusAdminDtos.AnchorEvalRunSummary findAnchorEvalRun(UUID runId) {
        String sql = """
                SELECT run_id, run_name, status, product_name, source_id, sample_size, candidate_limit, created_by,
                       summary_json::text AS summary_json, created_at, updated_at
                FROM anchor_eval_run
                WHERE run_id = :runId
                """;
        return jdbcTemplate.queryForObject(sql, new MapSqlParameterSource("runId", runId), anchorEvalRunRowMapper());
    }

    public List<CorpusAdminDtos.AnchorEvalSampleDto> findAnchorEvalSamples(UUID runId) {
        String sampleSql = """
                SELECT sample_id, document_id, chunk_id, chunk_text
                FROM anchor_eval_sample
                WHERE run_id = :runId
                ORDER BY created_at, sample_id
                """;
        List<CorpusAdminDtos.AnchorEvalSampleDto> samples = jdbcTemplate.query(
                sampleSql,
                new MapSqlParameterSource("runId", runId),
                (rs, rowNum) -> new CorpusAdminDtos.AnchorEvalSampleDto(
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
        Map<UUID, List<CorpusAdminDtos.AnchorEvalCandidateDto>> candidateMap = new LinkedHashMap<>();
        for (CorpusAdminDtos.AnchorEvalSampleDto sample : samples) {
            candidateMap.put(sample.sampleId(), new ArrayList<>());
        }
        jdbcTemplate.query(candidateSql, new MapSqlParameterSource()
                        .addValue("runId", runId)
                        .addValue("sampleIds", samples.stream().map(CorpusAdminDtos.AnchorEvalSampleDto::sampleId).toArray(UUID[]::new)),
                (rs) -> {
                    UUID sampleId = readUuid(rs, "sample_id");
                    List<CorpusAdminDtos.AnchorEvalCandidateDto> bucket = candidateMap.get(sampleId);
                    if (bucket != null) {
                        bucket.add(new CorpusAdminDtos.AnchorEvalCandidateDto(
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
        return samples.stream().map(sample -> new CorpusAdminDtos.AnchorEvalSampleDto(
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

    @Transactional
    public void updateSourceEnabled(String sourceId, boolean enabled) {
        String sql = """
                UPDATE corpus_sources
                SET enabled = :enabled,
                    updated_at = NOW()
                WHERE source_id = :sourceId
                """;
        jdbcTemplate.update(
                sql,
                new MapSqlParameterSource()
                        .addValue("sourceId", sourceId)
                        .addValue("enabled", enabled)
        );
    }

    @Transactional
    public void updateGlossaryTerm(
            UUID termId,
            Boolean keepInEnglish,
            Boolean active,
            String descriptionShort
    ) {
        StringBuilder sql = new StringBuilder("""
                UPDATE corpus_glossary_terms
                SET updated_at = NOW()
                """);
        MapSqlParameterSource params = new MapSqlParameterSource("termId", termId);
        if (keepInEnglish != null) {
            sql.append(", keep_in_english = :keepInEnglish");
            params.addValue("keepInEnglish", keepInEnglish);
        }
        if (active != null) {
            sql.append(", is_active = :active");
            params.addValue("active", active);
        }
        if (descriptionShort != null) {
            sql.append(", description_short = :descriptionShort");
            params.addValue("descriptionShort", descriptionShort);
        }
        sql.append(" WHERE term_id = :termId");
        jdbcTemplate.update(sql.toString(), params);
    }

    @Transactional
    public UUID insertGlossaryAlias(
            UUID termId,
            String aliasText,
            String aliasLanguage,
            String aliasType
    ) {
        UUID aliasId = UUID.randomUUID();
        String sql = """
                INSERT INTO corpus_glossary_aliases (
                    alias_id,
                    term_id,
                    alias_text,
                    alias_language,
                    alias_type,
                    created_at
                ) VALUES (
                    :aliasId,
                    :termId,
                    :aliasText,
                    :aliasLanguage,
                    :aliasType,
                    NOW()
                )
                ON CONFLICT (term_id, alias_text, alias_language) DO NOTHING
                """;
        jdbcTemplate.update(
                sql,
                new MapSqlParameterSource()
                        .addValue("aliasId", aliasId)
                        .addValue("termId", termId)
                        .addValue("aliasText", aliasText)
                        .addValue("aliasLanguage", aliasLanguage)
                        .addValue("aliasType", aliasType)
        );
        return aliasId;
    }

    @Transactional
    public void deleteGlossaryAlias(UUID aliasId) {
        String sql = "DELETE FROM corpus_glossary_aliases WHERE alias_id = :aliasId";
        jdbcTemplate.update(sql, new MapSqlParameterSource("aliasId", aliasId));
    }

    public List<CorpusAdminDtos.TopTermPreview> findTopTermsPreview(
            Integer limit,
            String productName,
            String termType,
            Boolean keepInEnglish
    ) {
        List<CorpusAdminDtos.GlossaryTermSummary> terms = findGlossaryTerms(
                productName,
                null,
                null,
                termType,
                keepInEnglish,
                null,
                true,
                null,
                limit,
                0
        );
        List<CorpusAdminDtos.TopTermPreview> previews = new ArrayList<>();
        for (CorpusAdminDtos.GlossaryTermSummary term : terms) {
            List<CorpusAdminDtos.GlossaryEvidenceDto> evidences = findGlossaryEvidence(term.termId());
            List<String> snippets = evidences.stream()
                    .map(CorpusAdminDtos.GlossaryEvidenceDto::matchedText)
                    .limit(3)
                    .toList();
            previews.add(new CorpusAdminDtos.TopTermPreview(
                    term.termId(),
                    term.canonicalForm(),
                    term.termType(),
                    term.evidenceCount(),
                    term.keepInEnglish(),
                    snippets
            ));
        }
        return previews;
    }

    public String stripOverlapPrefix(String chunkText) {
        if (!chunkText.startsWith(OVERLAP_LABEL)) {
            return chunkText;
        }
        String[] parts = chunkText.split("\\n\\n", 2);
        if (parts.length < 2) {
            return chunkText;
        }
        return parts[1];
    }

    private void appendDocumentFilters(
            StringBuilder sql,
            MapSqlParameterSource params,
            String productName,
            String versionLabel,
            String sourceId,
            String documentId,
            String headingKeyword,
            String chunkKeyword,
            String search,
            UUID runId,
            boolean activeOnly
    ) {
        if (productName != null && !productName.isBlank()) {
            sql.append(" AND d.product_name = :productName");
            params.addValue("productName", productName);
        }
        if (versionLabel != null && !versionLabel.isBlank()) {
            sql.append(" AND d.version_label = :versionLabel");
            params.addValue("versionLabel", versionLabel);
        }
        if (sourceId != null && !sourceId.isBlank()) {
            sql.append(" AND d.source_id = :sourceId");
            params.addValue("sourceId", sourceId);
        }
        if (documentId != null && !documentId.isBlank()) {
            sql.append(" AND d.document_id = :documentId");
            params.addValue("documentId", documentId);
        }
        if (runId != null) {
            sql.append(" AND (to_jsonb(d) ->> 'import_run_id')::uuid = :runId");
            params.addValue("runId", runId);
        }
        if (activeOnly) {
            sql.append(" AND d.is_active = TRUE");
        }
        if (headingKeyword != null && !headingKeyword.isBlank()) {
            sql.append("""
                     AND EXISTS (
                         SELECT 1
                         FROM corpus_sections s2
                         WHERE s2.document_id = d.document_id
                           AND s2.heading_text ILIKE :headingKeyword
                     )
                    """);
            params.addValue("headingKeyword", like(headingKeyword));
        }
        if (chunkKeyword != null && !chunkKeyword.isBlank()) {
            sql.append("""
                     AND EXISTS (
                         SELECT 1
                         FROM corpus_chunks c2
                         WHERE c2.document_id = d.document_id
                           AND c2.chunk_text ILIKE :chunkKeyword
                     )
                    """);
            params.addValue("chunkKeyword", like(chunkKeyword));
        }
        if (search != null && !search.isBlank()) {
            sql.append("""
                     AND (
                         d.document_id ILIKE :search
                         OR d.title ILIKE :search
                         OR d.canonical_url ILIKE :search
                         OR d.section_path_text ILIKE :search
                         OR EXISTS (
                             SELECT 1
                             FROM corpus_sections s3
                             WHERE s3.document_id = d.document_id
                               AND s3.heading_text ILIKE :search
                         )
                         OR EXISTS (
                             SELECT 1
                             FROM corpus_chunks c3
                             WHERE c3.document_id = d.document_id
                               AND c3.chunk_text ILIKE :search
                         )
                     )
                    """);
            params.addValue("search", like(search));
        }
    }

    private void appendChunkFilters(
            StringBuilder sql,
            MapSqlParameterSource params,
            String productName,
            String versionLabel,
            String sourceId,
            String documentId,
            String chunkKeyword,
            String search,
            Boolean codePresence,
            Integer minTokenLen,
            Integer maxTokenLen,
            UUID runId,
            boolean activeOnly
    ) {
        if (productName != null && !productName.isBlank()) {
            sql.append(" AND c.product_name = :productName");
            params.addValue("productName", productName);
        }
        if (versionLabel != null && !versionLabel.isBlank()) {
            sql.append(" AND c.version_label = :versionLabel");
            params.addValue("versionLabel", versionLabel);
        }
        if (sourceId != null && !sourceId.isBlank()) {
            sql.append(" AND d.source_id = :sourceId");
            params.addValue("sourceId", sourceId);
        }
        if (documentId != null && !documentId.isBlank()) {
            sql.append(" AND c.document_id = :documentId");
            params.addValue("documentId", documentId);
        }
        if (runId != null) {
            sql.append(" AND c.import_run_id = :runId");
            params.addValue("runId", runId);
        }
        if (activeOnly) {
            sql.append(" AND d.is_active = TRUE");
        }
        if (chunkKeyword != null && !chunkKeyword.isBlank()) {
            sql.append(" AND c.chunk_text ILIKE :chunkKeyword");
            params.addValue("chunkKeyword", like(chunkKeyword));
        }
        if (search != null && !search.isBlank()) {
            sql.append("""
                     AND (
                         c.chunk_id ILIKE :search
                         OR c.document_id ILIKE :search
                         OR c.section_path_text ILIKE :search
                         OR c.chunk_text ILIKE :search
                     )
                    """);
            params.addValue("search", like(search));
        }
        if (codePresence != null) {
            sql.append(" AND c.code_presence = :codePresence");
            params.addValue("codePresence", codePresence);
        }
        if (minTokenLen != null) {
            sql.append(" AND c.token_len >= :minTokenLen");
            params.addValue("minTokenLen", minTokenLen);
        }
        if (maxTokenLen != null) {
            sql.append(" AND c.token_len <= :maxTokenLen");
            params.addValue("maxTokenLen", maxTokenLen);
        }
    }

    private void appendAnchorEvidenceScopeFilters(
            StringBuilder sql,
            MapSqlParameterSource params,
            String evidenceAlias,
            String documentId,
            String chunkId
    ) {
        if (documentId != null && !documentId.isBlank()) {
            sql.append(" AND ").append(evidenceAlias).append(".document_id = :anchorDocumentId");
            params.addValue("anchorDocumentId", documentId);
        }
        if (chunkId != null && !chunkId.isBlank()) {
            sql.append(" AND ").append(evidenceAlias).append(".chunk_id = :anchorChunkId");
            params.addValue("anchorChunkId", chunkId);
        }
    }

    private void appendGlossaryFilters(
            StringBuilder sql,
            MapSqlParameterSource params,
            String productName,
            String versionLabel,
            String sourceId,
            String termType,
            Boolean keepInEnglish,
            UUID runId,
            boolean activeOnly,
            String keyword
    ) {
        if (productName != null && !productName.isBlank()) {
            sql.append(" AND d.product_name = :productName");
            params.addValue("productName", productName);
        }
        if (versionLabel != null && !versionLabel.isBlank()) {
            sql.append(" AND d.version_label = :versionLabel");
            params.addValue("versionLabel", versionLabel);
        }
        if (sourceId != null && !sourceId.isBlank()) {
            sql.append(" AND d.source_id = :sourceId");
            params.addValue("sourceId", sourceId);
        }
        if (termType != null && !termType.isBlank()) {
            sql.append(" AND gt.term_type = :termType");
            params.addValue("termType", termType);
        }
        if (keepInEnglish != null) {
            sql.append(" AND gt.keep_in_english = :keepInEnglish");
            params.addValue("keepInEnglish", keepInEnglish);
        }
        if (runId != null) {
            sql.append(" AND gt.import_run_id = :runId");
            params.addValue("runId", runId);
        }
        if (activeOnly) {
            sql.append(" AND gt.is_active = TRUE");
        }
        if (keyword != null && !keyword.isBlank()) {
            sql.append("""
                     AND (
                         gt.canonical_form ILIKE :keyword
                         OR ga.alias_text ILIKE :keyword
                     )
                    """);
            params.addValue("keyword", like(keyword));
        }
    }

    private RowMapper<CorpusAdminDtos.SourceSummary> sourceRowMapper() {
        return (rs, rowNum) -> new CorpusAdminDtos.SourceSummary(
                rs.getString("source_id"),
                rs.getString("source_type"),
                rs.getString("product_name"),
                rs.getString("source_name"),
                rs.getString("base_url"),
                readJson(rs, "include_patterns"),
                readJson(rs, "exclude_patterns"),
                rs.getString("default_version"),
                rs.getBoolean("enabled"),
                rs.getLong("total_documents"),
                rs.getLong("active_documents"),
                readJson(rs, "version_stats"),
                readInstant(rs, "created_at"),
                readInstant(rs, "updated_at")
        );
    }

    private RowMapper<CorpusAdminDtos.RunSummary> runRowMapper() {
        return (rs, rowNum) -> new CorpusAdminDtos.RunSummary(
                readUuid(rs, "run_id"),
                rs.getString("run_type"),
                rs.getString("run_status"),
                rs.getString("trigger_type"),
                readJson(rs, "source_scope"),
                readJson(rs, "config_snapshot"),
                readInstant(rs, "started_at"),
                readInstant(rs, "finished_at"),
                readNullableLong(rs, "duration_ms"),
                readJson(rs, "summary_json"),
                rs.getString("error_message"),
                rs.getString("created_by"),
                readInstant(rs, "created_at"),
                readInstant(rs, "cancel_requested_at"),
                readInstant(rs, "updated_at")
        );
    }

    private RowMapper<CorpusAdminDtos.RunStep> runStepRowMapper() {
        return (rs, rowNum) -> new CorpusAdminDtos.RunStep(
                readUuid(rs, "step_id"),
                rs.getString("step_name"),
                rs.getInt("step_order"),
                rs.getString("step_status"),
                rs.getString("input_artifact_path"),
                rs.getString("output_artifact_path"),
                rs.getString("command_line"),
                readJson(rs, "metrics_json"),
                readInstant(rs, "started_at"),
                readInstant(rs, "finished_at"),
                rs.getString("error_message"),
                rs.getString("stdout_log_path"),
                rs.getString("stderr_log_path"),
                rs.getString("stdout_excerpt"),
                rs.getString("stderr_excerpt"),
                readInstant(rs, "updated_at")
        );
    }

    private RowMapper<CorpusAdminDtos.DocumentSummary> documentSummaryRowMapper() {
        return (rs, rowNum) -> new CorpusAdminDtos.DocumentSummary(
                rs.getString("document_id"),
                rs.getString("source_id"),
                rs.getString("product_name"),
                rs.getString("version_label"),
                rs.getString("canonical_url"),
                rs.getString("title"),
                rs.getString("section_path_text"),
                rs.getString("language_code"),
                rs.getString("content_type"),
                rs.getBoolean("is_active"),
                readUuid(rs, "import_run_id"),
                rs.getLong("section_count"),
                rs.getLong("chunk_count"),
                readInstant(rs, "collected_at"),
                readInstant(rs, "normalized_at"),
                readInstant(rs, "updated_at")
        );
    }

    private RowMapper<CorpusAdminDtos.DocumentDetail> documentDetailRowMapper() {
        return (rs, rowNum) -> new CorpusAdminDtos.DocumentDetail(
                rs.getString("document_id"),
                rs.getString("source_id"),
                rs.getString("product_name"),
                rs.getString("version_label"),
                rs.getString("canonical_url"),
                rs.getString("title"),
                rs.getString("section_path_text"),
                readJson(rs, "heading_hierarchy_json"),
                rs.getString("raw_checksum"),
                rs.getString("cleaned_checksum"),
                rs.getString("raw_text"),
                rs.getString("cleaned_text"),
                rs.getString("language_code"),
                rs.getString("content_type"),
                readInstant(rs, "collected_at"),
                readInstant(rs, "normalized_at"),
                rs.getBoolean("is_active"),
                rs.getString("superseded_by_document_id"),
                readUuid(rs, "import_run_id"),
                readJson(rs, "metadata_json"),
                readInstant(rs, "created_at"),
                readInstant(rs, "updated_at")
        );
    }

    private RowMapper<CorpusAdminDtos.SectionDto> sectionRowMapper() {
        return (rs, rowNum) -> new CorpusAdminDtos.SectionDto(
                rs.getString("section_id"),
                rs.getString("document_id"),
                rs.getString("parent_section_id"),
                readNullableInteger(rs, "heading_level"),
                rs.getString("heading_text"),
                rs.getInt("section_order"),
                rs.getString("section_path_text"),
                rs.getString("content_text"),
                rs.getInt("code_block_count"),
                rs.getInt("table_count"),
                rs.getInt("list_count"),
                readUuid(rs, "import_run_id"),
                readJson(rs, "structural_blocks_json"),
                readInstant(rs, "created_at"),
                readInstant(rs, "updated_at")
        );
    }

    private RowMapper<CorpusAdminDtos.ChunkSummary> chunkSummaryRowMapper() {
        return (rs, rowNum) -> new CorpusAdminDtos.ChunkSummary(
                rs.getString("chunk_id"),
                rs.getString("document_id"),
                rs.getString("section_id"),
                rs.getInt("chunk_index_in_document"),
                rs.getInt("chunk_index_in_section"),
                rs.getString("section_path_text"),
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
                readUuid(rs, "import_run_id"),
                readInstant(rs, "created_at"),
                readInstant(rs, "updated_at")
        );
    }

    private RowMapper<CorpusAdminDtos.ChunkDetail> chunkDetailRowMapper() {
        return (rs, rowNum) -> new CorpusAdminDtos.ChunkDetail(
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
                readJson(rs, "metadata_json"),
                readInstant(rs, "created_at"),
                readInstant(rs, "updated_at")
        );
    }

    private RowMapper<CorpusAdminDtos.ChunkNeighborDto> chunkNeighborRowMapper() {
        return (rs, rowNum) -> new CorpusAdminDtos.ChunkNeighborDto(
                readUuid(rs, "relation_id"),
                rs.getString("source_chunk_id"),
                rs.getString("target_chunk_id"),
                rs.getString("relation_type"),
                readNullableInteger(rs, "distance_in_doc"),
                rs.getString("target_document_id"),
                readNullableInteger(rs, "target_chunk_index_in_document"),
                rs.getString("target_section_path_text")
        );
    }

    private RowMapper<CorpusAdminDtos.GlossaryTermSummary> glossaryTermRowMapper() {
        return (rs, rowNum) -> new CorpusAdminDtos.GlossaryTermSummary(
                readUuid(rs, "term_id"),
                rs.getString("canonical_form"),
                rs.getString("normalized_form"),
                rs.getString("term_type"),
                rs.getBoolean("keep_in_english"),
                rs.getString("description_short"),
                rs.getDouble("source_confidence"),
                rs.getString("first_seen_document_id"),
                rs.getString("first_seen_chunk_id"),
                rs.getInt("evidence_count"),
                rs.getBoolean("is_active"),
                readUuid(rs, "import_run_id"),
                readJson(rs, "metadata_json"),
                readInstant(rs, "created_at"),
                readInstant(rs, "updated_at")
        );
    }

    private RowMapper<CorpusAdminDtos.GlossaryAliasDto> glossaryAliasRowMapper() {
        return (rs, rowNum) -> new CorpusAdminDtos.GlossaryAliasDto(
                readUuid(rs, "alias_id"),
                readUuid(rs, "term_id"),
                rs.getString("alias_text"),
                rs.getString("alias_language"),
                rs.getString("alias_type"),
                readUuid(rs, "import_run_id"),
                readInstant(rs, "created_at")
        );
    }

    private RowMapper<CorpusAdminDtos.GlossaryEvidenceDto> glossaryEvidenceRowMapper() {
        return (rs, rowNum) -> new CorpusAdminDtos.GlossaryEvidenceDto(
                readUuid(rs, "evidence_id"),
                readUuid(rs, "term_id"),
                rs.getString("document_id"),
                rs.getString("chunk_id"),
                rs.getString("matched_text"),
                readJson(rs, "line_or_offset_info"),
                readUuid(rs, "import_run_id"),
                readInstant(rs, "created_at")
        );
    }

    private RowMapper<CorpusAdminDtos.AnchorSummary> anchorSummaryRowMapper() {
        return (rs, rowNum) -> new CorpusAdminDtos.AnchorSummary(
                readUuid(rs, "term_id"),
                rs.getString("canonical_form"),
                rs.getString("term_type"),
                rs.getBoolean("keep_in_english"),
                rs.getDouble("source_confidence"),
                rs.getInt("evidence_count"),
                rs.getInt("scoped_evidence_count"),
                rs.getString("first_seen_document_id"),
                rs.getString("first_seen_chunk_id"),
                readInstant(rs, "updated_at")
        );
    }

    private RowMapper<CorpusAdminDtos.AnchorEvalRunSummary> anchorEvalRunRowMapper() {
        return (rs, rowNum) -> new CorpusAdminDtos.AnchorEvalRunSummary(
                readUuid(rs, "run_id"),
                rs.getString("run_name"),
                rs.getString("status"),
                rs.getString("product_name"),
                rs.getString("source_id"),
                rs.getInt("sample_size"),
                rs.getInt("candidate_limit"),
                rs.getString("created_by"),
                readJson(rs, "summary_json"),
                readInstant(rs, "created_at"),
                readInstant(rs, "updated_at")
        );
    }

    private String like(String value) {
        return "%" + value.trim() + "%";
    }

    private int normalizeLimit(Integer limit) {
        if (limit == null || limit <= 0) {
            return 50;
        }
        return Math.min(limit, 500);
    }

    private int normalizeOffset(Integer offset) {
        if (offset == null || offset < 0) {
            return 0;
        }
        return offset;
    }

    private JsonNode readJson(ResultSet rs, String column) throws SQLException {
        String raw = rs.getString(column);
        if (raw == null || raw.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            return objectMapper.readTree(raw);
        } catch (Exception exception) {
            throw new SQLException("Failed to parse JSON column " + column, exception);
        }
    }

    private UUID readUuid(ResultSet rs, String column) throws SQLException {
        Object value = rs.getObject(column);
        if (value == null) {
            return null;
        }
        if (value instanceof UUID uuid) {
            return uuid;
        }
        return UUID.fromString(value.toString());
    }

    private Instant readInstant(ResultSet rs, String column) throws SQLException {
        Timestamp timestamp = rs.getTimestamp(column);
        return timestamp != null ? timestamp.toInstant() : null;
    }

    private Long readNullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private Integer readNullableInteger(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }
}
