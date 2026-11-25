package io.queryforge.backend.admin.pipeline.dashboard;

import io.queryforge.backend.admin.pipeline.source.SourceCatalogService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PipelineDashboardService {

    private final PipelineDashboardRepository repository;
    private final SourceCatalogService sourceCatalogService;

    @Transactional
    public PipelineDashboardDtos.DashboardStats getDashboardStats(UUID domainId) {
        sourceCatalogService.syncSourcesFromConfig();
        return repository.fetchDashboardStats(domainId);
    }
}
