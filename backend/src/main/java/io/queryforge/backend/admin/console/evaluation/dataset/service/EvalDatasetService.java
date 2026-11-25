package io.queryforge.backend.admin.console.evaluation.dataset.service;

import com.fasterxml.jackson.databind.JsonNode;
import io.queryforge.backend.admin.console.evaluation.dataset.dto.EvalDatasetDtos;
import io.queryforge.backend.admin.console.evaluation.dataset.repository.AdminEvalDatasetRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class EvalDatasetService {

    public static final String DEFAULT_DATASET_KEY = "human_eval_default";

    private final AdminEvalDatasetRepository evalDatasetRepository;

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
