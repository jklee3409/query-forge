package io.queryforge.backend.rag.eval.retrieval.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.queryforge.backend.rag.eval.retrieval.dto.RagRetrievalEvalDtos;
import io.queryforge.backend.rag.eval.retrieval.exception.RagRetrievalEvalException;
import io.queryforge.backend.rag.eval.retrieval.service.EvalRuntimeConfigService.AgenticEvalSettings;
import io.queryforge.backend.rag.eval.retrieval.service.EvalRuntimeConfigService.RuntimeEvalContext;
import io.queryforge.backend.rag.retrieval.agentic.dto.AgenticExecutionDtos;
import io.queryforge.backend.rag.retrieval.agentic.service.AgenticRetrievalService;
import io.queryforge.backend.rag.retrieval.candidate.dto.RetrievalDtos;
import io.queryforge.backend.rag.retrieval.candidate.model.RetrievalDoc;
import io.queryforge.backend.rag.retrieval.candidate.service.DomainScopedRetrievalService;
import io.queryforge.backend.rag.retrieval.execution.dto.RagLlmCallCount;
import io.queryforge.backend.rag.retrieval.execution.dto.RetrievalExecutionDtos;
import io.queryforge.backend.rag.retrieval.execution.model.RagPersistPolicy;
import io.queryforge.backend.rag.retrieval.execution.service.RagRetrievalExecutionService;
import io.queryforge.backend.rag.retrieval.memory.model.MemoryCandidate;
import io.queryforge.backend.rag.retrieval.routing.dto.QueryRouteContext;
import io.queryforge.backend.rag.retrieval.routing.dto.QueryRouteDecision;
import io.queryforge.backend.rag.retrieval.routing.service.QueryStrategyRouter;
import io.queryforge.backend.rag.runtime.configuration.dto.ChatConfigDtos;
import io.queryforge.backend.rag.runtime.configuration.model.RagExecutionSettings;
import io.queryforge.backend.rag.runtime.configuration.service.ChatRuntimeConfigService;
import io.queryforge.backend.rag.runtime.readiness.dto.ChatReadinessDtos;
import io.queryforge.backend.rag.runtime.readiness.service.ChatReadinessService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.IntStream;

import static io.queryforge.backend.rag.eval.retrieval.service.EvalRuntimeConfigService.agenticSettings;
import static io.queryforge.backend.rag.eval.retrieval.service.EvalRuntimeConfigService.nonNullList;
import static io.queryforge.backend.rag.eval.retrieval.service.EvalRuntimeConfigService.normalizeText;
import static io.queryforge.backend.rag.eval.retrieval.service.EvalRuntimeConfigService.normalizedMode;

@Service
@RequiredArgsConstructor
public class RagRetrievalEvalService {

    private static final int CONTENT_PREVIEW_MAX_LENGTH = 240;
    private static final Pattern TOKEN_PATTERN = Pattern.compile("[A-Za-z0-9_./:$#-]+|\\p{InHangulSyllables}+");
    private static final Set<String> SUPPORTED_FORCED_MODES = Set.of(
            "raw_only",
            "selective_rewrite",
            "anchor_aware_rewrite",
            "agentic_multi_query",
            "strategy_router"
    );
    private static final String ERROR_REQUEST_REQUIRED = "request_required";
    private static final String ERROR_DOMAIN_ID_REQUIRED = "domainId_required";
    private static final String ERROR_QUERY_REQUIRED = "query_required";
    private static final String ERROR_UNSUPPORTED_PERSIST_POLICY = "unsupported_persist_policy";
    private static final String ERROR_UNSUPPORTED_ANSWER_GENERATION = "unsupported_answer_generation";
    private static final String ERROR_UNSUPPORTED_FORCED_MODE = "unsupported_forced_mode";
    private final ChatRuntimeConfigService chatRuntimeConfigService;
    private final ChatReadinessService chatReadinessService;
    private final DomainScopedRetrievalService domainScopedRetrievalService;
    private final QueryStrategyRouter queryStrategyRouter;
    private final RagRetrievalExecutionService ragRetrievalExecutionService;
    private final AgenticRetrievalService agenticRetrievalService;
    private final ObjectMapper objectMapper;
    private final EvalRuntimeConfigService runtimeConfigService;

    public RagRetrievalEvalDtos.RagRetrievalEvalResponse evaluate(
            RagRetrievalEvalDtos.RagRetrievalEvalRequest request
    ) {
        return execute(request);
    }

