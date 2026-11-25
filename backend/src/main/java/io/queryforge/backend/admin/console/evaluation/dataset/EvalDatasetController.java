package io.queryforge.backend.admin.console.evaluation.dataset;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/admin/console")
@RequiredArgsConstructor
public class EvalDatasetController {

    private final EvalDatasetService evalDatasetService;

    @GetMapping("/rag/datasets")
    public List<EvalDatasetDtos.EvalDatasetRow> ragDatasets(
            @RequestParam(name = "domain_id", required = false) UUID domainId
    ) {
        return evalDatasetService.listEvalDatasets(domainId);
    }

    @GetMapping("/rag/datasets/{datasetId}/items")
    public List<EvalDatasetDtos.EvalDatasetItemRow> ragDatasetItems(
            @PathVariable UUID datasetId,
            @RequestParam(name = "limit", required = false) Integer limit,
            @RequestParam(name = "offset", required = false) Integer offset
    ) {
        return evalDatasetService.listEvalDatasetItems(datasetId, limit, offset);
    }

}
