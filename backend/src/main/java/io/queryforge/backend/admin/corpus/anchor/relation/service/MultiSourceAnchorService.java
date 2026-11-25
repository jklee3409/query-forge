package io.queryforge.backend.admin.corpus.anchor.relation.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.queryforge.backend.admin.corpus.anchor.relation.dto.MultiSourceAnchorDtos;
import io.queryforge.backend.admin.corpus.anchor.relation.repository.MultiSourceAnchorRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MultiSourceAnchorService {

    private static final String DEFAULT_RELATION_VERSION = "multi-source-anchor-v1";
    private static final List<String> DEFAULT_RELATION_TYPES = List.of(
            "canonical_alias",
            "synthetic_query_cooccurrence",
            "chunk_cooccurrence"
    );
    private static final Set<String> SUPPORTED_RELATION_TYPES = Set.of(
            "canonical_alias",
            "synthetic_query_cooccurrence",
            "chunk_cooccurrence"
    );

    private final MultiSourceAnchorRepository repository;
    private final ObjectMapper objectMapper;

    @Transactional
    public MultiSourceAnchorDtos.MultiSourceAnchorBuildRunSummary buildRelations(
            MultiSourceAnchorDtos.MultiSourceAnchorBuildRequest request
    ) {
        MultiSourceAnchorDtos.MultiSourceAnchorBuildRequest safeRequest = request == null
                ? MultiSourceAnchorDtos.MultiSourceAnchorBuildRequest.builder().build()
                : request;
        String relationVersion = normalizeText(safeRequest.relationVersion(), DEFAULT_RELATION_VERSION);
        List<String> relationTypes = normalizeRelationTypes(safeRequest.relationTypes());
        double minRelationScore = clampScore(safeRequest.minRelationScore(), 0.55d);
        Integer maxRelationsPerAnchor = safeRequest.maxRelationsPerAnchor() == null
                ? 80
                : Math.max(1, Math.min(500, safeRequest.maxRelationsPerAnchor()));
        String runName = normalizeText(
                safeRequest.runName(),
                "multi-source-anchor-" + Instant.now().toString().replace(":", "").substring(0, 15)
        );
        String createdBy = normalizeText(safeRequest.createdBy(), "admin-ui");
        UUID runId = UUID.randomUUID();
        ArrayNode relationTypeAllowlist = objectMapper.valueToTree(relationTypes);

        repository.createRun(
                runId,
                runName,
                relationVersion,
                relationTypeAllowlist,
                minRelationScore,
                maxRelationsPerAnchor,
                createdBy
        );
        int candidateAnchorCount = repository.countActiveAnchors();
        repository.markExistingRelationsSuperseded(relationVersion, relationTypes);

        int canonicalAliasRows = relationTypes.contains("canonical_alias")
                ? repository.insertCanonicalAliasRelations(runId, relationVersion, minRelationScore, maxRelationsPerAnchor)
                : 0;
        int syntheticRows = relationTypes.contains("synthetic_query_cooccurrence")
                ? repository.insertSyntheticQueryCooccurrenceRelations(runId, relationVersion, minRelationScore, maxRelationsPerAnchor)
                : 0;
        int chunkRows = relationTypes.contains("chunk_cooccurrence")
                ? repository.insertChunkCooccurrenceRelations(runId, relationVersion, minRelationScore, maxRelationsPerAnchor)
                : 0;

        int relationCount = repository.countActiveRunRelations(runId);
        int evidenceCount = repository.sumActiveRunEvidence(runId);
        ObjectNode summary = objectMapper.createObjectNode();
        summary.put("schema_version", "multi-source-anchor-build-summary-v1");
        summary.put("synthetic_query_data_mutated", false);
        summary.put("candidate_anchor_count", candidateAnchorCount);
        summary.put("relation_count", relationCount);
        summary.put("evidence_count", evidenceCount);
        ObjectNode rowsBySource = objectMapper.createObjectNode();
        rowsBySource.put("canonical_alias", canonicalAliasRows);
        rowsBySource.put("synthetic_query_cooccurrence", syntheticRows);
        rowsBySource.put("chunk_cooccurrence", chunkRows);
        summary.set("affected_rows_by_relation_type", rowsBySource);
        summary.put("topic_drift_policy", "runtime filters expanded anchors as low-priority hints only");

        repository.completeRun(runId, candidateAnchorCount, relationCount, evidenceCount, summary);
        return getRun(runId);
    }

    private List<String> normalizeRelationTypes(List<String> rawTypes) {
        List<String> source = rawTypes == null || rawTypes.isEmpty() ? DEFAULT_RELATION_TYPES : rawTypes;
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String item : source) {
            String value = normalizeText(item, "").toLowerCase(Locale.ROOT).replace("-", "_");
            if (!value.isBlank() && SUPPORTED_RELATION_TYPES.contains(value)) {
                normalized.add(value);
            }
        }
        if (normalized.isEmpty()) {
            return new ArrayList<>(DEFAULT_RELATION_TYPES);
        }
        return new ArrayList<>(normalized);
    }

    private double clampScore(Double value, double defaultValue) {
        double parsed = value == null ? defaultValue : value;
        if (Double.isNaN(parsed) || Double.isInfinite(parsed)) {
            parsed = defaultValue;
        }
        return Math.max(0.0d, Math.min(1.0d, parsed));
    }

    private String normalizeText(String value, String defaultValue) {
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        return value.trim();
    }

    public List<MultiSourceAnchorDtos.MultiSourceAnchorBuildRunSummary> listRuns(Integer limit, Integer offset) {
        return repository.listRuns(limit, offset);
    }

    public MultiSourceAnchorDtos.MultiSourceAnchorBuildRunSummary getRun(UUID runId) {
        return repository.getRun(runId);
    }
}
