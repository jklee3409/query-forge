package io.queryforge.backend.admin.domain.workspace;

import io.queryforge.backend.admin.domain.identity.DomainIdentityDtos.DomainSummary;
import io.queryforge.backend.admin.domain.policy.DomainMethodPolicy;
import io.queryforge.backend.admin.domain.source.DomainSourceDtos.DomainSource;

import java.util.List;

public record DomainDetail(
        DomainSummary domain,
        List<DomainSource> sources,
        List<DomainMethodPolicy> methodPolicies
) {
}
