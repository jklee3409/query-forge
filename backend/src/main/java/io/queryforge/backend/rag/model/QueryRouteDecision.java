package io.queryforge.backend.rag.model;

import java.util.LinkedHashMap;
import java.util.Map;

public record QueryRouteDecision(
        QueryStrategy strategy,
        String reason,
        boolean routerEnabled,
        boolean fallbackAllowed,
        boolean fallbackApplied,
        String fallbackReason,
        String effectiveMode,
        String rewriteQueryProfile,
        boolean anchorInjectionEnabled,
        Map<String, Object> metadata
) {
    public QueryRouteDecision {
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    public QueryRouteDecision withFallbackApplied(String fallbackReason) {
        return new QueryRouteDecision(
                strategy,
                reason,
                routerEnabled,
                fallbackAllowed,
                true,
                fallbackReason,
                effectiveMode,
                rewriteQueryProfile,
                anchorInjectionEnabled,
                metadata
        );
    }

    public QueryRouteDecision withLatency(long latencyMs) {
        Map<String, Object> updated = new LinkedHashMap<>(metadata);
        updated.put("latencyMs", latencyMs);
        return new QueryRouteDecision(
                strategy,
                reason,
                routerEnabled,
                fallbackAllowed,
                fallbackApplied,
                fallbackReason,
                effectiveMode,
                rewriteQueryProfile,
                anchorInjectionEnabled,
                updated
        );
    }
}
