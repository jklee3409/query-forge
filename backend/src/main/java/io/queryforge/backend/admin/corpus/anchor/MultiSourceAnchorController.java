package io.queryforge.backend.admin.corpus.anchor;

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
@RequestMapping("/api/admin/corpus")
@RequiredArgsConstructor
public class MultiSourceAnchorController {

    private final MultiSourceAnchorService multiSourceAnchorService;

    @PostMapping("/anchors/multi-source/build-runs")
    public MultiSourceAnchorDtos.MultiSourceAnchorBuildRunSummary createMultiSourceAnchorBuildRun(
            @RequestBody(required = false) MultiSourceAnchorDtos.MultiSourceAnchorBuildRequest request
    ) {
        return multiSourceAnchorService.buildRelations(request);
    }

    @GetMapping("/anchors/multi-source/build-runs")
    public List<MultiSourceAnchorDtos.MultiSourceAnchorBuildRunSummary> listMultiSourceAnchorBuildRuns(
            @RequestParam(name = "limit", required = false) Integer limit,
            @RequestParam(name = "offset", required = false) Integer offset
    ) {
        return multiSourceAnchorService.listRuns(limit, offset);
    }

    @GetMapping("/anchors/multi-source/build-runs/{runId}")
    public MultiSourceAnchorDtos.MultiSourceAnchorBuildRunSummary getMultiSourceAnchorBuildRun(@PathVariable UUID runId) {
        return multiSourceAnchorService.getRun(runId);
    }
}
