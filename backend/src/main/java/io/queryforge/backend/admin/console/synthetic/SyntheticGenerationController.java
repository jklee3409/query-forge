package io.queryforge.backend.admin.console.synthetic;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/admin/console")
@RequiredArgsConstructor
public class SyntheticGenerationController {

    private final SyntheticGenerationService syntheticGenerationService;

    @GetMapping("/synthetic/methods")
    public List<SyntheticGenerationDtos.SyntheticGenerationMethod> syntheticMethods(
            @RequestParam(name = "source_id", required = false) String sourceId,
            @RequestParam(name = "source_document_id", required = false) String sourceDocumentId,
            @RequestParam(name = "dataset_id", required = false) UUID datasetId,
            @RequestParam(name = "domain_id", required = false) UUID domainId
    ) {
        return syntheticGenerationService.listGenerationMethods(sourceId, sourceDocumentId, datasetId, domainId);
    }

    @GetMapping("/synthetic/batches")
    public List<SyntheticGenerationDtos.SyntheticGenerationBatchRow> syntheticBatches(
            @RequestParam(name = "limit", required = false) Integer limit,
            @RequestParam(name = "domain_id", required = false) UUID domainId
    ) {
        return syntheticGenerationService.listGenerationBatches(limit, domainId);
    }

    @PostMapping("/synthetic/batches/run")
    public SyntheticGenerationDtos.SyntheticGenerationBatchRow runSyntheticBatch(
            @RequestBody SyntheticGenerationDtos.SyntheticBatchRunRequest request
    ) {
        return syntheticGenerationService.runSyntheticGeneration(request);
    }

    @DeleteMapping("/synthetic/batches/{batchId}")
    public void deleteSyntheticBatch(@PathVariable UUID batchId) {
        syntheticGenerationService.deleteSyntheticGenerationBatch(batchId);
    }

    @GetMapping("/synthetic/queries")
    public List<SyntheticGenerationDtos.SyntheticQueryRow> syntheticQueries(
            @RequestParam(name = "method_code", required = false) String methodCode,
            @RequestParam(name = "batch_id", required = false) UUID batchId,
            @RequestParam(name = "query_type", required = false) String queryType,
            @RequestParam(name = "gated", required = false) Boolean gated,
            @RequestParam(name = "limit", required = false) Integer limit,
            @RequestParam(name = "offset", required = false) Integer offset,
            @RequestParam(name = "domain_id", required = false) UUID domainId
    ) {
        return syntheticGenerationService.listSyntheticQueries(methodCode, batchId, queryType, gated, limit, offset, domainId);
    }

    @GetMapping("/synthetic/queries/{queryId}")
    public SyntheticGenerationDtos.SyntheticQueryDetailResponse syntheticQueryDetail(@PathVariable String queryId) {
        return syntheticGenerationService.getSyntheticQueryDetail(queryId);
    }

    @GetMapping("/synthetic/stats")
    public SyntheticGenerationDtos.SyntheticStatsResponse syntheticStats(
            @RequestParam(name = "method_code", required = false) String methodCode,
            @RequestParam(name = "batch_id", required = false) UUID batchId,
            @RequestParam(name = "domain_id", required = false) UUID domainId
    ) {
        return syntheticGenerationService.getSyntheticStats(methodCode, batchId, domainId);
    }
}
