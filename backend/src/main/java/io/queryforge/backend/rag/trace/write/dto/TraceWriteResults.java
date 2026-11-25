package io.queryforge.backend.rag.trace.write.dto;

import io.queryforge.backend.rag.retrieval.execution.model.RagPersistPolicy;

import java.util.UUID;

public final class TraceWriteResults {

    private TraceWriteResults() {
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
}
