package io.queryforge.backend.rag.trace.write.agentic.service;

import io.queryforge.backend.rag.retrieval.execution.model.RagPersistPolicy;
import io.queryforge.backend.rag.trace.write.agentic.dto.AgenticTraceWriteDtos.AgenticFinalRerankTracePersistenceRequest;
import io.queryforge.backend.rag.trace.write.agentic.dto.AgenticTraceWriteDtos.AgenticMemoryRetrievalLogPersistenceRequest;
import io.queryforge.backend.rag.trace.write.agentic.dto.AgenticTraceWriteDtos.AgenticOnlineQueryDecisionPersistenceRequest;
import io.queryforge.backend.rag.trace.write.agentic.dto.AgenticTraceWriteDtos.AgenticOnlineQueryMetadataMergePersistenceRequest;
import io.queryforge.backend.rag.trace.write.agentic.dto.AgenticTraceWriteDtos.AgenticOnlineRewriteLogPersistenceRequest;
import io.queryforge.backend.rag.trace.write.agentic.dto.AgenticTraceWriteDtos.AgenticRetrievalExecutionKind;
import io.queryforge.backend.rag.trace.write.agentic.dto.AgenticTraceWriteDtos.AgenticRewriteCandidateAdoptionPersistenceRequest;
import io.queryforge.backend.rag.trace.write.agentic.dto.AgenticTraceWriteDtos.AgenticRewriteCandidateLogPersistenceRequest;
import io.queryforge.backend.rag.trace.write.agentic.dto.AgenticTraceWriteDtos.AgenticRewriteCandidateTracePersistenceRequest;
import io.queryforge.backend.rag.trace.write.agentic.dto.AgenticTraceWriteDtos.AgenticSubqueryRetrievalTracePersistenceRequest;
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
public class AgenticTracePersistenceService {

    private final RagTraceRepository traceRepository;

