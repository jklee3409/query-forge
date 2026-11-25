package io.queryforge.backend.admin.corpus.anchor.extraction.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.queryforge.backend.admin.corpus.anchor.extraction.dto.AnchorExtractionDtos.AnchorCandidate;
import io.queryforge.backend.admin.corpus.anchor.extraction.dto.AnchorExtractionDtos.AnchorChunkRow;
import io.queryforge.backend.admin.corpus.anchor.extraction.dto.AnchorExtractionDtos.AnchorRemapSummary;
import io.queryforge.backend.admin.corpus.anchor.extraction.dto.AnchorExtractionDtos.TermRefreshSummary;
import io.queryforge.backend.admin.corpus.anchor.extraction.dto.AnchorExtractionDtos;
import io.queryforge.backend.admin.corpus.anchor.extraction.repository.AnchorExtractionRepository;
import io.queryforge.backend.admin.pipeline.configuration.AdminPipelineProperties;
import io.queryforge.backend.admin.pipeline.source.service.SourceCatalogService;
import lombok.RequiredArgsConstructor;
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

    private final AnchorExtractionRepository repository;
    private final ObjectMapper objectMapper;
    private final AdminPipelineProperties pipelineProperties;
    private final SourceCatalogService sourceCatalogService;

    @Transactional
    public AnchorExtractionDtos.AnchorExtractResponse extractAnchors(AnchorExtractionDtos.AnchorExtractRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("anchor extract request is required");
        }
        List<String> requestedDocumentIds = request.documentIds();
        List<String> requestedChunkIds = request.chunkIds();
        List<String> documentIds = normalizeIds(requestedDocumentIds);
        List<String> chunkIds = normalizeIds(requestedChunkIds);
        List<AnchorChunkRow> targetChunks = repository.findTargetChunks(documentIds, chunkIds);
        if (targetChunks.isEmpty()) {
            throw new IllegalArgumentException("No target chunks found for anchor extraction.");
        }

        List<String> targetChunkIds = targetChunks.stream().map(AnchorChunkRow::chunkId).toList();
        Set<UUID> touchedTermIds = new LinkedHashSet<>(repository.findTermIdsByChunkIds(targetChunkIds));
        int deletedEvidenceCount = repository.deleteEvidenceByChunkIds(targetChunkIds);

        List<AnchorCandidate> candidates = extractCandidates(targetChunks);
        int insertedEvidenceCount = 0;
        for (AnchorCandidate candidate : candidates) {
            UUID termId = repository.upsertGlossaryTerm(candidate);
            touchedTermIds.add(termId);
            insertedEvidenceCount += repository.insertEvidence(termId, candidate);
        }

        TermRefreshSummary termRefreshSummary = repository.refreshTouchedTerms(touchedTermIds);
        AnchorRemapSummary remapSummary = repository.remapSyntheticQueryAnchors(targetChunkIds);
        return new AnchorExtractionDtos.AnchorExtractResponse(
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

}
