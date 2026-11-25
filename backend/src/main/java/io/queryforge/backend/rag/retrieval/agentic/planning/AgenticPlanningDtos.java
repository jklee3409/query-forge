package io.queryforge.backend.rag.retrieval.agentic.planning;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.UUID;

public final class AgenticPlanningDtos {

    private AgenticPlanningDtos() {
    }

    public record AgenticSubquery(
            int index,
            String query,
            String intent,
            double weight,
            JsonNode metadata
    ) {
    }

    public record AgenticQueryPlan(
            String originalQuery,
            UUID domainId,
            String domainKey,
            String domainName,
            int maxSubqueries,
            List<AgenticSubquery> subqueries,
            String plannerModel,
            boolean fallbackApplied,
            String fallbackReason,
            JsonNode metadata
    ) {
    }
}
