package io.queryforge.backend.rag.retrieval.execution.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.queryforge.backend.rag.retrieval.candidate.dto.RetrievalDtos;
import io.queryforge.backend.rag.retrieval.candidate.model.RetrievalDoc;
import io.queryforge.backend.rag.retrieval.candidate.service.DomainScopedRetrievalService;
import io.queryforge.backend.rag.retrieval.execution.dto.RetrievalExecutionDtos.AnchorAwareRewriteExecutionRequest;
import io.queryforge.backend.rag.retrieval.execution.dto.RetrievalExecutionDtos.AnchorAwareRewriteExecutionResult;
import io.queryforge.backend.rag.retrieval.execution.dto.RetrievalExecutionDtos.ExecutedRewriteCandidate;
import io.queryforge.backend.rag.retrieval.execution.dto.RetrievalExecutionDtos.RawOnlyExecutionRequest;
import io.queryforge.backend.rag.retrieval.execution.dto.RetrievalExecutionDtos.RawOnlyExecutionResult;
import io.queryforge.backend.rag.retrieval.execution.dto.RetrievalExecutionDtos.RetrievalMaterial;
import io.queryforge.backend.rag.retrieval.execution.dto.RetrievalExecutionDtos.SelectiveRewriteExecutionRequest;
import io.queryforge.backend.rag.retrieval.execution.dto.RetrievalExecutionDtos.SelectiveRewriteExecutionResult;
import io.queryforge.backend.rag.retrieval.memory.model.MemoryCandidate;
import io.queryforge.backend.rag.retrieval.rerank.service.CohereRerankService;
import io.queryforge.backend.rag.retrieval.rewrite.dto.RewriteDtos;
import io.queryforge.backend.rag.retrieval.rewrite.service.RewriteCandidateService;
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
        List<RetrievalDoc> localRetrievedDocs = domainScopedRetrievalService.retrieveChunks(
                request.originalQuery(),
                request.queryEmbeddingLiteral(),
                request.retrievalTopK(),
                request.domainId(),
                request.retrievalRuntime()
        );
        List<RetrievalDoc> rerankedDocs = cohereRerankService.rerank(
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
        List<RewriteDtos.CandidateTemplate> generatedCandidates = rewriteCandidateService.buildCandidates(
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
            RewriteDtos.CandidateTemplate generated = generatedCandidates.get(index);
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
            RewriteDtos.CandidateTemplate generated,
            int retrievalTopK,
            int rerankTopN,
            UUID domainId,
            RetrievalDtos.RetrievalRuntime retrievalRuntime,
            double rawDenseScore
    ) {
        long started = System.nanoTime();
        String embeddingLiteral = domainScopedRetrievalService.embeddingLiteral(
                generated.query(),
                retrievalRuntime
        );
        List<RetrievalDoc> localRetrievedDocs = domainScopedRetrievalService.retrieveChunks(
                generated.query(),
                embeddingLiteral,
                retrievalTopK,
                domainId,
                retrievalRuntime
        );
        List<RetrievalDoc> rerankedDocs = cohereRerankService.rerank(
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

    private ObjectNode retrievalMetadata(RetrievalDtos.RetrievalRuntime runtime) {
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

    private double confidence(List<RetrievalDoc> docs, double denseScore) {
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

}
