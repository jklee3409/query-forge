package io.queryforge.backend.rag.service;

import io.queryforge.backend.rag.model.ForcedRetrievalMode;
import io.queryforge.backend.rag.model.RagPersistPolicy;
import io.queryforge.backend.rag.model.RagRetrievalExecutionResult;
import io.queryforge.backend.rag.repository.RagRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

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
                    "TRACE_ONLY persistence is not implemented in Phase 5A"
            );
            case ONLINE_QUERY -> throw new UnsupportedOperationException(
                    "ONLINE_QUERY persistence migration is not implemented in Phase 5A"
            );
        };
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
}
