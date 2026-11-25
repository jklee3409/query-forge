package io.queryforge.backend.admin.console.dashboard.controller;

import io.queryforge.backend.admin.console.dashboard.dto.ConsoleDashboardDtos;
import io.queryforge.backend.admin.console.dashboard.repository.ConsoleDashboardRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/console")
@RequiredArgsConstructor
public class ConsoleDashboardController {

    private final ConsoleDashboardRepository dashboardRepository;

    @GetMapping("/dashboard/stats")
    public ConsoleDashboardDtos.AdminDashboardStats dashboardStats() {
        return dashboardRepository.findAdminDashboardStats();
    }
}
