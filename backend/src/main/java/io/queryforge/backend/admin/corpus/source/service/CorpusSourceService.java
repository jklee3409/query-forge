package io.queryforge.backend.admin.corpus.source.service;

import io.queryforge.backend.admin.corpus.source.dto.CorpusSourceDtos;
import io.queryforge.backend.admin.corpus.source.repository.CorpusSourceRepository;
import io.queryforge.backend.admin.domain.source.dto.DomainSourceDtos;
import io.queryforge.backend.admin.domain.source.service.DomainSourceService;
import io.queryforge.backend.admin.pipeline.source.service.SourceCatalogService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CorpusSourceService {

    private final CorpusSourceRepository repository;
    private final SourceCatalogService sourceCatalogService;
    private final DomainSourceService domainSourceService;

    @Transactional
    public List<CorpusSourceDtos.SourceSummary> listSources() {
        return listSources(null);
    }

    @Transactional
    public List<CorpusSourceDtos.SourceSummary> listSources(UUID domainId) {
        sourceCatalogService.syncSourcesFromConfig();
        return repository.findSources(domainId);
    }

    @Transactional
    public CorpusSourceDtos.SourceSummary updateSourceEnabled(String sourceId, boolean enabled) {
        repository.updateSourceEnabled(sourceId, enabled);
        return repository.findSources().stream()
                .filter(source -> source.sourceId().equals(sourceId))
                .findFirst()
                .orElseThrow();
    }

    @Transactional
    public CorpusSourceDtos.SourceSummary upsertSource(CorpusSourceDtos.SourceUpsertRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("source upsert request is required");
        }
        String sourceId = requireTrimmed(request.sourceId(), "sourceId");
        String productName = requireTrimmed(request.productName(), "productName");
        List<String> startUrls = normalizeDistinctNonBlank(request.startUrls());
        List<String> allowPrefixes = normalizeDistinctNonBlank(request.allowPrefixes());
        List<String> denyPatterns = normalizeDistinctNonBlank(request.denyUrlPatterns());
        boolean enabled = request.enabled() == null || request.enabled();
        double requestDelaySeconds = request.requestDelaySeconds() == null
                ? 0.75
                : Math.max(0.0, Math.min(15.0, request.requestDelaySeconds()));
        int maxDepth = request.maxDepth() == null ? 4 : Math.max(1, Math.min(20, request.maxDepth()));

        sourceCatalogService.upsertSourceAndPersistConfig(
                sourceId,
                productName,
                startUrls,
                allowPrefixes,
                denyPatterns,
                enabled,
                requestDelaySeconds,
                maxDepth
        );
        attachSourceToDomainIfRequested(request.domainId(), sourceId);
        return repository.findSourceById(sourceId);
    }

    @Transactional
    public CorpusSourceDtos.SourceSummary autoRegisterSource(CorpusSourceDtos.SourceAutoRegisterRequest request) {
        if (request == null || request.url() == null || request.url().isBlank()) {
            throw new IllegalArgumentException("url is required");
        }
        URI uri;
        try {
            uri = URI.create(request.url().trim());
        } catch (Exception exception) {
            throw new IllegalArgumentException("Invalid URL format");
        }
        if (uri.getScheme() == null || uri.getHost() == null) {
            throw new IllegalArgumentException("URL must include scheme and host");
        }

        String normalizedUrl = uri.normalize().toString();
        String sourceId = request.sourceId() == null || request.sourceId().isBlank()
                ? inferSourceId(uri)
                : request.sourceId().trim();
        String productName = request.productName() == null || request.productName().isBlank()
                ? inferProductName(uri)
                : request.productName().trim();
        String allowPrefix = inferAllowPrefix(uri);

        sourceCatalogService.upsertSourceAndPersistConfig(
                sourceId,
                productName,
                List.of(normalizedUrl),
                List.of(allowPrefix),
                defaultDenyPatterns(),
                request.enabled() == null || request.enabled(),
                request.requestDelaySeconds() == null ? 0.75 : Math.max(0.0, Math.min(15.0, request.requestDelaySeconds())),
                request.maxDepth() == null ? 4 : Math.max(1, Math.min(20, request.maxDepth()))
        );
        attachSourceToDomainIfRequested(request.domainId(), sourceId);
        return repository.findSourceById(sourceId);
    }

    private void attachSourceToDomainIfRequested(UUID domainId, String sourceId) {
        if (domainId == null) {
            return;
        }
        domainSourceService.attachSource(
                domainId.toString(),
                new DomainSourceDtos.DomainSourceAttachRequest(sourceId, "primary", true)
        );
    }

    private String inferSourceId(URI uri) {
        String hostPart = uri.getHost().toLowerCase(Locale.ROOT).replace(".", "-");
        String path = uri.getPath() == null ? "" : uri.getPath().toLowerCase(Locale.ROOT);
        String[] segments = path.split("/");
        List<String> picks = new ArrayList<>();
        for (String segment : segments) {
            if (segment == null || segment.isBlank()) {
                continue;
            }
            if (segment.endsWith(".html")) {
                continue;
            }
            picks.add(segment.replaceAll("[^a-z0-9-]", "-"));
            if (picks.size() == 2) {
                break;
            }
        }
        String tail = picks.isEmpty() ? "docs" : String.join("-", picks);
        return (hostPart + "-" + tail).replaceAll("-{2,}", "-");
    }

    private String inferProductName(URI uri) {
        String path = uri.getPath() == null ? "" : uri.getPath();
        String[] segments = path.split("/");
        for (String segment : segments) {
            if (segment == null || segment.isBlank()) {
                continue;
            }
            if ("reference".equalsIgnoreCase(segment) || segment.endsWith(".html")) {
                continue;
            }
            return segment;
        }
        return uri.getHost();
    }

    private String inferAllowPrefix(URI uri) {
        String path = uri.getPath() == null ? "/" : uri.getPath();
        int referenceIndex = path.indexOf("/reference/");
        String normalizedPath;
        if (referenceIndex >= 0) {
            normalizedPath = path.substring(0, referenceIndex + "/reference/".length());
        } else {
            int lastSlash = path.lastIndexOf('/');
            normalizedPath = lastSlash <= 0 ? "/" : path.substring(0, lastSlash + 1);
        }
        return uri.getScheme() + "://" + uri.getHost() + normalizedPath;
    }

    private List<String> defaultDenyPatterns() {
        return List.of(
                ".*#.*",
                ".*/search.*",
                ".*/login.*",
                ".*\\?.*"
        );
    }

    private String requireTrimmed(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }

    private List<String> normalizeDistinctNonBlank(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> dedup = new LinkedHashSet<>();
        for (String value : values) {
            if (value == null) {
                continue;
            }
            String trimmed = value.trim();
            if (!trimmed.isBlank()) {
                dedup.add(trimmed);
            }
        }
        return List.copyOf(dedup);
    }
}
