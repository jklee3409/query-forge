package io.queryforge.backend.admin.domain.workspace.service;

import io.queryforge.backend.admin.domain.identity.dto.DomainIdentityDtos;
import io.queryforge.backend.admin.domain.identity.service.DomainIdentityService;
import io.queryforge.backend.admin.domain.policy.repository.DomainMethodPolicyRepository;
import io.queryforge.backend.admin.domain.source.dto.DomainSourceDtos;
import io.queryforge.backend.admin.domain.source.service.DomainSourceService;
import io.queryforge.backend.admin.domain.workspace.dto.DomainDetail;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class DomainWorkspaceService {

    private final DomainIdentityService identityService;
    private final DomainSourceService sourceService;
    private final DomainMethodPolicyRepository policyRepository;

    public List<DomainIdentityDtos.DomainSummary> listDomains() {
        return identityService.listDomains();
    }

    public DomainDetail getDomain(String domainRef) {
        DomainIdentityDtos.DomainSummary domain = identityService.resolveDomain(domainRef);
        return new DomainDetail(
                domain,
                sourceService.findSources(domain.domainId()),
                policyRepository.findMethodPolicies(domain.domainId())
        );
    }

    @Transactional
    public DomainDetail createDomain(DomainIdentityDtos.DomainCreateRequest request) {
        return getDomain(identityService.createDomain(request).toString());
    }

    @Transactional
    public DomainDetail updateDomain(String domainRef, DomainIdentityDtos.DomainUpdateRequest request) {
        return getDomain(identityService.updateDomain(domainRef, request).toString());
    }

    @Transactional
    public DomainDetail attachSource(String domainRef, DomainSourceDtos.DomainSourceAttachRequest request) {
        return getDomain(sourceService.attachSource(domainRef, request).toString());
    }

    @Transactional
    public DomainDetail detachSource(String domainRef, String sourceId) {
        return getDomain(sourceService.detachSource(domainRef, sourceId).toString());
    }
}
