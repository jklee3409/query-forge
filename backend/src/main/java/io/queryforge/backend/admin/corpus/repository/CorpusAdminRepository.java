package io.queryforge.backend.admin.corpus.repository;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.queryforge.backend.admin.corpus.model.CorpusAdminDtos;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Repository
public class CorpusAdminRepository {

    private static final String OVERLAP_LABEL = "Overlap context from previous chunk:";

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public CorpusAdminRepository(
            NamedParameterJdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

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
                                   'version_label', version_label,
                                   'document_count', COUNT(*),
                                   'active_count', COUNT(*) FILTER (WHERE is_active)
                               )
                               ORDER BY version_label
                           ) AS version_stats
                    FROM corpus_documents cd
                    WHERE cd.source_id = cs.source_id
                    GROUP BY cd.source_id
                ) version_stats ON TRUE
                ORDER BY cs.product_name, cs.source_id
                """;
        return jdbcTemplate.query(sql, sourceRowMapper());
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
                       created_at
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
                       created_at
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
                       metrics_json::text AS metrics_json,
                       started_at,
                       finished_at,
                       error_message
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
                       d.import_run_id,
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
        appendDocumentFilters(sql, params, productName, versionLabel, sourceId, documentId, headingKeyword, chunkKeyword, runId, activeOnly);
        sql.append("""
                 GROUP BY d.document_id, d.source_id, d.product_name, d.version_label, d.canonical_url, d.title,
                          d.section_path_text, d.language_code, d.content_type, d.is_active, d.import_run_id,
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
                       superseded_by_document_id,
                       import_run_id,
                       metadata_json::text AS metadata_json,
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
        appendChunkFilters(sql, params, productName, versionLabel, sourceId, documentId, chunkKeyword, runId, activeOnly);
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
            sql.append(" AND d.import_run_id = :runId");
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
    }

    private void appendChunkFilters(
            StringBuilder sql,
            MapSqlParameterSource params,
            String productName,
            String versionLabel,
            String sourceId,
            String documentId,
            String chunkKeyword,
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
                readInstant(rs, "created_at")
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
                readJson(rs, "metrics_json"),
                readInstant(rs, "started_at"),
                readInstant(rs, "finished_at"),
                rs.getString("error_message")
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
