package io.queryforge.backend.admin.domain.controller;

import io.queryforge.backend.admin.domain.model.DomainAdminDtos;
import io.queryforge.backend.admin.domain.service.DomainAdminService;
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
public class DomainAdminController {

    private final DomainAdminService service;

    @GetMapping
    public List<DomainAdminDtos.DomainSummary> listDomains() {
        return service.listDomains();
    }

    @PostMapping
    public DomainAdminDtos.DomainDetail createDomain(@RequestBody DomainAdminDtos.DomainCreateRequest request) {
        return service.createDomain(request);
    }

    @GetMapping("/{domainRef}")
    public DomainAdminDtos.DomainDetail getDomain(@PathVariable String domainRef) {
        return service.getDomain(domainRef);
    }

    @PatchMapping("/{domainRef}")
    public DomainAdminDtos.DomainDetail updateDomain(
            @PathVariable String domainRef,
            @RequestBody DomainAdminDtos.DomainUpdateRequest request
    ) {
        return service.updateDomain(domainRef, request);
    }

    @GetMapping("/{domainRef}/summary")
    public DomainAdminDtos.DomainDashboardSummary getSummary(@PathVariable String domainRef) {
        return service.getSummary(domainRef);
    }

    @PostMapping("/{domainRef}/sources")
    public DomainAdminDtos.DomainDetail attachSource(
            @PathVariable String domainRef,
            @RequestBody DomainAdminDtos.DomainSourceAttachRequest request
    ) {
        return service.attachSource(domainRef, request);
    }

    @DeleteMapping("/{domainRef}/sources/{sourceId}")
    public DomainAdminDtos.DomainDetail detachSource(
            @PathVariable String domainRef,
            @PathVariable String sourceId
    ) {
        return service.detachSource(domainRef, sourceId);
    }
}
