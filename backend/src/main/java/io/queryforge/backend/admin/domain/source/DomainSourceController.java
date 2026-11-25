package io.queryforge.backend.admin.domain.source;

import io.queryforge.backend.admin.domain.DomainIdentityDtos;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/domains")
@RequiredArgsConstructor
public class DomainSourceController {

    private final DomainSourceService service;

    @PostMapping("/{domainRef}/sources")
    public DomainIdentityDtos.DomainDetail attachSource(
            @PathVariable String domainRef,
            @RequestBody DomainSourceDtos.DomainSourceAttachRequest request
    ) {
        return service.attachSource(domainRef, request);
    }

    @DeleteMapping("/{domainRef}/sources/{sourceId}")
    public DomainIdentityDtos.DomainDetail detachSource(
            @PathVariable String domainRef,
            @PathVariable String sourceId
    ) {
        return service.detachSource(domainRef, sourceId);
    }
}
