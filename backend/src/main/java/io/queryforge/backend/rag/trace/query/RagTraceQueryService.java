package io.queryforge.backend.rag.trace.query;

import io.queryforge.backend.rag.retrieval.rewrite.RewriteDtos;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RagTraceQueryService {

    private final RagTraceReadRepository traceRepository;

    public RagTraceDtos.QueryTraceResponse getQueryTrace(UUID onlineQueryId) {
        RagTraceReadRepository.OnlineQueryRow onlineQuery = traceRepository.findOnlineQuery(onlineQueryId)
                .orElseThrow(() -> new IllegalArgumentException("online query was not found: " + onlineQueryId));
        return new RagTraceDtos.QueryTraceResponse(
                onlineQuery.onlineQueryId(),
                onlineQuery.rawQuery(),
                onlineQuery.finalQueryUsed(),
                onlineQuery.rewriteApplied(),
                onlineQuery.rewriteStrategy(),
                onlineQuery.sessionContextSnapshot(),
                onlineQuery.memoryTopN(),
                onlineQuery.rawScore(),
                onlineQuery.selectedRewriteCandidateId(),
                onlineQuery.selectedReason(),
                onlineQuery.rejectedReason(),
                onlineQuery.threshold(),
                onlineQuery.latencyBreakdown(),
                traceRepository.findRewriteCandidates(onlineQueryId).stream()
                        .map(candidate -> new RewriteDtos.RewriteCandidateDto(
                                candidate.rewriteCandidateId(),
                                candidate.candidateLabel(),
                                candidate.candidateQuery(),
                                candidate.confidenceScore() != null ? candidate.confidenceScore() : 0.0,
                                candidate.adopted() != null && candidate.adopted(),
                                candidate.rejectedReason(),
                                candidate.retrievalTopKDocs(),
                                candidate.scoreBreakdown()
                        ))
                        .toList(),
                traceRepository.findRetrievalResults(onlineQueryId),
                traceRepository.findRerankResults(onlineQueryId),
                traceRepository.findAnswer(onlineQueryId)
        );
    }
}
