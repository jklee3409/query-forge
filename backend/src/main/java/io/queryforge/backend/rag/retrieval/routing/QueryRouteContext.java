package io.queryforge.backend.rag.retrieval.routing;

import io.queryforge.backend.rag.runtime.ChatRuntimeDtos;
import java.util.UUID;

public record QueryRouteContext(
        String rawQuery,
        UUID domainId,
        ChatRuntimeDtos.ChatRuntimeConfigResponse config,
        ChatRuntimeDtos.ChatDomainReadinessResponse readiness,
        String runtimeMode,
        String rewriteQueryProfile,
        boolean anchorInjectionEnabled,
        int queryLength,
        int queryTokenCount,
        boolean containsKorean,
        boolean containsEnglish,
        boolean containsTechnicalAnchor,
        boolean memoryCandidatesKnown,
        boolean memoryCandidatesAvailable,
        Double rawRetrievalConfidence,
        boolean agenticSubquery
) {
}
