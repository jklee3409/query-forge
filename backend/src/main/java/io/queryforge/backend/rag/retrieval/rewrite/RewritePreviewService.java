package io.queryforge.backend.rag.retrieval.rewrite;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.queryforge.backend.rag.embedding.HashEmbeddingService;
import io.queryforge.backend.rag.retrieval.ChunkRetrievalRepository;
import io.queryforge.backend.rag.retrieval.CohereRerankService;
import io.queryforge.backend.rag.retrieval.RetrievalDoc;
import io.queryforge.backend.rag.retrieval.memory.MemoryCandidate;
import io.queryforge.backend.rag.retrieval.memory.RagMemoryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

import static io.queryforge.backend.rag.retrieval.RewriteScoring.confidence;
import static io.queryforge.backend.rag.retrieval.RewriteScoring.scoreBreakdown;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RewritePreviewService {

    private final ChunkRetrievalRepository chunkRepository;
    private final RagMemoryRepository memoryRepository;
    private final HashEmbeddingService embeddingService;
    private final CohereRerankService cohereRerankService;
    private final RewriteCandidateService rewriteCandidateService;
    private final ObjectMapper objectMapper;

    public RewriteDtos.RewritePreviewResponse previewRewrite(RewriteDtos.RewritePreviewRequest request) {
        String rawQuery = normalizedText(request.rawQuery());
        if (rawQuery.isBlank()) {
            throw new IllegalArgumentException("rawQuery must not be blank");
        }
        int memoryTopN = normalizedPositive(request.memoryTopN(), 5);
        int candidateCount = Math.min(normalizedPositive(request.candidateCount(), 2), 2);
        String gatingPreset = request.gatingPreset() == null || request.gatingPreset().isBlank()
                ? "full_gating"
                : request.gatingPreset();

        String queryEmbedding = embeddingService.toHalfvecLiteral(embeddingService.embed(rawQuery));
        List<MemoryCandidate> memories = memoryRepository.findMemoryTopN(
                queryEmbedding,
                memoryTopN,
                gatingPreset,
                null,
                List.of(),
                List.of(),
                List.of()
        );
        List<RewriteCandidateService.CandidateTemplate> candidates = rewriteCandidateService.buildCandidates(
                rawQuery,
                nullSafeJson(request.sessionContext()),
                memories,
                candidateCount,
                "compact_anchor",
                false,
                objectMapper.createObjectNode()
        );
        List<RewriteDtos.RewriteCandidateDto> previewDtos = new ArrayList<>();
        for (RewriteCandidateService.CandidateTemplate candidate : candidates) {
            String candidateEmbedding = embeddingService.toHalfvecLiteral(embeddingService.embed(candidate.query()));
            List<RetrievalDoc> retrievedLocal = chunkRepository.findTopChunksByEmbedding(candidateEmbedding, 20);
            List<RetrievalDoc> retrieved = cohereRerankService.rerank(candidate.query(), retrievedLocal, 5);
            double confidence = confidence(retrieved, memories.isEmpty() ? 0.0 : memories.getFirst().similarity());
            previewDtos.add(
                    new RewriteDtos.RewriteCandidateDto(
                            null,
                            candidate.label(),
                            candidate.query(),
                            confidence,
                            false,
                            null,
                            objectMapper.valueToTree(retrieved),
                            scoreBreakdown(objectMapper, retrieved, memories)
                    )
            );
        }
        return new RewriteDtos.RewritePreviewResponse(
                rawQuery,
                objectMapper.valueToTree(memories),
                previewDtos
        );
    }

    private int normalizedPositive(Integer value, int fallback) {
        if (value == null || value <= 0) {
            return fallback;
        }
        return value;
    }

    private String normalizedText(String value) {
        if (value == null) {
            return "";
        }
        return value.trim();
    }

    private JsonNode nullSafeJson(JsonNode node) {
        return node != null ? node : objectMapper.createObjectNode();
    }
}
