package io.queryforge.backend.admin.domain;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/admin/domains")
@RequiredArgsConstructor
public class DomainAdminController {

    private final DomainAdminService service;

    @GetMapping
    public List<DomainIdentityDtos.DomainSummary> listDomains() {
        return service.listDomains();
    }

    @PostMapping
    public DomainIdentityDtos.DomainDetail createDomain(@RequestBody DomainIdentityDtos.DomainCreateRequest request) {
        return service.createDomain(request);
    }

    @GetMapping("/{domainRef}")
    public DomainIdentityDtos.DomainDetail getDomain(@PathVariable String domainRef) {
        return service.getDomain(domainRef);
    }

    @PatchMapping("/{domainRef}")
    public DomainIdentityDtos.DomainDetail updateDomain(
            @PathVariable String domainRef,
            @RequestBody DomainIdentityDtos.DomainUpdateRequest request
    ) {
        return service.updateDomain(domainRef, request);
    }
}
