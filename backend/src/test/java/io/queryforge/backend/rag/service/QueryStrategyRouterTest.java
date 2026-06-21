package io.queryforge.backend.rag.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.queryforge.backend.rag.model.ChatRuntimeDtos;
import io.queryforge.backend.rag.model.QueryRouteContext;
import io.queryforge.backend.rag.model.QueryRouteDecision;
import io.queryforge.backend.rag.model.QueryStrategy;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class QueryStrategyRouterTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final QueryStrategyRouter router = new QueryStrategyRouter();

    @Test
    void routerDisabledSelectsRawOnlyDecisionWithoutActivatingServicePolicy() {
        QueryRouteDecision decision = router.route(context(
                "스프링 시큐리티 필터 순서",
                config("selective_rewrite", false, false),
                readiness(true),
                false,
                false
        ));

        assertThat(decision.strategy()).isEqualTo(QueryStrategy.RAW_ONLY);
        assertThat(decision.routerEnabled()).isFalse();
        assertThat(decision.reason()).isEqualTo("router_disabled");
    }

    @Test
    void rawOnlyModeSelectsRawOnly() {
        QueryRouteDecision decision = router.route(context(
                "스프링 시큐리티 필터 순서",
                config("raw_only", true, false),
                readiness(true),
                false,
                false
        ));

        assertThat(decision.strategy()).isEqualTo(QueryStrategy.RAW_ONLY);
        assertThat(decision.routerEnabled()).isTrue();
        assertThat(decision.fallbackApplied()).isFalse();
        assertThat(decision.reason()).isEqualTo("mode_raw_only");
    }

    @Test
    void rewriteReadinessFailureFallsBackToRawOnlyWhenRouterIsEnabled() {
        QueryRouteDecision decision = router.route(context(
                "스프링 시큐리티 필터 순서",
                config("selective_rewrite", true, false),
                readiness(false, "selected snapshot has no built synthetic memory"),
                false,
                false
        ));

        assertThat(decision.strategy()).isEqualTo(QueryStrategy.RAW_ONLY);
        assertThat(decision.fallbackAllowed()).isTrue();
        assertThat(decision.fallbackApplied()).isTrue();
        assertThat(decision.fallbackReason()).contains("synthetic memory");
    }

    @Test
    void rewriteModeWithReadinessSelectsSyntheticSelectiveRewrite() {
        QueryRouteDecision decision = router.route(context(
                "스프링 부트 설정 방법",
                config("selective_rewrite", true, false),
                readiness(true),
                false,
                false
        ));

        assertThat(decision.strategy()).isEqualTo(QueryStrategy.SYNTHETIC_SELECTIVE_REWRITE);
        assertThat(decision.reason()).isEqualTo("rewrite_backed_mode_ready");
    }

    @Test
    void anchorInjectionWithTechnicalAnchorSelectsAnchorAwareRewrite() {
        QueryRouteDecision decision = router.route(context(
                "Spring Security FilterChainProxy 순서",
                config("selective_rewrite", true, true),
                readiness(true),
                false,
                false
        ));

        assertThat(decision.strategy()).isEqualTo(QueryStrategy.ANCHOR_AWARE_REWRITE);
        assertThat(decision.anchorInjectionEnabled()).isTrue();
        assertThat(decision.reason()).isEqualTo("anchor_injection_enabled_and_technical_anchor_detected");
    }

    private QueryRouteContext context(
            String query,
            ChatRuntimeDtos.ChatRuntimeConfigResponse config,
            ChatRuntimeDtos.ChatDomainReadinessResponse readiness,
            boolean memoryCandidatesKnown,
            boolean memoryCandidatesAvailable
    ) {
        return new QueryRouteContext(
                query,
                config.domainId(),
                config,
                readiness,
                config.mode(),
                config.rewriteQueryProfile(),
                config.rewriteAnchorInjectionEnabled(),
                query.length(),
                query.split("\\s+").length,
                query.codePoints().anyMatch(codePoint -> codePoint >= 0xAC00 && codePoint <= 0xD7A3),
                query.codePoints().anyMatch(codePoint -> Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.LATIN),
                false,
                memoryCandidatesKnown,
                memoryCandidatesAvailable,
                null
        );
    }

    private ChatRuntimeDtos.ChatRuntimeConfigResponse config(String mode, boolean routerEnabled, boolean anchorInjectionEnabled) {
        ObjectNode metadata = objectMapper.createObjectNode();
        if (routerEnabled) {
            metadata.put("routerEnabled", true);
        }
        UUID domainId = UUID.randomUUID();
        return new ChatRuntimeDtos.ChatRuntimeConfigResponse(
                domainId,
                "spring",
                "Spring",
                "en",
                true,
                mode,
                List.of("KO_TECHNICAL"),
                "full_gating",
                null,
                null,
                List.of(),
                List.of(),
                "compact_anchor",
                anchorInjectionEnabled,
                false,
                "local",
                "intfloat/multilingual-e5-small",
                "hybrid",
                20,
                0.6,
                0.32,
                0.08,
                10,
                5,
                5,
                2,
                0.05,
                "skip_to_raw",
                routerEnabled,
                metadata,
                Instant.now(),
                true,
                "ready"
        );
    }

    private ChatRuntimeDtos.ChatDomainReadinessResponse readiness(boolean ready, String... reasons) {
        UUID domainId = UUID.randomUUID();
        return new ChatRuntimeDtos.ChatDomainReadinessResponse(
                domainId,
                "spring",
                "Spring",
                "en",
                true,
                true,
                "selective_rewrite",
                true,
                List.of("KO_TECHNICAL"),
                "full_gating",
                null,
                null,
                ready ? 3L : 0L,
                ready ? 3L : 0L,
                null,
                null,
                ready,
                List.of(reasons),
                Instant.now()
        );
    }
}
