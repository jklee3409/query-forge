package io.queryforge.backend.admin.console.gating;

import lombok.RequiredArgsConstructor;
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
public class GatingController {

    private final GatingService gatingService;

    @GetMapping("/gating/batches")
    public List<GatingDtos.GatingBatchRow> gatingBatches(
            @RequestParam(name = "limit", required = false) Integer limit,
            @RequestParam(name = "domain_id", required = false) UUID domainId
    ) {
        return gatingService.listGatingBatches(limit, domainId);
    }

    @PostMapping("/gating/batches/run")
    public GatingDtos.GatingBatchRow runGatingBatch(
            @RequestBody GatingDtos.GatingBatchRunRequest request
    ) {
        return gatingService.runGating(request);
    }

    @GetMapping("/gating/batches/{gatingBatchId}/funnel")
    public GatingDtos.GatingFunnelResponse gatingFunnel(
            @PathVariable UUID gatingBatchId,
            @RequestParam(name = "method_code", required = false) String methodCode
    ) {
        return gatingService.getGatingFunnel(gatingBatchId, methodCode);
    }

    @GetMapping("/gating/batches/{gatingBatchId}/results")
    public List<GatingDtos.GatingResultRow> gatingResults(
            @PathVariable UUID gatingBatchId,
            @RequestParam(name = "method_code", required = false) String methodCode,
            @RequestParam(name = "pass_stage", required = false) String passStage,
            @RequestParam(name = "query_type", required = false) String queryType,
            @RequestParam(name = "limit", required = false) Integer limit,
            @RequestParam(name = "offset", required = false) Integer offset
    ) {
        return gatingService.listGatingResults(gatingBatchId, methodCode, passStage, queryType, limit, offset);
    }
}
