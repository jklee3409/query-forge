package io.queryforge.backend.rag.retrieval.agentic.fusion;

import io.queryforge.backend.rag.retrieval.candidate.RetrievalDoc;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class SearchResultMerger {

    public List<RetrievalDoc> mergeRrf(
            List<List<RetrievalDoc>> rankedResults,
            int finalTopK,
            int rrfK
    ) {
        if (rankedResults == null || rankedResults.isEmpty()) {
            return List.of();
        }
        Map<String, Accumulator> merged = new LinkedHashMap<>();
        int safeRrfK = Math.max(1, rrfK);
        for (List<RetrievalDoc> resultSet : rankedResults) {
            if (resultSet == null || resultSet.isEmpty()) {
                continue;
            }
            for (int index = 0; index < resultSet.size(); index++) {
                RetrievalDoc doc = resultSet.get(index);
                if (doc == null || doc.chunkId() == null || doc.chunkId().isBlank()) {
                    continue;
                }
                double rrfScore = 1.0d / (safeRrfK + index + 1.0d);
                merged.compute(doc.chunkId(), (chunkId, current) -> {
                    if (current == null) {
                        return new Accumulator(doc, rrfScore, doc.score());
                    }
                    return current.add(doc, rrfScore);
                });
            }
        }
        if (merged.isEmpty()) {
            return List.of();
        }
        return merged.values().stream()
                .map(Accumulator::toRetrievalDoc)
                .sorted(Comparator
                        .comparingDouble(RetrievalDoc::score)
                        .reversed()
                        .thenComparing(RetrievalDoc::chunkId))
                .limit(Math.max(1, finalTopK))
                .toList();
    }

    private record Accumulator(
            RetrievalDoc representative,
            double rrfScore,
            double bestOriginalScore
    ) {
        Accumulator add(RetrievalDoc candidate, double additionalScore) {
            RetrievalDoc nextRepresentative = representative;
            double nextBestOriginalScore = bestOriginalScore;
            if (candidate.score() > bestOriginalScore) {
                nextRepresentative = candidate;
                nextBestOriginalScore = candidate.score();
            }
            return new Accumulator(nextRepresentative, rrfScore + additionalScore, nextBestOriginalScore);
        }

        RetrievalDoc toRetrievalDoc() {
            return new RetrievalDoc(
                    representative.documentId(),
                    representative.chunkId(),
                    representative.chunkText(),
                    rrfScore
            );
        }
    }
}
