package io.queryforge.backend.rag.chat.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.queryforge.backend.rag.chat.answer.dto.ChatAnswerDtos;
import io.queryforge.backend.rag.chat.answer.service.ChatAnswerService;
import io.queryforge.backend.rag.retrieval.agentic.dto.AgenticRetrievalLimits;
import io.queryforge.backend.rag.retrieval.agentic.dto.AgenticExecutionDtos;
import io.queryforge.backend.rag.retrieval.agentic.dto.AgenticRetrievalDtos;
import io.queryforge.backend.rag.retrieval.candidate.dto.RetrievalDtos;
import io.queryforge.backend.rag.retrieval.candidate.model.RetrievalDoc;
import io.queryforge.backend.rag.retrieval.memory.model.MemoryCandidate;
import io.queryforge.backend.rag.retrieval.rewrite.dto.RewriteDtos;
import io.queryforge.backend.rag.retrieval.routing.dto.QueryRouteDecision;
import io.queryforge.backend.rag.runtime.configuration.dto.ChatConfigDtos;
import io.queryforge.backend.rag.runtime.configuration.model.RagExecutionSettings;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Component
@RequiredArgsConstructor
class ChatResponseAssembler {

    private final ChatAnswerService chatAnswerService;
    private final ObjectMapper objectMapper;

    List<RetrievalDtos.ScoredDocumentDto> toScoredDocs(List<RetrievalDoc> docs) {
        return docs.stream()
                .map(doc -> new RetrievalDtos.ScoredDocumentDto(
                        doc.documentId(),
                        doc.chunkId(),
                        preview(doc.chunkText()),
                        doc.score()
                ))
                .toList();
    }

    List<RewriteDtos.RewriteCandidateDto> toAgenticRewriteDtos(
            List<AgenticExecutionDtos.PersistedRewriteCandidate> candidates
    ) {
        return candidates.stream()
                .map(candidate -> new RewriteDtos.RewriteCandidateDto(
                        candidate.rewriteCandidateId(),
                        candidate.label(),
                        candidate.query(),
                        candidate.confidence(),
                        candidate.selected(),
                        candidate.selected() ? null : candidate.rejectedReason(),
                        objectMapper.valueToTree(candidate.retrieved()),
                        candidate.scoreBreakdown()
                ))
                .toList();
    }

    AgenticRetrievalDtos.AgenticRetrievalMetadata toAgenticMetadata(
            AgenticExecutionDtos.AgenticExecutionResult result,
            AgenticSettings settings,
            int finalTopK
    ) {
        ObjectNode metadata = objectMapper.createObjectNode();
        metadata.set("execution", nullSafeJson(result.metadata()));
        metadata.put("fallback_plan", result.plan().fallbackApplied());
        return new AgenticRetrievalDtos.AgenticRetrievalMetadata(
                result.plan(),
                result.traces(),
                settings.mergeStrategy(),
                settings.rrfK(),
                finalTopK,
                toScoredDocs(result.mergedDocs()),
                metadata
        );
    }

    ObjectNode agenticMetadataEnvelope(
            AgenticExecutionDtos.AgenticExecutionResult result,
            AgenticSettings settings,
            int finalTopK,
            QueryRouteDecision routeDecision
    ) {
        ObjectNode node = objectMapper.createObjectNode();
        node.set("agentic_plan", objectMapper.valueToTree(result.plan()));
        node.set("subquery_traces", objectMapper.valueToTree(result.traces()));
        node.put("merge_strategy", settings.mergeStrategy());
        node.put("max_subqueries", settings.maxSubqueries());
        node.put("rrf_k", settings.rrfK());
        node.put("final_top_k", finalTopK);
        node.set("merged_docs", objectMapper.valueToTree(toScoredDocs(result.mergedDocs())));
        node.set("agentic_execution", nullSafeJson(result.metadata()));
        node.set("router", routerMetadata(routeDecision));
        return node;
    }

    AgenticSettings agenticSettings(JsonNode metadata) {
        boolean enabled = booleanMetadata(metadata, "agenticMultiQueryEnabled", "agentic_multi_query_enabled", false);
        AgenticRetrievalLimits limits = new AgenticRetrievalLimits(
                intMetadata(metadata, "maxSubqueries", "max_subqueries", AgenticRetrievalLimits.DEFAULT_CHAT_MAX_SUBQUERIES),
                intMetadata(metadata, "rrfK", "rrf_k", AgenticRetrievalLimits.DEFAULT_RRF_K)
        );
        String mergeStrategy = stringMetadata(metadata, "agenticMergeStrategy", "agentic_merge_strategy", "RRF");
        String normalizedMergeStrategy = mergeStrategy == null || mergeStrategy.isBlank()
                ? "RRF"
                : mergeStrategy.trim().toUpperCase(Locale.ROOT);
        if (!"RRF".equals(normalizedMergeStrategy)) {
            normalizedMergeStrategy = "RRF";
        }
        return new AgenticSettings(
                enabled,
                limits.maxSubqueries(),
                normalizedMergeStrategy,
                limits.rrfK()
        );
    }

