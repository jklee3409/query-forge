package io.queryforge.backend.ui.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class ReactUiController {

    @GetMapping("/admin")
    public String adminRoot() {
        return "redirect:/admin/pipeline";
    }

    @GetMapping({
            "/admin/sources",
            "/admin/runs",
            "/admin/documents",
            "/admin/chunks",
            "/admin/glossary",
            "/admin/ingest-wizard",
            "/admin/experiments"
    })
    public String legacyAdminPages() {
        return "redirect:/admin/pipeline";
    }

    @GetMapping({
            "/",
            "/admin/pipeline",
            "/admin/synthetic-queries",
            "/admin/quality-gating",
            "/admin/rag-tests"
    })
    public String reactApp() {
        return "forward:/react/index.html";
    }
}