    public RagRetrievalEvalDtos.RagRetrievalEvalResponse execute(
            RagRetrievalEvalDtos.RagRetrievalEvalRequest request
    ) {
        long started = System.nanoTime();
        validateRequest(request);

        UUID domainId = request.domainId();
        String query = request.query().trim();
        ChatConfigDtos.ChatRuntimeConfigResponse config = chatRuntimeConfigService.getConfig(domainId);
        ChatReadinessDtos.ChatDomainReadinessResponse readiness = chatReadinessService.evaluateReadiness(config);
        RuntimeEvalContext runtimeContext = runtimeConfigService.resolve(request, config, readiness);
        config = runtimeContext.config();
        readiness = runtimeContext.readiness();
        validateRuntime(config, readiness);

        RetrievalDtos.RetrievalRuntime retrievalRuntime =
                domainScopedRetrievalService.retrievalRuntime(config);
        RagExecutionSettings settings = runtimeContext.settings();

        return switch (request.forcedMode()) {
            case "raw_only" -> executeRawOnly(
                    request,
                    query,
                    config,
                    retrievalRuntime,
                    settings,
                    started,
                    null
            );
            case "selective_rewrite" -> executeRewrite(
                    request,
                    query,
                    config,
                    retrievalRuntime,
                    settings,
                    false,
                    "selective_rewrite",
                    started,
                    null
            );
            case "anchor_aware_rewrite" -> executeRewrite(
                    request,
                    query,
                    config,
                    retrievalRuntime,
                    settings,
                    true,
                    "anchor_aware_rewrite",
                    started,
                    null
            );
            case "agentic_multi_query" -> executeAgentic(
                    request,
                    query,
                    config,
                    readiness,
                    settings,
                    started,
                    null,
                    null
            );
            case "strategy_router" -> executeStrategyRouter(
                    request,
                    query,
                    config,
                    readiness,
                    retrievalRuntime,
                    settings,
                    started
            );
            default -> throw evalError(
                    ERROR_UNSUPPORTED_FORCED_MODE,
                    "unsupported_forced_mode: forcedMode is not supported for retrieval eval: " + request.forcedMode()
            );
        };
    }

    private RagRetrievalEvalDtos.RagRetrievalEvalResponse executeStrategyRouter(
            RagRetrievalEvalDtos.RagRetrievalEvalRequest request,
            String query,
            ChatConfigDtos.ChatRuntimeConfigResponse config,
            ChatReadinessDtos.ChatDomainReadinessResponse readiness,
            RetrievalDtos.RetrievalRuntime retrievalRuntime,
            RagExecutionSettings settings,
            long started
    ) {
        List<MemoryCandidate> memoryCandidates = findMemoryCandidates(
                query,
                config,
                retrievalRuntime,
                settings.memoryTopN()
        );
        QueryRouteDecision routeDecision = queryStrategyRouter.route(routeContext(
                query,
                config,
                readiness,
                normalizedMode(config.mode()),
                normalizedRewriteProfile(config.rewriteQueryProfile()),
                config.rewriteAnchorInjectionEnabled(),
                true,
                !memoryCandidates.isEmpty(),
                null
        ));
        QueryRouteDecision routed = routeDecision.withLatency(elapsedMs(started));
        return switch (routed.strategy()) {
            case RAW_ONLY -> executeRawOnly(
                    request,
                    query,
                    config,
                    retrievalRuntime,
                    settings,
                    started,
                    routed
            );
            case SYNTHETIC_SELECTIVE_REWRITE -> executeRewrite(
                    request,
                    query,
                    config,
                    retrievalRuntime,
                    settings,
                    false,
                    "selective_rewrite",
                    started,
                    routed,
                    memoryCandidates
            );
            case ANCHOR_AWARE_REWRITE -> executeRewrite(
                    request,
                    query,
                    config,
                    retrievalRuntime,
                    settings,
                    true,
                    "anchor_aware_rewrite",
                    started,
                    routed,
                    memoryCandidates
            );
            case AGENTIC_MULTI_QUERY -> executeAgentic(
                    request,
                    query,
                    config,
                    readiness,
                    settings,
                    started,
                    routed,
                    memoryCandidates
            );
        };
    }

