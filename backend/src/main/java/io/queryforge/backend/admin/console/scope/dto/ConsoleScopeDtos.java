package io.queryforge.backend.admin.console.scope.dto;

import java.util.UUID;

public final class ConsoleScopeDtos {

    private ConsoleScopeDtos() {
    }

    public record SourceStrategyContext(
            String sourceId,
            String sourceType,
            String productName,
            String sourceName,
            boolean hasKoDocuments,
            boolean hasEnDocuments
    ) {
    }

    public record DatasetStrategyContext(
            UUID datasetId,
            String datasetKey,
            String metadataStrategyProfile,
            String metadataQueryLanguage,
            boolean hasSpringSamples,
            boolean hasPythonSamples,
            boolean hasKoQueries,
            boolean hasEnQueries
    ) {
    }

    public record DomainLanguageContext(
            UUID domainId,
            String primaryLanguage,
            String sourceLanguage
    ) {
    }
}