    public RagTracePersistenceResult persistAgenticSubqueryRetrievalTrace(
            AgenticSubqueryRetrievalTracePersistenceRequest request
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
                    "TRACE_ONLY persistence is not implemented for Phase 6A agentic subquery retrieval trace"
            );
            case ONLINE_QUERY -> persistAgenticSubqueryRetrievalOnlineQueryTrace(request);
        };
    }

    public RewriteCandidatePersistenceResult createAgenticRewriteCandidateTrace(
            AgenticRewriteCandidateTracePersistenceRequest request
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
                    "TRACE_ONLY persistence is not implemented for Phase 6B agentic rewrite candidate persistence"
            );
            case ONLINE_QUERY -> createAgenticRewriteCandidateOnlineQueryTrace(request);
        };
    }

    public RewriteCandidatePersistenceResult markAgenticRewriteCandidateAdopted(
            AgenticRewriteCandidateAdoptionPersistenceRequest request
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
                    "TRACE_ONLY persistence is not implemented for Phase 6B agentic rewrite candidate persistence"
            );
            case ONLINE_QUERY -> markAgenticRewriteCandidateAdoptedOnlineQueryTrace(request);
        };
    }

    public RagTracePersistenceResult persistAgenticFinalRerankTrace(
            AgenticFinalRerankTracePersistenceRequest request
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
                    "TRACE_ONLY persistence is not implemented for Phase 6C agentic final RRF rerank trace"
            );
            case ONLINE_QUERY -> persistAgenticFinalRerankOnlineQueryTrace(request);
        };
    }

    public OnlineRewriteLogPersistenceResult createAgenticOnlineRewriteLogTrace(
            AgenticOnlineRewriteLogPersistenceRequest request
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
                    "TRACE_ONLY persistence is not implemented for Phase 6D agentic rewrite log persistence"
            );
            case ONLINE_QUERY -> createAgenticOnlineRewriteLogOnlineQueryTrace(request);
        };
    }

    public RagTracePersistenceResult insertAgenticMemoryRetrievalTrace(
            AgenticMemoryRetrievalLogPersistenceRequest request
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
                    "TRACE_ONLY persistence is not implemented for Phase 6D agentic memory retrieval log persistence"
            );
            case ONLINE_QUERY -> insertAgenticMemoryRetrievalOnlineQueryTrace(request);
        };
    }

    public RagTracePersistenceResult insertAgenticRewriteCandidateTrace(
            AgenticRewriteCandidateLogPersistenceRequest request
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
                    "TRACE_ONLY persistence is not implemented for Phase 6D agentic rewrite candidate log persistence"
            );
            case ONLINE_QUERY -> insertAgenticRewriteCandidateOnlineQueryTrace(request);
        };
    }

    public RagTracePersistenceResult persistAgenticOnlineQueryDecision(
            AgenticOnlineQueryDecisionPersistenceRequest request
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
                    "TRACE_ONLY persistence is not implemented for Phase 6E agentic online query decision persistence"
            );
            case ONLINE_QUERY -> persistAgenticOnlineQueryDecisionTrace(request);
        };
    }

    public RagTracePersistenceResult mergeAgenticOnlineQueryMetadata(
            AgenticOnlineQueryMetadataMergePersistenceRequest request
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
                    "TRACE_ONLY persistence is not implemented for Phase 6E agentic online query metadata persistence"
            );
            case ONLINE_QUERY -> mergeAgenticOnlineQueryMetadataTrace(request);
        };
    }

    private RagTracePersistenceResult persistAgenticSubqueryRetrievalOnlineQueryTrace(
            AgenticSubqueryRetrievalTracePersistenceRequest request
    ) {
        if (request.onlineQueryId() == null) {
            throw new IllegalArgumentException("onlineQueryId is required for ONLINE_QUERY agentic subquery retrieval trace persistence");
        }
        if (request.executionKind() == null) {
            throw new IllegalArgumentException("executionKind is required for ONLINE_QUERY agentic subquery retrieval trace persistence");
        }
        if (request.mode().isBlank()) {
            throw new IllegalArgumentException("mode is required for ONLINE_QUERY agentic subquery retrieval trace persistence");
        }
        switch (request.writeScope()) {
            case SUBQUERY_RAW_RETRIEVAL -> {
                if (request.rewriteCandidateId() != null) {
                    throw new IllegalArgumentException("rewriteCandidateId must be null for agentic subquery raw retrieval trace persistence");
                }
                traceRepository.insertRetrievalResults(
                        request.onlineQueryId(),
                        null,
                        request.writeScope().repositoryStage(),
                        request.retrievedDocs(),
                        request.mode(),
                        request.retrieverName(),
                        request.retrievalMetadata()
                );
            }
            case SUBQUERY_CANDIDATE_RETRIEVAL -> {
                if (request.rewriteCandidateId() == null) {
                    throw new IllegalArgumentException("rewriteCandidateId is required for agentic subquery candidate retrieval trace persistence");
                }
                traceRepository.insertRetrievalResults(
                        request.onlineQueryId(),
                        request.rewriteCandidateId(),
                        request.writeScope().repositoryStage(),
                        request.retrievedDocs(),
                        request.mode(),
                        request.retrieverName(),
                        request.retrievalMetadata()
                );
            }
        }
        return new RagTracePersistenceResult(
                RagPersistPolicy.ONLINE_QUERY,
                request.onlineQueryId(),
                true,
                "persisted_" + request.executionKind().name().toLowerCase(Locale.ROOT)
                        + "_" + request.writeScope().statusSuffix()
        );
    }

    private RagTracePersistenceResult persistAgenticFinalRerankOnlineQueryTrace(
            AgenticFinalRerankTracePersistenceRequest request
    ) {
        if (request.onlineQueryId() == null) {
            throw new IllegalArgumentException("onlineQueryId is required for ONLINE_QUERY agentic final RRF rerank trace persistence");
        }
        validateAgenticExecutionKind(request.executionKind(), "agentic final RRF rerank trace persistence");
        if (!"agentic-rrf".equals(request.resultScope())) {
            throw new IllegalArgumentException("agentic final RRF rerank trace persistence requires resultScope=agentic-rrf");
        }
        if (!"agentic-rrf".equals(request.rerankerModel())) {
            throw new IllegalArgumentException("agentic final RRF rerank trace persistence requires rerankerModel=agentic-rrf");
        }
        traceRepository.insertRerankResults(
                request.onlineQueryId(),
                null,
                request.finalMergedDocs(),
                request.rerankerModel()
        );
        return new RagTracePersistenceResult(
                RagPersistPolicy.ONLINE_QUERY,
                request.onlineQueryId(),
                true,
                "persisted_" + request.executionKind().name().toLowerCase(Locale.ROOT) + "_final_rrf_rerank"
        );
    }

    private OnlineRewriteLogPersistenceResult createAgenticOnlineRewriteLogOnlineQueryTrace(
            AgenticOnlineRewriteLogPersistenceRequest request
    ) {
        if (request.onlineQueryId() == null) {
            throw new IllegalArgumentException("onlineQueryId is required for ONLINE_QUERY agentic rewrite log persistence");
        }
        validateAgenticExecutionKind(request.executionKind(), "agentic rewrite log persistence");
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

    private RagTracePersistenceResult insertAgenticMemoryRetrievalOnlineQueryTrace(
            AgenticMemoryRetrievalLogPersistenceRequest request
    ) {
        if (request.onlineQueryId() == null) {
            throw new IllegalArgumentException("onlineQueryId is required for ONLINE_QUERY agentic memory retrieval log persistence");
        }
        if (request.rewriteLogId() == null) {
            throw new IllegalArgumentException("rewriteLogId is required for ONLINE_QUERY agentic memory retrieval log persistence");
        }
        if (request.candidate() == null) {
            throw new IllegalArgumentException("candidate is required for ONLINE_QUERY agentic memory retrieval log persistence");
        }
        validateAgenticExecutionKind(request.executionKind(), "agentic memory retrieval log persistence");
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

    private RagTracePersistenceResult insertAgenticRewriteCandidateOnlineQueryTrace(
            AgenticRewriteCandidateLogPersistenceRequest request
    ) {
        if (request.onlineQueryId() == null) {
            throw new IllegalArgumentException("onlineQueryId is required for ONLINE_QUERY agentic rewrite candidate log persistence");
        }
        if (request.rewriteLogId() == null) {
            throw new IllegalArgumentException("rewriteLogId is required for ONLINE_QUERY agentic rewrite candidate log persistence");
        }
        if (request.rewriteCandidateId() == null) {
            throw new IllegalArgumentException("rewriteCandidateId is required for ONLINE_QUERY agentic rewrite candidate log persistence");
        }
        validateAgenticExecutionKind(request.executionKind(), "agentic rewrite candidate log persistence");
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

    private RagTracePersistenceResult persistAgenticOnlineQueryDecisionTrace(
            AgenticOnlineQueryDecisionPersistenceRequest request
    ) {
        if (request.onlineQueryId() == null) {
            throw new IllegalArgumentException("onlineQueryId is required for ONLINE_QUERY agentic decision persistence");
        }
        validateAgenticExecutionKind(request.executionKind(), "agentic online query decision persistence");
        traceRepository.upsertOnlineQueryDecision(
                request.onlineQueryId(),
                request.finalQueryUsed(),
                request.rewriteApplied(),
                request.memoryTopN(),
                request.selectedConfidence(),
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

    private RagTracePersistenceResult mergeAgenticOnlineQueryMetadataTrace(
            AgenticOnlineQueryMetadataMergePersistenceRequest request
    ) {
        if (request.onlineQueryId() == null) {
            throw new IllegalArgumentException("onlineQueryId is required for ONLINE_QUERY agentic metadata merge persistence");
        }
        validateAgenticExecutionKind(request.executionKind(), "agentic online query metadata merge persistence");
        traceRepository.mergeOnlineQueryMetadata(request.onlineQueryId(), request.metadata());
        return new RagTracePersistenceResult(
                RagPersistPolicy.ONLINE_QUERY,
                request.onlineQueryId(),
                true,
                "persisted_" + request.executionKind().name().toLowerCase(Locale.ROOT) + "_online_query_metadata"
        );
    }

    private RewriteCandidatePersistenceResult createAgenticRewriteCandidateOnlineQueryTrace(
            AgenticRewriteCandidateTracePersistenceRequest request
    ) {
        if (request.onlineQueryId() == null) {
            throw new IllegalArgumentException("onlineQueryId is required for ONLINE_QUERY agentic rewrite candidate creation persistence");
        }
        validateAgenticExecutionKind(request.executionKind(), "agentic rewrite candidate creation persistence");
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

    private RewriteCandidatePersistenceResult markAgenticRewriteCandidateAdoptedOnlineQueryTrace(
            AgenticRewriteCandidateAdoptionPersistenceRequest request
    ) {
        if (request.onlineQueryId() == null) {
            throw new IllegalArgumentException("onlineQueryId is required for ONLINE_QUERY agentic rewrite candidate adoption persistence");
        }
        if (request.rewriteCandidateId() == null) {
            throw new IllegalArgumentException("rewriteCandidateId is required for ONLINE_QUERY agentic rewrite candidate adoption persistence");
        }
        validateAgenticExecutionKind(request.executionKind(), "agentic rewrite candidate adoption persistence");
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

    private void validateAgenticExecutionKind(
            AgenticRetrievalExecutionKind executionKind,
            String context
    ) {
        if (executionKind == null) {
            throw new IllegalArgumentException("executionKind is required for ONLINE_QUERY " + context);
        }
    }

}