    private RagRetrievalEvalDtos.RagRetrievalEvalResponse executeRawOnly(
            RagRetrievalEvalDtos.RagRetrievalEvalRequest request,
            String query,
            ChatConfigDtos.ChatRuntimeConfigResponse config,
            RetrievalDtos.RetrievalRuntime retrievalRuntime,
            RagExecutionSettings settings,
            long started,
            QueryRouteDecision routeDecision
    ) {
        String embeddingLiteral = domainScopedRetrievalService.embeddingLiteral(query, retrievalRuntime);
        RetrievalExecutionDtos.RawOnlyExecutionResult result =
                ragRetrievalExecutionService.executeRawOnly(new RetrievalExecutionDtos.RawRetrievalRequest(
                        query,
                        embeddingLiteral,
                        settings.retrievalTopK(),
                        settings.rerankTopN(),
                        config.domainId(),
                        retrievalRuntime
                ));
        return response(
                request,
                config.domainId(),
                query,
                result.finalQuery(),
                "raw_only",
                result.rerankedDocs(),
                result.latencyMs(),
                started,
                routeDecision,
                retrievalTrace("raw_only", result.rawRetrieval(), null),
                List.of()
        );
    }

    private RagRetrievalEvalDtos.RagRetrievalEvalResponse executeRewrite(
            RagRetrievalEvalDtos.RagRetrievalEvalRequest request,
            String query,
            ChatConfigDtos.ChatRuntimeConfigResponse config,
            RetrievalDtos.RetrievalRuntime retrievalRuntime,
            RagExecutionSettings settings,
            boolean anchorAware,
            String selectedMode,
            long started,
            QueryRouteDecision routeDecision
    ) {
        return executeRewrite(
                request,
                query,
                config,
                retrievalRuntime,
                settings,
                anchorAware,
                selectedMode,
                started,
                routeDecision,
                findMemoryCandidates(query, config, retrievalRuntime, settings.memoryTopN())
        );
    }

    private RagRetrievalEvalDtos.RagRetrievalEvalResponse executeRewrite(
            RagRetrievalEvalDtos.RagRetrievalEvalRequest request,
            String query,
            ChatConfigDtos.ChatRuntimeConfigResponse config,
            RetrievalDtos.RetrievalRuntime retrievalRuntime,
            RagExecutionSettings settings,
            boolean anchorAware,
            String selectedMode,
            long started,
            QueryRouteDecision routeDecision,
            List<MemoryCandidate> memoryCandidates
    ) {
        double rawDenseScore = memoryCandidates.isEmpty() ? 0.0d : memoryCandidates.getFirst().similarity();
        String rewriteProfile = normalizedRewriteProfile(config.rewriteQueryProfile());
        RetrievalExecutionDtos.RewriteExecutionResult result = ragRetrievalExecutionService.executeRewrite(
                new RetrievalExecutionDtos.RewriteExecutionRequest(
                        query,
                        objectMapper.createObjectNode(),
                        memoryCandidates,
                        settings.rewriteCandidateCount(),
                        rewriteProfile,
                        domainContext(config),
                        settings.retrievalTopK(),
                        settings.rerankTopN(),
                        config.domainId(),
                        retrievalRuntime,
                        rawDenseScore,
                        anchorAware
                )
        );
        RetrievalExecutionDtos.ExecutedRewriteCandidate selected = selectCandidate(result.candidates());
        List<String> warnings = selected == null
                ? List.of("rewrite execution returned no candidates; retrievedChunkIds is empty")
                : List.of();
        String finalQuery = selected == null ? query : selected.query();
        List<RetrievalDoc> docs = selected == null ? List.of() : selected.rerankedDocs();
        return response(
                request,
                config.domainId(),
                query,
                finalQuery,
                selectedMode,
                docs,
                result.latencyMs(),
                started,
                routeDecision,
                retrievalTrace(selectedMode, null, result.candidates()),
                warnings
        );
    }

    private List<MemoryCandidate> findMemoryCandidates(
            String query,
            ChatConfigDtos.ChatRuntimeConfigResponse config,
            RetrievalDtos.RetrievalRuntime retrievalRuntime,
            int memoryTopN
    ) {
        String embeddingLiteral = domainScopedRetrievalService.embeddingLiteral(query, retrievalRuntime);
        return domainScopedRetrievalService.findMemoryCandidates(
                query,
                embeddingLiteral,
                memoryTopN,
                normalizedPreset(config.gatingPreset()),
                config.domainId(),
                nonNullList(config.generationStrategies()),
                nonNullList(config.sourceGatingRunIds()),
                nonNullList(config.sourceGatingBatchIds()),
                retrievalRuntime
        );
    }

