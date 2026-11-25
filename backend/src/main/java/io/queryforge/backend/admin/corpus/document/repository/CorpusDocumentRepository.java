package io.queryforge.backend.admin.corpus.document.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.queryforge.backend.admin.corpus.document.dto.CorpusDocumentDtos;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

import static io.queryforge.backend.admin.corpus.persistence.support.CorpusJdbc.like;
import static io.queryforge.backend.admin.corpus.persistence.support.CorpusJdbc.normalizeLimit;
import static io.queryforge.backend.admin.corpus.persistence.support.CorpusJdbc.normalizeOffset;
import static io.queryforge.backend.admin.corpus.persistence.support.CorpusJdbc.readInstant;
import static io.queryforge.backend.admin.corpus.persistence.support.CorpusJdbc.readJson;
import static io.queryforge.backend.admin.corpus.persistence.support.CorpusJdbc.readNullableInteger;
import static io.queryforge.backend.admin.corpus.persistence.support.CorpusJdbc.readUuid;

@Repository
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CorpusDocumentRepository {

    private final ObjectMapper objectMapper;

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public List<CorpusDocumentDtos.DocumentSummary> findDocuments(
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
        return findDocuments(
                productName,
                versionLabel,
                sourceId,
                documentId,
                headingKeyword,
                chunkKeyword,
                search,
                runId,
                null,
                activeOnly,
                limit,
                offset
        );
    }

    public List<CorpusDocumentDtos.DocumentSummary> findDocuments(
            String productName,
            String versionLabel,
            String sourceId,
            String documentId,
            String headingKeyword,
            String chunkKeyword,
            String search,
            UUID runId,
            UUID domainId,
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
        appendDocumentFilters(sql, params, productName, versionLabel, sourceId, documentId, headingKeyword, chunkKeyword, search, runId, domainId, activeOnly);
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

    public CorpusDocumentDtos.DocumentDetail findDocument(String documentId) {
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

    public List<CorpusDocumentDtos.SectionDto> findSections(
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

    public List<CorpusDocumentDtos.ChunkSummary> findChunks(
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

    public CorpusDocumentDtos.ChunkDetail findChunk(String chunkId) {
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

    public List<CorpusDocumentDtos.ChunkDetail> findChunkDetailsByDocumentId(String documentId, Integer limit) {
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

    public List<CorpusDocumentDtos.ChunkNeighborDto> findChunkNeighbors(String chunkId) {
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
            UUID domainId,
            boolean activeOnly
    ) {
        if (domainId != null) {
            sql.append(" AND d.domain_id = :domainId");
            params.addValue("domainId", domainId);
        }
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

    private RowMapper<CorpusDocumentDtos.DocumentSummary> documentSummaryRowMapper() {
        return (rs, rowNum) -> new CorpusDocumentDtos.DocumentSummary(
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

    private RowMapper<CorpusDocumentDtos.DocumentDetail> documentDetailRowMapper() {
        return (rs, rowNum) -> new CorpusDocumentDtos.DocumentDetail(
                rs.getString("document_id"),
                rs.getString("source_id"),
                rs.getString("product_name"),
                rs.getString("version_label"),
                rs.getString("canonical_url"),
                rs.getString("title"),
                rs.getString("section_path_text"),
                readJson(objectMapper, rs, "heading_hierarchy_json"),
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
                readJson(objectMapper, rs, "metadata_json"),
                readInstant(rs, "created_at"),
                readInstant(rs, "updated_at")
        );
    }

    private RowMapper<CorpusDocumentDtos.SectionDto> sectionRowMapper() {
        return (rs, rowNum) -> new CorpusDocumentDtos.SectionDto(
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
                readJson(objectMapper, rs, "structural_blocks_json"),
                readInstant(rs, "created_at"),
                readInstant(rs, "updated_at")
        );
    }

    private RowMapper<CorpusDocumentDtos.ChunkSummary> chunkSummaryRowMapper() {
        return (rs, rowNum) -> new CorpusDocumentDtos.ChunkSummary(
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

    private RowMapper<CorpusDocumentDtos.ChunkNeighborDto> chunkNeighborRowMapper() {
        return (rs, rowNum) -> new CorpusDocumentDtos.ChunkNeighborDto(
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
}
