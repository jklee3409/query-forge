package io.queryforge.backend.rag.chat.service;

import io.queryforge.backend.rag.chat.service.RewriteDecisionPolicy.Decision;
import io.queryforge.backend.rag.retrieval.candidate.model.RetrievalDoc;
import io.queryforge.backend.rag.retrieval.execution.dto.RetrievalExecutionDtos.ExecutedRewriteCandidate;
import io.queryforge.backend.rag.retrieval.execution.dto.RetrievalExecutionDtos.RetrievalMaterial;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RewriteDecisionPolicyTest {

    private final RetrievalMaterial raw = retrieval("original", 0.5d);

    @Test
    void keepsOriginalWhenForcedOrThereAreNoRewriteCandidates() {
        Decision forced = RewriteDecisionPolicy.decide("raw_only", raw, List.of(candidate(1, 0.75d)), 0.1d, null);
        Decision empty = RewriteDecisionPolicy.decide("selective_rewrite", raw, List.of(), 0.1d, null);

        assertThat(forced.rewriteApplied()).isFalse();
        assertThat(forced.selectedReason()).isEqualTo("raw_only");
        assertThat(forced.rejectedReason()).isEqualTo("mode=raw_only");
        assertThat(empty.finalRetrieved()).isEqualTo(raw.rerankedDocs());
        assertThat(empty.selectedReason()).isEqualTo("no_candidate");
    }

    @ParameterizedTest
    @ValueSource(strings = {"memory_only_ungated", "memory_only_gated"})
    void memoryModesUseMemoryResultsOrKeepOriginalWhenMemoryIsMissing(String mode) {
        RetrievalMaterial memory = retrieval("memory", 0.25d);
        List<ExecutedRewriteCandidate> candidates = List.of(candidate(1, 0.75d));

        Decision selected = RewriteDecisionPolicy.decide(mode, raw, candidates, 0.1d, memory);
        Decision fallback = RewriteDecisionPolicy.decide(mode, raw, candidates, 0.1d, null);

        assertThat(selected.rewriteApplied()).isTrue();
        assertThat(selected.finalQuery()).isEqualTo(memory.query());
        assertThat(selected.finalRetrieved()).isEqualTo(memory.rerankedDocs());
        assertThat(selected.selectedCandidate()).isNull();
        assertThat(fallback.finalQuery()).isEqualTo(raw.query());
        assertThat(fallback.selectedReason()).isEqualTo("memory_empty");
    }

    @Test
    void selectiveRewriteAcceptsEqualThresholdAndPreservesCandidateOrderOnTies() {
        ExecutedRewriteCandidate firstBest = candidate(2, 0.75d);
        List<ExecutedRewriteCandidate> candidates = List.of(candidate(1, 0.625d), firstBest, candidate(3, 0.75d));

        Decision selected = RewriteDecisionPolicy.decide("selective_rewrite", raw, candidates, 0.25d, null);
        Decision rejected = RewriteDecisionPolicy.decide("selective_rewrite", raw, candidates, 0.3d, null);
        Decision forced = RewriteDecisionPolicy.decide("rewrite_always", raw, candidates, 0.3d, null);

        assertThat(selected.selectedCandidate()).isSameAs(firstBest);
        assertThat(selected.selectedReason()).isEqualTo("delta_above_threshold");
        assertThat(rejected.finalQuery()).isEqualTo(raw.query());
        assertThat(rejected.selectedReason()).isEqualTo("delta_below_threshold");
        assertThat(forced.selectedCandidate()).isSameAs(firstBest);
        assertThat(forced.selectedReason()).isEqualTo("forced");
    }

    private ExecutedRewriteCandidate candidate(int index, double confidence) {
        String query = "rewrite " + index;
        return new ExecutedRewriteCandidate(index, query, query, null, retrieval(query, confidence));
    }

    private RetrievalMaterial retrieval(String query, double confidence) {
        List<RetrievalDoc> docs = List.of(new RetrievalDoc("doc", query, query, confidence));
        return new RetrievalMaterial(query, docs, docs, confidence, "retriever", "reranker", null, 0L);
    }
}
