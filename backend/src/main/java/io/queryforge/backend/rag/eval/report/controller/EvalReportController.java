package io.queryforge.backend.rag.eval.report.controller;

import io.queryforge.backend.rag.eval.report.dto.EvalReportDtos;
import io.queryforge.backend.rag.eval.report.service.EvalReportService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class EvalReportController {

    private final EvalReportService evalReportService;

    @GetMapping("/eval/retrieval")
    public EvalReportDtos.EvalReportResponse retrievalEval() {
        return evalReportService.readEvalReport("retrieval");
    }

    @GetMapping("/eval/answer")
    public EvalReportDtos.EvalReportResponse answerEval() {
        return evalReportService.readEvalReport("answer");
    }
}
