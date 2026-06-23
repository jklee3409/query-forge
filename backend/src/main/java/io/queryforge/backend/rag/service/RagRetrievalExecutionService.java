package io.queryforge.backend.rag.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.queryforge.backend.rag.repository.RagRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class RagRetrievalExecutionService {

    private final DomainScopedRetrievalService domainScopedRetrievalService;
    private final CohereRerankService cohereRerankService;
    private final RewriteCandidateService rewriteCandidateService;
    private final ObjectMapper objectMapper;

    public RawOnlyExecutionResult executeRawOnly(RawOnlyExecutionRequest request) {
        long started = System.nanoTime();
        List<RagRepository.RetrievalDoc> localRetrievedDocs = domainScopedRetrievalService.retrieveChunks(
                request.originalQuery(),
                request.queryEmbeddingLiteral(),
                request.retrievalTopK(),
                request.domainId(),
                request.retrievalRuntime()
        );
        List<RagRepository.RetrievalDoc> rerankedDocs = cohereRerankService.rerank(
                request.originalQuery(),
                localRetrievedDocs,
                request.rerankTopN()
        );
        return new RawOnlyExecutionResult(
                request.originalQuery(),
                request.originalQuery(),
                localRetrievedDocs,
                rerankedDocs,
                confidence(rerankedDocs, 0.0d),
                request.retrievalRuntime().retrieverName(),
                retrievalMetadata(request.retrievalRuntime()),
                elapsedMs(started)
        );
    }

    public SelectiveRewriteExecutionResult executeSelectiveRewrite(SelectiveRewriteExecutionRequest request) {
        long started = System.nanoTime();
        List<ExecutedRewriteCandidate> executedCandidates = executeRewriteCandidates(
                request.rawQuery(),
                request.sessionContextSnapshot(),
                request.memoryCandidates(),
                request.candidateCount(),
                request.rewriteQueryProfile(),
                request.domainContext(),
                request.retrievalTopK(),
                request.rerankTopN(),
                request.domainId(),
                request.retrievalRuntime(),
                request.rawDenseScore(),
                false
        );
        return new SelectiveRewriteExecutionResult(executedCandidates, elapsedMs(started));
    }

    public AnchorAwareRewriteExecutionResult executeAnchorAwareRewrite(AnchorAwareRewriteExecutionRequest request) {
        long started = System.nanoTime();
        List<ExecutedRewriteCandidate> executedCandidates = executeRewriteCandidates(
                request.rawQuery(),
                request.sessionContextSnapshot(),
                request.memoryCandidates(),
                request.candidateCount(),
                request.rewriteQueryProfile(),
                request.domainContext(),
                request.retrievalTopK(),
                request.rerankTopN(),
                request.domainId(),
                request.retrievalRuntime(),
                request.rawDenseScore(),
                true
        );
        return new AnchorAwareRewriteExecutionResult(executedCandidates, true, elapsedMs(started));
    }

    private List<ExecutedRewriteCandidate> executeRewriteCandidates(
            String rawQuery,
            JsonNode sessionContextSnapshot,
            List<RagRepository.MemoryCandidate> memoryCandidates,
            int candidateCount,
            String rewriteQueryProfile,
            ObjectNode domainContext,
            int retrievalTopK,
            int rerankTopN,
            UUID domainId,
            DomainScopedRetrievalService.RetrievalRuntime retrievalRuntime,
            double rawDenseScore,
            boolean anchorInjectionEnabled
    ) {
        List<RewriteCandidateService.CandidateTemplate> generatedCandidates = rewriteCandidateService.buildCandidates(
                rawQuery,
                sessionContextSnapshot,
                memoryCandidates,
                candidateCount,
                rewriteQueryProfile,
                anchorInjectionEnabled,
                domainContext
        );
        return generatedCandidates.stream()
                .map(generated -> executeCandidate(
                        generated,
                        retrievalTopK,
                        rerankTopN,
                        domainId,
                        retrievalRuntime,
                        rawDenseScore
                ))
                .toList();
    }

    private ExecutedRewriteCandidate executeCandidate(
            RewriteCandidateService.CandidateTemplate generated,
            int retrievalTopK,
            int rerankTopN,
            UUID domainId,
            DomainScopedRetrievalService.RetrievalRuntime retrievalRuntime,
            double rawDenseScore
    ) {
        String embeddingLiteral = domainScopedRetrievalService.embeddingLiteral(
                generated.query(),
                retrievalRuntime
        );
        List<RagRepository.RetrievalDoc> localRetrievedDocs = domainScopedRetrievalService.retrieveChunks(
                generated.query(),
                embeddingLiteral,
                retrievalTopK,
                domainId,
                retrievalRuntime
        );
        List<RagRepository.RetrievalDoc> rerankedDocs = cohereRerankService.rerank(
                generated.query(),
                localRetrievedDocs,
                rerankTopN
        );
        return new ExecutedRewriteCandidate(
                generated.label(),
                generated.query(),
                localRetrievedDocs,
                rerankedDocs,
                confidence(rerankedDocs, rawDenseScore)
        );
    }

    private ObjectNode retrievalMetadata(DomainScopedRetrievalService.RetrievalRuntime runtime) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("retrieval_backend", runtime.retrievalBackend());
        node.put("dense_embedding_model", runtime.denseEmbeddingModel());
        node.put("retriever_mode", runtime.retrieverMode());
        node.put("retriever_candidate_pool_k", runtime.candidatePoolK());
        ObjectNode weights = node.putObject("retriever_fusion_weights");
        weights.put("dense", runtime.denseWeight());
        weights.put("bm25", runtime.bm25Weight());
        weights.put("technical", runtime.technicalWeight());
        return node;
    }

    private double confidence(List<RagRepository.RetrievalDoc> docs, double denseScore) {
        if (docs.isEmpty()) {
            return 0.0;
        }
        double r1 = normalizeScore(docs.getFirst().score());
        int topN = Math.min(3, docs.size());
        double r3 = 0.0;
        for (int index = 0; index < topN; index++) {
            r3 += normalizeScore(docs.get(index).score());
        }
        r3 /= topN;
        double dense = normalizeScore(denseScore);
        return (0.6 * r1) + (0.3 * r3) + (0.1 * dense);
    }

    private double normalizeScore(double score) {
        return Math.max(0.0, Math.min(1.0, (score + 1.0) / 2.0));
    }

    private long elapsedMs(long startedNano) {
        return (System.nanoTime() - startedNano) / 1_000_000L;
    }

    public record RawOnlyExecutionRequest(
            String originalQuery,
            String queryEmbeddingLiteral,
            int retrievalTopK,
            int rerankTopN,
            UUID domainId,
            DomainScopedRetrievalService.RetrievalRuntime retrievalRuntime
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

    public record RawOnlyExecutionResult(
            String originalQuery,
            String finalQuery,
            List<RagRepository.RetrievalDoc> localRetrievedDocs,
            List<RagRepository.RetrievalDoc> rerankedDocs,
            double rawRetrievalConfidence,
            String retrieverName,
            JsonNode retrievalMetadata,
            long latencyMs
    ) {
        public RawOnlyExecutionResult {
            localRetrievedDocs = localRetrievedDocs == null ? List.of() : List.copyOf(localRetrievedDocs);
            rerankedDocs = rerankedDocs == null ? List.of() : List.copyOf(rerankedDocs);
        }
    }

    public record SelectiveRewriteExecutionRequest(
            String rawQuery,
            JsonNode sessionContextSnapshot,
            List<RagRepository.MemoryCandidate> memoryCandidates,
            int candidateCount,
            String rewriteQueryProfile,
            ObjectNode domainContext,
            int retrievalTopK,
            int rerankTopN,
            UUID domainId,
            DomainScopedRetrievalService.RetrievalRuntime retrievalRuntime,
            double rawDenseScore
    ) {
        public SelectiveRewriteExecutionRequest {
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

    public record SelectiveRewriteExecutionResult(
            List<ExecutedRewriteCandidate> candidates,
            long latencyMs
    ) {
        public SelectiveRewriteExecutionResult {
            candidates = candidates == null ? List.of() : List.copyOf(candidates);
        }
    }

    public record AnchorAwareRewriteExecutionRequest(
            String rawQuery,
            JsonNode sessionContextSnapshot,
            List<RagRepository.MemoryCandidate> memoryCandidates,
            int candidateCount,
            String rewriteQueryProfile,
            ObjectNode domainContext,
            int retrievalTopK,
            int rerankTopN,
            UUID domainId,
            DomainScopedRetrievalService.RetrievalRuntime retrievalRuntime,
            double rawDenseScore
    ) {
        public AnchorAwareRewriteExecutionRequest {
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

    public record AnchorAwareRewriteExecutionResult(
            List<ExecutedRewriteCandidate> candidates,
            boolean anchorInjectionApplied,
            long latencyMs
    ) {
        public AnchorAwareRewriteExecutionResult {
            candidates = candidates == null ? List.of() : List.copyOf(candidates);
        }
    }

    public record ExecutedRewriteCandidate(
            String label,
            String query,
            List<RagRepository.RetrievalDoc> localRetrievedDocs,
            List<RagRepository.RetrievalDoc> rerankedDocs,
            double confidence
    ) {
        public ExecutedRewriteCandidate {
            localRetrievedDocs = localRetrievedDocs == null ? List.of() : List.copyOf(localRetrievedDocs);
            rerankedDocs = rerankedDocs == null ? List.of() : List.copyOf(rerankedDocs);
        }
    }
}
