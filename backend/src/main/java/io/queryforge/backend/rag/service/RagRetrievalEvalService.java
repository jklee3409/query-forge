package io.queryforge.backend.rag.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.queryforge.backend.rag.model.ChatRuntimeDtos;
import io.queryforge.backend.rag.model.QueryRouteContext;
import io.queryforge.backend.rag.model.QueryRouteDecision;
import io.queryforge.backend.rag.model.QueryStrategy;
import io.queryforge.backend.rag.model.RagLlmCallCount;
import io.queryforge.backend.rag.model.RagPersistPolicy;
import io.queryforge.backend.rag.model.RagRetrievalEvalDtos;
import io.queryforge.backend.rag.repository.RagRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.IntStream;

@Service
@RequiredArgsConstructor
public class RagRetrievalEvalService {

    private static final int DEFAULT_RETRIEVAL_TOP_K = 10;
    private static final int DEFAULT_RERANK_TOP_N = 5;
    private static final int DEFAULT_MEMORY_TOP_N = 5;
    private static final int DEFAULT_CANDIDATE_COUNT = 2;
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
    private final DomainScopedRetrievalService domainScopedRetrievalService;
    private final QueryStrategyRouter queryStrategyRouter;
    private final RagRetrievalExecutionService ragRetrievalExecutionService;
    private final AgenticRetrievalService agenticRetrievalService;
    private final ObjectMapper objectMapper;

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
        ChatRuntimeDtos.ChatRuntimeConfigResponse config = chatRuntimeConfigService.getConfig(domainId);
        ChatRuntimeDtos.ChatDomainReadinessResponse readiness = chatRuntimeConfigService.getReadiness(domainId);
        RuntimeEvalContext runtimeContext = runtimeContext(request, config, readiness);
        config = runtimeContext.config();
        readiness = runtimeContext.readiness();
        validateRuntime(config, readiness);

        DomainScopedRetrievalService.RetrievalRuntime retrievalRuntime =
                domainScopedRetrievalService.retrievalRuntime(config);
        int retrievalTopK = normalizedPositive(request.topK(), normalizedPositive(config.retrievalTopK(), DEFAULT_RETRIEVAL_TOP_K));
        int rerankTopN = request.topK() == null
                ? normalizedPositive(config.rerankTopN(), DEFAULT_RERANK_TOP_N)
                : retrievalTopK;

        return switch (request.forcedMode()) {
            case "raw_only" -> executeRawOnly(
                    request,
                    query,
                    config,
                    retrievalRuntime,
                    retrievalTopK,
                    rerankTopN,
                    started,
                    null
            );
            case "selective_rewrite" -> executeRewrite(
                    request,
                    query,
                    config,
                    retrievalRuntime,
                    retrievalTopK,
                    rerankTopN,
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
                    retrievalTopK,
                    rerankTopN,
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
                    retrievalTopK,
                    rerankTopN,
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
                    retrievalTopK,
                    rerankTopN,
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
            ChatRuntimeDtos.ChatRuntimeConfigResponse config,
            ChatRuntimeDtos.ChatDomainReadinessResponse readiness,
            DomainScopedRetrievalService.RetrievalRuntime retrievalRuntime,
            int retrievalTopK,
            int rerankTopN,
            long started
    ) {
        List<RagRepository.MemoryCandidate> memoryCandidates = findMemoryCandidates(
                query,
                config,
                retrievalRuntime
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
                    retrievalTopK,
                    rerankTopN,
                    started,
                    routed
            );
            case SYNTHETIC_SELECTIVE_REWRITE -> executeRewrite(
                    request,
                    query,
                    config,
                    retrievalRuntime,
                    retrievalTopK,
                    rerankTopN,
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
                    retrievalTopK,
                    rerankTopN,
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
                    retrievalTopK,
                    rerankTopN,
                    started,
                    routed,
                    memoryCandidates
            );
        };
    }

