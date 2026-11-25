package io.queryforge.backend.rag.retrieval.agentic;

import com.fasterxml.jackson.databind.JsonNode;
import io.queryforge.backend.rag.retrieval.agentic.planning.AgenticPlanningDtos.AgenticQueryPlan;
import io.queryforge.backend.rag.retrieval.candidate.RetrievalDtos.ScoredDocumentDto;
import io.queryforge.backend.rag.retrieval.rewrite.RewriteDtos.RewriteCandidateDto;

import java.util.List;

public final class AgenticRetrievalDtos {

    private AgenticRetrievalDtos() {
    }

    public record SubqueryRetrievalTrace(
            int subqueryIndex,
            String subquery,
            String effectiveQuery,
            boolean rewriteApplied,
            String routeStrategy,
            String selectedReason,
            String rejectedReason,
            List<ScoredDocumentDto> retrievedDocs,
            List<RewriteCandidateDto> rewriteCandidates,
            JsonNode memoryTopN,
            long latencyMs,
            JsonNode metadata
    ) {
    }

    public record AgenticRetrievalMetadata(
            AgenticQueryPlan plan,
            List<SubqueryRetrievalTrace> subqueryTraces,
            String mergeStrategy,
            int rrfK,
            int finalTopK,
            List<ScoredDocumentDto> mergedDocs,
            JsonNode metadata
    ) {
    }
}
