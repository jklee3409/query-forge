package io.queryforge.backend.admin.pipeline.dashboard.controller;

import io.queryforge.backend.admin.pipeline.dashboard.dto.PipelineDashboardDtos;
import io.queryforge.backend.admin.pipeline.dashboard.service.PipelineDashboardService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/admin/pipeline")
@RequiredArgsConstructor
public class PipelineDashboardController {

    private final PipelineDashboardService service;

    @GetMapping("/dashboard")
    public PipelineDashboardDtos.DashboardStats dashboard(
            @RequestParam(name = "domain_id", required = false) UUID domainId
    ) {
        return service.getDashboardStats(domainId);
    }
}
