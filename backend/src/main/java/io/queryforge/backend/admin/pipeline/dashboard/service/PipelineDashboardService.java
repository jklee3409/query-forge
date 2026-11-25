package io.queryforge.backend.admin.pipeline.dashboard.service;

import io.queryforge.backend.admin.pipeline.dashboard.dto.PipelineDashboardDtos;
import io.queryforge.backend.admin.pipeline.dashboard.repository.PipelineDashboardRepository;
import io.queryforge.backend.admin.pipeline.source.service.SourceCatalogService;
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
