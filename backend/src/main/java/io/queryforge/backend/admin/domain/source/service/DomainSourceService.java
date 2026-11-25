package io.queryforge.backend.admin.domain.source.service;

import io.queryforge.backend.admin.domain.identity.service.DomainIdentityService;
import io.queryforge.backend.admin.domain.source.dto.DomainSourceDtos;
import io.queryforge.backend.admin.domain.source.repository.DomainSourceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class DomainSourceService {

    private final DomainSourceRepository repository;
    private final DomainIdentityService identityService;

    public List<DomainSourceDtos.DomainSource> findSources(UUID domainId) {
        return repository.findSources(domainId);
    }

    @Transactional
    public UUID attachSource(String domainRef, DomainSourceDtos.DomainSourceAttachRequest request) {
        UUID domainId = identityService.resolveDomain(domainRef).domainId();
        if (request.sourceId() == null || request.sourceId().isBlank()) {
            throw new IllegalArgumentException("sourceId is required");
        }
        repository.attachSource(domainId, request);
        return domainId;
    }

    @Transactional
    public UUID detachSource(String domainRef, String sourceId) {
        UUID domainId = identityService.resolveDomain(domainRef).domainId();
        repository.detachSource(domainId, sourceId);
        return domainId;
    }
}
