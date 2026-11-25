package io.queryforge.backend.admin.domain.workspace.dto;

import io.queryforge.backend.admin.domain.identity.dto.DomainIdentityDtos.DomainSummary;
import io.queryforge.backend.admin.domain.policy.dto.DomainMethodPolicy;
import io.queryforge.backend.admin.domain.source.dto.DomainSourceDtos.DomainSource;

import java.util.List;

public record DomainDetail(
        DomainSummary domain,
        List<DomainSource> sources,
        List<DomainMethodPolicy> methodPolicies
) {
}