    ObjectNode agenticSettingsMetadata(AgenticSettings settings, int finalTopK) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("agenticMultiQueryEnabled", settings.enabled());
        node.put("maxSubqueries", settings.maxSubqueries());
        node.put("agenticMergeStrategy", settings.mergeStrategy());
        node.put("rrfK", settings.rrfK());
        node.put("finalTopK", finalTopK);
        return node;
    }

    private boolean booleanMetadata(JsonNode metadata, String primaryKey, String aliasKey, boolean fallback) {
        JsonNode value = metadataValue(metadata, primaryKey, aliasKey);
        return value == null || value.isMissingNode() || value.isNull() ? fallback : value.asBoolean(fallback);
    }

    private int intMetadata(JsonNode metadata, String primaryKey, String aliasKey, int fallback) {
        JsonNode value = metadataValue(metadata, primaryKey, aliasKey);
        return value == null || value.isMissingNode() || value.isNull() ? fallback : value.asInt(fallback);
    }

    private String stringMetadata(JsonNode metadata, String primaryKey, String aliasKey, String fallback) {
        JsonNode value = metadataValue(metadata, primaryKey, aliasKey);
        if (value == null || value.isMissingNode() || value.isNull()) {
            return fallback;
        }
        String raw = value.asText("");
        return raw.isBlank() ? fallback : raw;
    }

    private JsonNode metadataValue(JsonNode metadata, String primaryKey, String aliasKey) {
        if (metadata == null || !metadata.isObject()) {
            return null;
        }
        JsonNode value = metadata.path(primaryKey);
        if (!value.isMissingNode()) {
            return value;
        }
        return metadata.path(aliasKey);
    }

    ObjectNode retrievalMetadata(RetrievalDtos.RetrievalRuntime runtime) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("retrieval_backend", runtime.retrievalBackend());
        node.put("dense_embedding_model", runtime.denseEmbeddingModel());
        node.put("retriever_mode", runtime.retrieverMode());
        node.put("retriever_candidate_pool_k", runtime.candidatePoolK());
        ObjectNode weights = node.putObject("retriever_fusion_weights");
        weights.put("dense", runtime.denseWeight());
        weights.put("bm25", runtime.bm25Weight());
        weights.put("technical", runtime.technicalWeight());
        return node;
    }

    JsonNode memorySourceIds(List<MemoryCandidate> memories) {
        ArrayNode array = objectMapper.createArrayNode();
        for (MemoryCandidate memory : memories) {
            array.add(memory.memoryId().toString());
        }
        return array;
    }

    JsonNode generationMethodCodes(List<MemoryCandidate> memories) {
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (MemoryCandidate memory : memories) {
            if (memory.generationStrategy() != null && !memory.generationStrategy().isBlank()) {
                unique.add(memory.generationStrategy().trim().toUpperCase(Locale.ROOT));
            }
        }
        ArrayNode array = objectMapper.createArrayNode();
        unique.forEach(array::add);
        return array;
    }

    JsonNode generationBatchIds(List<MemoryCandidate> memories) {
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (MemoryCandidate memory : memories) {
            if (memory.generationBatchId() != null) {
                unique.add(memory.generationBatchId().toString());
            }
        }
        ArrayNode array = objectMapper.createArrayNode();
        unique.forEach(array::add);
        return array;
    }

    ObjectNode runtimeMetadata(ChatConfigDtos.ChatRuntimeConfigResponse config) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("domain_id", config.domainId().toString());
        node.put("domain_key", config.domainKey());
        node.put("domain_display_name", config.displayName());
        node.put("mode", config.mode());
        node.put("gating_preset", config.gatingPreset());
        node.set("generation_strategies", objectMapper.valueToTree(config.generationStrategies()));
        putNullableUuid(node, "source_gating_batch_id", config.sourceGatingBatchId());
        putNullableUuid(node, "source_gating_run_id", config.sourceGatingRunId());
        node.set("source_gating_batch_ids", objectMapper.valueToTree(config.sourceGatingBatchIds()));
        node.set("source_gating_run_ids", objectMapper.valueToTree(config.sourceGatingRunIds()));
        node.put("rewrite_query_profile", config.rewriteQueryProfile());
        node.put("rewrite_anchor_injection_enabled", config.rewriteAnchorInjectionEnabled());
        node.put("use_session_context", config.useSessionContext());
        node.put("retrieval_backend", config.retrievalBackend());
        node.put("dense_embedding_model", config.denseEmbeddingModel());
        node.put("retriever_mode", config.retrieverMode());
        node.put("retriever_candidate_pool_k", config.retrieverCandidatePoolK());
        ObjectNode weights = node.putObject("retriever_fusion_weights");
        weights.put("dense", config.retrieverDenseWeight());
        weights.put("bm25", config.retrieverBm25Weight());
        weights.put("technical", config.retrieverTechnicalWeight());
        return node;
    }

    ObjectNode rewriteLogMetadata(
            ChatConfigDtos.ChatRuntimeConfigResponse config,
            String rewriteQueryProfile,
            RagExecutionSettings settings,
            Map<String, Long> latencyBreakdown,
            RetrievalDtos.RetrievalRuntime retrievalRuntime,
            QueryRouteDecision routeDecision
    ) {
        ObjectNode node = runtimeMetadata(config);
        node.put("rewrite_query_profile", rewriteQueryProfile);
        node.put("retrieval_top_k", settings.retrievalTopK());
        node.put("rerank_top_n", settings.rerankTopN());
        node.put("memory_top_n", settings.memoryTopN());
        node.put("rewrite_candidate_count", settings.rewriteCandidateCount());
        node.put("rewrite_threshold", settings.rewriteThreshold());
        node.set("retrieval_runtime", retrievalMetadata(retrievalRuntime));
        node.set("latency_breakdown", objectMapper.valueToTree(latencyBreakdown));
        node.set("router", routerMetadata(routeDecision));
        return node;
    }

    ObjectNode routerMetadataEnvelope(QueryRouteDecision routeDecision) {
        ObjectNode node = objectMapper.createObjectNode();
        node.set("router", routerMetadata(routeDecision));
        return node;
    }

    ObjectNode routerMetadata(QueryRouteDecision routeDecision) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("enabled", routeDecision.routerEnabled());
        node.put("strategy", routeDecision.strategy().name());
        node.put("reason", routeDecision.reason());
        node.put("fallbackAllowed", routeDecision.fallbackAllowed());
        node.put("fallbackApplied", routeDecision.fallbackApplied());
        putNullableString(node, "fallbackReason", routeDecision.fallbackReason());
        node.put("effectiveMode", routeDecision.effectiveMode());
        node.put("rewriteQueryProfile", routeDecision.rewriteQueryProfile());
        node.put("anchorInjectionEnabled", routeDecision.anchorInjectionEnabled());
        JsonNode metadata = objectMapper.valueToTree(routeDecision.metadata());
        if (metadata.has("latencyMs")) {
            node.put("latencyMs", metadata.path("latencyMs").asLong());
        }
        node.set("metadata", metadata);
        return node;
    }

    ObjectNode domainContext(ChatConfigDtos.ChatRuntimeConfigResponse config) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("current_technical_domain", config.displayName());
        node.put("domain_key", config.domainKey());
        node.put("source_language", config.sourceLanguage() == null ? "" : config.sourceLanguage());
        node.put(
                "rewrite_instruction",
                "Keep the query inside the " + config.displayName()
                        + " documentation domain and do not add anchors from other domains."
        );
        return node;
    }

    private void putNullableUuid(ObjectNode node, String fieldName, UUID value) {
        if (value == null) {
            node.putNull(fieldName);
        } else {
            node.put(fieldName, value.toString());
        }
    }

    private void putNullableString(ObjectNode node, String fieldName, String value) {
        if (value == null) {
            node.putNull(fieldName);
        } else {
            node.put(fieldName, value);
        }
    }

    AnswerDraft buildAnswer(
            String rawQuery,
            String finalQuery,
            String domainDisplayName,
            List<RetrievalDoc> reranked
    ) {
        ChatAnswerDtos.GeneratedAnswer generated = chatAnswerService.generateAnswer(
                rawQuery,
                finalQuery,
                domainDisplayName,
                reranked
        );
        return new AnswerDraft(
                generated.answerText(),
                generated.citedDocumentIds(),
                generated.citedChunkIds(),
                generated.modelName()
        );
    }

    JsonNode nullSafeJson(JsonNode node) {
        return node != null ? node : objectMapper.createObjectNode();
    }

    private String preview(String text) {
        if (text == null) {
            return "";
        }
        String normalized = text.replaceAll("\\s+", " ").trim();
        if (normalized.length() <= 220) {
            return normalized;
        }
        return normalized.substring(0, 220) + "...";
    }

    long elapsedMs(long startedNano) {
        return (System.nanoTime() - startedNano) / 1_000_000L;
    }

    record AgenticSettings(
            boolean enabled,
            int maxSubqueries,
            String mergeStrategy,
            int rrfK
    ) {
    }

    record AnswerDraft(
            String answerText,
            List<String> citedDocumentIds,
            List<String> citedChunkIds,
            String modelName
    ) {
    }
}
