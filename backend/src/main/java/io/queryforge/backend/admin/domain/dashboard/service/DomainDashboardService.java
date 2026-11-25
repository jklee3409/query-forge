package io.queryforge.backend.admin.domain.dashboard.service;

import io.queryforge.backend.admin.domain.dashboard.dto.DomainDashboardDtos;
import io.queryforge.backend.admin.domain.dashboard.repository.DomainDashboardRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class DomainDashboardService {

    private final DomainDashboardRepository repository;

    public DomainDashboardDtos.DomainDashboardSummary getSummary(String domainRef) {
        return repository.findDashboardSummary(domainRef)
                .orElseThrow(() -> new IllegalArgumentException("domain not found: " + domainRef));
    }
}
