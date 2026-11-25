package io.queryforge.backend.admin.domain.dashboard;

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
