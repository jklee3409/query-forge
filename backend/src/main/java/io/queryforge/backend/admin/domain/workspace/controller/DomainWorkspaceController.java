package io.queryforge.backend.admin.domain.workspace.controller;

import io.queryforge.backend.admin.domain.identity.dto.DomainIdentityDtos;
import io.queryforge.backend.admin.domain.source.dto.DomainSourceDtos;
import io.queryforge.backend.admin.domain.workspace.dto.DomainDetail;
import io.queryforge.backend.admin.domain.workspace.service.DomainWorkspaceService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
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
public class DomainWorkspaceController {

    private final DomainWorkspaceService service;

    @GetMapping
    public List<DomainIdentityDtos.DomainSummary> listDomains() {
        return service.listDomains();
    }

    @PostMapping
    public DomainDetail createDomain(@RequestBody DomainIdentityDtos.DomainCreateRequest request) {
        return service.createDomain(request);
    }

    @GetMapping("/{domainRef}")
    public DomainDetail getDomain(@PathVariable String domainRef) {
        return service.getDomain(domainRef);
    }

    @PatchMapping("/{domainRef}")
    public DomainDetail updateDomain(
            @PathVariable String domainRef,
            @RequestBody DomainIdentityDtos.DomainUpdateRequest request
    ) {
        return service.updateDomain(domainRef, request);
    }

    @PostMapping("/{domainRef}/sources")
    public DomainDetail attachSource(
            @PathVariable String domainRef,
            @RequestBody DomainSourceDtos.DomainSourceAttachRequest request
    ) {
        return service.attachSource(domainRef, request);
    }

    @DeleteMapping("/{domainRef}/sources/{sourceId}")
    public DomainDetail detachSource(@PathVariable String domainRef, @PathVariable String sourceId) {
        return service.detachSource(domainRef, sourceId);
    }
}