    private RagRetrievalEvalDtos.RagRetrievalEvalResponse executeAgentic(
            RagRetrievalEvalDtos.RagRetrievalEvalRequest request,
            String query,
            ChatConfigDtos.ChatRuntimeConfigResponse config,
            ChatReadinessDtos.ChatDomainReadinessResponse readiness,
            RagExecutionSettings executionSettings,
            long started,
            QueryRouteDecision routeDecision,
            List<MemoryCandidate> plannerMemoryCandidates
    ) {
        RetrievalDtos.RetrievalRuntime retrievalRuntime =
                domainScopedRetrievalService.retrievalRuntime(config);
        List<MemoryCandidate> memoryCandidates = plannerMemoryCandidates == null
                ? findMemoryCandidates(query, config, retrievalRuntime, executionSettings.memoryTopN())
                : plannerMemoryCandidates;
        AgenticEvalSettings settings = agenticSettings(config.metadata());
        AgenticExecutionDtos.AgenticExecutionResult result = agenticRetrievalService.execute(
                new AgenticExecutionDtos.AgenticExecutionRequest(
                        query,
                        null,
                        config,
                        readiness,
                        objectMapper.createObjectNode(),
                        memoryCandidates,
                        "agentic_multi_query",
                        normalizedRewriteProfile(config.rewriteQueryProfile()),
                        normalizedPreset(config.gatingPreset()),
                        executionSettings,
                        settings.maxSubqueries(),
                        settings.rrfK(),
                        executionSettings.retrievalTopK(),
                        RagPersistPolicy.NONE
                )
        );
        RagLlmCallCount llmCallCount = agenticLlmCallCount(result);
        return response(
                request,
                config.domainId(),
                query,
                query,
                "agentic_multi_query",
                result.mergedDocs(),
                result.totalLatencyMs(),
                started,
                routeDecision,
                agenticTrace(result, settings, executionSettings.retrievalTopK()),
                List.of(),
                llmCallCount
        );
    }

    private RagRetrievalEvalDtos.RagRetrievalEvalResponse response(
            RagRetrievalEvalDtos.RagRetrievalEvalRequest request,
            UUID domainId,
            String query,
            String finalQuery,
            String selectedMode,
            List<RetrievalDoc> docs,
            long executionLatencyMs,
            long started,
            QueryRouteDecision routeDecision,
            JsonNode retrievalTrace,
            List<String> warnings
    ) {
        return response(
                request,
                domainId,
                query,
                finalQuery,
                selectedMode,
                docs,
                executionLatencyMs,
                started,
                routeDecision,
                retrievalTrace,
                warnings,
                RagLlmCallCount.zero()
        );
    }

    private RagRetrievalEvalDtos.RagRetrievalEvalResponse response(
            RagRetrievalEvalDtos.RagRetrievalEvalRequest request,
            UUID domainId,
            String query,
            String finalQuery,
            String selectedMode,
            List<RetrievalDoc> docs,
            long executionLatencyMs,
            long started,
            QueryRouteDecision routeDecision,
            JsonNode retrievalTrace,
            List<String> warnings,
            RagLlmCallCount llmCallCount
    ) {
        List<RetrievalDoc> safeDocs = docs == null ? List.of() : docs;
        String safeFinalQuery = finalQueryOrOriginal(finalQuery, query);
        List<String> chunkIds = safeDocs.stream()
                .map(RetrievalDoc::chunkId)
                .toList();
        List<RagRetrievalEvalDtos.RagRetrievalEvalDoc> responseDocs = toDocs(safeDocs, request.includeScores());
        return new RagRetrievalEvalDtos.RagRetrievalEvalResponse(
                domainId,
                query,
                safeFinalQuery,
                request.forcedMode(),
                selectedMode,
                chunkIds,
                responseDocs,
                request.includeTrace()
                        ? new RagRetrievalEvalDtos.RagEvalTrace(routeDecisionName(routeDecision), retrievalTrace)
                        : null,
                llmCallCount,
                Math.max(executionLatencyMs, elapsedMs(started)),
                false,
                RagPersistPolicy.NONE,
                warningsWithMetadataWarning(request, warnings)
        );
    }

