package io.queryforge.backend.rag.service;

import com.fasterxml.jackson.databind.JsonNode;
import io.queryforge.backend.rag.model.ChatRuntimeDtos;
import io.queryforge.backend.rag.model.QueryRouteContext;
import io.queryforge.backend.rag.model.QueryRouteDecision;
import io.queryforge.backend.rag.model.QueryStrategy;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class QueryStrategyRouter {

    private static final Pattern TOKEN_PATTERN = Pattern.compile("[A-Za-z0-9_./:$#-]+|\\p{InHangulSyllables}+");
    private static final Pattern TECHNICAL_ANCHOR_PATTERN = Pattern.compile(
            "(?i)(\\b[A-Za-z][A-Za-z0-9_$]*(?:Exception|Error|Config|Configuration|Properties|Client|Template|Repository|Controller|Service|Filter|Chain|Manager|Factory)\\b)"
                    + "|(\\b[A-Za-z][A-Za-z0-9_]*[./:$#-][A-Za-z0-9_./:$#-]*\\b)"
                    + "|(\\b[A-Z][A-Za-z0-9_]+[A-Z][A-Za-z0-9_]*\\b)"
                    + "|(\\b[A-Za-z]+\\d+[A-Za-z0-9_]*\\b)"
    );

    public QueryRouteDecision route(QueryRouteContext context) {
        ChatRuntimeDtos.ChatRuntimeConfigResponse config = context.config();
        String mode = normalize(context.runtimeMode(), "selective_rewrite");
        String rewriteProfile = normalize(context.rewriteQueryProfile(), "compact_anchor");
        boolean routerEnabled = routerEnabled(config == null ? null : config.metadata());
        boolean fallbackAllowed = routerEnabled && fallbackAllowed(config == null ? null : config.metadata());
        boolean technicalAnchor = context.containsTechnicalAnchor() || hasTechnicalAnchor(context.rawQuery());
        boolean korean = context.containsKorean() || hasKorean(context.rawQuery());
        int tokenCount = context.queryTokenCount() > 0 ? context.queryTokenCount() : countTokens(context.rawQuery());

        Map<String, Object> metadata = baseMetadata(context, mode, rewriteProfile, technicalAnchor, korean, tokenCount);
        if (!routerEnabled) {
            return decision(
                    QueryStrategy.RAW_ONLY,
                    "router_disabled",
                    false,
                    false,
                    false,
                    null,
                    mode,
                    rewriteProfile,
                    context.anchorInjectionEnabled(),
                    metadata
            );
        }
        if ("raw_only".equals(mode)) {
            return decision(
                    QueryStrategy.RAW_ONLY,
                    "mode_raw_only",
                    true,
                    fallbackAllowed,
                    false,
                    null,
                    mode,
                    rewriteProfile,
                    context.anchorInjectionEnabled(),
                    metadata
            );
        }
        if (!readyForRewrite(context.readiness())) {
            String fallbackReason = blockingReason(context.readiness(), "rewrite_readiness_failed");
            return decision(
                    QueryStrategy.RAW_ONLY,
                    "rewrite_readiness_failed",
                    true,
                    fallbackAllowed,
                    fallbackAllowed,
                    fallbackAllowed ? fallbackReason : null,
                    mode,
                    rewriteProfile,
                    context.anchorInjectionEnabled(),
                    metadata
            );
        }
        if (context.memoryCandidatesKnown() && !context.memoryCandidatesAvailable()) {
            return decision(
                    QueryStrategy.RAW_ONLY,
                    "memory_candidates_unavailable",
                    true,
                    fallbackAllowed,
                    fallbackAllowed,
                    fallbackAllowed ? "memory_candidates_empty" : null,
                    mode,
                    rewriteProfile,
                    context.anchorInjectionEnabled(),
                    metadata
            );
        }
        if (context.anchorInjectionEnabled() && technicalAnchor) {
            return decision(
                    QueryStrategy.ANCHOR_AWARE_REWRITE,
                    "anchor_injection_enabled_and_technical_anchor_detected",
                    true,
                    fallbackAllowed,
                    false,
                    null,
                    mode,
                    rewriteProfile,
                    true,
                    metadata
            );
        }
        if (specificTechnicalQuery(context.rawQuery(), korean, technicalAnchor, tokenCount)) {
            return decision(
                    QueryStrategy.RAW_ONLY,
                    "specific_technical_query",
                    true,
                    fallbackAllowed,
                    false,
                    null,
                    mode,
                    rewriteProfile,
                    context.anchorInjectionEnabled(),
                    metadata
            );
        }
        return decision(
                QueryStrategy.SYNTHETIC_SELECTIVE_REWRITE,
                "rewrite_backed_mode_ready",
                true,
                fallbackAllowed,
                false,
                null,
                mode,
                rewriteProfile,
                context.anchorInjectionEnabled(),
                metadata
        );
    }

    private QueryRouteDecision decision(
            QueryStrategy strategy,
            String reason,
            boolean routerEnabled,
            boolean fallbackAllowed,
            boolean fallbackApplied,
            String fallbackReason,
            String mode,
            String rewriteProfile,
            boolean anchorInjectionEnabled,
            Map<String, Object> metadata
    ) {
        return new QueryRouteDecision(
                strategy,
                reason,
                routerEnabled,
                fallbackAllowed,
                fallbackApplied,
                fallbackReason,
                mode,
                rewriteProfile,
                anchorInjectionEnabled,
                metadata
        );
    }

    private Map<String, Object> baseMetadata(
            QueryRouteContext context,
            String mode,
            String rewriteProfile,
            boolean technicalAnchor,
            boolean korean,
            int tokenCount
    ) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("runtimeMode", mode);
        metadata.put("rewriteQueryProfile", rewriteProfile);
        metadata.put("queryLength", context.queryLength());
        metadata.put("queryTokenCount", tokenCount);
        metadata.put("containsKorean", korean);
        metadata.put("containsEnglish", context.containsEnglish() || hasEnglish(context.rawQuery()));
        metadata.put("containsTechnicalAnchor", technicalAnchor);
        metadata.put("memoryCandidatesKnown", context.memoryCandidatesKnown());
        metadata.put("memoryCandidatesAvailable", context.memoryCandidatesAvailable());
        if (context.rawRetrievalConfidence() != null) {
            metadata.put("rawRetrievalConfidence", context.rawRetrievalConfidence());
        }
        return metadata;
    }

    private boolean routerEnabled(JsonNode metadata) {
        if (metadata == null || metadata.isMissingNode() || metadata.isNull()) {
            return false;
        }
        return metadata.path("routerEnabled").asBoolean(false)
                || metadata.path("queryRouterEnabled").asBoolean(false)
                || metadata.path("query_router_enabled").asBoolean(false);
    }

    private boolean fallbackAllowed(JsonNode metadata) {
        if (metadata == null || metadata.isMissingNode() || metadata.isNull()) {
            return true;
        }
        if (metadata.has("routerFallbackAllowed")) {
            return metadata.path("routerFallbackAllowed").asBoolean(true);
        }
        if (metadata.has("query_router_fallback_allowed")) {
            return metadata.path("query_router_fallback_allowed").asBoolean(true);
        }
        return true;
    }

    private boolean readyForRewrite(ChatRuntimeDtos.ChatDomainReadinessResponse readiness) {
        return readiness != null && readiness.readyForRewrite();
    }

    private String blockingReason(ChatRuntimeDtos.ChatDomainReadinessResponse readiness, String fallback) {
        if (readiness == null || readiness.blockingReasons() == null || readiness.blockingReasons().isEmpty()) {
            return fallback;
        }
        return String.join("; ", readiness.blockingReasons());
    }

    private boolean specificTechnicalQuery(String query, boolean korean, boolean technicalAnchor, int tokenCount) {
        if (!technicalAnchor || korean) {
            return false;
        }
        int anchorCount = technicalAnchorCount(query);
        return anchorCount >= 2 && tokenCount >= 3;
    }

    private boolean hasTechnicalAnchor(String query) {
        return technicalAnchorCount(query) > 0;
    }

    private int technicalAnchorCount(String query) {
        Matcher matcher = TECHNICAL_ANCHOR_PATTERN.matcher(query == null ? "" : query);
        int count = 0;
        while (matcher.find() && count < 4) {
            count++;
        }
        return count;
    }

    private int countTokens(String query) {
        Matcher matcher = TOKEN_PATTERN.matcher(query == null ? "" : query);
        int count = 0;
        while (matcher.find()) {
            count++;
        }
        return count;
    }

    private boolean hasKorean(String query) {
        return query != null && query.codePoints().anyMatch(codePoint -> codePoint >= 0xAC00 && codePoint <= 0xD7A3);
    }

    private boolean hasEnglish(String query) {
        return query != null && query.codePoints().anyMatch(codePoint ->
                (codePoint >= 'A' && codePoint <= 'Z') || (codePoint >= 'a' && codePoint <= 'z'));
    }

    private String normalize(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value.trim().toLowerCase(Locale.ROOT).replace("-", "_");
    }
}
