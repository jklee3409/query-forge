package io.queryforge.backend.rag.retrieval.execution.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.queryforge.backend.rag.retrieval.candidate.dto.RetrievalDtos;
import io.queryforge.backend.rag.retrieval.candidate.model.RetrievalDoc;
import io.queryforge.backend.rag.retrieval.candidate.service.DomainScopedRetrievalService;
import io.queryforge.backend.rag.retrieval.execution.dto.RetrievalExecutionDtos.ExecutedRewriteCandidate;
import io.queryforge.backend.rag.retrieval.execution.dto.RetrievalExecutionDtos.RawOnlyExecutionRequest;
import io.queryforge.backend.rag.retrieval.execution.dto.RetrievalExecutionDtos.RawOnlyExecutionResult;
import io.queryforge.backend.rag.retrieval.execution.dto.RetrievalExecutionDtos.RetrievalMaterial;
import io.queryforge.backend.rag.retrieval.execution.dto.RetrievalExecutionDtos.RewriteExecutionRequest;
import io.queryforge.backend.rag.retrieval.execution.dto.RetrievalExecutionDtos.RewriteExecutionResult;
import io.queryforge.backend.rag.retrieval.rerank.service.CohereRerankService;
import io.queryforge.backend.rag.retrieval.rewrite.dto.RewriteDtos;
import io.queryforge.backend.rag.retrieval.rewrite.service.RewriteCandidateService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

import static io.queryforge.backend.rag.retrieval.rewrite.service.RewriteScoring.confidence;

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

    public RewriteExecutionResult executeRewrite(RewriteExecutionRequest request) {
        long started = System.nanoTime();
        List<RewriteDtos.CandidateTemplate> generatedCandidates = generateRewriteCandidates(request);
        List<ExecutedRewriteCandidate> executedCandidates = new ArrayList<>();
        for (int index = 0; index < generatedCandidates.size(); index++) {
            executedCandidates.add(executeCandidate(request, index + 1, generatedCandidates.get(index)));
        }
        return new RewriteExecutionResult(executedCandidates, request.anchorInjectionEnabled(), elapsedMs(started));
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
        List<RetrievalDoc> localRetrievedDocs = domainScopedRetrievalService.retrieveChunks(
                query,
                embeddingLiteral,
                request.retrievalTopK(),
                request.domainId(),
                retrievalRuntime
        );
        List<RetrievalDoc> rerankedDocs = cohereRerankService.rerank(
                query,
                localRetrievedDocs,
                request.rerankTopN()
        );
        return new ExecutedRewriteCandidate(
                candidateIndex,
                generated.label(),
                query,
                candidateMetadata(candidateIndex, generated.label()),
                new RetrievalMaterial(
                        query,
                        localRetrievedDocs,
                        rerankedDocs,
                        confidence(rerankedDocs, request.rawDenseScore()),
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

    private long elapsedMs(long startedNano) {
        return (System.nanoTime() - startedNano) / 1_000_000L;
    }

}
