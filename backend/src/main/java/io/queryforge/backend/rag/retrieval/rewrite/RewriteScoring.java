package io.queryforge.backend.rag.retrieval.rewrite;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.queryforge.backend.rag.retrieval.candidate.RetrievalDoc;
import io.queryforge.backend.rag.retrieval.memory.MemoryCandidate;

import java.util.List;

public final class RewriteScoring {

    private RewriteScoring() {
    }

    public static double confidence(List<RetrievalDoc> docs, double denseScore) {
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

    public static JsonNode scoreBreakdown(ObjectMapper objectMapper, List<RetrievalDoc> retrieved, List<MemoryCandidate> memories) {
        ObjectNode node = objectMapper.createObjectNode();
        double dense = memories.isEmpty() ? 0.0 : memories.getFirst().similarity();
        node.put("r1", retrieved.isEmpty() ? 0.0 : normalizeScore(retrieved.getFirst().score()));
        node.put(
                "r3",
                retrieved.isEmpty()
                        ? 0.0
                        : retrieved.stream().limit(3).mapToDouble(item -> normalizeScore(item.score())).average().orElse(0.0)
        );
        node.put("dense", normalizeScore(dense));
        return node;
    }

    private static double normalizeScore(double score) {
        return Math.max(0.0, Math.min(1.0, (score + 1.0) / 2.0));
    }
}
