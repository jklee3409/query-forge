package io.queryforge.backend.admin.domain.source;

import io.queryforge.backend.admin.domain.DomainAdminService;
import io.queryforge.backend.admin.domain.DomainIdentityDtos;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class DomainSourceService {

    private final DomainSourceRepository repository;
    private final DomainAdminService domainService;

    @Transactional
    public DomainIdentityDtos.DomainDetail attachSource(
            String domainRef,
            DomainSourceDtos.DomainSourceAttachRequest request
    ) {
        DomainIdentityDtos.DomainSummary domain = domainService.resolveDomain(domainRef);
        if (request.sourceId() == null || request.sourceId().isBlank()) {
            throw new IllegalArgumentException("sourceId is required");
        }
        repository.attachSource(domain.domainId(), request);
        return domainService.getDomain(domain.domainId().toString());
    }

    @Transactional
    public DomainIdentityDtos.DomainDetail detachSource(String domainRef, String sourceId) {
        DomainIdentityDtos.DomainSummary domain = domainService.resolveDomain(domainRef);
        repository.detachSource(domain.domainId(), sourceId);
        return domainService.getDomain(domain.domainId().toString());
    }
}
