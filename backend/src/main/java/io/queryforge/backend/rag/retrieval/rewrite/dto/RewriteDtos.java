package io.queryforge.backend.rag.retrieval.rewrite.dto;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.UUID;

public final class RewriteDtos {

    private RewriteDtos() {
    }

    public record RewriteCandidateDto(
            UUID rewriteCandidateId,
            String label,
            String candidateQuery,
            double confidenceScore,
            boolean adopted,
            String rejectedReason,
            JsonNode retrievalTopKDocs,
            JsonNode scoreBreakdown
    ) {
    }

    public record CandidateGeneration(
            List<CandidateTemplate> candidates,
            GenerationDiagnostics diagnostics,
            JsonNode promptMemories
    ) {
    }

    public record GenerationDiagnostics(
            boolean attempted,
            boolean succeeded,
            boolean heuristicFallback,
            int attemptCount,
            String promptAssetId,
            String provider,
            String model,
            String failureReason,
            long latencyMs
    ) {
    }

    public record CandidateTemplate(String label, String query) {
    }
}
