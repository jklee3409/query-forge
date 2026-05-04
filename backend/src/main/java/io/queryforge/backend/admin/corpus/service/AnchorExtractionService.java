package io.queryforge.backend.admin.corpus.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.queryforge.backend.admin.pipeline.config.AdminPipelineProperties;
import io.queryforge.backend.admin.pipeline.service.SourceCatalogService;
import io.queryforge.backend.admin.corpus.model.CorpusAdminDtos;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

@Service
@RequiredArgsConstructor
public class AnchorExtractionService {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };
    private static final int OUTPUT_EXCERPT_MAX_CHARS = 1200;

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final AdminPipelineProperties pipelineProperties;
    private final SourceCatalogService sourceCatalogService;

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
        try {
            return extractCandidatesViaPipelineGlossary(chunks);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to extract anchors via pipeline glossary logic.", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Anchor extraction command was interrupted.", exception);
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
            case "spring_product" -> "product";
            case "cli_command" -> "cli";
            default -> "concept";
        };
    }

    private List<AnchorCandidate> extractCandidatesViaPipelineGlossary(List<AnchorChunkRow> chunks) throws IOException, InterruptedException {
        Path tempDir = Files.createTempDirectory("anchor-reextract-");
        Path inputChunksPath = tempDir.resolve("chunks.jsonl");
        Path outputCandidatesPath = tempDir.resolve("anchor_candidates.jsonl");
        try {
            writeChunkRows(inputChunksPath, chunks);
            runPipelineExtractionCommand(inputChunksPath, outputCandidatesPath);
            return readCandidateRows(outputCandidatesPath);
        } finally {
            cleanupTempDirectory(tempDir);
        }
    }

    private void writeChunkRows(Path outputPath, List<AnchorChunkRow> chunks) throws IOException {
        Files.createDirectories(outputPath.getParent());
        try (BufferedWriter writer = Files.newBufferedWriter(outputPath, StandardCharsets.UTF_8)) {
            for (AnchorChunkRow chunk : chunks) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("chunk_id", chunk.chunkId());
                row.put("document_id", chunk.documentId());
                row.put("chunk_text", chunk.chunkText());
                row.put("product_name", chunk.productName());
                writer.write(objectMapper.writeValueAsString(row));
                writer.newLine();
            }
        }
    }

    private void runPipelineExtractionCommand(Path inputChunksPath, Path outputCandidatesPath) throws IOException, InterruptedException {
        Path repoRoot = sourceCatalogService.repoRoot();
        Path pipelineCliPath = sourceCatalogService.resolveWithinRepo("pipeline/cli.py", "pipeline CLI entrypoint");
        Path chunkingConfigPath = sourceCatalogService.resolveWithinRepo(pipelineProperties.chunkingConfig(), "chunking config");

        List<String> command = new ArrayList<>();
        command.add(pipelineProperties.pythonCommand());
        command.add(pipelineCliPath.toString());
        command.add("extract-anchor-candidates");
        command.add("--input-chunks");
        command.add(inputChunksPath.toString());
        command.add("--output-candidates");
        command.add(outputCandidatesPath.toString());
        command.add("--config");
        command.add(chunkingConfigPath.toString());
        command.add("--log-level");
        command.add("WARNING");

        ProcessBuilder processBuilder = new ProcessBuilder(command)
                .directory(repoRoot.toFile())
                .redirectErrorStream(true);
        Process process = processBuilder.start();
        String output;
        try (BufferedReader reader = new BufferedReader(new java.io.InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            StringBuilder buffer = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                buffer.append(line).append('\n');
            }
            output = buffer.toString();
        }
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new IllegalStateException(
                    "Pipeline anchor extraction failed. exitCode=" + exitCode + ", output=" + excerpt(output)
            );
        }
    }

    private List<AnchorCandidate> readCandidateRows(Path inputPath) throws IOException {
        if (!Files.exists(inputPath)) {
            return List.of();
        }
        LinkedHashMap<String, AnchorCandidate> dedup = new LinkedHashMap<>();
        try (Stream<String> lines = Files.lines(inputPath, StandardCharsets.UTF_8)) {
            lines.filter(line -> line != null && !line.isBlank()).forEach(line -> {
                try {
                    Map<String, Object> payload = objectMapper.readValue(line, MAP_TYPE);
                    String documentId = readRequiredText(payload, "document_id");
                    String chunkId = readRequiredText(payload, "chunk_id");
                    String termType = readRequiredText(payload, "term_type");
                    String canonicalForm = readRequiredText(payload, "canonical_form");
                    String matchedText = readOptionalText(payload, "matched_text");
                    addPipelineCandidate(dedup, documentId, chunkId, termType, canonicalForm, matchedText);
                } catch (IOException exception) {
                    throw new IllegalStateException("Failed to parse pipeline candidate row.", exception);
                }
            });
        }
        return new ArrayList<>(dedup.values());
    }

    private void addPipelineCandidate(
            Map<String, AnchorCandidate> bucket,
            String documentId,
            String chunkId,
            String termType,
            String canonical,
            String matchedText
    ) {
        String canonicalNormalized = canonical == null ? "" : canonical.trim();
        String normalized = canonicalNormalized.toLowerCase().trim();
        if (normalized.isEmpty() || normalized.length() < 3 || normalized.length() > 120) {
            return;
        }
        if (normalized.chars().filter(ch -> ch == '.').count() > 6) {
            return;
        }
        String key = chunkId + "|" + termType + "|" + normalized;
        String matched = (matchedText == null || matchedText.isBlank()) ? canonicalNormalized : matchedText.trim();
        bucket.putIfAbsent(
                key,
                new AnchorCandidate(
                        documentId,
                        chunkId,
                        termType,
                        canonicalNormalized,
                        normalized,
                        matched
                )
        );
    }

    private String readRequiredText(Map<String, Object> payload, String key) {
        Object value = payload.get(key);
        if (value == null) {
            throw new IllegalStateException("Missing required field from pipeline candidate: " + key);
        }
        String text = String.valueOf(value).trim();
        if (text.isBlank()) {
            throw new IllegalStateException("Blank required field from pipeline candidate: " + key);
        }
        return text;
    }

    private String readOptionalText(Map<String, Object> payload, String key) {
        Object value = payload.get(key);
        return value == null ? "" : String.valueOf(value).trim();
    }

    private void cleanupTempDirectory(Path tempDir) {
        if (tempDir == null || !Files.exists(tempDir)) {
            return;
        }
        try (Stream<Path> pathStream = Files.walk(tempDir)) {
            pathStream.sorted((left, right) -> right.compareTo(left)).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                }
            });
        } catch (IOException ignored) {
        }
    }

    private String excerpt(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String normalized = text.replace("\r", "").replace('\n', ' ').trim();
        if (normalized.length() <= OUTPUT_EXCERPT_MAX_CHARS) {
            return normalized;
        }
        return normalized.substring(0, OUTPUT_EXCERPT_MAX_CHARS) + "...";
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
