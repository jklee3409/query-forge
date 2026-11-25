package io.queryforge.backend.rag.chat.service;

import io.queryforge.backend.rag.retrieval.candidate.model.RetrievalDoc;
import io.queryforge.backend.rag.retrieval.execution.dto.RetrievalExecutionDtos.ExecutedRewriteCandidate;
import io.queryforge.backend.rag.retrieval.execution.dto.RetrievalExecutionDtos.RetrievalMaterial;
import io.queryforge.backend.rag.retrieval.routing.dto.QueryRouteDecision;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;

final class RewriteDecisionPolicy {

    private RewriteDecisionPolicy() {
    }

    static boolean usesMemoryQuery(String mode) {
        return "memory_only_ungated".equals(mode) || "memory_only_gated".equals(mode);
    }

    static Decision routerRawOnly(RetrievalMaterial raw, QueryRouteDecision routeDecision) {
        String rejectedReason = routeDecision.fallbackApplied()
                ? routeDecision.fallbackReason()
                : "query_router_strategy=" + routeDecision.strategy().name().toLowerCase(Locale.ROOT);
        return keepOriginal(raw, routeDecision.reason(), rejectedReason);
    }

    static Decision decide(
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
        ExecutedRewriteCandidate best = candidates.stream()
                .max(Comparator.comparingDouble(ExecutedRewriteCandidate::confidence))
                .orElse(null);
        if (best == null) {
            return keepOriginal(raw, "no_candidate", "rewrite candidate missing");
        }
        if ("rewrite_always".equals(mode)) {
            return adopt(best, "forced");
        }
        if (best.confidence() - raw.confidence() >= threshold) {
            return adopt(best, "delta_above_threshold");
        }
        return keepOriginal(raw, "delta_below_threshold", "best-candidate delta below threshold");
    }

    private static Decision adopt(ExecutedRewriteCandidate candidate, String reason) {
        return new Decision(candidate.query(), true, candidate.rerankedDocs(), candidate, reason, null);
    }

    private static Decision keepOriginal(RetrievalMaterial raw, String reason, String rejectedReason) {
        return new Decision(raw.query(), false, raw.rerankedDocs(), null, reason, rejectedReason);
    }

    record Decision(
            String finalQuery,
            boolean rewriteApplied,
            List<RetrievalDoc> finalRetrieved,
            ExecutedRewriteCandidate selectedCandidate,
            String selectedReason,
            String rejectedReason
    ) {
    }
}
