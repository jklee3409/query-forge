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
                    "TRACE_ONLY persistence is not implemented in Phase 5D"
            );
            case ONLINE_QUERY -> throw new UnsupportedOperationException(
                    "ONLINE_QUERY generic persistence migration is not implemented in Phase 5D; use a phase-specific trace persistence method"
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
                    "TRACE_ONLY persistence is not implemented in Phase 5D"
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
                    "TRACE_ONLY persistence is not implemented in Phase 5D"
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
                    "TRACE_ONLY persistence is not implemented in Phase 5D"
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
                    "TRACE_ONLY persistence is not implemented in Phase 5D"
            );
            case ONLINE_QUERY -> markRewriteCandidateAdoptedOnlineQueryTrace(request);
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

    private void validateRewriteCandidateExecutionKind(
            RagRetrievalExecutionService.NonAgenticExecutionKind executionKind,
            String context
    ) {
        if (executionKind == null) {
            throw new IllegalArgumentException("executionKind is required for ONLINE_QUERY " + context);
        }
        if (executionKind == RagRetrievalExecutionService.NonAgenticExecutionKind.RAW_ONLY) {
            throw new IllegalArgumentException(context + " does not support RAW_ONLY execution kind");
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
}
