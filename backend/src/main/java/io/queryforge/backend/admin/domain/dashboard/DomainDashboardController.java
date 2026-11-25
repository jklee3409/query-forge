package io.queryforge.backend.admin.domain.dashboard;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/domains")
@RequiredArgsConstructor
public class DomainDashboardController {

    private final DomainDashboardService service;

    @GetMapping("/{domainRef}/summary")
    public DomainDashboardDtos.DomainDashboardSummary getSummary(@PathVariable String domainRef) {
        return service.getSummary(domainRef);
    }
}
