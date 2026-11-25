package io.queryforge.backend.admin.console.evaluation;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class EvalDatasetService {

    private final RagTestHistoryService ragTestHistoryService;

    private static final String DEFAULT_DATASET_KEY = "human_eval_default";

    private final AdminEvalDatasetRepository evalDatasetRepository;

    private final RagTestRunRepository ragTestRunRepository;

    @Transactional
    public List<EvalDatasetDtos.EvalDatasetRow> listEvalDatasets() {
        return listEvalDatasets(null);
    }

    @Transactional
    public List<EvalDatasetDtos.EvalDatasetRow> listEvalDatasets(UUID domainId) {
        ensureDefaultEvalDataset();
        return evalDatasetRepository.findEvalDatasets(domainId);
    }

    @Transactional
    public List<EvalDatasetDtos.EvalDatasetItemRow> listEvalDatasetItems(UUID datasetId, Integer limit, Integer offset) {
        ensureDefaultEvalDataset();
        return evalDatasetRepository.findEvalDatasetItems(datasetId, limit, offset);
    }

    @Transactional
    public void deleteEvalDataset(UUID datasetId) {
        String datasetKey = evalDatasetRepository.findEvalDatasetKey(datasetId)
                .orElseThrow(() -> new IllegalArgumentException("eval dataset not found: " + datasetId));
        if (DEFAULT_DATASET_KEY.equals(datasetKey)) {
            throw new IllegalArgumentException("default eval dataset is auto-managed and cannot be deleted: " + datasetId);
        }
        List<RagTestDtos.RagTestRunRow> linkedRuns = ragTestRunRepository.findRagTestRunsByDataset(datasetId);
        List<RagTestDtos.RagTestRunRow> activeRuns = linkedRuns.stream()
                .filter(row -> {
                    String status = row.status() == null ? "" : row.status().trim().toLowerCase(Locale.ROOT);
                    return Set.of("planned", "queued", "running").contains(status);
                })
                .toList();
        if (!activeRuns.isEmpty()) {
            throw new IllegalArgumentException(
                    "eval dataset has active rag test runs: " + activeRuns.stream()
                            .map(row -> row.ragTestRunId().toString())
                            .collect(java.util.stream.Collectors.joining(", "))
            );
        }
        for (RagTestDtos.RagTestRunRow run : linkedRuns) {
            ragTestHistoryService.deleteRagTestRun(run.ragTestRunId());
        }
        int removed = evalDatasetRepository.deleteEvalDataset(datasetId);
        if (removed <= 0) {
            throw new IllegalArgumentException("eval dataset not found: " + datasetId);
        }
    }

    @Transactional(propagation = Propagation.SUPPORTS)
    public void ensureDefaultEvalDataset() {
        if (evalDatasetRepository.countEvalSamplesForDefaultDataset() <= 0) {
            return;
        }
        JsonNode categoryDistribution = evalDatasetRepository.aggregateCategoryDistributionFromDefaultSamples();
        JsonNode singleMultiDistribution = evalDatasetRepository.aggregateSingleMultiDistributionFromDefaultSamples();
        int totalItems = (int) evalDatasetRepository.countEvalSamplesForDefaultDataset();
        UUID datasetId = evalDatasetRepository.upsertEvalDataset(
                DEFAULT_DATASET_KEY,
                "기본 평가 데이터셋 (build-eval-dataset)",
                "build-eval-dataset 표본만 자동 동기화한 기본 데이터셋 (short-user 전용셋 제외)",
                "v1",
                totalItems,
                categoryDistribution,
                singleMultiDistribution
        );
        evalDatasetRepository.refreshDefaultEvalDatasetItems(datasetId);
    }
}
