package io.queryforge.backend.rag.retrieval.rewrite.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.queryforge.backend.rag.provider.environment.service.RuntimeEnvService;
import io.queryforge.backend.rag.provider.springai.gateway.SpringAiChatGateway;
import io.queryforge.backend.rag.provider.springai.gateway.SpringAiChatGateway.Completion;
import io.queryforge.backend.rag.retrieval.memory.model.MemoryCandidate;
import io.queryforge.backend.rag.retrieval.rewrite.dto.RewriteDtos.CandidateGeneration;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RewriteGenerationDiagnosticsTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void distinguishesSkippedProviderFromUnusableResponse() {
        assertThat(observe(new Completion(false, null, null)).diagnostics().attempted()).isFalse();

        var result = observe(new Completion(true, "", "test-model"));

        assertThat(result.diagnostics().attempted()).isTrue();
        assertThat(result.diagnostics().attemptCount()).isEqualTo(1);
        assertThat(result.diagnostics().heuristicFallback()).isTrue();
        assertThat(result.diagnostics().failureReason()).isEqualTo("provider_response_unusable");
        assertThat(result.candidates()).isNotEmpty();
    }

    @Test
    void tracksActualModelForSuccessfulGeneration() {
        var result = observe(new Completion(true, """
                {"candidates":[{"label":"expanded","query":"FilterChainProxy order configuration"}]}
                """, "actual-model"));

        assertThat(result.diagnostics().succeeded()).isTrue();
        assertThat(result.diagnostics().heuristicFallback()).isFalse();
        assertThat(result.diagnostics().model()).isEqualTo("actual-model");
        assertThat(result.diagnostics().promptAssetId()).isNotBlank();
        assertThat(result.diagnostics().failureReason()).isNull();
        assertThat(result.candidates()).hasSize(1);
    }

    @Test
    void recordsAnchorHintsOnlyWhenTheyAreInTheProviderPrompt() {
        var glossary = mapper.createArrayNode().add("FilterChainProxy");
        var metadata = mapper.createObjectNode();
        metadata.putArray("canonical_anchors").addObject().put("canonical_form", "SecurityFilterChain");
        var memory = new MemoryCandidate(null, "How do I configure filters?", "doc", null, glossary, metadata,
                0.5d, "A", null, null, null, null, "batch");
        var service = serviceReturning(new Completion(true, """
                {"candidates":[{"label":"expanded","query":"FilterChainProxy order configuration"}]}
                """, "actual-model"));

        var off = service.buildObservedCandidates("How are filters ordered?", mapper.createObjectNode(),
                List.of(memory), 1, "compact_anchor", false, mapper.createObjectNode());
        var on = service.buildObservedCandidates("How are filters ordered?", mapper.createObjectNode(),
                List.of(memory), 1, "compact_anchor", true, mapper.createObjectNode());

        var offMemory = off.promptMemories().get(0);
        var onMemory = on.promptMemories().get(0);
        assertThat(offMemory.has("glossary_terms")).isFalse();
        assertThat(offMemory.has("canonical_anchors")).isFalse();
        assertThat(onMemory.path("glossary_terms")).isEqualTo(glossary);
        assertThat(onMemory.path("canonical_anchors")).isEqualTo(metadata.path("canonical_anchors"));
        assertThat(offMemory.path("source_gating_batch_id")).isEqualTo(onMemory.path("source_gating_batch_id"));
        assertThat(off.candidates()).isEqualTo(on.candidates());
    }

    private CandidateGeneration observe(Completion completion) {
        return serviceReturning(completion).buildObservedCandidates(
                "FilterChainProxy", mapper.createObjectNode(), List.of(), 1,
                "compact_anchor", false, mapper.createObjectNode()
        );
    }

    private RewriteCandidateService serviceReturning(Completion completion) {
        RuntimeEnvService environment = mock(RuntimeEnvService.class);
        when(environment.getOrDefault(anyString(), anyString())).thenAnswer(call -> call.getArgument(1));
        SpringAiChatGateway gateway = mock(SpringAiChatGateway.class);
        when(gateway.completeJsonObserved(anyString(), anyString(), anyString())).thenReturn(completion);
        return new RewriteCandidateService(mapper, environment, gateway);
    }
}
