package io.queryforge.backend.admin.corpus.source;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/admin/corpus")
@RequiredArgsConstructor
public class CorpusSourceController {

    private final CorpusSourceService service;

    @GetMapping("/sources")
    public List<CorpusSourceDtos.SourceSummary> listSources(
            @RequestParam(name = "domain_id", required = false) UUID domainId
    ) {
        return service.listSources(domainId);
    }

    @PatchMapping("/sources/{sourceId}")
    public CorpusSourceDtos.SourceSummary patchSource(
            @PathVariable String sourceId,
            @RequestBody CorpusSourceDtos.SourceUpdateRequest request
    ) {
        return service.updateSourceEnabled(sourceId, Boolean.TRUE.equals(request.enabled()));
    }

    @PostMapping("/sources")
    public CorpusSourceDtos.SourceSummary upsertSource(
            @RequestBody CorpusSourceDtos.SourceUpsertRequest request
    ) {
        return service.upsertSource(request);
    }

    @PostMapping("/sources/auto-register")
    public CorpusSourceDtos.SourceSummary autoRegisterSource(
            @RequestBody CorpusSourceDtos.SourceAutoRegisterRequest request
    ) {
        return service.autoRegisterSource(request);
    }
}