    private List<RagRetrievalEvalDtos.RagRetrievalEvalDoc> toDocs(
            List<RetrievalDoc> docs,
            boolean includeScores
    ) {
        return IntStream.range(0, docs.size())
                .mapToObj(index -> {
                    RetrievalDoc doc = docs.get(index);
                    return new RagRetrievalEvalDtos.RagRetrievalEvalDoc(
                            doc.chunkId(),
                            doc.documentId(),
                            null,
                            preview(doc.chunkText()),
                            includeScores ? doc.score() : null,
                            index + 1
                    );
                })
                .toList();
    }

    private JsonNode retrievalTrace(
            String selectedMode,
            RetrievalExecutionDtos.RetrievalMaterial rawRetrieval,
            List<RetrievalExecutionDtos.ExecutedRewriteCandidate> candidates
    ) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("selectedMode", selectedMode);
        if (rawRetrieval != null) {
            node.put("finalQuery", rawRetrieval.query());
            node.set("retrievedChunkIds", objectMapper.valueToTree(rawRetrieval.rerankedChunkIds()));
        }
        if (candidates != null) {
            ArrayNode array = node.putArray("rewriteCandidates");
            for (RetrievalExecutionDtos.ExecutedRewriteCandidate candidate : candidates) {
                ObjectNode item = array.addObject();
                item.put("index", candidate.index());
                item.put("label", candidate.label());
                item.put("query", candidate.query());
                item.put("confidence", candidate.confidence());
                item.set("retrievedChunkIds", objectMapper.valueToTree(candidate.rerankedChunkIds()));
            }
        }
        return node;
    }

    private List<String> warningsWithMetadataWarning(
            RagRetrievalEvalDtos.RagRetrievalEvalRequest request,
            List<String> warnings
    ) {
        if (!request.includeMetadata()) {
            return warnings == null ? List.of() : List.copyOf(warnings);
        }
        java.util.ArrayList<String> merged = new java.util.ArrayList<>(warnings == null ? List.of() : warnings);
        merged.add("includeMetadata is accepted but detailed document metadata is not exposed in Phase 7C");
        return List.copyOf(merged);
    }

    private RetrievalExecutionDtos.ExecutedRewriteCandidate selectCandidate(
            List<RetrievalExecutionDtos.ExecutedRewriteCandidate> candidates
    ) {
        return (candidates == null ? List.<RetrievalExecutionDtos.ExecutedRewriteCandidate>of() : candidates)
                .stream()
                .max(Comparator
                        .comparingDouble(RetrievalExecutionDtos.ExecutedRewriteCandidate::confidence)
                        .thenComparing(candidate -> -candidate.index()))
                .orElse(null);
    }

    private void validateRequest(RagRetrievalEvalDtos.RagRetrievalEvalRequest request) {
        if (request == null) {
            throw evalError(ERROR_REQUEST_REQUIRED, "request_required: request is required");
        }
        if (request.domainId() == null) {
            throw evalError(ERROR_DOMAIN_ID_REQUIRED, "domainId_required: domainId is required");
        }
        if (request.query() == null || request.query().isBlank()) {
            throw evalError(ERROR_QUERY_REQUIRED, "query_required: query must not be blank");
        }
        if (request.persistPolicy() != RagPersistPolicy.NONE) {
            throw evalError(
                    ERROR_UNSUPPORTED_PERSIST_POLICY,
                    "unsupported_persist_policy: retrieval eval supports only persistPolicy=NONE"
            );
        }
        if (Boolean.TRUE.equals(request.answerGeneration())) {
            throw evalError(
                    ERROR_UNSUPPORTED_ANSWER_GENERATION,
                    "unsupported_answer_generation: answerGeneration=true is unsupported for retrieval eval"
            );
        }
        if (!SUPPORTED_FORCED_MODES.contains(request.forcedMode())) {
            throw evalError(
                    ERROR_UNSUPPORTED_FORCED_MODE,
                    "unsupported_forced_mode: forcedMode is not supported for retrieval eval: " + request.forcedMode()
            );
        }
    }

    private RagRetrievalEvalException evalError(String code, String message) {
        return new RagRetrievalEvalException(code, message);
    }

    private JsonNode agenticTrace(
            AgenticExecutionDtos.AgenticExecutionResult result,
            AgenticEvalSettings settings,
            int finalTopK
    ) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("selectedMode", "agentic_multi_query");
        node.set("agenticPlan", objectMapper.valueToTree(result.plan()));
        node.set("subqueryTraces", objectMapper.valueToTree(result.traces()));
        node.set("retrievedChunkIds", objectMapper.valueToTree(result.mergedDocs().stream()
                .map(RetrievalDoc::chunkId)
                .toList()));
        node.put("mergeStrategy", "RRF");
        node.put("rrfK", settings.rrfK());
        node.put("finalTopK", finalTopK);
        node.set("metadata", result.metadata());
        return node;
    }

    private RagLlmCallCount agenticLlmCallCount(AgenticExecutionDtos.AgenticExecutionResult result) {
        int plannerCalls = result.plan() != null && !result.plan().fallbackApplied() ? 1 : 0;
        int rewriteCalls = result.traces() == null
                ? 0
                : (int) result.traces().stream()
                .filter(trace -> trace.rewriteCandidates() != null && !trace.rewriteCandidates().isEmpty())
                .count();
        return new RagLlmCallCount(rewriteCalls, plannerCalls, 0, rewriteCalls + plannerCalls);
    }

    private void validateRuntime(
            ChatConfigDtos.ChatRuntimeConfigResponse config,
            ChatReadinessDtos.ChatDomainReadinessResponse readiness
    ) {
        if (config == null || config.domainId() == null) {
            throw new IllegalArgumentException("active chat runtime config is required for retrieval eval");
        }
        if (readiness == null || !readiness.activeConfigPresent()) {
            throw new IllegalArgumentException("active chat_runtime_config is missing for domain: " + config.displayName());
        }
        if (!config.enabled()) {
            throw new IllegalArgumentException("chat is disabled for domain: " + config.displayName());
        }
    }

    private QueryRouteContext routeContext(
            String rawQuery,
            ChatConfigDtos.ChatRuntimeConfigResponse config,
            ChatReadinessDtos.ChatDomainReadinessResponse readiness,
            String mode,
            String rewriteQueryProfile,
            boolean anchorInjectionEnabled,
            boolean memoryCandidatesKnown,
            boolean memoryCandidatesAvailable,
            Double rawRetrievalConfidence
    ) {
        return new QueryRouteContext(
                rawQuery,
                config.domainId(),
                config,
                readiness,
                mode,
                rewriteQueryProfile,
                anchorInjectionEnabled,
                rawQuery.length(),
                queryTokenCount(rawQuery),
                containsKorean(rawQuery),
                containsEnglish(rawQuery),
                false,
                memoryCandidatesKnown,
                memoryCandidatesAvailable,
                rawRetrievalConfidence,
                false
        );
    }

    private ObjectNode domainContext(ChatConfigDtos.ChatRuntimeConfigResponse config) {
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

    private String normalizedRewriteProfile(String rewriteProfile) {
        return normalizeText(rewriteProfile, "compact_anchor");
    }

    private String normalizedPreset(String preset) {
        return normalizeText(preset, "full_gating");
    }

    private int queryTokenCount(String query) {
        Matcher matcher = TOKEN_PATTERN.matcher(query == null ? "" : query);
        int count = 0;
        while (matcher.find()) {
            count++;
        }
        return count;
    }

    private boolean containsKorean(String query) {
        return query != null && query.codePoints().anyMatch(codePoint -> codePoint >= 0xAC00 && codePoint <= 0xD7A3);
    }

    private boolean containsEnglish(String query) {
        return query != null && query.codePoints().anyMatch(codePoint ->
                (codePoint >= 'A' && codePoint <= 'Z') || (codePoint >= 'a' && codePoint <= 'z'));
    }

    private String routeDecisionName(QueryRouteDecision routeDecision) {
        return routeDecision == null ? null : routeDecision.strategy().name();
    }

    private String preview(String text) {
        if (text == null) {
            return "";
        }
        String normalized = text.replaceAll("\\s+", " ").trim();
        if (normalized.length() <= CONTENT_PREVIEW_MAX_LENGTH) {
            return normalized;
        }
        return normalized.substring(0, CONTENT_PREVIEW_MAX_LENGTH - 3) + "...";
    }

    private String finalQueryOrOriginal(String finalQuery, String originalQuery) {
        if (finalQuery == null || finalQuery.isBlank()) {
            return originalQuery;
        }
        return finalQuery;
    }

    private long elapsedMs(long startedNano) {
        return (System.nanoTime() - startedNano) / 1_000_000L;
    }

}
