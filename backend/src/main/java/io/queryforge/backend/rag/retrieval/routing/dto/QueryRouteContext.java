package io.queryforge.backend.rag.retrieval.routing.dto;

import io.queryforge.backend.rag.runtime.configuration.dto.ChatConfigDtos;
import io.queryforge.backend.rag.runtime.readiness.dto.ChatReadinessDtos;

import java.util.UUID;

public record QueryRouteContext(
        String rawQuery,
        UUID domainId,
        ChatConfigDtos.ChatRuntimeConfigResponse config,
        ChatReadinessDtos.ChatDomainReadinessResponse readiness,
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
