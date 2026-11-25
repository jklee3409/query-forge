package io.queryforge.backend.rag.chat.routing;

import io.queryforge.backend.rag.retrieval.routing.QueryRouteContext;
import io.queryforge.backend.rag.runtime.configuration.ChatConfigDtos;
import io.queryforge.backend.rag.runtime.readiness.ChatReadinessDtos;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ChatRequestRouting {

    private static final Pattern SEARCH_TOKEN_PATTERN = Pattern.compile("[@A-Za-z0-9_./:$-]{2,}|\\p{InHangulSyllables}{2,}");
    private static final Pattern TECHNICAL_TOKEN_PATTERN = Pattern.compile("[@A-Za-z_][A-Za-z0-9_./:$-]{1,}");

    private ChatRequestRouting() {
    }

    public static QueryRouteContext routeContext(
            String rawQuery,
            ChatConfigDtos.ChatRuntimeConfigResponse config,
            ChatReadinessDtos.ChatDomainReadinessResponse readiness,
            String mode,
            String rewriteQueryProfile,
            boolean anchorInjectionEnabled,
            boolean memoryCandidatesKnown,
            boolean memoryCandidatesAvailable,
            Double rawRetrievalConfidence
    ) {
        return new QueryRouteContext(
                rawQuery,
                config.domainId(),
                config,
                readiness,
                mode,
                rewriteQueryProfile,
                anchorInjectionEnabled,
                rawQuery == null ? 0 : rawQuery.length(),
                queryTokenCount(rawQuery),
                containsKorean(rawQuery),
                containsEnglish(rawQuery),
                containsTechnicalAnchor(rawQuery),
                memoryCandidatesKnown,
                memoryCandidatesAvailable,
                rawRetrievalConfidence,
                false
        );
    }

    private static int queryTokenCount(String query) {
        Matcher matcher = SEARCH_TOKEN_PATTERN.matcher(query == null ? "" : query);
        int count = 0;
        while (matcher.find()) {
            count++;
        }
        return count;
    }

    private static boolean containsKorean(String query) {
        return query != null && query.codePoints().anyMatch(codePoint -> codePoint >= 0xAC00 && codePoint <= 0xD7A3);
    }

    private static boolean containsEnglish(String query) {
        return query != null && query.codePoints().anyMatch(codePoint ->
                (codePoint >= 'A' && codePoint <= 'Z') || (codePoint >= 'a' && codePoint <= 'z'));
    }

    private static boolean containsTechnicalAnchor(String query) {
        if (query == null || query.isBlank()) {
            return false;
        }
        Matcher matcher = TECHNICAL_TOKEN_PATTERN.matcher(query);
        while (matcher.find()) {
            String token = matcher.group();
            if (token.contains(".")
                    || token.contains("/")
                    || token.contains(":")
                    || token.contains("$")
                    || token.contains("-")
                    || token.matches(".*[A-Z].*[A-Z].*")
                    || token.matches(".*\\d.*")
                    || token.endsWith("Exception")
                    || token.endsWith("Error")) {
                return true;
            }
        }
        return false;
    }
}
