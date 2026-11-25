package io.queryforge.backend.admin.console.evaluation.dataset.lifecycle.controller;

import io.queryforge.backend.admin.console.evaluation.dataset.lifecycle.service.EvalDatasetDeletionService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/admin/console")
@RequiredArgsConstructor
public class EvalDatasetDeletionController {

    private final EvalDatasetDeletionService deletionService;

    @DeleteMapping("/rag/datasets/{datasetId}")
    public void deleteRagDataset(@PathVariable UUID datasetId) {
        deletionService.deleteEvalDataset(datasetId);
    }
}
