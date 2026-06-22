package io.queryforge.backend.rag.service;

import io.queryforge.backend.rag.repository.RagRepository;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class SearchResultMerger {

    public List<RagRepository.RetrievalDoc> mergeRrf(
            List<List<RagRepository.RetrievalDoc>> rankedResults,
            int finalTopK,
            int rrfK
    ) {
        if (rankedResults == null || rankedResults.isEmpty()) {
            return List.of();
        }
        Map<String, Accumulator> merged = new LinkedHashMap<>();
        int safeRrfK = Math.max(1, rrfK);
        for (List<RagRepository.RetrievalDoc> resultSet : rankedResults) {
            if (resultSet == null || resultSet.isEmpty()) {
                continue;
            }
            for (int index = 0; index < resultSet.size(); index++) {
                RagRepository.RetrievalDoc doc = resultSet.get(index);
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
                        .comparingDouble(RagRepository.RetrievalDoc::score)
                        .reversed()
                        .thenComparing(RagRepository.RetrievalDoc::chunkId))
                .limit(Math.max(1, finalTopK))
                .toList();
    }

    private record Accumulator(
            RagRepository.RetrievalDoc representative,
            double rrfScore,
            double bestOriginalScore
    ) {
        Accumulator add(RagRepository.RetrievalDoc candidate, double additionalScore) {
            RagRepository.RetrievalDoc nextRepresentative = representative;
            double nextBestOriginalScore = bestOriginalScore;
            if (candidate.score() > bestOriginalScore) {
                nextRepresentative = candidate;
                nextBestOriginalScore = candidate.score();
            }
            return new Accumulator(nextRepresentative, rrfScore + additionalScore, nextBestOriginalScore);
        }

        RagRepository.RetrievalDoc toRetrievalDoc() {
            return new RagRepository.RetrievalDoc(
                    representative.documentId(),
                    representative.chunkId(),
                    representative.chunkText(),
                    rrfScore
            );
        }
    }
}
