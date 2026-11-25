package io.queryforge.backend.admin.console.evaluation.run;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.queryforge.backend.admin.console.evaluation.anchor.RagRewriteAnchorRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RagTestHistoryService {

    private final RagRewriteAnchorRepository anchorRepository;

    private final RagTestRunRepository ragTestRunRepository;

    private final ObjectMapper objectMapper;

    public List<RagTestDtos.RagTestRunRow> listRagTestRuns(Integer limit) {
        return listRagTestRuns(limit, null);
    }

    public List<RagTestDtos.RagTestRunRow> listRagTestRuns(Integer limit, UUID domainId) {
        return enrichRagRunRows(ragTestRunRepository.findRagTestRuns(limit, domainId));
    }

    public RagTestDtos.RagTestRunDetail getRagTestRunDetail(UUID runId, Integer detailLimit) {
        RagTestDtos.RagTestRunRow run = ragTestRunRepository.findRagTestRun(runId)
                .orElseThrow(() -> new IllegalArgumentException("rag test run not found: " + runId));
        JsonNode summary = ragTestRunRepository.findRagSummaryMetrics(runId).orElseGet(objectMapper::createObjectNode);
        JsonNode anchorSummary = anchorRepository.findRagAnchorSummary(runId);
        RagTestDtos.RagTestRunRow enrichedRun = enrichRagRunRow(run, anchorSummary);
        JsonNode enrichedSummary = enrichRagSummary(summary, anchorSummary);
        List<RagTestDtos.RagTestResultDetailRow> details = ragTestRunRepository.findRagTestDetails(runId, detailLimit);
        return new RagTestDtos.RagTestRunDetail(enrichedRun, enrichedSummary, anchorSummary, details);
    }

    @Transactional
    public void deleteRagTestRun(UUID runId) {
        int removed = ragTestRunRepository.deleteRagTestRun(runId);
        if (removed <= 0) {
            throw new IllegalArgumentException("rag test run not found: " + runId);
        }
    }

    public RagTestDtos.RagCompareResponse compareRagRuns(UUID datasetId) {
        return new RagTestDtos.RagCompareResponse(datasetId, enrichRagRunRows(ragTestRunRepository.findRagTestRunsByDataset(datasetId)));
    }

    private List<RagTestDtos.RagTestRunRow> enrichRagRunRows(List<RagTestDtos.RagTestRunRow> rows) {
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }
        List<UUID> runIds = rows.stream()
                .map(RagTestDtos.RagTestRunRow::ragTestRunId)
                .filter(id -> id != null)
                .toList();
        Map<UUID, JsonNode> summaries = anchorRepository.findRagAnchorSummaries(runIds);
        return rows.stream()
                .map(row -> enrichRagRunRow(row, summaries.get(row.ragTestRunId())))
                .toList();
    }

    private RagTestDtos.RagTestRunRow enrichRagRunRow(
            RagTestDtos.RagTestRunRow row,
            JsonNode anchorSummary
    ) {
        if (row == null) {
            return null;
        }
        return row.withMetricsJson(attachAnchorSummary(row.metricsJson(), anchorSummary));
    }

    private JsonNode enrichRagSummary(JsonNode summary, JsonNode anchorSummary) {
        ObjectNode copy = summary != null && summary.isObject()
                ? summary.deepCopy()
                : objectMapper.createObjectNode();
        copy.set("anchor_evaluation", anchorSummary == null ? objectMapper.createObjectNode() : anchorSummary);
        copy.set("metrics_json", attachAnchorSummary(copy.get("metrics_json"), anchorSummary));
        return copy;
    }

    private JsonNode attachAnchorSummary(JsonNode metricsJson, JsonNode anchorSummary) {
        ObjectNode copy = metricsJson != null && metricsJson.isObject()
                ? metricsJson.deepCopy()
                : objectMapper.createObjectNode();
        copy.set("anchor_evaluation", anchorSummary == null ? objectMapper.createObjectNode() : anchorSummary);
        return copy;
    }

}
