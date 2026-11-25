package io.queryforge.backend.rag.retrieval.execution.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.queryforge.backend.rag.retrieval.candidate.dto.RetrievalDtos;
import io.queryforge.backend.rag.retrieval.candidate.model.RetrievalDoc;
import io.queryforge.backend.rag.retrieval.memory.model.MemoryCandidate;

import java.util.List;
import java.util.UUID;

public final class RetrievalExecutionDtos {

    private RetrievalExecutionDtos() {
    }

    public record RawOnlyExecutionRequest(
            String originalQuery,
            String queryEmbeddingLiteral,
            int retrievalTopK,
            int rerankTopN,
            UUID domainId,
            RetrievalDtos.RetrievalRuntime retrievalRuntime
    ) {
        public RawOnlyExecutionRequest {
            if (originalQuery == null || originalQuery.isBlank()) {
                throw new IllegalArgumentException("originalQuery must not be blank");
            }
            if (queryEmbeddingLiteral == null || queryEmbeddingLiteral.isBlank()) {
                throw new IllegalArgumentException("queryEmbeddingLiteral must not be blank");
            }
            if (domainId == null) {
                throw new IllegalArgumentException("domainId is required");
            }
            if (retrievalRuntime == null) {
                throw new IllegalArgumentException("retrievalRuntime is required");
            }
            retrievalTopK = Math.max(1, retrievalTopK);
            rerankTopN = Math.max(1, rerankTopN);
        }
    }

    public enum NonAgenticExecutionKind {
        RAW_ONLY,
        SELECTIVE_REWRITE,
        ANCHOR_AWARE_REWRITE
    }

    public record RetrievalMaterial(
            String query,
            List<RetrievalDoc> retrievedDocs,
            List<RetrievalDoc> rerankedDocs,
            double confidence,
            String retrieverName,
            String rerankerModel,
            JsonNode retrievalMetadata,
            long latencyMs
    ) {
        public RetrievalMaterial {
            retrievedDocs = retrievedDocs == null ? List.of() : List.copyOf(retrievedDocs);
            rerankedDocs = rerankedDocs == null ? List.of() : List.copyOf(rerankedDocs);
            retrievalMetadata = retrievalMetadata == null ? JsonNodeFactory.instance.objectNode() : retrievalMetadata;
        }

        public List<String> retrievedChunkIds() {
            return retrievedDocs.stream()
                    .map(RetrievalDoc::chunkId)
                    .toList();
        }

        public List<String> rerankedChunkIds() {
            return rerankedDocs.stream()
                    .map(RetrievalDoc::chunkId)
                    .toList();
        }
    }

    public record RawOnlyExecutionResult(
            String originalQuery,
            String finalQuery,
            RetrievalMaterial rawRetrieval,
            long latencyMs
    ) {
        public RawOnlyExecutionResult {
            if (rawRetrieval == null) {
                throw new IllegalArgumentException("rawRetrieval is required");
            }
        }

        public List<RetrievalDoc> localRetrievedDocs() {
            return rawRetrieval.retrievedDocs();
        }

        public List<RetrievalDoc> rerankedDocs() {
            return rawRetrieval.rerankedDocs();
        }

        public double rawRetrievalConfidence() {
            return rawRetrieval.confidence();
        }

        public String retrieverName() {
            return rawRetrieval.retrieverName();
        }

        public JsonNode retrievalMetadata() {
            return rawRetrieval.retrievalMetadata();
        }
    }

    public record RewriteExecutionRequest(
            String rawQuery,
            JsonNode sessionContextSnapshot,
            List<MemoryCandidate> memoryCandidates,
            int candidateCount,
            String rewriteQueryProfile,
            ObjectNode domainContext,
            int retrievalTopK,
            int rerankTopN,
            UUID domainId,
            RetrievalDtos.RetrievalRuntime retrievalRuntime,
            double rawDenseScore,
            boolean anchorInjectionEnabled
    ) {
        public RewriteExecutionRequest {
            if (rawQuery == null || rawQuery.isBlank()) {
                throw new IllegalArgumentException("rawQuery must not be blank");
            }
            if (domainId == null) {
                throw new IllegalArgumentException("domainId is required");
            }
            if (retrievalRuntime == null) {
                throw new IllegalArgumentException("retrievalRuntime is required");
            }
            memoryCandidates = memoryCandidates == null ? List.of() : List.copyOf(memoryCandidates);
            candidateCount = Math.max(1, candidateCount);
            retrievalTopK = Math.max(1, retrievalTopK);
            rerankTopN = Math.max(1, rerankTopN);
        }
    }

    public record RewriteExecutionResult(
            List<ExecutedRewriteCandidate> candidates,
            boolean anchorInjectionApplied,
            long latencyMs
    ) {
        public RewriteExecutionResult {
            candidates = candidates == null ? List.of() : List.copyOf(candidates);
        }

        public NonAgenticExecutionKind executionKind() {
            return anchorInjectionApplied
                    ? NonAgenticExecutionKind.ANCHOR_AWARE_REWRITE
                    : NonAgenticExecutionKind.SELECTIVE_REWRITE;
        }
    }

    public record ExecutedRewriteCandidate(
            int index,
            String label,
            String query,
            JsonNode metadata,
            RetrievalMaterial retrieval
    ) {
        public ExecutedRewriteCandidate {
            metadata = metadata == null ? JsonNodeFactory.instance.objectNode() : metadata;
            if (retrieval == null) {
                throw new IllegalArgumentException("retrieval is required");
            }
        }

        public List<RetrievalDoc> localRetrievedDocs() {
            return retrieval.retrievedDocs();
        }

        public List<RetrievalDoc> rerankedDocs() {
            return retrieval.rerankedDocs();
        }

        public double confidence() {
            return retrieval.confidence();
        }

        public String retrieverName() {
            return retrieval.retrieverName();
        }

        public String rerankerModel() {
            return retrieval.rerankerModel();
        }

        public JsonNode retrievalMetadata() {
            return retrieval.retrievalMetadata();
        }

        public long latencyMs() {
            return retrieval.latencyMs();
        }

        public List<String> retrievedChunkIds() {
            return retrieval.retrievedChunkIds();
        }

        public List<String> rerankedChunkIds() {
            return retrieval.rerankedChunkIds();
        }
    }
}
