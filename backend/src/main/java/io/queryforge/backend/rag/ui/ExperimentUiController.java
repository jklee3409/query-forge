package io.queryforge.backend.rag.ui;

import io.queryforge.backend.rag.service.RagService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

@Controller
@RequestMapping("/admin/experiments")
@RequiredArgsConstructor
public class ExperimentUiController {

    private final RagService ragService;

    @GetMapping
    public String experiments(Model model) {
        model.addAttribute("navKey", "experiments");
        model.addAttribute("recentExperimentRuns", ragService.listRecentExperimentRuns(30));
        model.addAttribute("pageTitle", "실험/평가 모니터링");
        model.addAttribute("pageSubtitle", "실험 실행 결과, 리트리벌/응답 평가 요약, 리라이트 사례를 비교합니다.");
        return "admin/experiments";
    }
}

