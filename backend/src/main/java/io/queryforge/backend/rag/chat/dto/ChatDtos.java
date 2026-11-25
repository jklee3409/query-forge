package io.queryforge.backend.rag.chat.dto;

import com.fasterxml.jackson.databind.JsonNode;
import io.queryforge.backend.rag.retrieval.agentic.dto.AgenticRetrievalDtos.AgenticRetrievalMetadata;
import io.queryforge.backend.rag.retrieval.candidate.dto.RetrievalDtos.ScoredDocumentDto;
import io.queryforge.backend.rag.retrieval.rewrite.dto.RewriteDtos.RewriteCandidateDto;
import io.queryforge.backend.rag.runtime.configuration.dto.ChatConfigDtos;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class ChatDtos {

    private ChatDtos() {
    }

    public record AskRequest(
            String query,
            UUID domainId,
            String sessionId,
            JsonNode sessionContext,
            String rewriteQueryProfile
    ) {
    }

    public record AskResponse(
            UUID onlineQueryId,
            String answer,
            String finalQueryUsed,
            String rawQuery,
            boolean rewriteApplied,
            List<RewriteCandidateDto> rewriteCandidates,
            List<ScoredDocumentDto> retrievedDocs,
            List<ScoredDocumentDto> rerankedDocs,
            JsonNode memoryTopN,
            String answerModel,
            List<String> citedDocumentIds,
            List<String> citedChunkIds,
            ChatConfigDtos.ChatRuntimeConfigResponse appliedConfig,
            Map<String, Long> latencyBreakdown,
            AgenticRetrievalMetadata agenticMetadata
    ) {
    }
}
