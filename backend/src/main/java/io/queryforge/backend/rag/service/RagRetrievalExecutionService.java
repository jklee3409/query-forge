package io.queryforge.backend.rag.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
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
                new RetrievalMaterial(
                        request.originalQuery(),
                        localRetrievedDocs,
                        rerankedDocs,
                        confidence(rerankedDocs, 0.0d),
                        request.retrievalRuntime().retrieverName(),
                        cohereRerankService.modelName(),
                        retrievalMetadata(request.retrievalRuntime()),
                        elapsedMs(started)
                ),
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
        return new SelectiveRewriteExecutionResult(
                request.rawQuery(),
                executedCandidates,
                request.retrievalRuntime().retrieverName(),
                retrievalMetadata(request.retrievalRuntime()),
                elapsedMs(started)
        );
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
        return new AnchorAwareRewriteExecutionResult(
                request.rawQuery(),
                executedCandidates,
                true,
                request.retrievalRuntime().retrieverName(),
                retrievalMetadata(request.retrievalRuntime()),
                elapsedMs(started)
        );
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
        List<ExecutedRewriteCandidate> executedCandidates = new java.util.ArrayList<>();
        for (int index = 0; index < generatedCandidates.size(); index++) {
            RewriteCandidateService.CandidateTemplate generated = generatedCandidates.get(index);
            executedCandidates.add(executeCandidate(
                    index + 1,
                    generated,
                    retrievalTopK,
                    rerankTopN,
                    domainId,
                    retrievalRuntime,
                    rawDenseScore
            ));
        }
        return List.copyOf(executedCandidates);
    }

    private ExecutedRewriteCandidate executeCandidate(
            int candidateIndex,
            RewriteCandidateService.CandidateTemplate generated,
            int retrievalTopK,
            int rerankTopN,
            UUID domainId,
            DomainScopedRetrievalService.RetrievalRuntime retrievalRuntime,
            double rawDenseScore
    ) {
        long started = System.nanoTime();
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
                candidateIndex,
                generated.label(),
                generated.query(),
                candidateMetadata(candidateIndex, generated.label()),
                new RetrievalMaterial(
                        generated.query(),
                        localRetrievedDocs,
                        rerankedDocs,
                        confidence(rerankedDocs, rawDenseScore),
                        retrievalRuntime.retrieverName(),
                        cohereRerankService.modelName(),
                        retrievalMetadata(retrievalRuntime),
                        elapsedMs(started)
                )
        );
    }

    private ObjectNode candidateMetadata(int candidateIndex, String label) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("candidate_index", candidateIndex);
        node.put("label", label == null ? "" : label);
        return node;
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

    public interface NonAgenticExecutionResult {
        NonAgenticExecutionKind executionKind();

        String rawQuery();

        default RetrievalMaterial rawRetrieval() {
            return null;
        }

        default List<ExecutedRewriteCandidate> candidateExecutions() {
            return List.of();
        }

        default boolean anchorInjectionApplied() {
            return false;
        }

        String retrieverName();

        JsonNode retrievalMetadata();

        long latencyMs();

        default List<String> rawRerankedChunkIds() {
            RetrievalMaterial material = rawRetrieval();
            return material == null ? List.of() : material.rerankedChunkIds();
        }
    }

    public enum NonAgenticExecutionKind {
        RAW_ONLY,
        SELECTIVE_REWRITE,
        ANCHOR_AWARE_REWRITE
    }

    public record RetrievalMaterial(
            String query,
            List<RagRepository.RetrievalDoc> retrievedDocs,
            List<RagRepository.RetrievalDoc> rerankedDocs,
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
                    .map(RagRepository.RetrievalDoc::chunkId)
                    .toList();
        }

        public List<String> rerankedChunkIds() {
            return rerankedDocs.stream()
                    .map(RagRepository.RetrievalDoc::chunkId)
                    .toList();
        }
    }

    public record RawOnlyExecutionResult(
            String originalQuery,
            String finalQuery,
            RetrievalMaterial rawRetrieval,
            long latencyMs
    ) implements NonAgenticExecutionResult {
        public RawOnlyExecutionResult {
            if (rawRetrieval == null) {
                throw new IllegalArgumentException("rawRetrieval is required");
            }
        }

        @Override
        public NonAgenticExecutionKind executionKind() {
            return NonAgenticExecutionKind.RAW_ONLY;
        }

        @Override
        public String rawQuery() {
            return originalQuery;
        }

        public List<RagRepository.RetrievalDoc> localRetrievedDocs() {
            return rawRetrieval.retrievedDocs();
        }

        public List<RagRepository.RetrievalDoc> rerankedDocs() {
            return rawRetrieval.rerankedDocs();
        }

        public double rawRetrievalConfidence() {
            return rawRetrieval.confidence();
        }

        @Override
        public String retrieverName() {
            return rawRetrieval.retrieverName();
        }

        @Override
        public JsonNode retrievalMetadata() {
            return rawRetrieval.retrievalMetadata();
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
            String rawQuery,
            List<ExecutedRewriteCandidate> candidates,
            String retrieverName,
            JsonNode retrievalMetadata,
            long latencyMs
    ) implements NonAgenticExecutionResult {
        public SelectiveRewriteExecutionResult {
            candidates = candidates == null ? List.of() : List.copyOf(candidates);
            retrievalMetadata = retrievalMetadata == null ? JsonNodeFactory.instance.objectNode() : retrievalMetadata;
        }

        @Override
        public NonAgenticExecutionKind executionKind() {
            return NonAgenticExecutionKind.SELECTIVE_REWRITE;
        }

        @Override
        public List<ExecutedRewriteCandidate> candidateExecutions() {
            return candidates;
        }

        @Override
        public boolean anchorInjectionApplied() {
            return false;
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
            String rawQuery,
            List<ExecutedRewriteCandidate> candidates,
            boolean anchorInjectionApplied,
            String retrieverName,
            JsonNode retrievalMetadata,
            long latencyMs
    ) implements NonAgenticExecutionResult {
        public AnchorAwareRewriteExecutionResult {
            candidates = candidates == null ? List.of() : List.copyOf(candidates);
            retrievalMetadata = retrievalMetadata == null ? JsonNodeFactory.instance.objectNode() : retrievalMetadata;
        }

        @Override
        public NonAgenticExecutionKind executionKind() {
            return NonAgenticExecutionKind.ANCHOR_AWARE_REWRITE;
        }

        @Override
        public List<ExecutedRewriteCandidate> candidateExecutions() {
            return candidates;
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

        public List<RagRepository.RetrievalDoc> localRetrievedDocs() {
            return retrieval.retrievedDocs();
        }

        public List<RagRepository.RetrievalDoc> rerankedDocs() {
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
