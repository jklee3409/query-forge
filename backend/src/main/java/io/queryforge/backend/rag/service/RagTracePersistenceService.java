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
                    "TRACE_ONLY persistence is not implemented in Phase 5B"
            );
            case ONLINE_QUERY -> throw new UnsupportedOperationException(
                    "ONLINE_QUERY generic persistence migration is not implemented in Phase 5B; use raw_only trace persistence"
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
                    "TRACE_ONLY persistence is not implemented in Phase 5B"
            );
            case ONLINE_QUERY -> persistRawOnlyOnlineQueryTrace(request);
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

    public enum RawOnlyTraceWriteScope {
        RETRIEVAL("retrieval"),
        RERANK("rerank");

        private final String statusSuffix;

        RawOnlyTraceWriteScope(String statusSuffix) {
            this.statusSuffix = statusSuffix;
        }
    }
}
