package io.queryforge.backend.admin.corpus.anchor.normalization;

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
@RequestMapping("/api/admin/corpus")
@RequiredArgsConstructor
public class AnchorNormalizationController {

    private final AnchorNormalizationService anchorNormalizationService;

    @PostMapping("/anchors/normalization-runs")
    public AnchorNormalizationDtos.AnchorNormalizationRunSummary createAnchorNormalizationRun(
            @RequestBody(required = false) AnchorNormalizationDtos.AnchorNormalizationRunCreateRequest request
    ) {
        return anchorNormalizationService.createDryRun(request);
    }

    @GetMapping("/anchors/normalization-runs")
    public List<AnchorNormalizationDtos.AnchorNormalizationRunSummary> listAnchorNormalizationRuns(
            @RequestParam(name = "limit", required = false) Integer limit,
            @RequestParam(name = "offset", required = false) Integer offset
    ) {
        return anchorNormalizationService.listRuns(limit, offset);
    }

    @GetMapping("/anchors/normalization-runs/{runId}")
    public AnchorNormalizationDtos.AnchorNormalizationRunDetail getAnchorNormalizationRun(@PathVariable UUID runId) {
        return anchorNormalizationService.getRunDetail(runId);
    }

    @DeleteMapping("/anchors/normalization-runs/{runId}")
    public void deleteAnchorNormalizationRun(@PathVariable UUID runId) {
        anchorNormalizationService.deleteRun(runId);
    }

    @PostMapping("/anchors/normalization-runs/{runId}/candidates/{candidateId}/review")
    public AnchorNormalizationDtos.AnchorNormalizationCandidateDto reviewAnchorNormalizationCandidate(
            @PathVariable UUID runId,
            @PathVariable UUID candidateId,
            @RequestBody AnchorNormalizationDtos.AnchorNormalizationCandidateReviewRequest request
    ) {
        return anchorNormalizationService.reviewCandidate(runId, candidateId, request);
    }

    @PostMapping("/anchors/normalization-runs/{runId}/candidate-reviews")
    public AnchorNormalizationDtos.AnchorNormalizationRunDetail reviewAnchorNormalizationCandidates(
            @PathVariable UUID runId,
            @RequestBody AnchorNormalizationDtos.AnchorNormalizationCandidateReviewBatchRequest request
    ) {
        return anchorNormalizationService.reviewCandidates(runId, request);
    }

    @PostMapping("/anchors/normalization-runs/{runId}/approve")
    public AnchorNormalizationDtos.AnchorNormalizationRunSummary approveAnchorNormalizationRun(
            @PathVariable UUID runId,
            @RequestBody(required = false) AnchorNormalizationDtos.AnchorNormalizationReviewRequest request
    ) {
        return anchorNormalizationService.approve(runId, request);
    }

    @PostMapping("/anchors/normalization-runs/{runId}/reject")
    public AnchorNormalizationDtos.AnchorNormalizationRunSummary rejectAnchorNormalizationRun(
            @PathVariable UUID runId,
            @RequestBody(required = false) AnchorNormalizationDtos.AnchorNormalizationReviewRequest request
    ) {
        return anchorNormalizationService.reject(runId, request);
    }
}
