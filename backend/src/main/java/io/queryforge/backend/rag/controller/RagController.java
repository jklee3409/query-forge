package io.queryforge.backend.rag.controller;

import io.queryforge.backend.rag.model.RagDtos;
import io.queryforge.backend.rag.service.ExperimentPipelineService;
import io.queryforge.backend.rag.service.RagService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class RagController {

    private final RagService ragService;
    private final ExperimentPipelineService experimentPipelineService;

    @PostMapping("/chat/ask")
    public RagDtos.AskResponse ask(@RequestBody RagDtos.AskRequest request) {
        return ragService.ask(request);
    }

    @PostMapping("/rewrite/preview")
    public RagDtos.RewritePreviewResponse previewRewrite(@RequestBody RagDtos.RewritePreviewRequest request) {
        return ragService.previewRewrite(request);
    }

    @GetMapping("/queries/{id}/trace")
    public RagDtos.QueryTraceResponse queryTrace(@PathVariable("id") UUID onlineQueryId) {
        return ragService.getQueryTrace(onlineQueryId);
    }

    @GetMapping("/experiments/{runId}/summary")
    public RagDtos.ExperimentSummaryResponse experimentSummary(@PathVariable UUID runId) {
        return ragService.getExperimentSummary(runId);
    }

    @GetMapping("/eval/retrieval")
    public RagDtos.EvalReportResponse retrievalEval() {
        return ragService.readEvalReport("retrieval");
    }

    @GetMapping("/eval/answer")
    public RagDtos.EvalReportResponse answerEval() {
        return ragService.readEvalReport("answer");
    }

    @PostMapping("/admin/reindex")
    public RagDtos.ReindexResponse reindex(@RequestBody(required = false) RagDtos.ReindexRequest request) {
        return ragService.reindex(request);
    }

    @PostMapping("/admin/experiments/run")
    public RagDtos.ExperimentCommandResponse runExperimentCommand(
            @RequestBody RagDtos.ExperimentCommandRequest request
    ) {
        return experimentPipelineService.run(request);
    }
}
