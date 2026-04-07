package io.queryforge.backend.admin.ui.controller;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

@Controller
@RequestMapping("/admin")
public class AdminUiController {

    @GetMapping
    public String adminRoot() {
        return "redirect:/admin/pipeline";
    }

    @GetMapping("/pipeline")
    public String pipeline(Model model) {
        model.addAttribute("navKey", "pipeline");
        model.addAttribute("pageTitle", "문서 파이프라인 관리");
        model.addAttribute("pageSubtitle", "수집, 정제, 청킹, 용어 추출 파이프라인 실행과 결과를 관리합니다.");
        return "admin/pipeline";
    }

    @GetMapping("/synthetic-queries")
    public String syntheticQueries(Model model) {
        model.addAttribute("navKey", "synthetic");
        model.addAttribute("pageTitle", "합성 질의 생성/조회");
        model.addAttribute("pageSubtitle", "A/B/C/D 생성 방식, 배치 버전, 생성 결과를 조회하고 관리합니다.");
        return "admin/synthetic-queries";
    }

    @GetMapping("/quality-gating")
    public String qualityGating(Model model) {
        model.addAttribute("navKey", "gating");
        model.addAttribute("pageTitle", "퀄리티 게이팅 관리");
        model.addAttribute("pageSubtitle", "게이팅 단계별 퍼널과 합성 질의 생존 결과를 확인합니다.");
        return "admin/quality-gating";
    }

    @GetMapping("/rag-tests")
    public String ragTests(Model model) {
        model.addAttribute("navKey", "rag-tests");
        model.addAttribute("pageTitle", "RAG 성능/품질 테스트");
        model.addAttribute("pageSubtitle", "평가 데이터셋 기반으로 설정별 RAG 실험을 실행하고 비교합니다.");
        return "admin/rag-tests";
    }

    @GetMapping({
            "/sources",
            "/runs",
            "/documents",
            "/chunks",
            "/glossary",
            "/ingest-wizard",
            "/experiments"
    })
    public String legacyAdminPages() {
        return "redirect:/admin/pipeline";
    }
}

