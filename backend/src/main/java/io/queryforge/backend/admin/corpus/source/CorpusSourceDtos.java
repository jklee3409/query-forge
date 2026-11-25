package io.queryforge.backend.admin.corpus.source;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Builder;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class CorpusSourceDtos {

    private CorpusSourceDtos() {
    }

    public record SourceSummary(
            String sourceId,
            String sourceType,
            String productName,
            String sourceName,
            String baseUrl,
            JsonNode includePatterns,
            JsonNode excludePatterns,
            String defaultVersion,
            boolean enabled,
            long totalDocuments,
            long activeDocuments,
            JsonNode versionStats,
            Instant createdAt,
            Instant updatedAt
    ) {
    }

    @Builder
    public record SourceUpdateRequest(
            Boolean enabled
    ) {
    }

    @Builder
    public record SourceUpsertRequest(
            String sourceId,
            String productName,
            List<String> startUrls,
            List<String> allowPrefixes,
            List<String> denyUrlPatterns,
            Boolean enabled,
            Double requestDelaySeconds,
            Integer maxDepth,
            UUID domainId
    ) {
    }

    @Builder
    public record SourceAutoRegisterRequest(
            String url,
            String sourceId,
            String productName,
            Boolean enabled,
            Double requestDelaySeconds,
            Integer maxDepth,
            UUID domainId
    ) {
    }
}
