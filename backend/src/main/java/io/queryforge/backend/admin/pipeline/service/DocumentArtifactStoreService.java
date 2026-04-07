package io.queryforge.backend.admin.pipeline.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.queryforge.backend.admin.pipeline.config.AdminPipelineProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DocumentArtifactStoreService {

    private final AdminPipelineProperties properties;
    private final SourceCatalogService sourceCatalogService;
    private final ObjectMapper objectMapper;

    public List<String> resolveAllDocumentIds() {
        return loadManifests().values().stream()
                .filter(manifest -> hasStage(manifest.raw()))
                .sorted(manifestComparator())
                .map(DocumentManifest::documentId)
                .toList();
    }

    public List<String> resolveDocumentIdsBySource(Collection<String> sourceIds) {
        if (sourceIds == null || sourceIds.isEmpty()) {
            return resolveAllDocumentIds();
        }
        Set<String> requestedSourceIds = new LinkedHashSet<>(sourceIds);
        return loadManifests().values().stream()
                .filter(manifest -> requestedSourceIds.contains(manifest.sourceId()))
                .filter(manifest -> hasStage(manifest.raw()))
                .sorted(manifestComparator())
                .map(DocumentManifest::documentId)
                .toList();
    }

    public List<String> selectDocumentsForNormalize(Collection<String> candidateDocumentIds) {
        return selectDocuments(candidateDocumentIds, Stage.NORMALIZED);
    }

    public List<String> selectDocumentsForChunk(Collection<String> candidateDocumentIds) {
        return selectDocuments(candidateDocumentIds, Stage.CHUNKED);
    }

    public List<String> selectDocumentsForGlossary(Collection<String> candidateDocumentIds) {
        return selectDocuments(candidateDocumentIds, Stage.GLOSSARY);
    }

    public List<String> selectDocumentsForImport(Collection<String> candidateDocumentIds) {
        return streamCandidateManifests(candidateDocumentIds)
                .filter(manifest -> hasStage(manifest.raw()))
                .filter(manifest -> hasStage(manifest.normalized()))
                .filter(manifest -> hasStage(manifest.chunked()))
                .sorted(manifestComparator())
                .map(DocumentManifest::documentId)
                .toList();
    }

    public void materializeRawArtifacts(Collection<String> documentIds, Path outputPath) {
        materializeJsonl(documentIds, outputPath, Stage.RAW);
    }

    public void materializeNormalizedArtifacts(Collection<String> documentIds, Path outputPath) {
        materializeJsonl(documentIds, outputPath, Stage.NORMALIZED);
    }

    public void materializeChunkArtifacts(Collection<String> documentIds, Path outputPath) {
        materializeJsonl(documentIds, outputPath, Stage.CHUNKED);
    }

    public void materializeGlossaryArtifacts(Collection<String> documentIds, Path outputPath) {
        Map<String, AggregatedGlossaryRow> aggregated = new LinkedHashMap<>();
        for (DocumentManifest manifest : streamCandidateManifests(documentIds).sorted(manifestComparator()).toList()) {
            if (!hasStage(manifest.glossary())) {
                continue;
            }
            for (String line : readJsonlLines(resolveArtifactPath(manifest.glossary().path()))) {
                JsonNode row = readTree(line);
                String key = glossaryKey(row);
                AggregatedGlossaryRow bucket = aggregated.get(key);
                if (bucket == null) {
                    aggregated.put(key, new AggregatedGlossaryRow(row));
                } else {
                    bucket.merge(row);
                }
            }
        }
        List<String> lines = aggregated.values().stream()
                .map(AggregatedGlossaryRow::toJson)
                .sorted()
                .toList();
        writeLines(outputPath, lines);
    }

    @Transactional
    public PersistResult persistRawArtifacts(Path workspaceRawPath, UUID runId) {
        if (!Files.exists(workspaceRawPath)) {
            writeLines(workspaceRawPath, List.of());
            refreshRawSnapshot();
            return PersistResult.empty();
        }

        Map<String, JsonNode> rowsByDocument = new LinkedHashMap<>();
        for (String line : readJsonlLines(workspaceRawPath)) {
            JsonNode node = readTree(line);
            rowsByDocument.put(node.path("document_id").asText(), node);
        }

        Map<String, DocumentManifest> manifests = loadManifests();
        List<String> changedDocumentIds = new ArrayList<>();
        List<String> changedLines = new ArrayList<>();

        for (Map.Entry<String, JsonNode> entry : rowsByDocument.entrySet()) {
            JsonNode node = entry.getValue();
            DocumentManifest existing = manifests.get(entry.getKey());
            String rawChecksum = node.path("content_hash").asText();
            if (existing != null && hasStage(existing.raw()) && Objects.equals(existing.raw().checksum(), rawChecksum)) {
                continue;
            }

            Path documentDirectory = documentDirectory(
                    node.path("source_id").asText(),
                    nullableText(node, "version_if_available"),
                    entry.getKey()
            );
            Path rawPath = documentDirectory.resolve("raw.json");
            writeLines(rawPath, List.of(toJson(node)));

            DocumentManifest updated = new DocumentManifest(
                    entry.getKey(),
                    node.path("source_id").asText(),
                    nullableText(node, "product"),
                    nullableText(node, "version_if_available"),
                    nullableText(node, "canonical_url"),
                    nullableText(node, "versioned_url"),
                    nullableText(node, "title"),
                    stageMetadata(rawPath, rawChecksum, null, 1, runId),
                    existing == null ? null : existing.normalized(),
                    existing == null ? null : existing.chunked(),
                    existing == null ? null : existing.glossary()
            );
            writeManifest(updated);
            manifests.put(entry.getKey(), updated);
            changedDocumentIds.add(entry.getKey());
            changedLines.add(toJson(node));
        }

        writeLines(workspaceRawPath, changedLines);
        refreshRawSnapshot();
        return new PersistResult(changedDocumentIds, rowsByDocument.size(), changedDocumentIds.size());
    }

    @Transactional
    public PersistResult persistNormalizedArtifacts(Path workspaceSectionsPath, UUID runId) {
        if (!Files.exists(workspaceSectionsPath)) {
            writeLines(workspaceSectionsPath, List.of());
            refreshSectionsSnapshot();
            return PersistResult.empty();
        }

        Map<String, List<String>> groupedLines = groupJsonlByDocumentId(workspaceSectionsPath);
        if (groupedLines.isEmpty()) {
            refreshSectionsSnapshot();
            return PersistResult.empty();
        }

        Map<String, DocumentManifest> manifests = loadManifests();
        List<String> processedDocumentIds = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : groupedLines.entrySet()) {
            DocumentManifest manifest = requireManifestWithStage(manifests, entry.getKey(), Stage.RAW);
            Path sectionsPath = documentDirectory(manifest.sourceId(), manifest.versionLabel(), manifest.documentId())
                    .resolve("sections.jsonl");
            String normalizedContent = joinLines(entry.getValue());
            writeLines(sectionsPath, entry.getValue());
            DocumentManifest updated = manifest.withNormalized(
                    stageMetadata(
                            sectionsPath,
                            checksum(normalizedContent),
                            manifest.raw().checksum(),
                            entry.getValue().size(),
                            runId
                    )
            );
            writeManifest(updated);
            manifests.put(entry.getKey(), updated);
            processedDocumentIds.add(entry.getKey());
        }

        refreshSectionsSnapshot();
        return new PersistResult(processedDocumentIds, groupedLines.size(), processedDocumentIds.size());
    }

    @Transactional
    public PersistResult persistChunkArtifacts(
            Path workspaceChunksPath,
            Path workspaceGlossaryPath,
            UUID runId
    ) {
        if (!Files.exists(workspaceChunksPath)) {
            writeLines(workspaceChunksPath, List.of());
            if (!Files.exists(workspaceGlossaryPath)) {
                writeLines(workspaceGlossaryPath, List.of());
            }
            refreshChunksSnapshot();
            refreshGlossarySnapshot();
            return PersistResult.empty();
        }

        Map<String, List<String>> chunkLinesByDocument = groupJsonlByDocumentId(workspaceChunksPath);
        Map<String, List<String>> glossaryLinesByDocument = splitGlossaryByDocument(workspaceGlossaryPath);
        if (chunkLinesByDocument.isEmpty()) {
            refreshChunksSnapshot();
            refreshGlossarySnapshot();
            return PersistResult.empty();
        }

        Map<String, DocumentManifest> manifests = loadManifests();
        List<String> processedDocumentIds = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : chunkLinesByDocument.entrySet()) {
            DocumentManifest manifest = requireManifestWithStage(manifests, entry.getKey(), Stage.NORMALIZED);
            Path documentDirectory = documentDirectory(manifest.sourceId(), manifest.versionLabel(), manifest.documentId());
            Path chunksPath = documentDirectory.resolve("chunks.jsonl");
            Path glossaryPath = documentDirectory.resolve("glossary_terms.jsonl");
            List<String> glossaryLines = glossaryLinesByDocument.getOrDefault(entry.getKey(), List.of());
            writeLines(chunksPath, entry.getValue());
            writeLines(glossaryPath, glossaryLines);

            DocumentManifest updated = manifest
                    .withChunked(stageMetadata(
                            chunksPath,
                            checksum(joinLines(entry.getValue())),
                            manifest.normalized().checksum(),
                            entry.getValue().size(),
                            runId
                    ))
                    .withGlossary(stageMetadata(
                            glossaryPath,
                            checksum(joinLines(glossaryLines)),
                            manifest.normalized().checksum(),
                            glossaryLines.size(),
                            runId
                    ));
            writeManifest(updated);
            manifests.put(entry.getKey(), updated);
            processedDocumentIds.add(entry.getKey());
        }

        refreshChunksSnapshot();
        refreshGlossarySnapshot();
        return new PersistResult(processedDocumentIds, chunkLinesByDocument.size(), processedDocumentIds.size());
    }

    @Transactional
    public PersistResult persistGlossaryArtifacts(Path workspaceGlossaryPath, UUID runId) {
        if (!Files.exists(workspaceGlossaryPath)) {
            writeLines(workspaceGlossaryPath, List.of());
            refreshGlossarySnapshot();
            return PersistResult.empty();
        }

        Map<String, List<String>> glossaryLinesByDocument = splitGlossaryByDocument(workspaceGlossaryPath);
        if (glossaryLinesByDocument.isEmpty()) {
            refreshGlossarySnapshot();
            return PersistResult.empty();
        }

        Map<String, DocumentManifest> manifests = loadManifests();
        List<String> processedDocumentIds = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : glossaryLinesByDocument.entrySet()) {
            DocumentManifest manifest = requireManifestWithStage(manifests, entry.getKey(), Stage.NORMALIZED);
            Path glossaryPath = documentDirectory(manifest.sourceId(), manifest.versionLabel(), manifest.documentId())
                    .resolve("glossary_terms.jsonl");
            writeLines(glossaryPath, entry.getValue());
            DocumentManifest updated = manifest.withGlossary(stageMetadata(
                    glossaryPath,
                    checksum(joinLines(entry.getValue())),
                    manifest.normalized().checksum(),
                    entry.getValue().size(),
                    runId
            ));
            writeManifest(updated);
            manifests.put(entry.getKey(), updated);
            processedDocumentIds.add(entry.getKey());
        }

        refreshGlossarySnapshot();
        return new PersistResult(processedDocumentIds, glossaryLinesByDocument.size(), processedDocumentIds.size());
    }

    public record PersistResult(
            List<String> documentIds,
            int discoveredDocumentCount,
            int persistedDocumentCount
    ) {
        static PersistResult empty() {
            return new PersistResult(List.of(), 0, 0);
        }
    }

    private enum Stage {
        RAW,
        NORMALIZED,
        CHUNKED,
        GLOSSARY
    }

    private record StageMetadata(
            String path,
            String checksum,
            String sourceChecksum,
            int rowCount,
            UUID runId,
            Instant updatedAt
    ) {
    }

    private record DocumentManifest(
            String documentId,
            String sourceId,
            String product,
            String versionLabel,
            String canonicalUrl,
            String versionedUrl,
            String title,
            StageMetadata raw,
            StageMetadata normalized,
            StageMetadata chunked,
            StageMetadata glossary
    ) {
        private DocumentManifest withNormalized(StageMetadata value) {
            return new DocumentManifest(documentId, sourceId, product, versionLabel, canonicalUrl, versionedUrl, title, raw, value, chunked, glossary);
        }

        private DocumentManifest withChunked(StageMetadata value) {
            return new DocumentManifest(documentId, sourceId, product, versionLabel, canonicalUrl, versionedUrl, title, raw, normalized, value, glossary);
        }

        private DocumentManifest withGlossary(StageMetadata value) {
            return new DocumentManifest(documentId, sourceId, product, versionLabel, canonicalUrl, versionedUrl, title, raw, normalized, chunked, value);
        }
    }

    private List<String> selectDocuments(Collection<String> candidateDocumentIds, Stage targetStage) {
        return streamCandidateManifests(candidateDocumentIds)
                .filter(manifest -> requiresExecution(manifest, targetStage))
                .sorted(manifestComparator())
                .map(DocumentManifest::documentId)
                .toList();
    }

    private boolean requiresExecution(DocumentManifest manifest, Stage targetStage) {
        return switch (targetStage) {
            case NORMALIZED -> hasStage(manifest.raw())
                    && (!hasStage(manifest.normalized()) || !Objects.equals(manifest.normalized().sourceChecksum(), manifest.raw().checksum()));
            case CHUNKED -> hasStage(manifest.normalized())
                    && (!hasStage(manifest.chunked()) || !Objects.equals(manifest.chunked().sourceChecksum(), manifest.normalized().checksum()));
            case GLOSSARY -> hasStage(manifest.normalized())
                    && (!hasStage(manifest.glossary()) || !Objects.equals(manifest.glossary().sourceChecksum(), manifest.normalized().checksum()));
            default -> false;
        };
    }

    private void materializeJsonl(Collection<String> documentIds, Path outputPath, Stage stage) {
        List<String> lines = streamCandidateManifests(documentIds)
                .sorted(manifestComparator())
                .flatMap(manifest -> readJsonlLines(resolveStagePath(manifest, stage)).stream())
                .toList();
        writeLines(outputPath, lines);
    }

    private Map<String, DocumentManifest> loadManifests() {
        Path artifactRoot = artifactStoreRoot();
        if (!Files.exists(artifactRoot)) {
            return new LinkedHashMap<>();
        }
        try (var paths = Files.walk(artifactRoot)) {
            return paths
                    .filter(path -> path.getFileName().toString().equals("manifest.json"))
                    .map(this::readManifest)
                    .collect(Collectors.toMap(
                            DocumentManifest::documentId,
                            manifest -> manifest,
                            (left, right) -> right,
                            LinkedHashMap::new
                    ));
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to scan document artifact store.", exception);
        }
    }

    private DocumentManifest readManifest(Path manifestPath) {
        try {
            return objectMapper.readValue(Files.readString(manifestPath, StandardCharsets.UTF_8), DocumentManifest.class);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to read artifact manifest: " + manifestPath, exception);
        }
    }

    private void writeManifest(DocumentManifest manifest) {
        Path manifestPath = documentDirectory(manifest.sourceId(), manifest.versionLabel(), manifest.documentId())
                .resolve("manifest.json");
        try {
            Files.createDirectories(manifestPath.getParent());
            Files.writeString(
                    manifestPath,
                    objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(manifest),
                    StandardCharsets.UTF_8
            );
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to write artifact manifest: " + manifestPath, exception);
        }
    }

    private Map<String, List<String>> groupJsonlByDocumentId(Path jsonlPath) {
        Map<String, List<String>> grouped = new LinkedHashMap<>();
        for (String line : readJsonlLines(jsonlPath)) {
            JsonNode node = readTree(line);
            grouped.computeIfAbsent(node.path("document_id").asText(), ignored -> new ArrayList<>())
                    .add(toJson(node));
        }
        return grouped;
    }

    private Map<String, List<String>> splitGlossaryByDocument(Path glossaryPath) {
        Map<String, List<String>> byDocument = new LinkedHashMap<>();
        if (!Files.exists(glossaryPath)) {
            return byDocument;
        }
        for (String line : readJsonlLines(glossaryPath)) {
            JsonNode node = readTree(line);
            JsonNode documentIdsNode = node.path("metadata").path("document_ids");
            if (!documentIdsNode.isArray()) {
                continue;
            }
            for (JsonNode documentIdNode : documentIdsNode) {
                String documentId = documentIdNode.asText();
                if (documentId == null || documentId.isBlank()) {
                    continue;
                }
                @SuppressWarnings("unchecked")
                Map<String, Object> row = objectMapper.convertValue(node, Map.class);
                Map<String, Object> metadata = new LinkedHashMap<>();
                Object rawMetadata = row.get("metadata");
                if (rawMetadata instanceof Map<?, ?> metadataMap) {
                    metadataMap.forEach((key, value) -> metadata.put(String.valueOf(key), value));
                }
                metadata.put("document_ids", List.of(documentId));
                metadata.put("evidence_count", 1);
                if (!metadata.containsKey("source_products")) {
                    metadata.put("source_products", List.of());
                }
                row.put("metadata", metadata);
                byDocument.computeIfAbsent(documentId, ignored -> new ArrayList<>())
                        .add(toJson(row));
            }
        }
        return byDocument;
    }

    private void refreshRawSnapshot() {
        materializeRawArtifacts(resolveAllDocumentIds(), sourceCatalogService.resolveWithinRepo(
                properties.rawOutputPath(),
                "raw snapshot path"
        ));
    }

    private void refreshSectionsSnapshot() {
        materializeNormalizedArtifacts(resolveAllDocumentIds(), sourceCatalogService.resolveWithinRepo(
                properties.sectionsOutputPath(),
                "sections snapshot path"
        ));
    }

    private void refreshChunksSnapshot() {
        materializeChunkArtifacts(resolveAllDocumentIds(), sourceCatalogService.resolveWithinRepo(
                properties.chunksOutputPath(),
                "chunks snapshot path"
        ));
    }

    private void refreshGlossarySnapshot() {
        materializeGlossaryArtifacts(resolveAllDocumentIds(), sourceCatalogService.resolveWithinRepo(
                properties.glossaryOutputPath(),
                "glossary snapshot path"
        ));
    }

    private Path resolveStagePath(DocumentManifest manifest, Stage stage) {
        StageMetadata metadata = switch (stage) {
            case RAW -> manifest.raw();
            case NORMALIZED -> manifest.normalized();
            case CHUNKED -> manifest.chunked();
            case GLOSSARY -> manifest.glossary();
        };
        if (!hasStage(metadata)) {
            return null;
        }
        return resolveArtifactPath(metadata.path());
    }

    private Path resolveArtifactPath(String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            return null;
        }
        return sourceCatalogService.resolveWithinRepo(relativePath, "document artifact path");
    }

    private DocumentManifest requireManifestWithStage(
            Map<String, DocumentManifest> manifests,
            String documentId,
            Stage requiredStage
    ) {
        DocumentManifest manifest = manifests.get(documentId);
        if (manifest == null) {
            throw new IllegalStateException("Document artifact manifest not found for " + documentId);
        }
        StageMetadata metadata = switch (requiredStage) {
            case RAW -> manifest.raw();
            case NORMALIZED -> manifest.normalized();
            case CHUNKED -> manifest.chunked();
            case GLOSSARY -> manifest.glossary();
        };
        if (!hasStage(metadata)) {
            throw new IllegalStateException("Document artifact stage is missing for " + documentId + ": " + requiredStage.name().toLowerCase(Locale.ROOT));
        }
        return manifest;
    }

    private boolean hasStage(StageMetadata metadata) {
        return metadata != null && metadata.path() != null && !metadata.path().isBlank();
    }

    private Path artifactStoreRoot() {
        return sourceCatalogService.resolveWithinRepo(properties.artifactStoreDir(), "document artifact store root");
    }

    private Path documentDirectory(String sourceId, String versionLabel, String documentId) {
        return artifactStoreRoot()
                .resolve(sanitizePathSegment(sourceId, "unknown-source"))
                .resolve(sanitizePathSegment(versionLabel, "no-version"))
                .resolve(sanitizePathSegment(documentId, "unknown-document"));
    }

    private String sanitizePathSegment(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        String sanitized = value.trim().replaceAll("[^A-Za-z0-9._-]+", "-");
        sanitized = sanitized.replaceAll("-{2,}", "-");
        sanitized = sanitized.replaceAll("^[.-]+|[.-]+$", "");
        return sanitized.isBlank() ? fallback : sanitized;
    }

    private StageMetadata stageMetadata(
            Path artifactPath,
            String checksum,
            String sourceChecksum,
            int rowCount,
            UUID runId
    ) {
        return new StageMetadata(
                repoRelativePath(artifactPath),
                checksum,
                sourceChecksum,
                rowCount,
                runId,
                Instant.now()
        );
    }

    private String repoRelativePath(Path path) {
        return sourceCatalogService.repoRoot().relativize(path.toAbsolutePath().normalize()).toString().replace('\\', '/');
    }

    private List<String> readJsonlLines(Path path) {
        if (path == null || !Files.exists(path)) {
            return List.of();
        }
        try {
            return Files.readAllLines(path, StandardCharsets.UTF_8).stream()
                    .map(String::trim)
                    .filter(line -> !line.isBlank())
                    .toList();
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to read JSONL artifact: " + path, exception);
        }
    }

    private void writeLines(Path path, List<String> lines) {
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, joinLines(lines), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to write artifact file: " + path, exception);
        }
    }

    private String joinLines(List<String> lines) {
        if (lines == null || lines.isEmpty()) {
            return "";
        }
        return String.join(System.lineSeparator(), lines) + System.lineSeparator();
    }

    private String checksum(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            byte[] bytes = digest.digest(content.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder();
            for (byte value : bytes) {
                builder.append(String.format("%02x", value));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-1 digest is not available.", exception);
        }
    }

    private JsonNode readTree(String line) {
        try {
            return objectMapper.readTree(line);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to parse JSON artifact line.", exception);
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to serialize artifact JSON.", exception);
        }
    }

    private String nullableText(JsonNode node, String fieldName) {
        JsonNode child = node.path(fieldName);
        if (child.isMissingNode() || child.isNull()) {
            return null;
        }
        String value = child.asText();
        return value == null || value.isBlank() ? null : value;
    }

    private String glossaryKey(JsonNode row) {
        return row.path("term_type").asText().trim().toLowerCase(Locale.ROOT)
                + "::"
                + row.path("canonical_form").asText().trim().toLowerCase(Locale.ROOT);
    }

    private Comparator<DocumentManifest> manifestComparator() {
        return Comparator
                .comparing(DocumentManifest::sourceId, Comparator.nullsLast(String::compareTo))
                .thenComparing(DocumentManifest::versionLabel, Comparator.nullsLast(String::compareTo))
                .thenComparing(DocumentManifest::documentId);
    }

    private java.util.stream.Stream<DocumentManifest> streamCandidateManifests(Collection<String> candidateDocumentIds) {
        Map<String, DocumentManifest> manifests = loadManifests();
        if (candidateDocumentIds == null || candidateDocumentIds.isEmpty()) {
            return manifests.values().stream();
        }
        LinkedHashSet<String> requested = new LinkedHashSet<>(candidateDocumentIds);
        return requested.stream()
                .map(manifests::get)
                .filter(Objects::nonNull);
    }

    private final class AggregatedGlossaryRow {
        private final Map<String, Object> row;
        private final LinkedHashSet<String> aliases = new LinkedHashSet<>();
        private final LinkedHashSet<String> sourceProducts = new LinkedHashSet<>();
        private final LinkedHashSet<String> documentIds = new LinkedHashSet<>();
        private int evidenceCount;
        private String sourceProduct;

        @SuppressWarnings("unchecked")
        private AggregatedGlossaryRow(JsonNode initial) {
            this.row = objectMapper.convertValue(initial, Map.class);
            this.sourceProduct = stringOrNull(row.get("source_product"));
            merge(initial);
        }

        @SuppressWarnings("unchecked")
        private void merge(JsonNode node) {
            Map<String, Object> incoming = objectMapper.convertValue(node, Map.class);
            aliases.addAll(asStringList(incoming.get("aliases")));
            Map<String, Object> metadata = incoming.get("metadata") instanceof Map<?, ?> map
                    ? (Map<String, Object>) map
                    : Map.of();
            sourceProducts.addAll(asStringList(metadata.get("source_products")));
            documentIds.addAll(asStringList(metadata.get("document_ids")));
            evidenceCount += integerValue(metadata.get("evidence_count"));
            String incomingSourceProduct = stringOrNull(incoming.get("source_product"));
            if (sourceProduct == null) {
                sourceProduct = incomingSourceProduct;
            } else if (!Objects.equals(sourceProduct, incomingSourceProduct)) {
                sourceProduct = null;
            }
        }

        private String toJson() {
            row.put("aliases", List.copyOf(aliases));
            row.put("source_product", sourceProduct);
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("source_products", List.copyOf(sourceProducts));
            metadata.put("document_ids", List.copyOf(documentIds));
            metadata.put("evidence_count", evidenceCount);
            row.put("metadata", metadata);
            return DocumentArtifactStoreService.this.toJson(row);
        }

        private List<String> asStringList(Object value) {
            if (!(value instanceof Collection<?> collection)) {
                return List.of();
            }
            return collection.stream()
                    .filter(Objects::nonNull)
                    .map(String::valueOf)
                    .toList();
        }

        private int integerValue(Object value) {
            if (value == null) {
                return 0;
            }
            try {
                return Integer.parseInt(String.valueOf(value));
            } catch (NumberFormatException exception) {
                return 0;
            }
        }

        private String stringOrNull(Object value) {
            if (value == null) {
                return null;
            }
            String text = String.valueOf(value).trim();
            return text.isBlank() ? null : text;
        }
    }
}