    private RagRetrievalEvalDtos.RagRetrievalEvalResponse executeRawOnly(
            RagRetrievalEvalDtos.RagRetrievalEvalRequest request,
            String query,
            ChatRuntimeDtos.ChatRuntimeConfigResponse config,
            DomainScopedRetrievalService.RetrievalRuntime retrievalRuntime,
            int retrievalTopK,
            int rerankTopN,
            long started,
            QueryRouteDecision routeDecision
    ) {
        String embeddingLiteral = domainScopedRetrievalService.embeddingLiteral(query, retrievalRuntime);
        RagRetrievalExecutionService.RawOnlyExecutionResult result =
                ragRetrievalExecutionService.executeRawOnly(new RagRetrievalExecutionService.RawOnlyExecutionRequest(
                        query,
                        embeddingLiteral,
                        retrievalTopK,
                        rerankTopN,
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
            ChatRuntimeDtos.ChatRuntimeConfigResponse config,
            DomainScopedRetrievalService.RetrievalRuntime retrievalRuntime,
            int retrievalTopK,
            int rerankTopN,
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
                retrievalTopK,
                rerankTopN,
                anchorAware,
                selectedMode,
                started,
                routeDecision,
                findMemoryCandidates(query, config, retrievalRuntime)
        );
    }

    private RagRetrievalEvalDtos.RagRetrievalEvalResponse executeRewrite(
            RagRetrievalEvalDtos.RagRetrievalEvalRequest request,
            String query,
            ChatRuntimeDtos.ChatRuntimeConfigResponse config,
            DomainScopedRetrievalService.RetrievalRuntime retrievalRuntime,
            int retrievalTopK,
            int rerankTopN,
            boolean anchorAware,
            String selectedMode,
            long started,
            QueryRouteDecision routeDecision,
            List<RagRepository.MemoryCandidate> memoryCandidates
    ) {
        double rawDenseScore = memoryCandidates.isEmpty() ? 0.0d : memoryCandidates.getFirst().similarity();
        int candidateCount = Math.min(
                normalizedPositive(config.rewriteCandidateCount(), DEFAULT_CANDIDATE_COUNT),
                DEFAULT_CANDIDATE_COUNT
        );
        String rewriteProfile = normalizedRewriteProfile(config.rewriteQueryProfile());
        RagRetrievalExecutionService.NonAgenticExecutionResult result = anchorAware
                ? ragRetrievalExecutionService.executeAnchorAwareRewrite(
                new RagRetrievalExecutionService.AnchorAwareRewriteExecutionRequest(
                        query,
                        objectMapper.createObjectNode(),
                        memoryCandidates,
                        candidateCount,
                        rewriteProfile,
                        domainContext(config),
                        retrievalTopK,
                        rerankTopN,
                        config.domainId(),
                        retrievalRuntime,
                        rawDenseScore
                )
        )
                : ragRetrievalExecutionService.executeSelectiveRewrite(
                new RagRetrievalExecutionService.SelectiveRewriteExecutionRequest(
                        query,
                        objectMapper.createObjectNode(),
                        memoryCandidates,
                        candidateCount,
                        rewriteProfile,
                        domainContext(config),
                        retrievalTopK,
                        rerankTopN,
                        config.domainId(),
                        retrievalRuntime,
                        rawDenseScore
                )
        );
        RagRetrievalExecutionService.ExecutedRewriteCandidate selected = selectCandidate(result.candidateExecutions());
        List<String> warnings = selected == null
                ? List.of("rewrite execution returned no candidates; retrievedChunkIds is empty")
                : List.of();
        String finalQuery = selected == null ? query : selected.query();
        List<RagRepository.RetrievalDoc> docs = selected == null ? List.of() : selected.rerankedDocs();
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
                retrievalTrace(selectedMode, null, result.candidateExecutions()),
                warnings
        );
    }

