package io.queryforge.backend.admin.corpus.glossary;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static io.queryforge.backend.admin.corpus.persistence.CorpusJdbc.like;
import static io.queryforge.backend.admin.corpus.persistence.CorpusJdbc.normalizeLimit;
import static io.queryforge.backend.admin.corpus.persistence.CorpusJdbc.normalizeOffset;
import static io.queryforge.backend.admin.corpus.persistence.CorpusJdbc.readInstant;
import static io.queryforge.backend.admin.corpus.persistence.CorpusJdbc.readJson;
import static io.queryforge.backend.admin.corpus.persistence.CorpusJdbc.readUuid;

@Repository
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CorpusGlossaryRepository {

    private final ObjectMapper objectMapper;

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public List<CorpusGlossaryDtos.GlossaryTermSummary> findGlossaryTerms(
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

    public CorpusGlossaryDtos.GlossaryTermSummary findGlossaryTerm(UUID termId) {
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

    public List<CorpusGlossaryDtos.GlossaryAliasDto> findGlossaryAliases(UUID termId) {
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

    public List<CorpusGlossaryDtos.GlossaryEvidenceDto> findGlossaryEvidence(UUID termId) {
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

    public List<CorpusGlossaryDtos.TopTermPreview> findTopTermsPreview(
            Integer limit,
            String productName,
            String termType,
            Boolean keepInEnglish
    ) {
        List<CorpusGlossaryDtos.GlossaryTermSummary> terms = findGlossaryTerms(
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
        List<CorpusGlossaryDtos.TopTermPreview> previews = new ArrayList<>();
        for (CorpusGlossaryDtos.GlossaryTermSummary term : terms) {
            List<CorpusGlossaryDtos.GlossaryEvidenceDto> evidences = findGlossaryEvidence(term.termId());
            List<String> snippets = evidences.stream()
                    .map(CorpusGlossaryDtos.GlossaryEvidenceDto::matchedText)
                    .limit(3)
                    .toList();
            previews.add(new CorpusGlossaryDtos.TopTermPreview(
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

    private RowMapper<CorpusGlossaryDtos.GlossaryTermSummary> glossaryTermRowMapper() {
        return (rs, rowNum) -> new CorpusGlossaryDtos.GlossaryTermSummary(
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
                readJson(objectMapper, rs, "metadata_json"),
                readInstant(rs, "created_at"),
                readInstant(rs, "updated_at")
        );
    }

    private RowMapper<CorpusGlossaryDtos.GlossaryAliasDto> glossaryAliasRowMapper() {
        return (rs, rowNum) -> new CorpusGlossaryDtos.GlossaryAliasDto(
                readUuid(rs, "alias_id"),
                readUuid(rs, "term_id"),
                rs.getString("alias_text"),
                rs.getString("alias_language"),
                rs.getString("alias_type"),
                readUuid(rs, "import_run_id"),
                readInstant(rs, "created_at")
        );
    }

    private RowMapper<CorpusGlossaryDtos.GlossaryEvidenceDto> glossaryEvidenceRowMapper() {
        return (rs, rowNum) -> new CorpusGlossaryDtos.GlossaryEvidenceDto(
                readUuid(rs, "evidence_id"),
                readUuid(rs, "term_id"),
                rs.getString("document_id"),
                rs.getString("chunk_id"),
                rs.getString("matched_text"),
                readJson(objectMapper, rs, "line_or_offset_info"),
                readUuid(rs, "import_run_id"),
                readInstant(rs, "created_at")
        );
    }
}
