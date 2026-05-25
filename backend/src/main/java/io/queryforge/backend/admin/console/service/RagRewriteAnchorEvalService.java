package io.queryforge.backend.admin.console.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.queryforge.backend.admin.console.model.AdminConsoleDtos;
import io.queryforge.backend.admin.console.repository.AdminConsoleRepository;
import io.queryforge.backend.admin.pipeline.config.AdminPipelineProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class RagRewriteAnchorEvalService {

    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final Pattern NON_COMPACT_ANCHOR = Pattern.compile("[^\\p{L}\\p{N}]+");
    private static final List<String> SOURCE_PRIORITY = List.of(
            "added_by_rewrite",
            "preserved_raw",
            "canonical",
            "multi_source",
            "injected_candidate",
            "glossary",
            "memory"
    );

    private final AdminConsoleRepository repository;
    private final AdminPipelineProperties pipelineProperties;
    private final ObjectMapper objectMapper;

    public List<AdminConsoleDtos.RagRewriteAnchorEvalRow> buildRowsForRun(
            UUID runId,
            String experimentName,
            List<AdminConsoleDtos.RagTestResultDetailRow> details
    ) {
        if (runId == null || experimentName == null || experimentName.isBlank() || details == null || details.isEmpty()) {
            return List.of();
        }
        JsonNode caseRoot = readRewriteCases(experimentName).orElse(null);
        if (caseRoot == null || !caseRoot.isArray() || caseRoot.isEmpty()) {
            return List.of();
        }

        Map<String, JsonNode> caseRowsByKey = new LinkedHashMap<>();
        Set<String> sampleIds = new LinkedHashSet<>();
        Set<String> allChunkIds = new LinkedHashSet<>();
        Set<String> allDocIds = new LinkedHashSet<>();
        Set<UUID> allMemoryIds = new LinkedHashSet<>();
        for (JsonNode row : caseRoot) {
            String sampleId = text(row.path("sample_id"));
            String mode = text(row.path("mode"));
            if (sampleId.isBlank() || mode.isBlank() || !row.path("rewrite_applied").asBoolean(false)) {
                continue;
            }
            caseRowsByKey.put(detailKey(sampleId, mode), row);
            sampleIds.add(sampleId);
            collectRetrievedChunkIds(row, allChunkIds);
            collectMemoryIds(row.path("memory_top_n"), allMemoryIds);
            collectMemoryIds(row.path("top_memory_candidates"), allMemoryIds);
        }
        if (caseRowsByKey.isEmpty()) {
            return List.of();
        }

        Map<String, AdminConsoleRepository.EvalDatasetAnchorMeta> sampleMeta = new LinkedHashMap<>();
        for (AdminConsoleRepository.EvalDatasetAnchorMeta meta : repository.findEvalDatasetAnchorMeta(runId, new ArrayList<>(sampleIds))) {
            sampleMeta.put(meta.sampleId(), meta);
            allChunkIds.addAll(jsonStringList(meta.expectedChunkIds()));
            allDocIds.addAll(jsonStringList(meta.expectedDocIds()));
        }
        Map<String, String> chunkTexts = repository.findChunkTexts(new ArrayList<>(allChunkIds));
        Map<String, String> docTexts = repository.findDocumentTexts(new ArrayList<>(allDocIds));
        Map<UUID, AdminConsoleRepository.MemoryAnchorSource> memorySources =
                repository.findMemoryAnchorSources(new ArrayList<>(allMemoryIds));

        List<AdminConsoleDtos.RagRewriteAnchorEvalRow> rows = new ArrayList<>();
        for (AdminConsoleDtos.RagTestResultDetailRow detail : details) {
            if (detail == null || detail.detailId() == null || !Boolean.TRUE.equals(detail.rewriteApplied())) {
                continue;
            }
            String mode = text(detail.metricContribution().path("mode"));
            JsonNode caseRow = caseRowsByKey.get(detailKey(detail.sampleId(), mode));
            if (caseRow == null) {
                continue;
            }
            rows.addAll(buildRowsForDetail(detail, caseRow, sampleMeta.get(detail.sampleId()), chunkTexts, docTexts, memorySources));
        }
        return rows;
    }

    private List<AdminConsoleDtos.RagRewriteAnchorEvalRow> buildRowsForDetail(
            AdminConsoleDtos.RagTestResultDetailRow detail,
            JsonNode caseRow,
            AdminConsoleRepository.EvalDatasetAnchorMeta meta,
            Map<String, String> chunkTexts,
            Map<String, String> docTexts,
            Map<UUID, AdminConsoleRepository.MemoryAnchorSource> memorySources
    ) {
        String rawQuery = firstNonBlank(detail.rawQuery(), text(caseRow.path("raw_query")));
        String finalQuery = firstNonBlank(detail.rewriteQuery(), text(caseRow.path("final_query")), rawQuery);
        JsonNode selectedRewrite = caseRow.path("selected_rewrite");
        int selectedMemoryIndex = selectedRewrite.path("source_memory_index").asInt(0);
        JsonNode selectedMemory = memoryAtIndex(caseRow.path("memory_top_n"), selectedMemoryIndex);
        Map<String, AnchorDraft> anchors = new LinkedHashMap<>();

        addAnchorObjects(anchors, caseRow.path("anchor_candidates"), "injected_candidate");
        addTermHints(anchors, caseRow.path("terminology_hints"));
        addCanonicalHints(anchors, caseRow.path("canonical_anchor_hints"));
        addMultiSourceHints(anchors, caseRow.path("multi_source_anchor_hints"));
        addRewriteAnchorTerms(anchors, selectedRewrite, true);
        addCandidateAnchorTerms(anchors, caseRow.path("rewrite_candidates"));
        addMemoryEvidenceAnchors(anchors, selectedMemory, selectedMemoryIndex);

        List<String> expectedChunkIds = meta == null ? List.of() : jsonStringList(meta.expectedChunkIds());
        List<String> expectedDocIds = meta == null ? List.of() : jsonStringList(meta.expectedDocIds());
        List<String> retrievedChunkIds = collectRetrievedChunkIds(caseRow);
        List<String> expectedChunkTexts = textsForIds(expectedChunkIds, chunkTexts);
        List<String> expectedDocTexts = textsForIds(expectedDocIds, docTexts);
        List<String> retrievedChunkTexts = textsForIds(retrievedChunkIds, chunkTexts);
        String selectedMemoryText = memoryEvidenceText(selectedMemory, memorySources);
        UUID selectedMemoryId = parseUuid(text(firstNode(selectedMemory, "memory_id", "id")));
        AdminConsoleRepository.MemoryAnchorSource selectedMemorySource = selectedMemoryId == null
                ? null
                : memorySources.get(selectedMemoryId);

        return anchors.values().stream()
                .filter(anchor -> !anchor.normalized.isBlank())
                .sorted(Comparator.comparing(anchor -> anchor.text.toLowerCase(Locale.ROOT)))
                .map(anchor -> evaluateAnchor(
                        detail,
                        meta,
                        anchor,
                        rawQuery,
                        finalQuery,
                        expectedChunkIds,
                        expectedDocIds,
                        retrievedChunkIds,
                        expectedChunkTexts,
                        expectedDocTexts,
                        retrievedChunkTexts,
                        selectedMemoryText,
                        memorySources,
                        selectedMemorySource
                ))
                .toList();
    }

    private AdminConsoleDtos.RagRewriteAnchorEvalRow evaluateAnchor(
            AdminConsoleDtos.RagTestResultDetailRow detail,
            AdminConsoleRepository.EvalDatasetAnchorMeta meta,
            AnchorDraft anchor,
            String rawQuery,
            String finalQuery,
            List<String> expectedChunkIds,
            List<String> expectedDocIds,
            List<String> retrievedChunkIds,
            List<String> expectedChunkTexts,
            List<String> expectedDocTexts,
            List<String> retrievedChunkTexts,
            String memoryEvidenceText,
            Map<UUID, AdminConsoleRepository.MemoryAnchorSource> memorySources,
            AdminConsoleRepository.MemoryAnchorSource selectedMemorySource
    ) {
        boolean appearsInRawQuery = containsAnchor(rawQuery, anchor.text) || containsAnchor(rawQuery, anchor.canonicalText);
        boolean appearsInFinalRewrite = containsAnchor(finalQuery, anchor.text) || containsAnchor(finalQuery, anchor.canonicalText);
        boolean appearsInExpectedChunk = containsAny(expectedChunkTexts, anchor);
        boolean appearsInExpectedDoc = containsAny(expectedDocTexts, anchor);
        boolean appearsInRetrievedChunk = containsAny(retrievedChunkTexts, anchor);
        boolean groundedByMemory = anchor.sources.contains("memory")
                || anchor.sources.contains("injected_candidate")
                || containsAnchor(memoryEvidenceText, anchor.text)
                || containsAnchor(memoryEvidenceText, anchor.canonicalText);
        boolean groundedByGlossary = anchor.sources.contains("glossary")
                || anchor.sources.contains("canonical")
                || !blank(anchor.canonicalText);

        double groundingScore = groundingScore(
                appearsInExpectedChunk,
                appearsInExpectedDoc,
                appearsInRetrievedChunk,
                groundedByMemory,
                groundedByGlossary
        );
        double intentRelevanceScore = intentRelevanceScore(
                appearsInRawQuery,
                appearsInFinalRewrite,
                appearsInExpectedChunk,
                appearsInExpectedDoc,
                appearsInRetrievedChunk,
                groundedByMemory,
                groundedByGlossary
        );
        double driftRiskScore = driftRiskScore(
                anchor.sources.contains("added_by_rewrite"),
                appearsInRawQuery,
                appearsInFinalRewrite,
                appearsInExpectedChunk,
                appearsInExpectedDoc,
                appearsInRetrievedChunk,
                groundedByMemory,
                groundedByGlossary
        );
        double overallScore = clamp(0.55 * groundingScore + 0.35 * intentRelevanceScore - 0.25 * driftRiskScore
                + (appearsInRawQuery && appearsInFinalRewrite ? 0.10 : 0.0));
        String label = labelFor(
                anchor,
                appearsInRawQuery,
                appearsInFinalRewrite,
                appearsInExpectedChunk,
                appearsInExpectedDoc,
                appearsInRetrievedChunk,
                groundedByMemory,
                groundedByGlossary,
                overallScore
        );
        String evidenceSummary = evidenceSummary(
                appearsInRawQuery,
                appearsInFinalRewrite,
                appearsInExpectedChunk,
                appearsInExpectedDoc,
                appearsInRetrievedChunk,
                groundedByMemory,
                groundedByGlossary
        );
        UUID sourceMemoryId = anchor.sourceMemoryId != null ? anchor.sourceMemoryId : parseUuid(text(firstNode(memoryAtIndex(detail.memoryCandidates(), anchor.sourceMemoryIndex), "memory_id", "id")));
        AdminConsoleRepository.MemoryAnchorSource anchorMemorySource = sourceMemoryId == null
                ? selectedMemorySource
                : memorySources.getOrDefault(sourceMemoryId, selectedMemorySource);
        String sourceMemoryQueryId = firstNonBlank(
                anchor.sourceMemoryQueryId,
                anchorMemorySource == null ? null : anchorMemorySource.sourceGatedQueryId()
        );

        return new AdminConsoleDtos.RagRewriteAnchorEvalRow(
                UUID.randomUUID(),
                detail.ragTestRunId(),
                detail.detailId(),
                detail.sampleId(),
                meta == null ? null : meta.datasetItemId(),
                text(detail.metricContribution().path("mode")),
                rawQuery,
                finalQuery,
                true,
                anchor.sourceMemoryIndex > 0 ? anchor.sourceMemoryIndex : null,
                anchor.text,
                anchor.normalized,
                blank(anchor.canonicalText) ? null : anchor.canonicalText,
                primarySource(anchor.sources),
                objectMapper.valueToTree(new ArrayList<>(anchor.sources)),
                appearsInRawQuery,
                appearsInFinalRewrite,
                appearsInExpectedChunk,
                appearsInExpectedDoc,
                appearsInRetrievedChunk,
                appearsInExpectedChunk,
                appearsInExpectedDoc,
                appearsInRetrievedChunk,
                groundedByMemory,
                groundedByGlossary,
                groundingScore,
                intentRelevanceScore,
                driftRiskScore,
                overallScore,
                label,
                evidenceSummary,
                objectMapper.valueToTree(expectedChunkIds),
                objectMapper.valueToTree(expectedDocIds),
                objectMapper.valueToTree(retrievedChunkIds),
                sourceMemoryId,
                blank(sourceMemoryQueryId) ? null : sourceMemoryQueryId,
                Instant.now()
        );
    }

    private void addAnchorObjects(
            Map<String, AnchorDraft> anchors,
            JsonNode node,
            String source
    ) {
        if (!node.isArray()) {
            return;
        }
        for (JsonNode item : node) {
            String anchorText = text(firstNode(item, "anchor", "term", "text", "canonical_form"));
            if (anchorText.isBlank()) {
                continue;
            }
            String itemSource = text(item.path("source"));
            String resolvedSource = switch (itemSource) {
                case "raw_query" -> "preserved_raw";
                case "memory_glossary" -> "glossary";
                case "memory_query" -> "memory";
                default -> source;
            };
            int itemMemoryIndex = item.hasNonNull("source_memory_index")
                    ? item.path("source_memory_index").asInt(0)
                    : 0;
            AnchorDraft draft = addAnchor(
                    anchors,
                    anchorText,
                    resolvedSource,
                    text(firstNode(item, "canonical_form", "canonical_anchor", "canonical_text")),
                    itemMemoryIndex
            );
            UUID memoryId = parseUuid(text(item.path("memory_id")));
            if (draft != null && memoryId != null) {
                draft.sourceMemoryId = memoryId;
            }
        }
    }

    private void addTermHints(Map<String, AnchorDraft> anchors, JsonNode node) {
        if (!node.isObject()) {
            return;
        }
        JsonNode sourceTerms = node.path("source_terms");
        if (sourceTerms.isObject()) {
            sourceTerms.fields().forEachRemaining(entry -> {
                String source = switch (entry.getKey()) {
                    case "raw_query" -> "preserved_raw";
                    case "memory_glossary" -> "glossary";
                    case "memory_query" -> "memory";
                    default -> "injected_candidate";
                };
                addStringArray(anchors, entry.getValue(), source, null, 0);
            });
            return;
        }
        addStringArray(anchors, node.path("terms"), "injected_candidate", null, 0);
    }

    private void addCanonicalHints(Map<String, AnchorDraft> anchors, JsonNode node) {
        if (!node.isObject()) {
            return;
        }
        JsonNode sourceTerms = node.path("source_terms");
        if (sourceTerms.isArray()) {
            for (JsonNode item : sourceTerms) {
                addAnchor(
                        anchors,
                        text(firstNode(item, "term", "canonical_form", "alias")),
                        "canonical",
                        text(firstNode(item, "canonical_form", "term")),
                        0
                );
            }
            return;
        }
        addStringArray(anchors, node.path("terms"), "canonical", null, 0);
    }

    private void addMultiSourceHints(Map<String, AnchorDraft> anchors, JsonNode node) {
        if (!node.isObject()) {
            return;
        }
        JsonNode anchorsNode = node.path("anchors");
        if (anchorsNode.isArray()) {
            for (JsonNode item : anchorsNode) {
                addAnchor(
                        anchors,
                        text(firstNode(item, "term", "canonical_form", "text")),
                        "multi_source",
                        text(item.path("normalized_form")),
                        0
                );
            }
            return;
        }
        addStringArray(anchors, node.path("terms"), "multi_source", null, 0);
    }

    private void addRewriteAnchorTerms(Map<String, AnchorDraft> anchors, JsonNode candidate, boolean selected) {
        if (!candidate.isObject()) {
            return;
        }
        int memoryIndex = candidate.path("source_memory_index").asInt(0);
        addStringArray(anchors, candidate.path("added_anchors"), "added_by_rewrite", null, memoryIndex);
        addStringArray(anchors, candidate.path("preserved_raw_terms"), "preserved_raw", null, memoryIndex);
        addStringArray(anchors, candidate.path("canonical_anchor_terms"), "canonical", null, memoryIndex);
        if (selected) {
            String query = text(candidate.path("query"));
            for (AnchorDraft draft : anchors.values()) {
                if (containsAnchor(query, draft.text)) {
                    draft.sources.add("selected_final_query");
                }
            }
        }
    }

    private void addCandidateAnchorTerms(Map<String, AnchorDraft> anchors, JsonNode candidates) {
        if (!candidates.isArray()) {
            return;
        }
        for (JsonNode candidate : candidates) {
            addRewriteAnchorTerms(anchors, candidate, false);
        }
    }

    private void addMemoryEvidenceAnchors(Map<String, AnchorDraft> anchors, JsonNode memory, int memoryIndex) {
        if (!memory.isObject()) {
            return;
        }
        addStringArray(anchors, memory.path("glossary_terms"), "glossary", null, memoryIndex);
        JsonNode canonicalAnchors = memory.path("canonical_anchors");
        if (canonicalAnchors.isArray()) {
            for (JsonNode item : canonicalAnchors) {
                String canonical = text(firstNode(item, "canonical_form", "display_alias", "normalized_alias"));
                String alias = text(firstNode(item, "display_alias", "normalized_alias", "canonical_form"));
                addAnchor(anchors, firstNonBlank(alias, canonical), "canonical", canonical, memoryIndex);
            }
        }
        UUID memoryId = parseUuid(text(firstNode(memory, "memory_id", "id")));
        String sourceQueryId = text(memory.path("source_gated_query_id"));
        if (memoryId == null && sourceQueryId.isBlank()) {
            return;
        }
        for (AnchorDraft draft : anchors.values()) {
            if (draft.sourceMemoryIndex == memoryIndex || draft.sourceMemoryIndex == 0) {
                if (memoryId != null && draft.sourceMemoryId == null) {
                    draft.sourceMemoryId = memoryId;
                }
                if (!sourceQueryId.isBlank() && blank(draft.sourceMemoryQueryId)) {
                    draft.sourceMemoryQueryId = sourceQueryId;
                }
            }
        }
    }

    private void addStringArray(
            Map<String, AnchorDraft> anchors,
            JsonNode node,
            String source,
            String canonicalText,
            int memoryIndex
    ) {
        if (!node.isArray()) {
            return;
        }
        for (JsonNode item : node) {
            String value = item.isTextual() ? item.asText("") : text(firstNode(item, "term", "anchor", "text"));
            addAnchor(anchors, value, source, canonicalText, memoryIndex);
        }
    }

    private AnchorDraft addAnchor(
            Map<String, AnchorDraft> anchors,
            String anchorText,
            String source,
            String canonicalText,
            int sourceMemoryIndex
    ) {
        String normalized = normalizeAnchor(anchorText);
        if (normalized.isBlank()) {
            return null;
        }
        AnchorDraft draft = anchors.computeIfAbsent(normalized, key -> new AnchorDraft(anchorText.strip(), key));
        draft.sources.add(source);
        if (!blank(canonicalText) && blank(draft.canonicalText)) {
            draft.canonicalText = canonicalText.strip();
        }
        if (sourceMemoryIndex > 0 && draft.sourceMemoryIndex == 0) {
            draft.sourceMemoryIndex = sourceMemoryIndex;
        }
        return draft;
    }

    private double groundingScore(
            boolean expectedChunk,
            boolean expectedDoc,
            boolean retrievedChunk,
            boolean memory,
            boolean glossary
    ) {
        if (expectedChunk) {
            return 1.0;
        }
        if (expectedDoc) {
            return 0.75;
        }
        if (retrievedChunk) {
            return 0.55;
        }
        if (memory) {
            return 0.35;
        }
        if (glossary) {
            return 0.25;
        }
        return 0.0;
    }

    private double intentRelevanceScore(
            boolean raw,
            boolean finalRewrite,
            boolean expectedChunk,
            boolean expectedDoc,
            boolean retrievedChunk,
            boolean memory,
            boolean glossary
    ) {
        if (raw && finalRewrite) {
            return 1.0;
        }
        if (finalRewrite && (expectedChunk || expectedDoc)) {
            return 0.85;
        }
        if (expectedChunk || expectedDoc) {
            return 0.70;
        }
        if (finalRewrite && retrievedChunk) {
            return 0.55;
        }
        if (finalRewrite && (memory || glossary)) {
            return 0.45;
        }
        if (finalRewrite) {
            return 0.25;
        }
        return 0.0;
    }

    private double driftRiskScore(
            boolean addedByRewrite,
            boolean raw,
            boolean finalRewrite,
            boolean expectedChunk,
            boolean expectedDoc,
            boolean retrievedChunk,
            boolean memory,
            boolean glossary
    ) {
        if (raw && finalRewrite || expectedChunk) {
            return 0.0;
        }
        if (expectedDoc) {
            return 0.15;
        }
        if (retrievedChunk || memory || glossary) {
            return 0.30;
        }
        if (addedByRewrite && finalRewrite) {
            return 0.85;
        }
        if (finalRewrite) {
            return 0.60;
        }
        return 0.40;
    }

    private String labelFor(
            AnchorDraft anchor,
            boolean raw,
            boolean finalRewrite,
            boolean expectedChunk,
            boolean expectedDoc,
            boolean retrievedChunk,
            boolean memory,
            boolean glossary,
            double overallScore
    ) {
        boolean grounded = expectedChunk || expectedDoc || retrievedChunk || memory || glossary;
        if (finalRewrite && anchor.sources.contains("added_by_rewrite") && !grounded) {
            return "risky";
        }
        if (finalRewrite && !grounded) {
            return "unsupported";
        }
        if (expectedChunk || overallScore >= 0.70 || raw && finalRewrite && grounded) {
            return "useful";
        }
        if (expectedDoc || retrievedChunk || memory || glossary || overallScore >= 0.40) {
            return "neutral";
        }
        return "unknown";
    }

    private String evidenceSummary(
            boolean raw,
            boolean finalRewrite,
            boolean expectedChunk,
            boolean expectedDoc,
            boolean retrievedChunk,
            boolean memory,
            boolean glossary
    ) {
        List<String> evidence = new ArrayList<>();
        if (raw) {
            evidence.add("raw_query");
        }
        if (finalRewrite) {
            evidence.add("final_rewrite");
        }
        if (expectedChunk) {
            evidence.add("expected_chunk");
        }
        if (expectedDoc) {
            evidence.add("expected_doc");
        }
        if (retrievedChunk) {
            evidence.add("retrieved_chunk");
        }
        if (memory) {
            evidence.add("source_memory");
        }
        if (glossary) {
            evidence.add("glossary_or_canonical");
        }
        return evidence.isEmpty() ? "no_internal_evidence" : String.join(", ", evidence);
    }

    private boolean containsAny(Collection<String> values, AnchorDraft anchor) {
        for (String value : values) {
            if (containsAnchor(value, anchor.text) || containsAnchor(value, anchor.canonicalText)) {
                return true;
            }
        }
        return false;
    }

    private boolean containsAnchor(String haystack, String anchor) {
        if (blank(haystack) || blank(anchor)) {
            return false;
        }
        String normalizedHaystack = normalizeText(haystack);
        String normalizedAnchor = normalizeText(anchor);
        if (normalizedAnchor.length() >= 2 && normalizedHaystack.contains(normalizedAnchor)) {
            return true;
        }
        String compactHaystack = compact(normalizedHaystack);
        String compactAnchor = compact(normalizedAnchor);
        return compactAnchor.length() >= 2 && compactHaystack.contains(compactAnchor);
    }

    private String memoryEvidenceText(JsonNode memory, Map<UUID, AdminConsoleRepository.MemoryAnchorSource> memorySources) {
        List<String> parts = new ArrayList<>();
        addIfPresent(parts, text(memory.path("query_text")));
        addJsonText(parts, memory.path("glossary_terms"));
        addJsonText(parts, memory.path("canonical_anchors"));
        UUID memoryId = parseUuid(text(firstNode(memory, "memory_id", "id")));
        AdminConsoleRepository.MemoryAnchorSource source = memoryId == null ? null : memorySources.get(memoryId);
        if (source != null) {
            addIfPresent(parts, source.queryText());
            addJsonText(parts, source.glossaryTerms());
        }
        return String.join("\n", parts);
    }

    private List<String> textsForIds(List<String> ids, Map<String, String> source) {
        List<String> texts = new ArrayList<>();
        for (String id : ids) {
            String value = source.get(id);
            if (!blank(value)) {
                texts.add(value);
            }
        }
        return texts;
    }

    private List<String> collectRetrievedChunkIds(JsonNode caseRow) {
        Set<String> ids = new LinkedHashSet<>();
        collectRetrievedChunkIds(caseRow, ids);
        return new ArrayList<>(ids);
    }

    private void collectRetrievedChunkIds(JsonNode caseRow, Set<String> ids) {
        JsonNode retrieved = caseRow.path("retrieved_top_k");
        if (!retrieved.isArray()) {
            return;
        }
        for (JsonNode item : retrieved) {
            String chunkId = text(firstNode(item, "chunk_id", "chunkId"));
            if (!chunkId.isBlank()) {
                ids.add(chunkId);
            }
        }
    }

    private void collectMemoryIds(JsonNode node, Set<UUID> output) {
        if (!node.isArray()) {
            return;
        }
        for (JsonNode item : node) {
            UUID memoryId = parseUuid(text(firstNode(item, "memory_id", "id")));
            if (memoryId != null) {
                output.add(memoryId);
            }
        }
    }

    private JsonNode memoryAtIndex(JsonNode memoryTopN, int sourceMemoryIndex) {
        if (memoryTopN == null || !memoryTopN.isArray() || sourceMemoryIndex <= 0 || sourceMemoryIndex > memoryTopN.size()) {
            return objectMapper.createObjectNode();
        }
        return memoryTopN.get(sourceMemoryIndex - 1);
    }

    private JsonNode firstNode(JsonNode node, String... fields) {
        if (node == null || !node.isObject()) {
            return objectMapper.nullNode();
        }
        for (String field : fields) {
            JsonNode value = node.get(field);
            if (value != null && !value.isMissingNode() && !value.isNull()) {
                return value;
            }
        }
        return objectMapper.nullNode();
    }

    private List<String> jsonStringList(JsonNode node) {
        if (!node.isArray()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (JsonNode item : node) {
            String value = item.isTextual() ? item.asText("") : text(item);
            if (!value.isBlank()) {
                values.add(value);
            }
        }
        return values;
    }

    private String primarySource(Set<String> sources) {
        for (String source : SOURCE_PRIORITY) {
            if (sources.contains(source)) {
                return source;
            }
        }
        return "memory";
    }

    private String detailKey(String sampleId, String mode) {
        return text(sampleId) + "\u0000" + text(mode);
    }

    private String normalizeAnchor(String value) {
        return normalizeText(value);
    }

    private String normalizeText(String value) {
        if (value == null) {
            return "";
        }
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFKC)
                .replace('\u00A0', ' ')
                .strip()
                .toLowerCase(Locale.ROOT);
        return WHITESPACE.matcher(normalized).replaceAll(" ");
    }

    private String compact(String value) {
        return NON_COMPACT_ANCHOR.matcher(value == null ? "" : value).replaceAll("");
    }

    private String text(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return "";
        }
        if (node.isTextual()) {
            return node.asText("").strip();
        }
        if (node.isValueNode()) {
            return node.asText("").strip();
        }
        return node.toString();
    }

    private String text(String value) {
        return value == null ? "" : value.strip();
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (!blank(value)) {
                return value.strip();
            }
        }
        return "";
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private void addIfPresent(List<String> parts, String value) {
        if (!blank(value)) {
            parts.add(value);
        }
    }

    private void addJsonText(List<String> parts, JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return;
        }
        if (node.isArray()) {
            for (JsonNode item : node) {
                addJsonText(parts, item);
            }
            return;
        }
        if (node.isObject()) {
            node.fields().forEachRemaining(entry -> addJsonText(parts, entry.getValue()));
            return;
        }
        addIfPresent(parts, node.asText(""));
    }

    private double clamp(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return 0.0;
        }
        return Math.max(0.0, Math.min(1.0, value));
    }

    private UUID parseUuid(String value) {
        if (blank(value)) {
            return null;
        }
        try {
            return UUID.fromString(value.strip());
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private Optional<JsonNode> readRewriteCases(String experimentName) {
        Path path = resolveRepoRoot()
                .resolve("data/reports")
                .resolve("rewrite_cases_" + experimentName + ".json")
                .normalize();
        if (!Files.exists(path)) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readTree(Files.readString(path, StandardCharsets.UTF_8)));
        } catch (IOException exception) {
            return Optional.empty();
        }
    }

    private Path resolveRepoRoot() {
        Path configured = Path.of(pipelineProperties.repoRoot()).toAbsolutePath().normalize();
        if (Files.exists(configured.resolve("pipeline/cli.py"))) {
            return configured;
        }
        Path parent = configured.getParent();
        if (parent != null && Files.exists(parent.resolve("pipeline/cli.py"))) {
            return parent;
        }
        throw new IllegalStateException("failed to resolve repository root for rag rewrite anchor eval");
    }

    private static final class AnchorDraft {
        private final String text;
        private final String normalized;
        private final LinkedHashSet<String> sources = new LinkedHashSet<>();
        private String canonicalText;
        private int sourceMemoryIndex;
        private UUID sourceMemoryId;
        private String sourceMemoryQueryId;

        private AnchorDraft(String text, String normalized) {
            this.text = text;
            this.normalized = normalized;
        }
    }
}
