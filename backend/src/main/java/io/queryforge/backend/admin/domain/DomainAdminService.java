package io.queryforge.backend.admin.domain;

import io.queryforge.backend.admin.domain.source.DomainSourceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class DomainAdminService {

    private final DomainSourceRepository sourceRepository;
    private static final Pattern DOMAIN_KEY_PATTERN = Pattern.compile("^[a-z0-9][a-z0-9-]{1,62}$");
    private final DomainAdminRepository repository;

    public List<DomainIdentityDtos.DomainSummary> listDomains() {
        return repository.findDomains();
    }

    public DomainIdentityDtos.DomainDetail getDomain(String domainRef) {
        DomainIdentityDtos.DomainSummary domain = resolveDomain(domainRef);
        return new DomainIdentityDtos.DomainDetail(
                domain,
                sourceRepository.findSources(domain.domainId()),
                sourceRepository.findMethodPolicies(domain.domainId())
        );
    }

    @Transactional
    public DomainIdentityDtos.DomainDetail createDomain(DomainIdentityDtos.DomainCreateRequest request) {
        validateCreateRequest(request);
        UUID domainId = repository.createDomain(new DomainIdentityDtos.DomainCreateRequest(
                normalizeDomainKey(request.domainKey()),
                request.displayName().trim(),
                request.description(),
                normalizeLanguage(request.primaryLanguage()),
                normalizeLanguage(request.sourceLanguage()),
                request.metadata(),
                request.createdBy()
        ));
        return getDomain(domainId.toString());
    }

    @Transactional
    public DomainIdentityDtos.DomainDetail updateDomain(String domainRef, DomainIdentityDtos.DomainUpdateRequest request) {
        DomainIdentityDtos.DomainSummary domain = resolveDomain(domainRef);
        validateStatus(request.status());
        repository.updateDomain(domain.domainId(), request);
        return getDomain(domain.domainId().toString());
    }

    public DomainIdentityDtos.DomainSummary resolveDomain(String domainRef) {
        if (domainRef == null || domainRef.isBlank()) {
            throw new IllegalArgumentException("domain reference is required");
        }
        return repository.findDomain(domainRef.trim())
                .orElseThrow(() -> new IllegalArgumentException("domain not found: " + domainRef));
    }

    private void validateCreateRequest(DomainIdentityDtos.DomainCreateRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request body is required");
        }
        String domainKey = normalizeDomainKey(request.domainKey());
        if (!DOMAIN_KEY_PATTERN.matcher(domainKey).matches()) {
            throw new IllegalArgumentException("domainKey must be lowercase kebab-case and 2-63 characters");
        }
        if (request.displayName() == null || request.displayName().isBlank()) {
            throw new IllegalArgumentException("displayName is required");
        }
    }

    private String normalizeDomainKey(String domainKey) {
        if (domainKey == null) {
            return "";
        }
        return domainKey.trim().toLowerCase(Locale.ROOT);
    }

    private String normalizeLanguage(String language) {
        if (language == null || language.isBlank()) {
            return null;
        }
        return language.trim().toLowerCase(Locale.ROOT);
    }

    private void validateStatus(String status) {
        if (status == null || status.isBlank()) {
            return;
        }
        if (!"active".equals(status) && !"archived".equals(status)) {
            throw new IllegalArgumentException("status must be active or archived");
        }
    }
}
