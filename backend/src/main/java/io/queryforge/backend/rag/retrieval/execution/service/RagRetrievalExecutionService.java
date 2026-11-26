package io.queryforge.backend.rag.retrieval.execution.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.queryforge.backend.rag.retrieval.candidate.dto.RetrievalDtos;
import io.queryforge.backend.rag.retrieval.candidate.model.RetrievalDoc;
import io.queryforge.backend.rag.retrieval.candidate.service.DomainScopedRetrievalService;
import io.queryforge.backend.rag.retrieval.execution.dto.RetrievalExecutionDtos.ExecutedRewriteCandidate;
import io.queryforge.backend.rag.retrieval.execution.dto.RetrievalExecutionDtos.RawRetrievalRequest;
import io.queryforge.backend.rag.retrieval.execution.dto.RetrievalExecutionDtos.RawOnlyExecutionResult;
import io.queryforge.backend.rag.retrieval.execution.dto.RetrievalExecutionDtos.RetrievalMaterial;
import io.queryforge.backend.rag.retrieval.execution.dto.RetrievalExecutionDtos.RewriteExecutionRequest;
import io.queryforge.backend.rag.retrieval.execution.dto.RetrievalExecutionDtos.RewriteExecutionResult;
import io.queryforge.backend.rag.retrieval.memory.model.MemoryCandidate;
import io.queryforge.backend.rag.retrieval.rerank.service.CohereRerankService;
import io.queryforge.backend.rag.retrieval.rewrite.dto.RewriteDtos;
import io.queryforge.backend.rag.retrieval.rewrite.service.RewriteCandidateService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static io.queryforge.backend.rag.retrieval.rewrite.service.RewriteScoring.confidence;

@Service
@RequiredArgsConstructor
public class RagRetrievalExecutionService {

    private final DomainScopedRetrievalService domainScopedRetrievalService;
    private final CohereRerankService cohereRerankService;
    private final RewriteCandidateService rewriteCandidateService;
    private final ObjectMapper objectMapper;

    public RawOnlyExecutionResult executeRawOnly(RawRetrievalRequest request) {
        long started = System.nanoTime();
        return new RawOnlyExecutionResult(
                request.originalQuery(),
                request.originalQuery(),
                executeRawRetrieval(request, 0.0d),
                elapsedMs(started)
        );
    }

    public RetrievalMaterial executeRawRetrieval(RawRetrievalRequest request, double memorySimilarity) {
        return executeRetrieval(
                request.originalQuery(), request.queryEmbeddingLiteral(), request.retrievalTopK(),
                request.rerankTopN(), request.domainId(), request.retrievalRuntime(), memorySimilarity,
                System.nanoTime()
        );
    }

    public RetrievalMaterial executeMemoryRetrieval(
            MemoryCandidate memory,
            int rawRerankedCount,
            UUID domainId,
            RetrievalDtos.RetrievalRuntime runtime
    ) {
        long started = System.nanoTime();
        String query = memory.queryText();
        String embeddingLiteral = domainScopedRetrievalService.embeddingLiteral(query, runtime);
        return executeRetrieval(
                query, embeddingLiteral, Math.max(20, rawRerankedCount), rawRerankedCount,
                domainId, runtime, memory.similarity(), started
        );
    }

    public RewriteExecutionResult executeRewrite(RewriteExecutionRequest request) {
        long started = System.nanoTime();
        List<RewriteDtos.CandidateTemplate> generatedCandidates = generateRewriteCandidates(request);
        return executeCandidates(request, generatedCandidates, started);
    }

    public ObservedRewrite executeObservedRewrite(RewriteExecutionRequest request) {
        long started = System.nanoTime();
        RewriteDtos.CandidateGeneration generation = rewriteCandidateService.buildObservedCandidates(
                request.rawQuery(), request.sessionContextSnapshot(), request.memoryCandidates(),
                request.candidateCount(), request.rewriteQueryProfile(), request.anchorInjectionEnabled(), request.domainContext()
        );
        // Only model-generated candidates may enter the official selective policy.
        List<RewriteDtos.CandidateTemplate> eligible = generation.diagnostics().succeeded()
                ? generation.candidates() : List.of();
        return new ObservedRewrite(executeCandidates(request, eligible, started), generation);
    }

    private RewriteExecutionResult executeCandidates(
            RewriteExecutionRequest request, List<RewriteDtos.CandidateTemplate> generatedCandidates, long started
    ) {
        List<ExecutedRewriteCandidate> executedCandidates = new ArrayList<>();
        for (int index = 0; index < generatedCandidates.size(); index++) {
            executedCandidates.add(executeCandidate(request, index + 1, generatedCandidates.get(index)));
        }
        return new RewriteExecutionResult(executedCandidates, request.anchorInjectionEnabled(), elapsedMs(started));
    }

    public record ObservedRewrite(RewriteExecutionResult execution, RewriteDtos.CandidateGeneration generation) {
    }

    public List<RewriteDtos.CandidateTemplate> generateRewriteCandidates(RewriteExecutionRequest request) {
        return rewriteCandidateService.buildCandidates(
                request.rawQuery(),
                request.sessionContextSnapshot(),
                request.memoryCandidates(),
                request.candidateCount(),
                request.rewriteQueryProfile(),
                request.anchorInjectionEnabled(),
                request.domainContext()
        );
    }

    public ExecutedRewriteCandidate executeCandidate(
            RewriteExecutionRequest request,
            int candidateIndex,
            RewriteDtos.CandidateTemplate generated
    ) {
        long started = System.nanoTime();
        String query = generated.query();
        RetrievalDtos.RetrievalRuntime retrievalRuntime = request.retrievalRuntime();
        String embeddingLiteral = domainScopedRetrievalService.embeddingLiteral(query, retrievalRuntime);
        RetrievalMaterial retrieval = executeRetrieval(
                query, embeddingLiteral, request.retrievalTopK(), request.rerankTopN(),
                request.domainId(), retrievalRuntime, request.rawDenseScore(), started
        );
        return new ExecutedRewriteCandidate(
                candidateIndex, generated.label(), query, candidateMetadata(candidateIndex, generated.label()), retrieval
        );
    }

    private RetrievalMaterial executeRetrieval(
            String query,
            String embeddingLiteral,
            int retrievalTopK,
            int rerankTopN,
            UUID domainId,
            RetrievalDtos.RetrievalRuntime runtime,
            double memorySimilarity,
            long started
    ) {
        List<RetrievalDoc> localRetrievedDocs = domainScopedRetrievalService.retrieveChunks(
                query, embeddingLiteral, retrievalTopK, domainId, runtime
        );
        List<RetrievalDoc> rerankedDocs = cohereRerankService.rerank(query, localRetrievedDocs, rerankTopN);
        return new RetrievalMaterial(
                query,
                localRetrievedDocs,
                rerankedDocs,
                confidence(rerankedDocs, memorySimilarity),
                runtime.retrieverName(),
                cohereRerankService.modelName(),
                retrievalMetadata(runtime),
                elapsedMs(started)
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

    private long elapsedMs(long startedNano) {
        return (System.nanoTime() - startedNano) / 1_000_000L;
    }

}
