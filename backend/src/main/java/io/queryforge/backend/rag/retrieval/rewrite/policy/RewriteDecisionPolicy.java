package io.queryforge.backend.rag.retrieval.rewrite.policy;

import io.queryforge.backend.rag.retrieval.candidate.model.RetrievalDoc;
import io.queryforge.backend.rag.retrieval.execution.dto.RetrievalExecutionDtos.ExecutedRewriteCandidate;
import io.queryforge.backend.rag.retrieval.execution.dto.RetrievalExecutionDtos.RetrievalMaterial;
import io.queryforge.backend.rag.retrieval.routing.dto.QueryRouteDecision;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.ToDoubleFunction;

public final class RewriteDecisionPolicy {

    private RewriteDecisionPolicy() {
    }

    public static boolean usesMemoryQuery(String mode) {
        return "memory_only_ungated".equals(mode) || "memory_only_gated".equals(mode);
    }

    public static Decision routerRawOnly(RetrievalMaterial raw, QueryRouteDecision routeDecision) {
        return keepOriginal(raw, routeDecision.reason(), routerRejectionReason(routeDecision));
    }

    public static Decision decide(
            String mode,
            RetrievalMaterial raw,
            List<ExecutedRewriteCandidate> candidates,
            double threshold,
            RetrievalMaterial memory
    ) {
        if ("raw_only".equals(mode)) {
            return keepOriginal(raw, "raw_only", "mode=raw_only");
        }
        if (usesMemoryQuery(mode)) {
            return memory == null
                    ? keepOriginal(raw, "memory_empty", "no memory candidate")
                    : new Decision(memory.query(), true, memory.rerankedDocs(), null, "memory_only", null);
        }
        Selection<ExecutedRewriteCandidate> selection = select(
                mode, raw.confidence(), candidates, ExecutedRewriteCandidate::confidence, threshold
        );
        return selection.candidate() == null
                ? keepOriginal(raw, selection.selectedReason(), selection.rejectedReason())
                : adopt(selection.candidate(), selection.selectedReason());
    }

    public static String routerRejectionReason(QueryRouteDecision routeDecision) {
        return routeDecision.fallbackApplied()
                ? routeDecision.fallbackReason()
                : "query_router_strategy=" + routeDecision.strategy().name().toLowerCase(Locale.ROOT);
    }

    public static ExecutedRewriteCandidate bestLegacyEvalCandidate(List<ExecutedRewriteCandidate> candidates) {
        return candidates.stream()
                .max(Comparator.comparingDouble(ExecutedRewriteCandidate::confidence)
                        .thenComparingInt(candidate -> -candidate.index()))
                .orElse(null);
    }

    public static <T> Selection<T> select(
            String mode,
            double rawConfidence,
            List<T> candidates,
            ToDoubleFunction<T> confidence,
            double threshold
    ) {
        T best = candidates.stream().max(Comparator.comparingDouble(confidence)).orElse(null);
        if (best == null) {
            return new Selection<>(null, "no_candidate", "rewrite candidate missing");
        }
        if ("rewrite_always".equals(mode)) {
            return new Selection<>(best, "forced", null);
        }
        if (confidence.applyAsDouble(best) - rawConfidence >= threshold) {
            return new Selection<>(best, "delta_above_threshold", null);
        }
        return new Selection<>(null, "delta_below_threshold", "best-candidate delta below threshold");
    }

    public record Selection<T>(T candidate, String selectedReason, String rejectedReason) {
    }

    private static Decision adopt(ExecutedRewriteCandidate candidate, String reason) {
        return new Decision(candidate.query(), true, candidate.rerankedDocs(), candidate, reason, null);
    }

    private static Decision keepOriginal(RetrievalMaterial raw, String reason, String rejectedReason) {
        return new Decision(raw.query(), false, raw.rerankedDocs(), null, reason, rejectedReason);
    }

    public record Decision(
            String finalQuery,
            boolean rewriteApplied,
            List<RetrievalDoc> finalRetrieved,
            ExecutedRewriteCandidate selectedCandidate,
            String selectedReason,
            String rejectedReason
    ) {
    }
}
