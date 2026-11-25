package io.queryforge.backend.rag.trace.write.service;

import io.queryforge.backend.rag.retrieval.execution.dto.RetrievalExecutionDtos;
import io.queryforge.backend.rag.retrieval.execution.model.RagPersistPolicy;
import io.queryforge.backend.rag.trace.write.dto.RagTraceWriteDtos.CreateRewriteCandidateTracePersistenceRequest;
import io.queryforge.backend.rag.trace.write.dto.RagTraceWriteDtos.MemoryRetrievalLogPersistenceRequest;
import io.queryforge.backend.rag.trace.write.dto.RagTraceWriteDtos.OnlineQueryDecisionPersistenceRequest;
import io.queryforge.backend.rag.trace.write.dto.RagTraceWriteDtos.OnlineQueryMetadataMergePersistenceRequest;
import io.queryforge.backend.rag.trace.write.dto.RagTraceWriteDtos.OnlineRewriteLogPersistenceRequest;
import io.queryforge.backend.rag.trace.write.dto.RagTraceWriteDtos.RagTracePersistenceRequest;
import io.queryforge.backend.rag.trace.write.dto.RagTraceWriteDtos.RawOnlyTracePersistenceRequest;
import io.queryforge.backend.rag.trace.write.dto.RagTraceWriteDtos.RewriteCandidateAdoptionPersistenceRequest;
import io.queryforge.backend.rag.trace.write.dto.RagTraceWriteDtos.RewriteCandidateLogPersistenceRequest;
import io.queryforge.backend.rag.trace.write.dto.RagTraceWriteDtos.RewriteCandidateTracePersistenceRequest;
import io.queryforge.backend.rag.trace.write.dto.TraceWriteResults.OnlineRewriteLogPersistenceResult;
import io.queryforge.backend.rag.trace.write.dto.TraceWriteResults.RagTracePersistenceResult;
import io.queryforge.backend.rag.trace.write.dto.TraceWriteResults.RewriteCandidatePersistenceResult;
import io.queryforge.backend.rag.trace.write.repository.RagTraceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class RagTracePersistenceService {

    private final RagTraceRepository traceRepository;

    public RagTracePersistenceResult persist(RagTracePersistenceRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request is required");
        }
        return switch (request.persistPolicy()) {
            case NONE -> new RagTracePersistenceResult(
                    RagPersistPolicy.NONE,
                    request.onlineQueryId(),
                    false,
                    "skipped_none"
            );
            case TRACE_ONLY -> throw new UnsupportedOperationException(
                    "TRACE_ONLY persistence is not implemented in Phase 5F"
            );
            case ONLINE_QUERY -> throw new UnsupportedOperationException(
                    "ONLINE_QUERY generic persistence migration is not implemented in Phase 5F; use a phase-specific trace persistence method"
            );
        };
    }

    public RagTracePersistenceResult persistRawOnlyTrace(RawOnlyTracePersistenceRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request is required");
        }
        return switch (request.persistPolicy()) {
            case NONE -> new RagTracePersistenceResult(
                    RagPersistPolicy.NONE,
                    request.onlineQueryId(),
                    false,
                    "skipped_none"
            );
            case TRACE_ONLY -> throw new UnsupportedOperationException(
                    "TRACE_ONLY persistence is not implemented in Phase 5F"
            );
            case ONLINE_QUERY -> persistRawOnlyOnlineQueryTrace(request);
        };
    }

    public RagTracePersistenceResult persistRewriteCandidateTrace(RewriteCandidateTracePersistenceRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request is required");
        }
        return switch (request.persistPolicy()) {
            case NONE -> new RagTracePersistenceResult(
                    RagPersistPolicy.NONE,
                    request.onlineQueryId(),
                    false,
                    "skipped_none"
            );
            case TRACE_ONLY -> throw new UnsupportedOperationException(
                    "TRACE_ONLY persistence is not implemented in Phase 5F"
            );
            case ONLINE_QUERY -> persistRewriteCandidateOnlineQueryTrace(request);
        };
    }

    public RewriteCandidatePersistenceResult createRewriteCandidateTrace(
            CreateRewriteCandidateTracePersistenceRequest request
    ) {
        if (request == null) {
            throw new IllegalArgumentException("request is required");
        }
        return switch (request.persistPolicy()) {
            case NONE -> new RewriteCandidatePersistenceResult(
                    RagPersistPolicy.NONE,
                    request.onlineQueryId(),
                    null,
                    false,
                    "skipped_none"
            );
            case TRACE_ONLY -> throw new UnsupportedOperationException(
                    "TRACE_ONLY persistence is not implemented in Phase 5F"
            );
            case ONLINE_QUERY -> createRewriteCandidateOnlineQueryTrace(request);
        };
    }

    public RewriteCandidatePersistenceResult markRewriteCandidateAdopted(
            RewriteCandidateAdoptionPersistenceRequest request
    ) {
        if (request == null) {
            throw new IllegalArgumentException("request is required");
        }
        return switch (request.persistPolicy()) {
            case NONE -> new RewriteCandidatePersistenceResult(
                    RagPersistPolicy.NONE,
                    request.onlineQueryId(),
                    request.rewriteCandidateId(),
                    false,
                    "skipped_none"
            );
            case TRACE_ONLY -> throw new UnsupportedOperationException(
                    "TRACE_ONLY persistence is not implemented in Phase 5F"
            );
            case ONLINE_QUERY -> markRewriteCandidateAdoptedOnlineQueryTrace(request);
        };
    }

    public OnlineRewriteLogPersistenceResult createOnlineRewriteLogTrace(
            OnlineRewriteLogPersistenceRequest request
    ) {
        if (request == null) {
            throw new IllegalArgumentException("request is required");
        }
        return switch (request.persistPolicy()) {
            case NONE -> new OnlineRewriteLogPersistenceResult(
                    RagPersistPolicy.NONE,
                    request.onlineQueryId(),
                    null,
                    false,
                    "skipped_none"
            );
            case TRACE_ONLY -> throw new UnsupportedOperationException(
                    "TRACE_ONLY persistence is not implemented in Phase 5F"
            );
            case ONLINE_QUERY -> createOnlineRewriteLogOnlineQueryTrace(request);
        };
    }

    public RagTracePersistenceResult insertMemoryRetrievalTrace(
            MemoryRetrievalLogPersistenceRequest request
    ) {
        if (request == null) {
            throw new IllegalArgumentException("request is required");
        }
        return switch (request.persistPolicy()) {
            case NONE -> new RagTracePersistenceResult(
                    RagPersistPolicy.NONE,
                    request.onlineQueryId(),
                    false,
                    "skipped_none"
            );
            case TRACE_ONLY -> throw new UnsupportedOperationException(
                    "TRACE_ONLY persistence is not implemented in Phase 5F"
            );
            case ONLINE_QUERY -> insertMemoryRetrievalOnlineQueryTrace(request);
        };
    }

    public RagTracePersistenceResult insertRewriteCandidateTrace(
            RewriteCandidateLogPersistenceRequest request
    ) {
        if (request == null) {
            throw new IllegalArgumentException("request is required");
        }
        return switch (request.persistPolicy()) {
            case NONE -> new RagTracePersistenceResult(
                    RagPersistPolicy.NONE,
                    request.onlineQueryId(),
                    false,
                    "skipped_none"
            );
            case TRACE_ONLY -> throw new UnsupportedOperationException(
                    "TRACE_ONLY persistence is not implemented in Phase 5F"
            );
            case ONLINE_QUERY -> insertRewriteCandidateOnlineQueryTrace(request);
        };
    }

    public RagTracePersistenceResult persistOnlineQueryDecision(
            OnlineQueryDecisionPersistenceRequest request
    ) {
        if (request == null) {
            throw new IllegalArgumentException("request is required");
        }
        return switch (request.persistPolicy()) {
            case NONE -> new RagTracePersistenceResult(
                    RagPersistPolicy.NONE,
                    request.onlineQueryId(),
                    false,
                    "skipped_none"
            );
            case TRACE_ONLY -> throw new UnsupportedOperationException(
                    "TRACE_ONLY persistence is not implemented in Phase 5F"
            );
            case ONLINE_QUERY -> persistOnlineQueryDecisionTrace(request);
        };
    }

    public RagTracePersistenceResult mergeOnlineQueryMetadata(
            OnlineQueryMetadataMergePersistenceRequest request
    ) {
        if (request == null) {
            throw new IllegalArgumentException("request is required");
        }
        return switch (request.persistPolicy()) {
            case NONE -> new RagTracePersistenceResult(
                    RagPersistPolicy.NONE,
                    request.onlineQueryId(),
                    false,
                    "skipped_none"
            );
            case TRACE_ONLY -> throw new UnsupportedOperationException(
                    "TRACE_ONLY persistence is not implemented in Phase 5F"
            );
            case ONLINE_QUERY -> mergeOnlineQueryMetadataTrace(request);
        };
    }

    private RagTracePersistenceResult persistRawOnlyOnlineQueryTrace(RawOnlyTracePersistenceRequest request) {
        if (request.onlineQueryId() == null) {
            throw new IllegalArgumentException("onlineQueryId is required for ONLINE_QUERY raw_only trace persistence");
        }
        if (!"raw_only".equals(request.mode())) {
            throw new IllegalArgumentException("raw_only trace persistence requires mode=raw_only");
        }
        switch (request.writeScope()) {
            case RETRIEVAL -> traceRepository.insertRetrievalResults(
                    request.onlineQueryId(),
                    null,
                    "raw",
                    request.rawRetrievedDocs(),
                    request.mode(),
                    request.retrieverName(),
                    request.retrievalMetadata()
            );
            case RERANK -> traceRepository.insertRerankResults(
                    request.onlineQueryId(),
                    null,
                    request.rawRerankedDocs(),
                    request.rerankerModel()
            );
        }
        return new RagTracePersistenceResult(
                RagPersistPolicy.ONLINE_QUERY,
                request.onlineQueryId(),
                true,
                "persisted_raw_only_" + request.writeScope().statusSuffix()
        );
    }

    private RagTracePersistenceResult persistRewriteCandidateOnlineQueryTrace(
            RewriteCandidateTracePersistenceRequest request
    ) {
        if (request.onlineQueryId() == null) {
            throw new IllegalArgumentException("onlineQueryId is required for ONLINE_QUERY rewrite candidate trace persistence");
        }
        if (request.rewriteCandidateId() == null) {
            throw new IllegalArgumentException("rewriteCandidateId is required for ONLINE_QUERY rewrite candidate trace persistence");
        }
        validateRewriteCandidateExecutionKind(request.executionKind(), "rewrite candidate trace persistence");
        if (request.mode().isBlank()) {
            throw new IllegalArgumentException("mode is required for ONLINE_QUERY rewrite candidate trace persistence");
        }
        switch (request.writeScope()) {
            case CANDIDATE_RETRIEVAL -> traceRepository.insertRetrievalResults(
                    request.onlineQueryId(),
                    request.rewriteCandidateId(),
                    "rewrite_candidate",
                    request.retrievedDocs(),
                    request.mode(),
                    request.retrieverName(),
                    request.retrievalMetadata()
            );
            case CANDIDATE_RERANK -> traceRepository.insertRerankResults(
                    request.onlineQueryId(),
                    request.rewriteCandidateId(),
                    request.rerankedDocs(),
                    request.rerankerModel()
            );
        }
        return new RagTracePersistenceResult(
                RagPersistPolicy.ONLINE_QUERY,
                request.onlineQueryId(),
                true,
                "persisted_" + request.executionKind().name().toLowerCase(Locale.ROOT) + "_" + request.writeScope().statusSuffix()
        );
    }

    private RewriteCandidatePersistenceResult createRewriteCandidateOnlineQueryTrace(
            CreateRewriteCandidateTracePersistenceRequest request
    ) {
        if (request.onlineQueryId() == null) {
            throw new IllegalArgumentException("onlineQueryId is required for ONLINE_QUERY rewrite candidate creation persistence");
        }
        validateRewriteCandidateExecutionKind(request.executionKind(), "rewrite candidate creation persistence");
        UUID rewriteCandidateId = traceRepository.createRewriteCandidate(
                request.onlineQueryId(),
                request.candidateIndex(),
                request.candidateLabel(),
                request.candidateQuery(),
                request.memorySourceIds(),
                request.retrievalTopKDocs(),
                request.confidenceScore(),
                request.scoreBreakdown()
        );
        return new RewriteCandidatePersistenceResult(
                RagPersistPolicy.ONLINE_QUERY,
                request.onlineQueryId(),
                rewriteCandidateId,
                true,
                "persisted_" + request.executionKind().name().toLowerCase(Locale.ROOT) + "_candidate_root"
        );
    }

    private RewriteCandidatePersistenceResult markRewriteCandidateAdoptedOnlineQueryTrace(
            RewriteCandidateAdoptionPersistenceRequest request
    ) {
        if (request.onlineQueryId() == null) {
            throw new IllegalArgumentException("onlineQueryId is required for ONLINE_QUERY rewrite candidate adoption persistence");
        }
        if (request.rewriteCandidateId() == null) {
            throw new IllegalArgumentException("rewriteCandidateId is required for ONLINE_QUERY rewrite candidate adoption persistence");
        }
        validateRewriteCandidateExecutionKind(request.executionKind(), "rewrite candidate adoption persistence");
        traceRepository.markRewriteCandidateAdopted(
                request.rewriteCandidateId(),
                request.adopted(),
                request.rejectedReason()
        );
        return new RewriteCandidatePersistenceResult(
                RagPersistPolicy.ONLINE_QUERY,
                request.onlineQueryId(),
                request.rewriteCandidateId(),
                true,
                "persisted_" + request.executionKind().name().toLowerCase(Locale.ROOT) + "_candidate_adoption"
        );
    }

    private OnlineRewriteLogPersistenceResult createOnlineRewriteLogOnlineQueryTrace(
            OnlineRewriteLogPersistenceRequest request
    ) {
        if (request.onlineQueryId() == null) {
            throw new IllegalArgumentException("onlineQueryId is required for ONLINE_QUERY rewrite log persistence");
        }
        validateRewriteCandidateExecutionKind(request.executionKind(), "rewrite log persistence");
        UUID rewriteLogId = traceRepository.createOnlineRewriteLog(
                request.onlineQueryId(),
                request.runId(),
                request.rawQuery(),
                request.finalQuery(),
                request.rewriteStrategy(),
                request.generationMethodCodes(),
                request.generationBatchIds(),
                request.gatingApplied(),
                request.gatingPreset(),
                request.rewriteApplied(),
                request.selectiveRewrite(),
                request.useSessionContext(),
                request.rawConfidence(),
                request.selectedConfidence(),
                request.confidenceDelta(),
                request.decisionReason(),
                request.rejectionReason(),
                request.metadata()
        );
        return new OnlineRewriteLogPersistenceResult(
                RagPersistPolicy.ONLINE_QUERY,
                request.onlineQueryId(),
                rewriteLogId,
                true,
                "persisted_" + request.executionKind().name().toLowerCase(Locale.ROOT) + "_rewrite_log"
        );
    }

    private RagTracePersistenceResult insertMemoryRetrievalOnlineQueryTrace(
            MemoryRetrievalLogPersistenceRequest request
    ) {
        if (request.onlineQueryId() == null) {
            throw new IllegalArgumentException("onlineQueryId is required for ONLINE_QUERY memory retrieval log persistence");
        }
        if (request.rewriteLogId() == null) {
            throw new IllegalArgumentException("rewriteLogId is required for ONLINE_QUERY memory retrieval log persistence");
        }
        if (request.candidate() == null) {
            throw new IllegalArgumentException("candidate is required for ONLINE_QUERY memory retrieval log persistence");
        }
        validateRewriteCandidateExecutionKind(request.executionKind(), "memory retrieval log persistence");
        traceRepository.insertMemoryRetrievalLog(
                request.rewriteLogId(),
                request.onlineQueryId(),
                request.retrievalRank(),
                request.candidate(),
                request.metadata()
        );
        return new RagTracePersistenceResult(
                RagPersistPolicy.ONLINE_QUERY,
                request.onlineQueryId(),
                true,
                "persisted_" + request.executionKind().name().toLowerCase(Locale.ROOT) + "_memory_retrieval_log"
        );
    }

    private RagTracePersistenceResult insertRewriteCandidateOnlineQueryTrace(
            RewriteCandidateLogPersistenceRequest request
    ) {
        if (request.onlineQueryId() == null) {
            throw new IllegalArgumentException("onlineQueryId is required for ONLINE_QUERY rewrite candidate log persistence");
        }
        if (request.rewriteLogId() == null) {
            throw new IllegalArgumentException("rewriteLogId is required for ONLINE_QUERY rewrite candidate log persistence");
        }
        if (request.rewriteCandidateId() == null) {
            throw new IllegalArgumentException("rewriteCandidateId is required for ONLINE_QUERY rewrite candidate log persistence");
        }
        validateRewriteCandidateExecutionKind(request.executionKind(), "rewrite candidate log persistence");
        traceRepository.insertRewriteCandidateLog(
                request.rewriteLogId(),
                request.onlineQueryId(),
                request.rewriteCandidateId(),
                request.candidateRank(),
                request.candidateLabel(),
                request.candidateQuery(),
                request.confidenceScore(),
                request.selected(),
                request.rejectionReason(),
                request.retrievalTopKDocs(),
                request.scoreBreakdown(),
                request.metadata()
        );
        return new RagTracePersistenceResult(
                RagPersistPolicy.ONLINE_QUERY,
                request.onlineQueryId(),
                true,
                "persisted_" + request.executionKind().name().toLowerCase(Locale.ROOT) + "_rewrite_candidate_log"
        );
    }

    private RagTracePersistenceResult persistOnlineQueryDecisionTrace(
            OnlineQueryDecisionPersistenceRequest request
    ) {
        if (request.onlineQueryId() == null) {
            throw new IllegalArgumentException("onlineQueryId is required for ONLINE_QUERY decision persistence");
        }
        validateNonAgenticExecutionKind(request.executionKind(), "online query decision persistence");
        traceRepository.upsertOnlineQueryDecision(
                request.onlineQueryId(),
                request.finalQueryUsed(),
                request.rewriteApplied(),
                request.memoryTopN(),
                request.rawScore(),
                request.selectedRewriteCandidateId(),
                request.selectedReason(),
                request.rejectedReason(),
                request.latencyBreakdown()
        );
        return new RagTracePersistenceResult(
                RagPersistPolicy.ONLINE_QUERY,
                request.onlineQueryId(),
                true,
                "persisted_" + request.executionKind().name().toLowerCase(Locale.ROOT) + "_online_query_decision"
        );
    }

    private RagTracePersistenceResult mergeOnlineQueryMetadataTrace(
            OnlineQueryMetadataMergePersistenceRequest request
    ) {
        if (request.onlineQueryId() == null) {
            throw new IllegalArgumentException("onlineQueryId is required for ONLINE_QUERY metadata merge persistence");
        }
        validateNonAgenticExecutionKind(request.executionKind(), "online query metadata merge persistence");
        traceRepository.mergeOnlineQueryMetadata(request.onlineQueryId(), request.metadata());
        return new RagTracePersistenceResult(
                RagPersistPolicy.ONLINE_QUERY,
                request.onlineQueryId(),
                true,
                "persisted_" + request.executionKind().name().toLowerCase(Locale.ROOT) + "_online_query_metadata"
        );
    }

    private void validateRewriteCandidateExecutionKind(
            RetrievalExecutionDtos.NonAgenticExecutionKind executionKind,
            String context
    ) {
        validateNonAgenticExecutionKind(executionKind, context);
        if (executionKind == RetrievalExecutionDtos.NonAgenticExecutionKind.RAW_ONLY) {
            throw new IllegalArgumentException(context + " does not support RAW_ONLY execution kind");
        }
    }

    private void validateNonAgenticExecutionKind(
            RetrievalExecutionDtos.NonAgenticExecutionKind executionKind,
            String context
    ) {
        if (executionKind == null) {
            throw new IllegalArgumentException("executionKind is required for ONLINE_QUERY " + context);
        }
    }

}
