package io.queryforge.backend.admin.corpus.service;

import io.queryforge.backend.admin.corpus.model.CorpusAdminDtos;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class AnchorExtractionService {

    private static final Pattern ANNOTATION_RE = Pattern.compile("@\\w+(?:\\.\\w+)*");
    private static final Pattern CONFIG_KEY_RE = Pattern.compile(
            "\\b(?:spring|management|server|logging|security|data|jpa|hibernate|jdbc|r2dbc|flyway|liquibase|web|webflux|mvc|main|test|actuator|application)"
                    + "\\.[a-z0-9][a-z0-9-]*(?:\\.[a-z0-9][a-z0-9-]*)+\\b"
    );
    private static final Pattern MAVEN_COORD_RE = Pattern.compile(
            "\\b(?:[a-z][a-z0-9_-]*\\.)+[a-z][a-z0-9_-]*:[A-Za-z0-9_.-]+(?::[A-Za-z0-9_.-]+)?\\b"
    );
    private static final Pattern STARTER_RE = Pattern.compile(
            "\\bspring-(?:boot|cloud|data|security|session|ai|batch|integration)-[a-z0-9-]+\\b"
    );
    private static final Pattern FULLY_QUALIFIED_TYPE_RE = Pattern.compile("\\b(?:[a-z_][\\w$]*\\.)+[A-Z][A-Za-z0-9_$]*\\b");
    private static final Pattern TYPE_DECLARATION_RE = Pattern.compile(
            "\\b(?:class|interface|enum|record|extends|implements|new)\\s+([A-Z][A-Za-z0-9_$]*)\\b"
    );
    private static final Pattern INLINE_TYPE_RE = Pattern.compile(
            "\\b[A-Z][A-Za-z0-9_$]*(?:Builder|Factory|Configurer|Template|Repository|Controller|Service|Configuration|Properties|Client|Manager|Resolver|Filter|Context|Bean|Application|Exception|Handler|Provider|Converter|Strategy|Endpoint|Details|Authentication|DataSource)\\b"
    );
    private static final Pattern GENERIC_TECH_TERM_RE = Pattern.compile(
            "\\b(?:[A-Z][A-Za-z0-9]+(?:[A-Z][A-Za-z0-9]+)+|[a-z][a-z0-9]+(?:[-_][a-z0-9]+){1,4}|[A-Za-z][A-Za-z0-9]*\\.[A-Za-z0-9._-]+)\\b"
    );
    private static final Pattern CODE_FENCE_RE = Pattern.compile("```([\\s\\S]*?)```");
    private static final Pattern WORD_RE = Pattern.compile("[A-Za-z][A-Za-z0-9._+-]{1,120}");
    private static final Set<String> STOPWORDS = Set.of(
            "the", "and", "for", "with", "that", "this", "from", "into", "over", "when", "where", "which",
            "while", "using", "used", "use", "your", "their", "they", "them", "then", "than", "also", "can",
            "could", "should", "would", "about", "after", "before", "have", "has", "had", "will", "may",
            "might", "must", "not", "are", "was", "were", "been", "being", "api", "http", "https", "www",
            "docs", "doc", "guide", "reference", "section", "page"
    );

    private final NamedParameterJdbcTemplate jdbcTemplate;

    @Transactional
    public CorpusAdminDtos.AnchorExtractResponse extractAnchors(
            List<String> requestedDocumentIds,
            List<String> requestedChunkIds
    ) {
        List<String> documentIds = normalizeIds(requestedDocumentIds);
        List<String> chunkIds = normalizeIds(requestedChunkIds);
        List<AnchorChunkRow> targetChunks = findTargetChunks(documentIds, chunkIds);
        if (targetChunks.isEmpty()) {
            throw new IllegalArgumentException("No target chunks found for anchor extraction.");
        }

        List<String> targetChunkIds = targetChunks.stream().map(AnchorChunkRow::chunkId).toList();
        Set<UUID> touchedTermIds = new LinkedHashSet<>(findTermIdsByChunkIds(targetChunkIds));
        int deletedEvidenceCount = deleteEvidenceByChunkIds(targetChunkIds);

        List<AnchorCandidate> candidates = extractCandidates(targetChunks);
        int insertedEvidenceCount = 0;
        for (AnchorCandidate candidate : candidates) {
            UUID termId = upsertGlossaryTerm(candidate);
            touchedTermIds.add(termId);
            insertedEvidenceCount += insertEvidence(termId, candidate);
        }

        TermRefreshSummary termRefreshSummary = refreshTouchedTerms(touchedTermIds);
        AnchorRemapSummary remapSummary = remapSyntheticQueryAnchors(targetChunkIds);
        return new CorpusAdminDtos.AnchorExtractResponse(
                targetChunkIds.size(),
                deletedEvidenceCount,
                insertedEvidenceCount,
                termRefreshSummary.updatedTermCount(),
                termRefreshSummary.deactivatedTermCount(),
                remapSummary.remappedSyntheticQueryCount(),
                remapSummary.remappedLinkCount()
        );
    }

    private List<String> normalizeIds(List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String value : ids) {
            if (value == null) {
                continue;
            }
            String trimmed = value.trim();
            if (!trimmed.isEmpty()) {
                normalized.add(trimmed);
            }
        }
        return List.copyOf(normalized);
    }

    private List<AnchorChunkRow> findTargetChunks(List<String> documentIds, List<String> chunkIds) {
        StringBuilder sql = new StringBuilder("""
                SELECT c.chunk_id, c.document_id, c.chunk_text, c.product_name
                FROM corpus_chunks c
                WHERE 1=1
                """);
        MapSqlParameterSource params = new MapSqlParameterSource();
        if (!documentIds.isEmpty()) {
            sql.append(" AND c.document_id = ANY(:documentIds)");
            params.addValue("documentIds", documentIds.toArray(String[]::new));
        }
        if (!chunkIds.isEmpty()) {
            sql.append(" AND c.chunk_id = ANY(:chunkIds)");
            params.addValue("chunkIds", chunkIds.toArray(String[]::new));
        }
        sql.append(" ORDER BY c.document_id, c.chunk_index_in_document");
        return jdbcTemplate.query(
                sql.toString(),
                params,
                (rs, rowNum) -> new AnchorChunkRow(
                        rs.getString("chunk_id"),
                        rs.getString("document_id"),
                        rs.getString("chunk_text"),
                        rs.getString("product_name")
                )
        );
    }

    private Set<UUID> findTermIdsByChunkIds(List<String> chunkIds) {
        if (chunkIds.isEmpty()) {
            return Set.of();
        }
        String sql = """
                SELECT DISTINCT term_id
                FROM corpus_glossary_evidence
                WHERE chunk_id = ANY(:chunkIds)
                """;
        MapSqlParameterSource params = new MapSqlParameterSource("chunkIds", chunkIds.toArray(String[]::new));
        return new LinkedHashSet<>(jdbcTemplate.query(sql, params, (rs, rowNum) -> rs.getObject("term_id", UUID.class)));
    }

    private int deleteEvidenceByChunkIds(List<String> chunkIds) {
        if (chunkIds.isEmpty()) {
            return 0;
        }
        String sql = "DELETE FROM corpus_glossary_evidence WHERE chunk_id = ANY(:chunkIds)";
        return jdbcTemplate.update(sql, new MapSqlParameterSource("chunkIds", chunkIds.toArray(String[]::new)));
    }

    private List<AnchorCandidate> extractCandidates(List<AnchorChunkRow> chunks) {
        LinkedHashMap<String, AnchorCandidate> dedup = new LinkedHashMap<>();
        for (AnchorChunkRow chunk : chunks) {
            addCandidates(dedup, chunk, "annotation", ANNOTATION_RE);
            addCandidates(dedup, chunk, "config_key", CONFIG_KEY_RE);
            addCandidates(dedup, chunk, "dependency_artifact", MAVEN_COORD_RE);
            addCandidates(dedup, chunk, "dependency_artifact", STARTER_RE);
            addCandidates(dedup, chunk, "class_interface", FULLY_QUALIFIED_TYPE_RE);
            addCandidates(dedup, chunk, "class_interface", TYPE_DECLARATION_RE);
            addCandidates(dedup, chunk, "class_interface", INLINE_TYPE_RE);
            addCandidates(dedup, chunk, "concept", GENERIC_TECH_TERM_RE);
            for (String phrase : extractKeyphrases(chunk.chunkText())) {
                putCandidate(dedup, chunk, "concept", phrase, phrase);
            }
            extractCodeSymbols(dedup, chunk);
        }
        return new ArrayList<>(dedup.values());
    }

    private void addCandidates(
            Map<String, AnchorCandidate> bucket,
            AnchorChunkRow chunk,
            String termType,
            Pattern pattern
    ) {
        Matcher matcher = pattern.matcher(chunk.chunkText());
        while (matcher.find()) {
            String matched = matcher.groupCount() >= 1 && matcher.group(1) != null
                    ? matcher.group(1)
                    : matcher.group();
            if (matched == null || matched.isBlank()) {
                continue;
            }
            String canonical = matched.trim();
            putCandidate(bucket, chunk, termType, canonical, canonical);
        }
    }

    private UUID upsertGlossaryTerm(AnchorCandidate candidate) {
        String sql = """
                INSERT INTO corpus_glossary_terms (
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
                    metadata_json,
                    created_at,
                    updated_at
                ) VALUES (
                    :canonicalForm,
                    :normalizedForm,
                    :termType,
                    TRUE,
                    :descriptionShort,
                    0.85,
                    :documentId,
                    :chunkId,
                    0,
                    TRUE,
                    CAST(:metadataJson AS jsonb),
                    NOW(),
                    NOW()
                )
                ON CONFLICT (term_type, normalized_form) DO UPDATE
                SET canonical_form = EXCLUDED.canonical_form,
                    keep_in_english = TRUE,
                    description_short = EXCLUDED.description_short,
                    is_active = TRUE,
                    updated_at = NOW()
                RETURNING term_id
                """;
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("canonicalForm", candidate.canonicalForm())
                .addValue("normalizedForm", candidate.normalizedForm())
                .addValue("termType", mapTermType(candidate.termType()))
                .addValue("descriptionShort", "Re-extracted anchor term from selected chunks.")
                .addValue("documentId", candidate.documentId())
                .addValue("chunkId", candidate.chunkId())
                .addValue("metadataJson", "{\"reextracted\":true}");
        return jdbcTemplate.queryForObject(sql, params, (rs, rowNum) -> rs.getObject("term_id", UUID.class));
    }

    private int insertEvidence(UUID termId, AnchorCandidate candidate) {
        String sql = """
                INSERT INTO corpus_glossary_evidence (
                    evidence_id,
                    term_id,
                    document_id,
                    chunk_id,
                    matched_text,
                    line_or_offset_info,
                    created_at
                ) VALUES (
                    :evidenceId,
                    :termId,
                    :documentId,
                    :chunkId,
                    :matchedText,
                    CAST(:offsetInfo AS jsonb),
                    NOW()
                )
                """;
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("evidenceId", UUID.randomUUID())
                .addValue("termId", termId)
                .addValue("documentId", candidate.documentId())
                .addValue("chunkId", candidate.chunkId())
                .addValue("matchedText", candidate.matchedText())
                .addValue("offsetInfo", "{\"reextracted\":true}");
        return jdbcTemplate.update(sql, params);
    }

    private TermRefreshSummary refreshTouchedTerms(Set<UUID> termIds) {
        if (termIds.isEmpty()) {
            return new TermRefreshSummary(0, 0);
        }
        int updated = 0;
        int deactivated = 0;
        for (UUID termId : termIds) {
            String statSql = """
                    SELECT COUNT(*) AS evidence_count,
                           MIN(document_id) AS first_document_id,
                           MIN(chunk_id) AS first_chunk_id
                    FROM corpus_glossary_evidence
                    WHERE term_id = :termId
                    """;
            MapSqlParameterSource params = new MapSqlParameterSource("termId", termId);
            TermStatRow stat = jdbcTemplate.queryForObject(
                    statSql,
                    params,
                    (rs, rowNum) -> new TermStatRow(
                            rs.getInt("evidence_count"),
                            rs.getString("first_document_id"),
                            rs.getString("first_chunk_id")
                    )
            );
            if (stat == null) {
                continue;
            }
            String updateSql = """
                    UPDATE corpus_glossary_terms
                    SET evidence_count = :evidenceCount,
                        first_seen_document_id = :firstDocumentId,
                        first_seen_chunk_id = :firstChunkId,
                        is_active = :active,
                        updated_at = NOW()
                    WHERE term_id = :termId
                    """;
            boolean active = stat.evidenceCount() > 0;
            updated += jdbcTemplate.update(
                    updateSql,
                    new MapSqlParameterSource()
                            .addValue("termId", termId)
                            .addValue("evidenceCount", stat.evidenceCount())
                            .addValue("firstDocumentId", stat.firstDocumentId())
                            .addValue("firstChunkId", stat.firstChunkId())
                            .addValue("active", active)
            );
            if (!active) {
                deactivated += 1;
            }
        }
        return new TermRefreshSummary(updated, deactivated);
    }

    private AnchorRemapSummary remapSyntheticQueryAnchors(List<String> chunkIds) {
        if (chunkIds.isEmpty()) {
            return new AnchorRemapSummary(0, 0);
        }
        List<String> affectedQueryIds = findAffectedSyntheticQueryIds(chunkIds);
        if (affectedQueryIds.isEmpty()) {
            return new AnchorRemapSummary(0, 0);
        }
        jdbcTemplate.update(
                "DELETE FROM synthetic_query_anchor_link WHERE synthetic_query_id = ANY(:queryIds)",
                new MapSqlParameterSource("queryIds", affectedQueryIds.toArray(String[]::new))
        );

        String insertSql = """
                WITH affected_queries AS (
                    SELECT unnest(CAST(:queryIds AS text[])) AS synthetic_query_id
                ),
                query_chunks AS (
                    SELECT r.synthetic_query_id, r.chunk_id_source AS chunk_id
                    FROM synthetic_queries_raw_all r
                    JOIN affected_queries aq ON aq.synthetic_query_id = r.synthetic_query_id
                    WHERE r.chunk_id_source IS NOT NULL
                    UNION
                    SELECT r.synthetic_query_id, source_chunks.value AS chunk_id
                    FROM synthetic_queries_raw_all r
                    JOIN affected_queries aq ON aq.synthetic_query_id = r.synthetic_query_id
                    JOIN LATERAL jsonb_array_elements_text(COALESCE(r.source_chunk_ids, '[]'::jsonb)) source_chunks ON TRUE
                ),
                candidates AS (
                    SELECT DISTINCT qc.synthetic_query_id, e.term_id, qc.chunk_id
                    FROM query_chunks qc
                    JOIN corpus_glossary_evidence e ON e.chunk_id = qc.chunk_id
                    JOIN corpus_glossary_terms t ON t.term_id = e.term_id
                    WHERE t.is_active = TRUE
                )
                INSERT INTO synthetic_query_anchor_link (
                    synthetic_query_id,
                    term_id,
                    source_chunk_id,
                    is_active,
                    created_at,
                    updated_at
                )
                SELECT c.synthetic_query_id,
                       c.term_id,
                       c.chunk_id,
                       TRUE,
                       NOW(),
                       NOW()
                FROM candidates c
                ON CONFLICT (synthetic_query_id, term_id, source_chunk_id) DO UPDATE
                SET is_active = TRUE,
                    updated_at = NOW()
                """;
        int inserted = jdbcTemplate.update(
                insertSql,
                new MapSqlParameterSource("queryIds", affectedQueryIds.toArray(String[]::new))
        );
        return new AnchorRemapSummary(affectedQueryIds.size(), inserted);
    }

    private List<String> findAffectedSyntheticQueryIds(List<String> chunkIds) {
        String sql = """
                SELECT DISTINCT query_id
                FROM (
                    SELECT r.synthetic_query_id AS query_id
                    FROM synthetic_queries_raw_all r
                    WHERE r.chunk_id_source = ANY(:chunkIds)
                    UNION
                    SELECT r.synthetic_query_id AS query_id
                    FROM synthetic_queries_raw_all r
                    JOIN LATERAL jsonb_array_elements_text(COALESCE(r.source_chunk_ids, '[]'::jsonb)) source_chunks ON TRUE
                    WHERE source_chunks.value = ANY(:chunkIds)
                ) q
                """;
        return jdbcTemplate.query(
                sql,
                new MapSqlParameterSource("chunkIds", chunkIds.toArray(String[]::new)),
                (rs, rowNum) -> rs.getString("query_id")
        );
    }

    private String mapTermType(String rawType) {
        return switch (rawType) {
            case "class_interface" -> "class";
            case "dependency_artifact" -> "artifact";
            case "config_key" -> "config_key";
            case "annotation" -> "annotation";
            default -> "concept";
        };
    }

    private void extractCodeSymbols(Map<String, AnchorCandidate> bucket, AnchorChunkRow chunk) {
        Matcher matcher = CODE_FENCE_RE.matcher(chunk.chunkText());
        while (matcher.find()) {
            String codeBlock = matcher.group(1);
            if (codeBlock == null || codeBlock.isBlank()) {
                continue;
            }
            for (String line : codeBlock.split("\\R")) {
                String trimmed = line.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                if (trimmed.startsWith("import ")) {
                    String symbol = trimmed
                            .replace("import ", "")
                            .replace(";", "")
                            .trim();
                    if (!symbol.isBlank()) {
                        putCandidate(bucket, chunk, "class_interface", symbol, symbol);
                    }
                }
                if (trimmed.contains("=") && !trimmed.startsWith("//")) {
                    String lhs = trimmed.substring(0, trimmed.indexOf('=')).trim();
                    if (lhs.matches("[A-Za-z_][A-Za-z0-9_\\-.]{2,80}")) {
                        putCandidate(bucket, chunk, "config_key", lhs, lhs);
                    }
                }
            }
        }
    }

    private void putCandidate(
            Map<String, AnchorCandidate> bucket,
            AnchorChunkRow chunk,
            String termType,
            String canonical,
            String matchedText
    ) {
        String normalized = canonical.toLowerCase().trim();
        if (normalized.isEmpty() || normalized.length() < 3 || normalized.length() > 120) {
            return;
        }
        if (normalized.chars().filter(ch -> ch == '.').count() > 6) {
            return;
        }
        String key = chunk.chunkId() + "|" + termType + "|" + normalized;
        bucket.putIfAbsent(
                key,
                new AnchorCandidate(
                        chunk.documentId(),
                        chunk.chunkId(),
                        termType,
                        canonical,
                        normalized,
                        matchedText
                )
        );
    }

    private List<String> extractKeyphrases(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        List<String> words = new ArrayList<>();
        Matcher matcher = WORD_RE.matcher(text);
        while (matcher.find()) {
            String token = matcher.group().trim();
            if (token.length() < 3 || STOPWORDS.contains(token.toLowerCase(Locale.ROOT))) {
                continue;
            }
            words.add(token);
        }
        if (words.isEmpty()) {
            return List.of();
        }

        Map<String, Double> scoreByPhrase = new HashMap<>();
        for (int i = 0; i < words.size(); i++) {
            for (int n = 1; n <= 3; n++) {
                int end = i + n;
                if (end > words.size()) {
                    break;
                }
                String phrase = String.join(" ", words.subList(i, end));
                phrase = phrase.replaceAll("^(?i)(a|an|the|this|that)\\s+", "");
                phrase = phrase.replaceAll("(?i)\\s+(guide|section|page)$", "");
                if (phrase.isBlank()) {
                    continue;
                }
                String normalized = phrase.toLowerCase(Locale.ROOT);
                if (normalized.length() < 4 || normalized.length() > 120) {
                    continue;
                }
                if (phrase.contains("{") || phrase.contains("}") || phrase.contains("[") || phrase.contains("]")
                        || phrase.contains(";") || phrase.contains("\"")) {
                    continue;
                }
                int stopwordCount = 0;
                for (String part : normalized.split("\\s+")) {
                    if (STOPWORDS.contains(part)) {
                        stopwordCount += 1;
                    }
                }
                if (stopwordCount == normalized.split("\\s+").length) {
                    continue;
                }
                double titleBonus = Character.isUpperCase(phrase.charAt(0)) ? 0.4 : 0.0;
                double dotBonus = normalized.contains(".") ? 0.5 : 0.0;
                double lenBonus = Math.min(0.7, normalized.length() / 80.0);
                scoreByPhrase.merge(phrase, 1.0 + titleBonus + dotBonus + lenBonus, Double::sum);
            }
        }

        HashSet<String> seen = new HashSet<>();
        return scoreByPhrase.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue(Comparator.reverseOrder()))
                .map(Map.Entry::getKey)
                .filter(term -> seen.add(term.toLowerCase(Locale.ROOT)))
                .limit(24)
                .toList();
    }

    private record AnchorChunkRow(
            String chunkId,
            String documentId,
            String chunkText,
            String productName
    ) {
    }

    private record AnchorCandidate(
            String documentId,
            String chunkId,
            String termType,
            String canonicalForm,
            String normalizedForm,
            String matchedText
    ) {
    }

    private record TermStatRow(
            int evidenceCount,
            String firstDocumentId,
            String firstChunkId
    ) {
    }

    private record TermRefreshSummary(
            int updatedTermCount,
            int deactivatedTermCount
    ) {
    }

    private record AnchorRemapSummary(
            int remappedSyntheticQueryCount,
            int remappedLinkCount
    ) {
    }
}
