package io.queryforge.backend.rag.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import io.queryforge.backend.rag.model.ForcedRetrievalMode;
import io.queryforge.backend.rag.model.RagPersistPolicy;
import io.queryforge.backend.rag.model.RagRetrievalExecutionResult;
import io.queryforge.backend.rag.repository.RagRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class RagTracePersistenceService {

    private final RagRepository repository;

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
            case RETRIEVAL -> repository.insertRetrievalResults(
                    request.onlineQueryId(),
                    null,
                    "raw",
                    request.rawRetrievedDocs(),
                    request.mode(),
                    request.retrieverName(),
                    request.retrievalMetadata()
            );
            case RERANK -> repository.insertRerankResults(
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
                "persisted_raw_only_" + request.writeScope().statusSuffix
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
            case CANDIDATE_RETRIEVAL -> repository.insertRetrievalResults(
                    request.onlineQueryId(),
                    request.rewriteCandidateId(),
                    "rewrite_candidate",
                    request.retrievedDocs(),
                    request.mode(),
                    request.retrieverName(),
                    request.retrievalMetadata()
            );
            case CANDIDATE_RERANK -> repository.insertRerankResults(
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
                "persisted_" + request.executionKind().name().toLowerCase(Locale.ROOT) + "_" + request.writeScope().statusSuffix
        );
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
                repository.insertRetrievalResults(
                        request.onlineQueryId(),
                        null,
                        request.writeScope().repositoryStage,
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
                repository.insertRetrievalResults(
                        request.onlineQueryId(),
                        request.rewriteCandidateId(),
                        request.writeScope().repositoryStage,
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
                        + "_" + request.writeScope().statusSuffix
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
        repository.insertRerankResults(
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

    private RewriteCandidatePersistenceResult createRewriteCandidateOnlineQueryTrace(
            CreateRewriteCandidateTracePersistenceRequest request
    ) {
        if (request.onlineQueryId() == null) {
            throw new IllegalArgumentException("onlineQueryId is required for ONLINE_QUERY rewrite candidate creation persistence");
        }
        validateRewriteCandidateExecutionKind(request.executionKind(), "rewrite candidate creation persistence");
        UUID rewriteCandidateId = repository.createRewriteCandidate(
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

    private RewriteCandidatePersistenceResult createAgenticRewriteCandidateOnlineQueryTrace(
            AgenticRewriteCandidateTracePersistenceRequest request
    ) {
        if (request.onlineQueryId() == null) {
            throw new IllegalArgumentException("onlineQueryId is required for ONLINE_QUERY agentic rewrite candidate creation persistence");
        }
        validateAgenticExecutionKind(request.executionKind(), "agentic rewrite candidate creation persistence");
        UUID rewriteCandidateId = repository.createRewriteCandidate(
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
        repository.markRewriteCandidateAdopted(
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
        repository.markRewriteCandidateAdopted(
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
        UUID rewriteLogId = repository.createOnlineRewriteLog(
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
        repository.insertMemoryRetrievalLog(
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
        repository.insertRewriteCandidateLog(
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
        repository.upsertOnlineQueryDecision(
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
        repository.mergeOnlineQueryMetadata(request.onlineQueryId(), request.metadata());
        return new RagTracePersistenceResult(
                RagPersistPolicy.ONLINE_QUERY,
                request.onlineQueryId(),
                true,
                "persisted_" + request.executionKind().name().toLowerCase(Locale.ROOT) + "_online_query_metadata"
        );
    }

    private void validateRewriteCandidateExecutionKind(
            RagRetrievalExecutionService.NonAgenticExecutionKind executionKind,
            String context
    ) {
        validateNonAgenticExecutionKind(executionKind, context);
        if (executionKind == RagRetrievalExecutionService.NonAgenticExecutionKind.RAW_ONLY) {
            throw new IllegalArgumentException(context + " does not support RAW_ONLY execution kind");
        }
    }

    private void validateNonAgenticExecutionKind(
            RagRetrievalExecutionService.NonAgenticExecutionKind executionKind,
            String context
    ) {
        if (executionKind == null) {
            throw new IllegalArgumentException("executionKind is required for ONLINE_QUERY " + context);
        }
    }

    private void validateAgenticExecutionKind(
            AgenticRetrievalExecutionKind executionKind,
            String context
    ) {
        if (executionKind == null) {
            throw new IllegalArgumentException("executionKind is required for ONLINE_QUERY " + context);
        }
    }

    public record RagTracePersistenceRequest(
            RagPersistPolicy persistPolicy,
            String source,
            String evalRunId,
            String sampleId,
            ForcedRetrievalMode forcedMode,
            UUID onlineQueryId,
            RagRetrievalExecutionResult executionResult
    ) {
        public RagTracePersistenceRequest {
            persistPolicy = persistPolicy == null ? RagPersistPolicy.NONE : persistPolicy;
        }
    }

    public record RagTracePersistenceResult(
            RagPersistPolicy persistPolicy,
            UUID onlineQueryId,
            boolean persisted,
            String status
    ) {
    }

    public record RewriteCandidatePersistenceResult(
            RagPersistPolicy persistPolicy,
            UUID onlineQueryId,
            UUID rewriteCandidateId,
            boolean persisted,
            String status
    ) {
    }

    public record OnlineRewriteLogPersistenceResult(
            RagPersistPolicy persistPolicy,
            UUID onlineQueryId,
            UUID rewriteLogId,
            boolean persisted,
            String status
    ) {
    }

    public record OnlineQueryDecisionPersistenceRequest(
            RagPersistPolicy persistPolicy,
            UUID onlineQueryId,
            RagRetrievalExecutionService.NonAgenticExecutionKind executionKind,
            String rawQuery,
            String finalQueryUsed,
            boolean rewriteApplied,
            JsonNode memoryTopN,
            Double rawScore,
            UUID selectedRewriteCandidateId,
            int finalRetrievedDocsCount,
            String selectedReason,
            String rejectedReason,
            JsonNode latencyBreakdown
    ) {
        public OnlineQueryDecisionPersistenceRequest {
            persistPolicy = persistPolicy == null ? RagPersistPolicy.NONE : persistPolicy;
            rawQuery = rawQuery == null ? "" : rawQuery;
            finalQueryUsed = finalQueryUsed == null ? rawQuery : finalQueryUsed;
            memoryTopN = memoryTopN == null ? JsonNodeFactory.instance.arrayNode() : memoryTopN;
            finalRetrievedDocsCount = Math.max(0, finalRetrievedDocsCount);
            latencyBreakdown = latencyBreakdown == null ? JsonNodeFactory.instance.objectNode() : latencyBreakdown;
        }
    }

    public record OnlineQueryMetadataMergePersistenceRequest(
            RagPersistPolicy persistPolicy,
            UUID onlineQueryId,
            RagRetrievalExecutionService.NonAgenticExecutionKind executionKind,
            JsonNode metadata,
            String sourceMarker
    ) {
        public OnlineQueryMetadataMergePersistenceRequest {
            persistPolicy = persistPolicy == null ? RagPersistPolicy.NONE : persistPolicy;
            metadata = metadata == null ? JsonNodeFactory.instance.objectNode() : metadata;
            sourceMarker = sourceMarker == null || sourceMarker.isBlank() ? null : sourceMarker;
        }
    }

    public record RawOnlyTracePersistenceRequest(
            RagPersistPolicy persistPolicy,
            UUID onlineQueryId,
            String rawQuery,
            String finalQuery,
            String mode,
            List<RagRepository.RetrievalDoc> rawRetrievedDocs,
            List<RagRepository.RetrievalDoc> rawRerankedDocs,
            JsonNode retrievalMetadata,
            String retrieverName,
            String rerankerModel,
            long latencyMs,
            RawOnlyTraceWriteScope writeScope
    ) {
        public RawOnlyTracePersistenceRequest {
            persistPolicy = persistPolicy == null ? RagPersistPolicy.NONE : persistPolicy;
            rawQuery = rawQuery == null ? "" : rawQuery;
            finalQuery = finalQuery == null ? rawQuery : finalQuery;
            mode = mode == null ? "raw_only" : mode;
            rawRetrievedDocs = rawRetrievedDocs == null ? List.of() : List.copyOf(rawRetrievedDocs);
            rawRerankedDocs = rawRerankedDocs == null ? List.of() : List.copyOf(rawRerankedDocs);
            retrievalMetadata = retrievalMetadata == null ? JsonNodeFactory.instance.objectNode() : retrievalMetadata;
            retrieverName = retrieverName == null ? "unknown" : retrieverName;
            rerankerModel = rerankerModel == null ? "unknown" : rerankerModel;
            writeScope = writeScope == null ? RawOnlyTraceWriteScope.RETRIEVAL : writeScope;
            latencyMs = Math.max(0L, latencyMs);
        }
    }

    public record RewriteCandidateTracePersistenceRequest(
            RagPersistPolicy persistPolicy,
            UUID onlineQueryId,
            UUID rewriteCandidateId,
            RagRetrievalExecutionService.NonAgenticExecutionKind executionKind,
            int candidateIndex,
            String candidateQuery,
            String candidateLabel,
            String mode,
            List<RagRepository.RetrievalDoc> retrievedDocs,
            List<RagRepository.RetrievalDoc> rerankedDocs,
            JsonNode retrievalMetadata,
            String retrieverName,
            String rerankerModel,
            long latencyMs,
            RewriteCandidateTraceWriteScope writeScope
    ) {
        public RewriteCandidateTracePersistenceRequest {
            persistPolicy = persistPolicy == null ? RagPersistPolicy.NONE : persistPolicy;
            candidateIndex = Math.max(0, candidateIndex);
            candidateQuery = candidateQuery == null ? "" : candidateQuery;
            candidateLabel = candidateLabel == null ? "" : candidateLabel;
            mode = mode == null ? "" : mode;
            retrievedDocs = retrievedDocs == null ? List.of() : List.copyOf(retrievedDocs);
            rerankedDocs = rerankedDocs == null ? List.of() : List.copyOf(rerankedDocs);
            retrievalMetadata = retrievalMetadata == null ? JsonNodeFactory.instance.objectNode() : retrievalMetadata;
            retrieverName = retrieverName == null ? "unknown" : retrieverName;
            rerankerModel = rerankerModel == null ? "unknown" : rerankerModel;
            latencyMs = Math.max(0L, latencyMs);
            writeScope = writeScope == null ? RewriteCandidateTraceWriteScope.CANDIDATE_RETRIEVAL : writeScope;
        }
    }

    public record AgenticSubqueryRetrievalTracePersistenceRequest(
            RagPersistPolicy persistPolicy,
            UUID onlineQueryId,
            AgenticRetrievalExecutionKind executionKind,
            int subqueryIndex,
            String subqueryText,
            String mode,
            UUID rewriteCandidateId,
            List<RagRepository.RetrievalDoc> retrievedDocs,
            JsonNode retrievalMetadata,
            String retrieverName,
            long latencyMs,
            AgenticSubqueryRetrievalTraceWriteScope writeScope
    ) {
        public AgenticSubqueryRetrievalTracePersistenceRequest {
            persistPolicy = persistPolicy == null ? RagPersistPolicy.NONE : persistPolicy;
            subqueryIndex = Math.max(0, subqueryIndex);
            subqueryText = subqueryText == null ? "" : subqueryText;
            mode = mode == null ? "" : mode;
            retrievedDocs = retrievedDocs == null ? List.of() : List.copyOf(retrievedDocs);
            retrievalMetadata = retrievalMetadata == null ? JsonNodeFactory.instance.objectNode() : retrievalMetadata;
            retrieverName = retrieverName == null ? "unknown" : retrieverName;
            latencyMs = Math.max(0L, latencyMs);
            writeScope = writeScope == null
                    ? AgenticSubqueryRetrievalTraceWriteScope.SUBQUERY_RAW_RETRIEVAL
                    : writeScope;
        }
    }

    public record AgenticRewriteCandidateTracePersistenceRequest(
            RagPersistPolicy persistPolicy,
            UUID onlineQueryId,
            AgenticRetrievalExecutionKind executionKind,
            int subqueryIndex,
            String subqueryText,
            int candidateIndex,
            String candidateLabel,
            String candidateQuery,
            JsonNode candidateMetadata,
            JsonNode memorySourceIds,
            JsonNode retrievalTopKDocs,
            double confidenceScore,
            JsonNode scoreBreakdown
    ) {
        public AgenticRewriteCandidateTracePersistenceRequest {
            persistPolicy = persistPolicy == null ? RagPersistPolicy.NONE : persistPolicy;
            subqueryIndex = Math.max(0, subqueryIndex);
            subqueryText = subqueryText == null ? "" : subqueryText;
            candidateIndex = Math.max(0, candidateIndex);
            candidateLabel = candidateLabel == null ? "" : candidateLabel;
            candidateQuery = candidateQuery == null ? "" : candidateQuery;
            candidateMetadata = candidateMetadata == null ? JsonNodeFactory.instance.objectNode() : candidateMetadata;
            memorySourceIds = memorySourceIds == null ? JsonNodeFactory.instance.arrayNode() : memorySourceIds;
            retrievalTopKDocs = retrievalTopKDocs == null ? JsonNodeFactory.instance.arrayNode() : retrievalTopKDocs;
            scoreBreakdown = scoreBreakdown == null ? JsonNodeFactory.instance.objectNode() : scoreBreakdown;
        }
    }

    public record AgenticRewriteCandidateAdoptionPersistenceRequest(
            RagPersistPolicy persistPolicy,
            UUID onlineQueryId,
            UUID rewriteCandidateId,
            AgenticRetrievalExecutionKind executionKind,
            int subqueryIndex,
            boolean adopted,
            String rejectedReason
    ) {
        public AgenticRewriteCandidateAdoptionPersistenceRequest {
            persistPolicy = persistPolicy == null ? RagPersistPolicy.NONE : persistPolicy;
            subqueryIndex = Math.max(0, subqueryIndex);
        }
    }

    public record AgenticFinalRerankTracePersistenceRequest(
            RagPersistPolicy persistPolicy,
            UUID onlineQueryId,
            AgenticRetrievalExecutionKind executionKind,
            List<RagRepository.RetrievalDoc> finalMergedDocs,
            String resultScope,
            String rerankerModel,
            long latencyMs
    ) {
        public AgenticFinalRerankTracePersistenceRequest {
            persistPolicy = persistPolicy == null ? RagPersistPolicy.NONE : persistPolicy;
            finalMergedDocs = finalMergedDocs == null ? List.of() : List.copyOf(finalMergedDocs);
            resultScope = resultScope == null || resultScope.isBlank() ? "agentic-rrf" : resultScope;
            rerankerModel = rerankerModel == null || rerankerModel.isBlank() ? resultScope : rerankerModel;
            latencyMs = Math.max(0L, latencyMs);
        }
    }

    public record CreateRewriteCandidateTracePersistenceRequest(
            RagPersistPolicy persistPolicy,
            UUID onlineQueryId,
            RagRetrievalExecutionService.NonAgenticExecutionKind executionKind,
            int candidateIndex,
            String candidateLabel,
            String candidateQuery,
            JsonNode candidateMetadata,
            JsonNode memorySourceIds,
            JsonNode retrievalTopKDocs,
            double confidenceScore,
            JsonNode scoreBreakdown
    ) {
        public CreateRewriteCandidateTracePersistenceRequest {
            persistPolicy = persistPolicy == null ? RagPersistPolicy.NONE : persistPolicy;
            candidateIndex = Math.max(0, candidateIndex);
            candidateLabel = candidateLabel == null ? "" : candidateLabel;
            candidateQuery = candidateQuery == null ? "" : candidateQuery;
            candidateMetadata = candidateMetadata == null ? JsonNodeFactory.instance.objectNode() : candidateMetadata;
            memorySourceIds = memorySourceIds == null ? JsonNodeFactory.instance.arrayNode() : memorySourceIds;
            retrievalTopKDocs = retrievalTopKDocs == null ? JsonNodeFactory.instance.arrayNode() : retrievalTopKDocs;
            scoreBreakdown = scoreBreakdown == null ? JsonNodeFactory.instance.objectNode() : scoreBreakdown;
        }
    }

    public record RewriteCandidateAdoptionPersistenceRequest(
            RagPersistPolicy persistPolicy,
            UUID onlineQueryId,
            UUID rewriteCandidateId,
            RagRetrievalExecutionService.NonAgenticExecutionKind executionKind,
            boolean adopted,
            String rejectedReason
    ) {
        public RewriteCandidateAdoptionPersistenceRequest {
            persistPolicy = persistPolicy == null ? RagPersistPolicy.NONE : persistPolicy;
        }
    }

    public record OnlineRewriteLogPersistenceRequest(
            RagPersistPolicy persistPolicy,
            UUID onlineQueryId,
            UUID runId,
            RagRetrievalExecutionService.NonAgenticExecutionKind executionKind,
            String rawQuery,
            String finalQuery,
            String rewriteStrategy,
            JsonNode generationMethodCodes,
            JsonNode generationBatchIds,
            boolean gatingApplied,
            String gatingPreset,
            boolean rewriteApplied,
            Boolean selectiveRewrite,
            Boolean useSessionContext,
            Double rawConfidence,
            Double selectedConfidence,
            Double confidenceDelta,
            String decisionReason,
            String rejectionReason,
            JsonNode metadata
    ) {
        public OnlineRewriteLogPersistenceRequest {
            persistPolicy = persistPolicy == null ? RagPersistPolicy.NONE : persistPolicy;
            rawQuery = rawQuery == null ? "" : rawQuery;
            finalQuery = finalQuery == null ? rawQuery : finalQuery;
            rewriteStrategy = rewriteStrategy == null ? "" : rewriteStrategy;
            generationMethodCodes = generationMethodCodes == null ? JsonNodeFactory.instance.arrayNode() : generationMethodCodes;
            generationBatchIds = generationBatchIds == null ? JsonNodeFactory.instance.arrayNode() : generationBatchIds;
            gatingPreset = gatingPreset == null ? "" : gatingPreset;
            metadata = metadata == null ? JsonNodeFactory.instance.objectNode() : metadata;
        }
    }

    public record MemoryRetrievalLogPersistenceRequest(
            RagPersistPolicy persistPolicy,
            UUID onlineQueryId,
            UUID rewriteLogId,
            RagRetrievalExecutionService.NonAgenticExecutionKind executionKind,
            int retrievalRank,
            RagRepository.MemoryCandidate candidate,
            JsonNode metadata
    ) {
        public MemoryRetrievalLogPersistenceRequest {
            persistPolicy = persistPolicy == null ? RagPersistPolicy.NONE : persistPolicy;
            retrievalRank = Math.max(0, retrievalRank);
            metadata = metadata == null ? JsonNodeFactory.instance.objectNode() : metadata;
        }
    }

    public record RewriteCandidateLogPersistenceRequest(
            RagPersistPolicy persistPolicy,
            UUID onlineQueryId,
            UUID rewriteLogId,
            UUID rewriteCandidateId,
            RagRetrievalExecutionService.NonAgenticExecutionKind executionKind,
            int candidateRank,
            String candidateLabel,
            String candidateQuery,
            Double confidenceScore,
            boolean selected,
            String rejectionReason,
            JsonNode retrievalTopKDocs,
            JsonNode scoreBreakdown,
            JsonNode metadata
    ) {
        public RewriteCandidateLogPersistenceRequest {
            persistPolicy = persistPolicy == null ? RagPersistPolicy.NONE : persistPolicy;
            candidateRank = Math.max(0, candidateRank);
            candidateLabel = candidateLabel == null ? "" : candidateLabel;
            candidateQuery = candidateQuery == null ? "" : candidateQuery;
            retrievalTopKDocs = retrievalTopKDocs == null ? JsonNodeFactory.instance.arrayNode() : retrievalTopKDocs;
            scoreBreakdown = scoreBreakdown == null ? JsonNodeFactory.instance.objectNode() : scoreBreakdown;
            metadata = metadata == null ? JsonNodeFactory.instance.objectNode() : metadata;
        }
    }

    public enum RawOnlyTraceWriteScope {
        RETRIEVAL("retrieval"),
        RERANK("rerank");

        private final String statusSuffix;

        RawOnlyTraceWriteScope(String statusSuffix) {
            this.statusSuffix = statusSuffix;
        }
    }

    public enum RewriteCandidateTraceWriteScope {
        CANDIDATE_RETRIEVAL("candidate_retrieval"),
        CANDIDATE_RERANK("candidate_rerank");

        private final String statusSuffix;

        RewriteCandidateTraceWriteScope(String statusSuffix) {
            this.statusSuffix = statusSuffix;
        }
    }

    public enum AgenticRetrievalExecutionKind {
        AGENTIC_MULTI_QUERY
    }

    public enum AgenticSubqueryRetrievalTraceWriteScope {
        SUBQUERY_RAW_RETRIEVAL("subquery_raw_retrieval", "raw"),
        SUBQUERY_CANDIDATE_RETRIEVAL("subquery_candidate_retrieval", "rewrite_candidate");

        private final String statusSuffix;
        private final String repositoryStage;

        AgenticSubqueryRetrievalTraceWriteScope(String statusSuffix, String repositoryStage) {
            this.statusSuffix = statusSuffix;
            this.repositoryStage = repositoryStage;
        }
    }
}
