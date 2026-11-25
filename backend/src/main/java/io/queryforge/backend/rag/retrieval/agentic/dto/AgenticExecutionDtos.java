package io.queryforge.backend.rag.retrieval.agentic.dto;

import com.fasterxml.jackson.databind.JsonNode;
import io.queryforge.backend.rag.retrieval.agentic.planning.dto.AgenticPlanningDtos;
import io.queryforge.backend.rag.retrieval.candidate.model.RetrievalDoc;
import io.queryforge.backend.rag.retrieval.execution.model.RagPersistPolicy;
import io.queryforge.backend.rag.retrieval.memory.model.MemoryCandidate;
import io.queryforge.backend.rag.runtime.configuration.dto.ChatConfigDtos;
import io.queryforge.backend.rag.runtime.readiness.dto.ChatReadinessDtos;

import java.util.List;
import java.util.UUID;

public final class AgenticExecutionDtos {

    private AgenticExecutionDtos() {
    }

    public record AgenticExecutionRequest(
            String rawQuery,
            UUID onlineQueryId,
            ChatConfigDtos.ChatRuntimeConfigResponse config,
            ChatReadinessDtos.ChatDomainReadinessResponse readiness,
            JsonNode sessionContext,
            List<MemoryCandidate> plannerMemoryHints,
            String mode,
            String rewriteQueryProfile,
            String memoryPreset,
            int retrievalTopK,
            int rerankTopN,
            int memoryTopN,
            int candidateCount,
            double threshold,
            int maxSubqueries,
            int rrfK,
            int finalTopK,
            RagPersistPolicy persistPolicy
    ) {
        public AgenticExecutionRequest {
            persistPolicy = persistPolicy == null ? RagPersistPolicy.ONLINE_QUERY : persistPolicy;
        }

        public AgenticExecutionRequest(
                String rawQuery,
                UUID onlineQueryId,
                ChatConfigDtos.ChatRuntimeConfigResponse config,
                ChatReadinessDtos.ChatDomainReadinessResponse readiness,
                JsonNode sessionContext,
                List<MemoryCandidate> plannerMemoryHints,
                String mode,
                String rewriteQueryProfile,
                String memoryPreset,
                int retrievalTopK,
                int rerankTopN,
                int memoryTopN,
                int candidateCount,
                double threshold,
                int maxSubqueries,
                int rrfK,
                int finalTopK
        ) {
            this(
                    rawQuery,
                    onlineQueryId,
                    config,
                    readiness,
                    sessionContext,
                    plannerMemoryHints,
                    mode,
                    rewriteQueryProfile,
                    memoryPreset,
                    retrievalTopK,
                    rerankTopN,
                    memoryTopN,
                    candidateCount,
                    threshold,
                    maxSubqueries,
                    rrfK,
                    finalTopK,
                    RagPersistPolicy.ONLINE_QUERY
            );
        }
    }

    public record AgenticExecutionResult(
            AgenticPlanningDtos.AgenticQueryPlan plan,
            List<AgenticRetrievalDtos.SubqueryRetrievalTrace> traces,
            List<PersistedRewriteCandidate> persistedCandidates,
            List<RetrievalDoc> mergedDocs,
            boolean rewriteApplied,
            String selectedReason,
            String rejectedReason,
            long planningLatencyMs,
            long totalLatencyMs,
            JsonNode metadata
    ) {
    }

    public record PersistedRewriteCandidate(
            UUID rewriteCandidateId,
            String label,
            String query,
            List<RetrievalDoc> retrieved,
            double confidence,
            boolean selected,
            String rejectedReason,
            JsonNode scoreBreakdown
    ) {
    }
}
