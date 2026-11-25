package io.queryforge.backend.admin.console.evaluation.dataset.lifecycle;

import io.queryforge.backend.admin.console.evaluation.dataset.AdminEvalDatasetRepository;
import io.queryforge.backend.admin.console.evaluation.dataset.EvalDatasetService;
import io.queryforge.backend.admin.console.evaluation.run.RagTestDtos;
import io.queryforge.backend.admin.console.evaluation.run.RagTestHistoryService;
import io.queryforge.backend.admin.console.evaluation.run.RagTestRunRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class EvalDatasetDeletionService {

    private final AdminEvalDatasetRepository evalDatasetRepository;
    private final RagTestRunRepository ragTestRunRepository;
    private final RagTestHistoryService ragTestHistoryService;

    @Transactional
    public void deleteEvalDataset(UUID datasetId) {
        String datasetKey = evalDatasetRepository.findEvalDatasetKey(datasetId)
                .orElseThrow(() -> new IllegalArgumentException("eval dataset not found: " + datasetId));
        if (EvalDatasetService.DEFAULT_DATASET_KEY.equals(datasetKey)) {
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
}
