package io.queryforge.backend.rag.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class RagRetrievalExecutionModelTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void persistPolicyValuesRemainStable() {
        assertThat(RagPersistPolicy.values())
                .containsExactly(
                        RagPersistPolicy.NONE,
                        RagPersistPolicy.TRACE_ONLY,
                        RagPersistPolicy.ONLINE_QUERY
                );
    }

    @Test
    void forcedRetrievalModeValuesRemainSeparateFromRouterStrategy() {
        assertThat(ForcedRetrievalMode.values())
                .containsExactly(
                        ForcedRetrievalMode.RAW_ONLY,
                        ForcedRetrievalMode.SELECTIVE_REWRITE,
                        ForcedRetrievalMode.ANCHOR_AWARE_REWRITE,
                        ForcedRetrievalMode.AGENTIC_MULTI_QUERY,
                        ForcedRetrievalMode.STRATEGY_ROUTER
                );
        assertThat(QueryStrategy.values())
                .extracting(Enum::name)
                .containsExactly(
                        "RAW_ONLY",
                        "SYNTHETIC_SELECTIVE_REWRITE",
                        "ANCHOR_AWARE_REWRITE",
                        "AGENTIC_MULTI_QUERY"
                );
        assertThat(ForcedRetrievalMode.AGENTIC_MULTI_QUERY.name())
                .isEqualTo(QueryStrategy.AGENTIC_MULTI_QUERY.name());
        assertThat(ForcedRetrievalMode.AGENTIC_MULTI_QUERY.getClass())
                .isNotEqualTo(QueryStrategy.AGENTIC_MULTI_QUERY.getClass());
    }

    @Test
    void requestDefaultsPersistPolicyToNoneWhenMissing() throws Exception {
        UUID domainId = UUID.randomUUID();
        RagRetrievalExecutionRequest request = objectMapper.readValue("""
                {
                  "domainId": "%s",
                  "query": "Spring Security filter chain order",
                  "forcedMode": "RAW_ONLY",
                  "topK": 5,
                  "includeTrace": true,
                  "evalRunId": "eval-2026-06-23",
                  "sampleId": "sample-001",
                  "metadata": {"source": "eval"}
                }
                """.formatted(domainId), RagRetrievalExecutionRequest.class);

        assertThat(request.domainId()).isEqualTo(domainId);
        assertThat(request.persistPolicy()).isEqualTo(RagPersistPolicy.NONE);
        assertThat(request.forcedMode()).isEqualTo(ForcedRetrievalMode.RAW_ONLY);
        assertThat(request.metadata().path("source").asText()).isEqualTo("eval");
    }

    @Test
    void requestResultAndTraceRoundTripThroughJson() throws Exception {
        UUID domainId = UUID.randomUUID();
        JsonNode metadata = objectMapper.readTree("{\"source\":\"phase2-model-test\"}");
        RagRetrievedDoc doc = new RagRetrievedDoc(
                "chunk-1",
                "doc-1",
                domainId,
                "Spring Security Reference",
                "https://docs.spring.io/spring-security/reference/",
                "FilterChainProxy delegates to matching SecurityFilterChain entries.",
                0.91,
                1,
                "db_ann",
                metadata
        );
        RagExecutionTrace trace = new RagExecutionTrace(
                "Spring Security filter order",
                "Spring Security FilterChainProxy SecurityFilterChain order",
                ForcedRetrievalMode.SELECTIVE_REWRITE,
                "SYNTHETIC_SELECTIVE_REWRITE",
                metadata,
                metadata,
                null,
                null,
                metadata,
                null,
                null,
                42,
                metadata
        );
        RagRetrievalExecutionResult result = new RagRetrievalExecutionResult(
                List.of("chunk-1"),
                List.of(doc),
                "Spring Security FilterChainProxy SecurityFilterChain order",
                "Spring Security filter order",
                ForcedRetrievalMode.SELECTIVE_REWRITE,
                "SYNTHETIC_SELECTIVE_REWRITE",
                trace,
                new RagLlmCallCount(1, 0, 0, 1),
                57,
                false,
                null
        );

        String json = objectMapper.writeValueAsString(result);
        RagRetrievalExecutionResult roundTripped = objectMapper.readValue(json, RagRetrievalExecutionResult.class);

        assertThat(roundTripped.retrievedChunkIds()).containsExactly("chunk-1");
        assertThat(roundTripped.retrievedDocs()).hasSize(1);
        assertThat(roundTripped.retrievedDocs().getFirst().domainId()).isEqualTo(domainId);
        assertThat(roundTripped.trace().forcedMode()).isEqualTo(ForcedRetrievalMode.SELECTIVE_REWRITE);
        assertThat(roundTripped.llmCallCount().answerCalls()).isZero();
        assertThat(roundTripped.latencyMs()).isEqualTo(57);
    }
}