    private List<RagRepository.MemoryCandidate> findMemoryCandidates(
            String query,
            ChatRuntimeDtos.ChatRuntimeConfigResponse config,
            DomainScopedRetrievalService.RetrievalRuntime retrievalRuntime
    ) {
        String embeddingLiteral = domainScopedRetrievalService.embeddingLiteral(query, retrievalRuntime);
        return domainScopedRetrievalService.findMemoryCandidates(
                query,
                embeddingLiteral,
                normalizedPositive(config.memoryTopN(), DEFAULT_MEMORY_TOP_N),
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
            ChatRuntimeDtos.ChatRuntimeConfigResponse config,
            ChatRuntimeDtos.ChatDomainReadinessResponse readiness,
            int retrievalTopK,
            int rerankTopN,
            long started,
            QueryRouteDecision routeDecision,
            List<RagRepository.MemoryCandidate> plannerMemoryCandidates
    ) {
        DomainScopedRetrievalService.RetrievalRuntime retrievalRuntime =
                domainScopedRetrievalService.retrievalRuntime(config);
        List<RagRepository.MemoryCandidate> memoryCandidates = plannerMemoryCandidates == null
                ? findMemoryCandidates(query, config, retrievalRuntime)
                : plannerMemoryCandidates;
        AgenticEvalSettings settings = agenticEvalSettings(config.metadata());
        AgenticRetrievalService.AgenticExecutionResult result = agenticRetrievalService.execute(
                new AgenticRetrievalService.AgenticExecutionRequest(
                        query,
                        null,
                        config,
                        readiness,
                        objectMapper.createObjectNode(),
                        memoryCandidates,
                        "agentic_multi_query",
                        normalizedRewriteProfile(config.rewriteQueryProfile()),
                        normalizedPreset(config.gatingPreset()),
                        retrievalTopK,
                        rerankTopN,
                        normalizedPositive(config.memoryTopN(), DEFAULT_MEMORY_TOP_N),
                        Math.min(
                                normalizedPositive(config.rewriteCandidateCount(), DEFAULT_CANDIDATE_COUNT),
                                DEFAULT_CANDIDATE_COUNT
                        ),
                        config.rewriteThreshold(),
                        settings.maxSubqueries(),
                        settings.rrfK(),
                        retrievalTopK,
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
                agenticTrace(result, settings, retrievalTopK),
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
            List<RagRepository.RetrievalDoc> docs,
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
            List<RagRepository.RetrievalDoc> docs,
            long executionLatencyMs,
            long started,
            QueryRouteDecision routeDecision,
            JsonNode retrievalTrace,
            List<String> warnings,
            RagLlmCallCount llmCallCount
    ) {
        List<RagRepository.RetrievalDoc> safeDocs = docs == null ? List.of() : docs;
        String safeFinalQuery = finalQueryOrOriginal(finalQuery, query);
        List<String> chunkIds = safeDocs.stream()
                .map(RagRepository.RetrievalDoc::chunkId)
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
            List<RagRepository.RetrievalDoc> docs,
            boolean includeScores
    ) {
        return IntStream.range(0, docs.size())
                .mapToObj(index -> {
                    RagRepository.RetrievalDoc doc = docs.get(index);
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
            RagRetrievalExecutionService.RetrievalMaterial rawRetrieval,
            List<RagRetrievalExecutionService.ExecutedRewriteCandidate> candidates
    ) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("selectedMode", selectedMode);
        if (rawRetrieval != null) {
            node.put("finalQuery", rawRetrieval.query());
            node.set("retrievedChunkIds", objectMapper.valueToTree(rawRetrieval.rerankedChunkIds()));
        }
        if (candidates != null) {
            ArrayNode array = node.putArray("rewriteCandidates");
            for (RagRetrievalExecutionService.ExecutedRewriteCandidate candidate : candidates) {
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

    private RagRetrievalExecutionService.ExecutedRewriteCandidate selectCandidate(
            List<RagRetrievalExecutionService.ExecutedRewriteCandidate> candidates
    ) {
        return (candidates == null ? List.<RagRetrievalExecutionService.ExecutedRewriteCandidate>of() : candidates)
                .stream()
                .max(Comparator
                        .comparingDouble(RagRetrievalExecutionService.ExecutedRewriteCandidate::confidence)
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

    private RuntimeEvalContext runtimeContext(
            RagRetrievalEvalDtos.RagRetrievalEvalRequest request,
            ChatRuntimeDtos.ChatRuntimeConfigResponse baseConfig,
            ChatRuntimeDtos.ChatDomainReadinessResponse baseReadiness
    ) {
        JsonNode runtime = request.runtimeConfig();
        if (runtime == null || runtime.isNull() || runtime.isMissingNode() || !runtime.isObject()) {
            return new RuntimeEvalContext(baseConfig, baseReadiness);
        }
        ObjectNode metadata = objectMapper.createObjectNode();
        if (baseConfig.metadata() != null && baseConfig.metadata().isObject()) {
            metadata.setAll((ObjectNode) baseConfig.metadata());
        }
        JsonNode runtimeMetadata = firstObject(runtime, "metadata", "metadata_json");
        if (runtimeMetadata != null && runtimeMetadata.isObject()) {
            metadata.setAll((ObjectNode) runtimeMetadata);
        }
        boolean routerEnabled = boolValue(
                runtime,
                boolValue(metadata, baseConfig.routerEnabled(), "routerEnabled", "router_enabled", "queryRouterEnabled"),
                "routerEnabled",
                "router_enabled",
                "query_router_enabled"
        );
        boolean agenticEnabled = boolValue(
                runtime,
                boolValue(metadata, false, "agenticMultiQueryEnabled", "agentic_multi_query_enabled", "queryRouterAgenticEnabled"),
                "agenticMultiQueryEnabled",
                "agentic_multi_query_enabled",
                "query_router_agentic_enabled"
        );
        metadata.put("routerEnabled", routerEnabled);
        metadata.put("agenticMultiQueryEnabled", agenticEnabled);

        JsonNode retrieverConfig = firstObject(runtime, "retrieverConfig", "retriever_config");
        JsonNode fusionWeights = firstObject(retrieverConfig, "retrieverFusionWeights", "retriever_fusion_weights");
        if (fusionWeights == null) {
            fusionWeights = firstObject(runtime, "retrieverFusionWeights", "retriever_fusion_weights");
        }
        String forcedMode = textValue(runtime, null, "forcedRetrievalMode", "forced_retrieval_mode", "java_forced_mode");
        String mode = textValue(runtime, null, "mode", "chat_mode", "runtime_mode");
        if (mode == null || mode.isBlank()) {
            mode = routerEnabled ? "strategy_router" : textValue(runtime, forcedMode, "retrieval_mode", "retrievalMode");
        }
        if (mode == null || mode.isBlank()) {
            mode = baseConfig.mode();
        }

        List<String> generationStrategies = stringList(
                runtime,
                baseConfig.generationStrategies(),
                "generationStrategies",
                "generation_strategies",
                "memory_generation_strategies",
                "source_generation_strategies"
        );
        List<UUID> sourceGatingBatchIds = uuidList(
                runtime,
                baseConfig.sourceGatingBatchIds(),
                "sourceGatingBatchIds",
                "source_gating_batch_ids"
        );
        UUID sourceGatingBatchId = uuidValue(
                runtime,
                firstUuid(baseConfig.sourceGatingBatchId(), sourceGatingBatchIds),
                "sourceGatingBatchId",
                "source_gating_batch_id",
                "snapshot_id"
        );
        if (sourceGatingBatchIds.isEmpty() && sourceGatingBatchId != null) {
            sourceGatingBatchIds = List.of(sourceGatingBatchId);
        }
        List<UUID> sourceGatingRunIds = uuidList(
                runtime,
                baseConfig.sourceGatingRunIds(),
                "sourceGatingRunIds",
                "source_gating_run_ids"
        );
        UUID sourceGatingRunId = uuidValue(
                runtime,
                firstUuid(baseConfig.sourceGatingRunId(), sourceGatingRunIds),
                "sourceGatingRunId",
                "source_gating_run_id"
        );
        if (sourceGatingRunIds.isEmpty() && sourceGatingRunId != null) {
            sourceGatingRunIds = List.of(sourceGatingRunId);
        }
        ChatRuntimeDtos.ChatRuntimeConfigResponse config = new ChatRuntimeDtos.ChatRuntimeConfigResponse(
                baseConfig.domainId(),
                baseConfig.domainKey(),
                baseConfig.displayName(),
                baseConfig.sourceLanguage(),
                baseConfig.enabled(),
                mode,
                generationStrategies,
                textValue(runtime, baseConfig.gatingPreset(), "gatingPreset", "gating_preset"),
                sourceGatingBatchId,
                sourceGatingRunId,
                sourceGatingBatchIds,
                sourceGatingRunIds,
                textValue(runtime, baseConfig.rewriteQueryProfile(), "rewriteQueryProfile", "rewrite_query_profile"),
                boolValue(runtime, baseConfig.rewriteAnchorInjectionEnabled(), "rewriteAnchorInjectionEnabled", "rewrite_anchor_injection_enabled"),
                boolValue(runtime, baseConfig.useSessionContext(), "useSessionContext", "use_session_context"),
                textValue(runtime, baseConfig.retrievalBackend(), "retrievalBackend", "retrieval_backend"),
                textValue(
                        retrieverConfig,
                        textValue(runtime, baseConfig.denseEmbeddingModel(), "denseEmbeddingModel", "dense_embedding_model", "chunk_embedding_model"),
                        "denseEmbeddingModel",
                        "dense_embedding_model"
                ),
                textValue(
                        retrieverConfig,
                        textValue(runtime, baseConfig.retrieverMode(), "retrieverMode", "retriever_mode"),
                        "retrieverMode",
                        "retriever_mode"
                ),
                intValue(retrieverConfig, intValue(runtime, baseConfig.retrieverCandidatePoolK(), "retrieverCandidatePoolK", "retriever_candidate_pool_k"), "candidatePoolK", "candidate_pool_k", "retrieverCandidatePoolK", "retriever_candidate_pool_k"),
                doubleValue(fusionWeights, baseConfig.retrieverDenseWeight(), "dense", "denseWeight", "dense_weight"),
                doubleValue(fusionWeights, baseConfig.retrieverBm25Weight(), "bm25", "bm25Weight", "bm25_weight"),
                doubleValue(fusionWeights, baseConfig.retrieverTechnicalWeight(), "technical", "technicalWeight", "technical_weight"),
                intValue(runtime, baseConfig.retrievalTopK(), "retrievalTopK", "retrieval_top_k", "topK", "top_k"),
                intValue(runtime, baseConfig.rerankTopN(), "rerankTopN", "rerank_top_n"),
                intValue(runtime, baseConfig.memoryTopN(), "memoryTopN", "memory_top_n"),
                intValue(runtime, baseConfig.rewriteCandidateCount(), "rewriteCandidateCount", "rewrite_candidate_count"),
                doubleValue(runtime, baseConfig.rewriteThreshold(), "rewriteThreshold", "rewrite_threshold", "threshold"),
                textValue(runtime, baseConfig.rewriteFailurePolicy(), "rewriteFailurePolicy", "rewrite_failure_policy"),
                routerEnabled,
                metadata,
                baseConfig.updatedAt(),
                boolValue(runtime, baseConfig.readyForRewrite(), "readyForRewrite", "ready_for_rewrite"),
                baseConfig.readinessMessage()
        );
        ChatRuntimeDtos.ChatDomainReadinessResponse readiness = readinessForRuntimeOverride(
                runtime,
                baseReadiness,
                config
        );
        return new RuntimeEvalContext(config, readiness);
    }

    private ChatRuntimeDtos.ChatDomainReadinessResponse readinessForRuntimeOverride(
            JsonNode runtime,
            ChatRuntimeDtos.ChatDomainReadinessResponse base,
            ChatRuntimeDtos.ChatRuntimeConfigResponse config
    ) {
        boolean rewriteBacked = !"raw_only".equals(normalizedMode(config.mode()));
        boolean hasSnapshot = !nonNullList(config.sourceGatingRunIds()).isEmpty()
                || !nonNullList(config.sourceGatingBatchIds()).isEmpty();
        boolean ready = boolValue(runtime, base.readyForRewrite() || !rewriteBacked || hasSnapshot, "readyForRewrite", "ready_for_rewrite");
        return new ChatRuntimeDtos.ChatDomainReadinessResponse(
                config.domainId(),
                config.domainKey(),
                config.displayName(),
                config.sourceLanguage(),
                base.activeConfigPresent(),
                config.enabled(),
                config.mode(),
                rewriteBacked,
                config.generationStrategies(),
                config.gatingPreset(),
                base.snapshot(),
                base.chunkEmbeddings(),
                base.memoryCount(),
                base.acceptedGatedQueryCount(),
                base.promptBinding(),
                base.retrieval(),
                ready,
                ready ? List.of() : nonNullList(base.blockingReasons()),
                base.checkedAt()
        );
    }

    private JsonNode firstObject(JsonNode source, String... keys) {
        if (source == null || source.isNull() || source.isMissingNode()) {
            return null;
        }
        for (String key : keys) {
            JsonNode value = source.get(key);
            if (value != null && value.isObject()) {
                return value;
            }
        }
        return null;
    }

    private String textValue(JsonNode source, String fallback, String... keys) {
        if (source == null || source.isNull() || source.isMissingNode()) {
            return fallback;
        }
        for (String key : keys) {
            JsonNode value = source.get(key);
            if (value != null && !value.isNull() && !value.asText("").isBlank()) {
                return value.asText().trim();
            }
        }
        return fallback;
    }

    private boolean boolValue(JsonNode source, boolean fallback, String... keys) {
        if (source == null || source.isNull() || source.isMissingNode()) {
            return fallback;
        }
        for (String key : keys) {
            JsonNode value = source.get(key);
            if (value != null && !value.isNull()) {
                return value.asBoolean(fallback);
            }
        }
        return fallback;
    }

    private int intValue(JsonNode source, int fallback, String... keys) {
        if (source == null || source.isNull() || source.isMissingNode()) {
            return fallback;
        }
        for (String key : keys) {
            JsonNode value = source.get(key);
            if (value != null && !value.isNull() && value.canConvertToInt()) {
                return value.asInt(fallback);
            }
        }
        return fallback;
    }

    private double doubleValue(JsonNode source, double fallback, String... keys) {
        if (source == null || source.isNull() || source.isMissingNode()) {
            return fallback;
        }
        for (String key : keys) {
            JsonNode value = source.get(key);
            if (value != null && !value.isNull() && value.isNumber()) {
                return value.asDouble(fallback);
            }
        }
        return fallback;
    }

    private UUID uuidValue(JsonNode source, UUID fallback, String... keys) {
        String text = textValue(source, null, keys);
        if (text == null) {
            return fallback;
        }
        try {
            return UUID.fromString(text);
        } catch (IllegalArgumentException ignored) {
            return fallback;
        }
    }

    private UUID firstUuid(UUID fallback, List<UUID> values) {
        return fallback != null ? fallback : (values == null || values.isEmpty() ? null : values.getFirst());
    }

    private List<UUID> uuidList(JsonNode source, List<UUID> fallback, String... keys) {
        if (source == null || source.isNull() || source.isMissingNode()) {
            return nonNullList(fallback);
        }
        for (String key : keys) {
            JsonNode value = source.get(key);
            if (value == null || !value.isArray()) {
                continue;
            }
            java.util.ArrayList<UUID> result = new java.util.ArrayList<>();
            for (JsonNode item : value) {
                try {
                    result.add(UUID.fromString(item.asText()));
                } catch (IllegalArgumentException ignored) {
                    // Ignore invalid override entries and keep the rest of the request usable.
                }
            }
            return List.copyOf(result);
        }
        return nonNullList(fallback);
    }

    private List<String> stringList(JsonNode source, List<String> fallback, String... keys) {
        if (source == null || source.isNull() || source.isMissingNode()) {
            return nonNullList(fallback);
        }
        for (String key : keys) {
            JsonNode value = source.get(key);
            if (value == null || !value.isArray()) {
                continue;
            }
            java.util.ArrayList<String> result = new java.util.ArrayList<>();
            for (JsonNode item : value) {
                String text = item.asText("");
                if (!text.isBlank()) {
                    result.add(text.trim().toUpperCase(Locale.ROOT));
                }
            }
            return List.copyOf(result);
        }
        return nonNullList(fallback);
    }

    private AgenticEvalSettings agenticEvalSettings(JsonNode metadata) {
        int maxSubqueries = intValue(metadata, 4, "agenticMaxSubqueries", "agentic_max_subqueries", "strategyRouterAgenticMaxSubqueries", "strategy_router_agentic_max_subqueries");
        int rrfK = intValue(metadata, 60, "agenticRrfK", "agentic_rrf_k", "rrfK", "rrf_k");
        return new AgenticEvalSettings(Math.max(1, Math.min(maxSubqueries, 4)), Math.max(1, rrfK));
    }

    private JsonNode agenticTrace(
            AgenticRetrievalService.AgenticExecutionResult result,
            AgenticEvalSettings settings,
            int finalTopK
    ) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("selectedMode", "agentic_multi_query");
        node.set("agenticPlan", objectMapper.valueToTree(result.plan()));
        node.set("subqueryTraces", objectMapper.valueToTree(result.traces()));
        node.set("retrievedChunkIds", objectMapper.valueToTree(result.mergedDocs().stream()
                .map(RagRepository.RetrievalDoc::chunkId)
                .toList()));
        node.put("mergeStrategy", "RRF");
        node.put("rrfK", settings.rrfK());
        node.put("finalTopK", finalTopK);
        node.set("metadata", result.metadata());
        return node;
    }

    private RagLlmCallCount agenticLlmCallCount(AgenticRetrievalService.AgenticExecutionResult result) {
        int plannerCalls = result.plan() != null && !result.plan().fallbackApplied() ? 1 : 0;
        int rewriteCalls = result.traces() == null
                ? 0
                : (int) result.traces().stream()
                .filter(trace -> trace.rewriteCandidates() != null && !trace.rewriteCandidates().isEmpty())
                .count();
        return new RagLlmCallCount(rewriteCalls, plannerCalls, 0, rewriteCalls + plannerCalls);
    }

    private void validateRuntime(
            ChatRuntimeDtos.ChatRuntimeConfigResponse config,
            ChatRuntimeDtos.ChatDomainReadinessResponse readiness
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
            ChatRuntimeDtos.ChatRuntimeConfigResponse config,
            ChatRuntimeDtos.ChatDomainReadinessResponse readiness,
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

    private ObjectNode domainContext(ChatRuntimeDtos.ChatRuntimeConfigResponse config) {
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

    private String normalizedMode(String mode) {
        return normalizeText(mode, "selective_rewrite");
    }

    private String normalizedRewriteProfile(String rewriteProfile) {
        return normalizeText(rewriteProfile, "compact_anchor");
    }

    private String normalizedPreset(String preset) {
        return normalizeText(preset, "full_gating");
    }

    private <T> List<T> nonNullList(List<T> values) {
        return values == null ? List.of() : values;
    }

    private String normalizeText(String value, String fallback) {
        String normalized = value == null || value.isBlank() ? fallback : value;
        return normalized == null ? "" : normalized.trim().toLowerCase(Locale.ROOT).replace("-", "_");
    }

    private int normalizedPositive(Integer value, int fallback) {
        int normalized = value == null ? fallback : value;
        return Math.max(1, normalized);
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

    private record RuntimeEvalContext(
            ChatRuntimeDtos.ChatRuntimeConfigResponse config,
            ChatRuntimeDtos.ChatDomainReadinessResponse readiness
    ) {
    }

    private record AgenticEvalSettings(
            int maxSubqueries,
            int rrfK
    ) {
    }
}
