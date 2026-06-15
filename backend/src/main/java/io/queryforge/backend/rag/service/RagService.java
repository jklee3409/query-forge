package io.queryforge.backend.rag.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.queryforge.backend.rag.model.ChatRuntimeDtos;
import io.queryforge.backend.rag.model.RagDtos;
import io.queryforge.backend.rag.repository.RagRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RagService {

    private static final Pattern SEARCH_TOKEN_PATTERN = Pattern.compile("[@A-Za-z0-9_./:$-]{2,}|\\p{InHangulSyllables}{2,}");
    private static final Pattern TECHNICAL_TOKEN_PATTERN = Pattern.compile("[@A-Za-z_][A-Za-z0-9_./:$-]{1,}");

    private final RagRepository repository;
    private final HashEmbeddingService embeddingService;
    private final DenseEmbeddingService denseEmbeddingService;
    private final CohereRerankService cohereRerankService;
    private final RewriteCandidateService rewriteCandidateService;
    private final ChatRuntimeConfigService chatRuntimeConfigService;
    private final ObjectMapper objectMapper;

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public RagDtos.AskResponse ask(RagDtos.AskRequest request) {
        String rawQuery = normalizedText(request.query());
        if (rawQuery.isBlank()) {
            throw new IllegalArgumentException("query must not be blank");
        }
        if (request.domainId() == null) {
            throw new IllegalArgumentException("domainId is required for chat");
        }
        ChatRuntimeDtos.ChatRuntimeConfigResponse config = chatRuntimeConfigService.getConfig(request.domainId());
        if (!config.enabled()) {
            throw new IllegalArgumentException("chat is disabled for domain: " + config.displayName());
        }
        String mode = normalizedMode(config.mode());
        if (!"raw_only".equals(mode) && !config.readyForRewrite()) {
            throw new IllegalArgumentException(config.readinessMessage());
        }
        int retrievalTopK = normalizedPositive(config.retrievalTopK(), 10);
        int rerankTopN = normalizedPositive(config.rerankTopN(), 5);
        int memoryTopN = normalizedPositive(config.memoryTopN(), 5);
        int candidateCount = Math.min(normalizedPositive(config.rewriteCandidateCount(), 2), 2);
        double threshold = config.rewriteThreshold();
        String gatingPreset = normalizedPreset(config.gatingPreset());
        boolean useSessionContext = config.useSessionContext() || "selective_rewrite_with_session".equals(mode);
        String rewriteQueryProfile = normalizedRewriteProfile(config.rewriteQueryProfile());
        RetrievalRuntime retrievalRuntime = retrievalRuntime(config);
        ObjectNode runtimeMetadata = runtimeMetadata(config);

        Instant started = Instant.now();
        long stageStart = System.nanoTime();
        UUID onlineQueryId = repository.createOnlineQuery(
                config.domainId(),
                request.sessionId(),
                rawQuery,
                useSessionContext ? nullSafeJson(request.sessionContext()) : objectMapper.createObjectNode(),
                mode,
                threshold,
                runtimeMetadata
        );
        long createQueryLatency = elapsedMs(stageStart);

        stageStart = System.nanoTime();
        String rawEmbeddingLiteral = embeddingLiteral(rawQuery, retrievalRuntime);
        String memoryPreset = switch (mode) {
            case "memory_only_ungated" -> "ungated";
            case "memory_only_gated" -> gatingPreset;
            default -> gatingPreset;
        };
        List<RagRepository.MemoryCandidate> memoryCandidates = findMemoryCandidates(
                rawQuery,
                rawEmbeddingLiteral,
                memoryTopN,
                memoryPreset,
                config.domainId(),
                config.generationStrategies(),
                config.sourceGatingRunId(),
                config.sourceGatingBatchId(),
                retrievalRuntime
        );
        JsonNode memoryTopNJson = objectMapper.valueToTree(memoryCandidates);
        long memoryLatency = elapsedMs(stageStart);

        stageStart = System.nanoTime();
        List<RagRepository.RetrievalDoc> rawRetrievedLocal = retrieveChunks(
                rawQuery,
                rawEmbeddingLiteral,
                retrievalTopK,
                config.domainId(),
                retrievalRuntime
        );
        List<RagRepository.RetrievalDoc> rawRetrieved = cohereRerankService.rerank(rawQuery, rawRetrievedLocal, rerankTopN);
        double rawDense = memoryCandidates.isEmpty() ? 0.0 : memoryCandidates.getFirst().similarity();
        double rawConfidence = confidence(rawRetrieved, rawDense);
        repository.insertRetrievalResults(
                onlineQueryId,
                null,
                "raw",
                rawRetrievedLocal,
                mode,
                retrievalRuntime.retrieverName(),
                retrievalMetadata(retrievalRuntime)
        );
        long rawRetrievalLatency = elapsedMs(stageStart);

        stageStart = System.nanoTime();
        List<RewriteCandidateService.CandidateTemplate> generatedCandidates = rewriteCandidateService.buildCandidates(
                rawQuery,
                useSessionContext ? nullSafeJson(request.sessionContext()) : objectMapper.createObjectNode(),
                memoryCandidates,
                candidateCount,
                rewriteQueryProfile,
                config.rewriteAnchorInjectionEnabled(),
                domainContext(config)
        );
        List<GeneratedCandidate> scoredCandidates = new ArrayList<>();
        for (int index = 0; index < generatedCandidates.size(); index++) {
            RewriteCandidateService.CandidateTemplate generated = generatedCandidates.get(index);
            String embeddingLiteral = embeddingLiteral(generated.query(), retrievalRuntime);
            List<RagRepository.RetrievalDoc> candidateRetrievedLocal = retrieveChunks(
                    generated.query(),
                    embeddingLiteral,
                    retrievalTopK,
                    config.domainId(),
                    retrievalRuntime
            );
            List<RagRepository.RetrievalDoc> candidateRetrieved = cohereRerankService.rerank(
                    generated.query(),
                    candidateRetrievedLocal,
                    rerankTopN
            );
            double confidence = confidence(candidateRetrieved, rawDense);
            UUID candidateId = repository.createRewriteCandidate(
                    onlineQueryId,
                    index + 1,
                    generated.label(),
                    generated.query(),
                    memorySourceIds(memoryCandidates),
                    objectMapper.valueToTree(candidateRetrieved),
                    confidence,
                    scoreBreakdown(candidateRetrieved, memoryCandidates)
            );
            repository.insertRetrievalResults(
                    onlineQueryId,
                    candidateId,
                    "rewrite_candidate",
                    candidateRetrievedLocal,
                    mode,
                    retrievalRuntime.retrieverName(),
                    retrievalMetadata(retrievalRuntime)
            );
            scoredCandidates.add(new GeneratedCandidate(generated.label(), generated.query(), candidateRetrieved, confidence, candidateId));
        }
        long candidateLatency = elapsedMs(stageStart);

        stageStart = System.nanoTime();
        Decision decision = decide(
                mode,
                rawQuery,
                rawConfidence,
                rawRetrieved,
                memoryCandidates,
                scoredCandidates,
                threshold,
                config.domainId(),
                retrievalRuntime
        );
        if (decision.selectedCandidateId() != null) {
            for (GeneratedCandidate candidate : scoredCandidates) {
                boolean adopted = candidate.rewriteCandidateId().equals(decision.selectedCandidateId());
                repository.markRewriteCandidateAdopted(
                        candidate.rewriteCandidateId(),
                        adopted,
                        adopted ? null : decision.rejectedReason()
                );
            }
        } else {
            for (GeneratedCandidate candidate : scoredCandidates) {
                repository.markRewriteCandidateAdopted(candidate.rewriteCandidateId(), false, decision.rejectedReason());
            }
        }
        long decisionLatency = elapsedMs(stageStart);

        stageStart = System.nanoTime();
        List<RagRepository.RetrievalDoc> reranked = decision.finalRetrieved();
        repository.insertRerankResults(onlineQueryId, decision.selectedCandidateId(), reranked, cohereRerankService.modelName());
        long rerankLatency = elapsedMs(stageStart);

        stageStart = System.nanoTime();
        AnswerDraft answerDraft = buildAnswer(reranked);
        repository.insertAnswer(
                onlineQueryId,
                answerDraft.answerText(),
                objectMapper.valueToTree(answerDraft.citedDocumentIds()),
                objectMapper.valueToTree(answerDraft.citedChunkIds())
        );
        long answerLatency = elapsedMs(stageStart);

        Map<String, Long> latencyBreakdown = Map.of(
                "createOnlineQueryMs", createQueryLatency,
                "memoryLookupMs", memoryLatency,
                "rawRetrievalMs", rawRetrievalLatency,
                "candidateGenerationMs", candidateLatency,
                "rewriteDecisionMs", decisionLatency,
                "rerankMs", rerankLatency,
                "answerGenerationMs", answerLatency,
                "totalMs", java.time.Duration.between(started, Instant.now()).toMillis()
        );
        repository.upsertOnlineQueryDecision(
                onlineQueryId,
                decision.finalQuery(),
                decision.rewriteApplied(),
                memoryTopNJson,
                rawConfidence,
                decision.selectedCandidateId(),
                decision.selectedReason(),
                decision.rejectedReason(),
                objectMapper.valueToTree(latencyBreakdown)
        );

        double selectedConfidence = confidence(decision.finalRetrieved(), rawDense);
        boolean gatingApplied = !"raw_only".equals(mode) && !"memory_only_ungated".equals(mode);
        boolean selectiveRewrite = mode.startsWith("selective_rewrite");
        UUID rewriteLogId = repository.createOnlineRewriteLog(
                onlineQueryId,
                null,
                rawQuery,
                decision.finalQuery(),
                mode,
                generationMethodCodes(memoryCandidates),
                generationBatchIds(memoryCandidates),
                gatingApplied,
                gatingPreset,
                decision.rewriteApplied(),
                selectiveRewrite,
                useSessionContext,
                rawConfidence,
                selectedConfidence,
                selectedConfidence - rawConfidence,
                decision.selectedReason(),
                decision.rejectedReason(),
                rewriteLogMetadata(
                        config,
                        rewriteQueryProfile,
                        retrievalTopK,
                        rerankTopN,
                        memoryTopN,
                        candidateCount,
                        threshold,
                        latencyBreakdown,
                        retrievalRuntime
                )
        );

        for (int index = 0; index < memoryCandidates.size(); index++) {
            RagRepository.MemoryCandidate memoryCandidate = memoryCandidates.get(index);
            repository.insertMemoryRetrievalLog(
                    rewriteLogId,
                    onlineQueryId,
                    index + 1,
                    memoryCandidate,
                    objectMapper.valueToTree(Map.of(
                            "gating_preset", gatingPreset,
                            "generation_batch_id", memoryCandidate.generationBatchId() == null ? "" : memoryCandidate.generationBatchId().toString(),
                            "source_gate_run_id", memoryCandidate.sourceGateRunId() == null ? "" : memoryCandidate.sourceGateRunId(),
                            "source_gating_batch_id", memoryCandidate.sourceGatingBatchId() == null ? "" : memoryCandidate.sourceGatingBatchId()
                    ))
            );
        }

        for (int index = 0; index < scoredCandidates.size(); index++) {
            GeneratedCandidate candidate = scoredCandidates.get(index);
            boolean selected = decision.selectedCandidateId() != null && decision.selectedCandidateId().equals(candidate.rewriteCandidateId());
            repository.insertRewriteCandidateLog(
                    rewriteLogId,
                    onlineQueryId,
                    candidate.rewriteCandidateId(),
                    index + 1,
                    candidate.label(),
                    candidate.query(),
                    candidate.confidence(),
                    selected,
                    selected ? null : decision.rejectedReason(),
                    objectMapper.valueToTree(candidate.retrieved()),
                    scoreBreakdown(candidate.retrieved(), memoryCandidates),
                    objectMapper.valueToTree(Map.of(
                            "mode", mode,
                            "selected_reason", decision.selectedReason()
                    ))
            );
        }

        return new RagDtos.AskResponse(
                onlineQueryId,
                answerDraft.answerText(),
                decision.finalQuery(),
                rawQuery,
                decision.rewriteApplied(),
                toRewriteDtos(scoredCandidates, decision.selectedCandidateId()),
                toScoredDocs(decision.finalRetrieved()),
                toScoredDocs(reranked),
                memoryTopNJson,
                config,
                latencyBreakdown
        );
    }

    public RagDtos.RewritePreviewResponse previewRewrite(RagDtos.RewritePreviewRequest request) {
        String rawQuery = normalizedText(request.rawQuery());
        if (rawQuery.isBlank()) {
            throw new IllegalArgumentException("rawQuery must not be blank");
        }
        int memoryTopN = normalizedPositive(request.memoryTopN(), 5);
        int candidateCount = Math.min(normalizedPositive(request.candidateCount(), 2), 2);
        String gatingPreset = request.gatingPreset() == null || request.gatingPreset().isBlank()
                ? "full_gating"
                : request.gatingPreset();

        String queryEmbedding = embeddingService.toHalfvecLiteral(embeddingService.embed(rawQuery));
        List<RagRepository.MemoryCandidate> memories = repository.findMemoryTopN(
                queryEmbedding,
                memoryTopN,
                gatingPreset,
                null,
                List.of(),
                null,
                null
        );
        List<RewriteCandidateService.CandidateTemplate> candidates = rewriteCandidateService.buildCandidates(
                rawQuery,
                nullSafeJson(request.sessionContext()),
                memories,
                candidateCount,
                "compact_anchor",
                false,
                objectMapper.createObjectNode()
        );
        List<RagDtos.RewriteCandidateDto> previewDtos = new ArrayList<>();
        for (RewriteCandidateService.CandidateTemplate candidate : candidates) {
            String candidateEmbedding = embeddingService.toHalfvecLiteral(embeddingService.embed(candidate.query()));
            List<RagRepository.RetrievalDoc> retrievedLocal = repository.findTopChunksByEmbedding(candidateEmbedding, 20);
            List<RagRepository.RetrievalDoc> retrieved = cohereRerankService.rerank(candidate.query(), retrievedLocal, 5);
            double confidence = confidence(retrieved, memories.isEmpty() ? 0.0 : memories.getFirst().similarity());
            previewDtos.add(
                    new RagDtos.RewriteCandidateDto(
                            null,
                            candidate.label(),
                            candidate.query(),
                            confidence,
                            false,
                            null,
                            objectMapper.valueToTree(retrieved),
                            scoreBreakdown(retrieved, memories)
                    )
            );
        }
        return new RagDtos.RewritePreviewResponse(
                rawQuery,
                objectMapper.valueToTree(memories),
                previewDtos
        );
    }

    public RagDtos.QueryTraceResponse getQueryTrace(UUID onlineQueryId) {
        RagRepository.OnlineQueryRow onlineQuery = repository.findOnlineQuery(onlineQueryId)
                .orElseThrow(() -> new IllegalArgumentException("online query was not found: " + onlineQueryId));
        return new RagDtos.QueryTraceResponse(
                onlineQuery.onlineQueryId(),
                onlineQuery.rawQuery(),
                onlineQuery.finalQueryUsed(),
                onlineQuery.rewriteApplied(),
                onlineQuery.rewriteStrategy(),
                onlineQuery.sessionContextSnapshot(),
                onlineQuery.memoryTopN(),
                onlineQuery.rawScore(),
                onlineQuery.selectedRewriteCandidateId(),
                onlineQuery.selectedReason(),
                onlineQuery.rejectedReason(),
                onlineQuery.threshold(),
                onlineQuery.latencyBreakdown(),
                repository.findRewriteCandidates(onlineQueryId).stream()
                        .map(candidate -> new RagDtos.RewriteCandidateDto(
                                candidate.rewriteCandidateId(),
                                candidate.candidateLabel(),
                                candidate.candidateQuery(),
                                candidate.confidenceScore() != null ? candidate.confidenceScore() : 0.0,
                                candidate.adopted() != null && candidate.adopted(),
                                candidate.rejectedReason(),
                                candidate.retrievalTopKDocs(),
                                candidate.scoreBreakdown()
                        ))
                        .toList(),
                repository.findRetrievalResults(onlineQueryId),
                repository.findRerankResults(onlineQueryId),
                repository.findAnswer(onlineQueryId)
        );
    }

    public RagDtos.ExperimentSummaryResponse getExperimentSummary(UUID experimentRunId) {
        RagRepository.ExperimentRunSummary summary = repository.findExperimentRunSummary(experimentRunId)
                .orElseThrow(() -> new IllegalArgumentException("experiment run was not found: " + experimentRunId));
        return new RagDtos.ExperimentSummaryResponse(
                summary.experimentRunId(),
                summary.experimentKey(),
                summary.status(),
                summary.startedAt(),
                summary.finishedAt(),
                summary.parameters(),
                summary.metrics(),
                summary.notes()
        );
    }

    public List<RagRepository.ExperimentRunSummary> listRecentExperimentRuns(int limit) {
        return repository.listRecentExperimentRuns(limit);
    }

    public RagDtos.EvalReportResponse readEvalReport(String reportType) {
        String normalized = reportType == null ? "retrieval" : reportType.toLowerCase(Locale.ROOT);
        List<Path> reportRoots = List.of(
                Path.of("data/reports"),
                Path.of("../data/reports"),
                Path.of("../../data/reports")
        );
        try {
            String prefix = normalized.startsWith("answer") ? "answer_summary_" : "retrieval_summary_";
            Optional<Path> latest = Optional.empty();
            for (Path reportDir : reportRoots) {
                if (!Files.exists(reportDir)) {
                    continue;
                }
                Optional<Path> candidate;
                try (Stream<Path> paths = Files.list(reportDir)) {
                    candidate = paths
                            .filter(path -> path.getFileName().toString().startsWith(prefix))
                            .filter(path -> path.getFileName().toString().endsWith(".json"))
                            .max(Comparator.comparingLong(path -> path.toFile().lastModified()));
                }
                if (candidate.isPresent()) {
                    if (latest.isEmpty() || candidate.get().toFile().lastModified() > latest.get().toFile().lastModified()) {
                        latest = candidate;
                    }
                }
            }
            if (latest.isEmpty()) {
                return new RagDtos.EvalReportResponse(normalized, objectMapper.createObjectNode());
            }
            JsonNode payload = objectMapper.readTree(Files.readString(latest.get()));
            return new RagDtos.EvalReportResponse(normalized, payload);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to read eval report.", exception);
        }
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public RagDtos.ReindexResponse reindex(RagDtos.ReindexRequest request) {
        boolean reindexChunks = request == null || request.reindexChunks() == null || request.reindexChunks();
        boolean reindexMemory = request == null || request.reindexMemory() == null || request.reindexMemory();
        int chunkUpdates = 0;
        int memoryUpdates = 0;
        String model = "hash-embedding-v1";

        if (reindexChunks) {
            for (RagRepository.ChunkSource chunk : repository.findAllChunksForEmbedding()) {
                String literal = embeddingService.toHalfvecLiteral(embeddingService.embed(chunk.chunkText()));
                repository.upsertChunkEmbedding(chunk.chunkId(), literal, model);
                chunkUpdates++;
            }
        }
        if (reindexMemory) {
            for (RagRepository.MemorySource memory : repository.findAllMemorySources()) {
                String literal = embeddingService.toHalfvecLiteral(embeddingService.embed(memory.queryText()));
                repository.updateMemoryEmbedding(memory.memoryId(), literal, model);
                memoryUpdates++;
            }
        }
        return new RagDtos.ReindexResponse(chunkUpdates, memoryUpdates, model);
    }

    private List<RagDtos.RewriteCandidateDto> toRewriteDtos(
            List<GeneratedCandidate> candidates,
            UUID selectedCandidateId
    ) {
        return candidates.stream()
                .map(candidate -> new RagDtos.RewriteCandidateDto(
                        candidate.rewriteCandidateId(),
                        candidate.label(),
                        candidate.query(),
                        candidate.confidence(),
                        selectedCandidateId != null && selectedCandidateId.equals(candidate.rewriteCandidateId()),
                        selectedCandidateId != null && !selectedCandidateId.equals(candidate.rewriteCandidateId())
                                ? "not_selected"
                                : null,
                        objectMapper.valueToTree(candidate.retrieved()),
                        scoreBreakdown(candidate.retrieved(), List.of())
                ))
                .toList();
    }

    private List<RagDtos.ScoredDocumentDto> toScoredDocs(List<RagRepository.RetrievalDoc> docs) {
        return docs.stream()
                .map(doc -> new RagDtos.ScoredDocumentDto(
                        doc.documentId(),
                        doc.chunkId(),
                        preview(doc.chunkText()),
                        doc.score()
                ))
                .toList();
    }

    private RetrievalRuntime retrievalRuntime(ChatRuntimeDtos.ChatRuntimeConfigResponse config) {
        String backend = config.retrievalBackend() == null || config.retrievalBackend().isBlank()
                ? "local"
                : config.retrievalBackend().trim().toLowerCase(Locale.ROOT).replace("-", "_");
        String mode = config.retrieverMode() == null || config.retrieverMode().isBlank()
                ? "hybrid"
                : config.retrieverMode().trim().toLowerCase(Locale.ROOT).replace("-", "_");
        String denseModel = config.denseEmbeddingModel() == null || config.denseEmbeddingModel().isBlank()
                ? "intfloat/multilingual-e5-small"
                : config.denseEmbeddingModel().trim();
        int candidatePoolK = Math.max(1, config.retrieverCandidatePoolK());
        double denseWeight = clampWeight(config.retrieverDenseWeight());
        double bm25Weight = clampWeight(config.retrieverBm25Weight());
        double technicalWeight = clampWeight(config.retrieverTechnicalWeight());
        if ("bm25_only".equals(mode)) {
            denseWeight = 0.0d;
            bm25Weight = 1.0d;
            technicalWeight = 0.0d;
        } else if ("dense_only".equals(mode)) {
            denseWeight = 1.0d;
            bm25Weight = 0.0d;
            technicalWeight = 0.0d;
        } else {
            double sum = denseWeight + bm25Weight + technicalWeight;
            if (sum <= 0.0d) {
                denseWeight = 0.60d;
                bm25Weight = 0.32d;
                technicalWeight = 0.08d;
                sum = denseWeight + bm25Weight + technicalWeight;
            }
            denseWeight = denseWeight / sum;
            bm25Weight = bm25Weight / sum;
            technicalWeight = technicalWeight / sum;
        }
        return new RetrievalRuntime(backend, denseModel, mode, candidatePoolK, denseWeight, bm25Weight, technicalWeight);
    }

    private String embeddingLiteral(String query, RetrievalRuntime runtime) {
        if ("db_ann".equals(runtime.retrievalBackend())) {
            return embeddingService.toHalfvecLiteral(denseEmbeddingService.embedQuery(
                    query,
                    runtime.retrieverMode(),
                    runtime.denseEmbeddingModel()
            ));
        }
        return embeddingService.toHalfvecLiteral(embeddingService.embed(query));
    }

    private List<RagRepository.RetrievalDoc> retrieveChunks(
            String query,
            String queryEmbeddingLiteral,
            int topK,
            UUID domainId,
            RetrievalRuntime runtime
    ) {
        int poolSize = Math.max(topK, runtime.candidatePoolK());
        List<RagRepository.RetrievalDoc> candidates = new ArrayList<>();
        List<String> patterns = searchPatterns(query);
        if ("db_ann".equals(runtime.retrievalBackend())) {
            if (!"bm25_only".equals(runtime.retrieverMode())) {
                candidates.addAll(repository.findDbAnnChunkDensePool(
                        queryEmbeddingLiteral,
                        runtime.denseEmbeddingModel(),
                        poolSize,
                        domainId
                ));
            }
            if (!"dense_only".equals(runtime.retrieverMode())) {
                candidates.addAll(repository.findDbAnnChunkTextPool(
                        queryEmbeddingLiteral,
                        runtime.denseEmbeddingModel(),
                        patterns,
                        poolSize,
                        domainId
                ));
            }
        } else {
            if (!"bm25_only".equals(runtime.retrieverMode())) {
                candidates.addAll(repository.findTopChunksByEmbedding(queryEmbeddingLiteral, poolSize, domainId));
            }
            if (!"dense_only".equals(runtime.retrieverMode())) {
                candidates.addAll(repository.findChunkTextPool(patterns, poolSize, domainId));
            }
        }
        if (candidates.isEmpty()) {
            return List.of();
        }
        return rankDocs(query, mergeDocs(candidates), topK, runtime);
    }

    private List<RagRepository.MemoryCandidate> findMemoryCandidates(
            String query,
            String queryEmbeddingLiteral,
            int topN,
            String gatingPreset,
            UUID domainId,
            List<String> generationStrategies,
            UUID sourceGatingRunId,
            UUID sourceGatingBatchId,
            RetrievalRuntime runtime
    ) {
        if (!"db_ann".equals(runtime.retrievalBackend())) {
            return repository.findMemoryTopN(
                    queryEmbeddingLiteral,
                    topN,
                    gatingPreset,
                    domainId,
                    generationStrategies,
                    sourceGatingRunId,
                    sourceGatingBatchId
            );
        }
        int poolSize = Math.max(topN, runtime.candidatePoolK());
        List<RagRepository.MemoryCandidate> candidates = new ArrayList<>();
        if (!"bm25_only".equals(runtime.retrieverMode())) {
            candidates.addAll(repository.findMemoryDensePool(
                    queryEmbeddingLiteral,
                    runtime.denseEmbeddingModel(),
                    poolSize,
                    gatingPreset,
                    domainId,
                    generationStrategies,
                    sourceGatingRunId,
                    sourceGatingBatchId
            ));
        }
        if (!"dense_only".equals(runtime.retrieverMode())) {
            candidates.addAll(repository.findMemoryTextPool(
                    queryEmbeddingLiteral,
                    runtime.denseEmbeddingModel(),
                    searchPatterns(query),
                    poolSize,
                    gatingPreset,
                    domainId,
                    generationStrategies,
                    sourceGatingRunId,
                    sourceGatingBatchId
            ));
        }
        return rankMemories(query, mergeMemories(candidates), topN, runtime);
    }

    private List<RagRepository.RetrievalDoc> mergeDocs(List<RagRepository.RetrievalDoc> docs) {
        Map<String, RagRepository.RetrievalDoc> merged = new LinkedHashMap<>();
        for (RagRepository.RetrievalDoc doc : docs) {
            RagRepository.RetrievalDoc current = merged.get(doc.chunkId());
            if (current == null || doc.score() > current.score()) {
                merged.put(doc.chunkId(), doc);
            }
        }
        return new ArrayList<>(merged.values());
    }

    private List<RagRepository.MemoryCandidate> mergeMemories(List<RagRepository.MemoryCandidate> memories) {
        Map<UUID, RagRepository.MemoryCandidate> merged = new LinkedHashMap<>();
        for (RagRepository.MemoryCandidate memory : memories) {
            RagRepository.MemoryCandidate current = merged.get(memory.memoryId());
            if (current == null || memory.similarity() > current.similarity()) {
                merged.put(memory.memoryId(), memory);
            }
        }
        return new ArrayList<>(merged.values());
    }

    private List<RagRepository.RetrievalDoc> rankDocs(
            String query,
            List<RagRepository.RetrievalDoc> candidates,
            int topK,
            RetrievalRuntime runtime
    ) {
        return candidates.stream()
                .map(doc -> new RagRepository.RetrievalDoc(
                        doc.documentId(),
                        doc.chunkId(),
                        doc.chunkText(),
                        fusedScore(query, doc.chunkText(), doc.score(), runtime)
                ))
                .sorted(Comparator
                        .comparingDouble(RagRepository.RetrievalDoc::score)
                        .reversed()
                        .thenComparing(RagRepository.RetrievalDoc::chunkId))
                .limit(Math.max(1, topK))
                .toList();
    }

    private List<RagRepository.MemoryCandidate> rankMemories(
            String query,
            List<RagRepository.MemoryCandidate> candidates,
            int topN,
            RetrievalRuntime runtime
    ) {
        return candidates.stream()
                .map(candidate -> withSimilarity(
                        candidate,
                        fusedScore(query, memoryLexicalText(candidate), candidate.similarity(), runtime)
                ))
                .sorted(Comparator
                        .comparingDouble(RagRepository.MemoryCandidate::similarity)
                        .reversed()
                        .thenComparing(memory -> memory.memoryId().toString()))
                .limit(Math.max(1, topN))
                .toList();
    }

    private double fusedScore(String query, String text, double denseScore, RetrievalRuntime runtime) {
        double dense = normalizeScore(denseScore);
        double lexical = lexicalOverlap(query, text);
        double technical = technicalOverlap(query, text);
        double combined = (runtime.denseWeight() * dense)
                + (runtime.bm25Weight() * lexical)
                + (runtime.technicalWeight() * technical);
        return Math.max(-1.0, Math.min(1.0, (combined * 2.0) - 1.0));
    }

    private RagRepository.MemoryCandidate withSimilarity(RagRepository.MemoryCandidate source, double similarity) {
        return new RagRepository.MemoryCandidate(
                source.memoryId(),
                source.queryText(),
                source.targetDocId(),
                source.targetChunkIds(),
                source.glossaryTerms(),
                source.metadata(),
                similarity,
                source.generationStrategy(),
                source.generationBatchId(),
                source.domainId(),
                source.sourceGatedQueryId(),
                source.sourceGateRunId(),
                source.sourceGatingBatchId()
        );
    }

    private String memoryLexicalText(RagRepository.MemoryCandidate candidate) {
        return String.join(
                " ",
                candidate.queryText() == null ? "" : candidate.queryText(),
                candidate.glossaryTerms() == null ? "" : candidate.glossaryTerms().toString(),
                candidate.metadata() == null ? "" : candidate.metadata().toString()
        );
    }

    private List<String> searchPatterns(String query) {
        LinkedHashSet<String> tokens = new LinkedHashSet<>();
        Matcher matcher = SEARCH_TOKEN_PATTERN.matcher(query == null ? "" : query);
        while (matcher.find() && tokens.size() < 16) {
            String value = matcher.group().trim().toLowerCase(Locale.ROOT);
            if (value.length() >= 2) {
                tokens.add(value);
            }
        }
        return List.copyOf(tokens);
    }

    private double lexicalOverlap(String query, String text) {
        List<String> tokens = searchPatterns(query);
        if (tokens.isEmpty() || text == null || text.isBlank()) {
            return 0.0;
        }
        String normalizedText = text.toLowerCase(Locale.ROOT);
        int hits = 0;
        for (String token : tokens) {
            if (normalizedText.contains(token)) {
                hits++;
            }
        }
        return (double) hits / tokens.size();
    }

    private double technicalOverlap(String query, String text) {
        if (query == null || query.isBlank() || text == null || text.isBlank()) {
            return 0.0;
        }
        LinkedHashSet<String> tokens = new LinkedHashSet<>();
        Matcher matcher = TECHNICAL_TOKEN_PATTERN.matcher(query);
        while (matcher.find() && tokens.size() < 12) {
            tokens.add(matcher.group().toLowerCase(Locale.ROOT));
        }
        if (tokens.isEmpty()) {
            return 0.0;
        }
        String normalizedText = text.toLowerCase(Locale.ROOT);
        int hits = 0;
        for (String token : tokens) {
            if (normalizedText.contains(token)) {
                hits++;
            }
        }
        return (double) hits / tokens.size();
    }

    private ObjectNode retrievalMetadata(RetrievalRuntime runtime) {
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

    private double clampWeight(double value) {
        return Math.max(0.0d, Math.min(1.0d, value));
    }

    private Decision decide(
            String mode,
            String rawQuery,
            double rawConfidence,
            List<RagRepository.RetrievalDoc> rawRetrieved,
            List<RagRepository.MemoryCandidate> memoryCandidates,
            List<GeneratedCandidate> candidates,
            double threshold,
            UUID domainId,
            RetrievalRuntime retrievalRuntime
    ) {
        if ("raw_only".equals(mode)) {
            return new Decision(rawQuery, false, rawRetrieved, null, "raw_only", "mode=raw_only");
        }
        if ("memory_only_ungated".equals(mode) || "memory_only_gated".equals(mode)) {
            if (memoryCandidates.isEmpty()) {
                return new Decision(rawQuery, false, rawRetrieved, null, "memory_empty", "no memory candidate");
            }
            String memoryQuery = memoryCandidates.getFirst().queryText();
            String embedding = embeddingLiteral(memoryQuery, retrievalRuntime);
            List<RagRepository.RetrievalDoc> retrievedLocal = retrieveChunks(
                    memoryQuery,
                    embedding,
                    Math.max(20, rawRetrieved.size()),
                    domainId,
                    retrievalRuntime
            );
            List<RagRepository.RetrievalDoc> retrieved = cohereRerankService.rerank(memoryQuery, retrievedLocal, rawRetrieved.size());
            return new Decision(memoryQuery, true, retrieved, null, "memory_only", null);
        }

        GeneratedCandidate best = candidates.stream()
                .max(Comparator.comparingDouble(GeneratedCandidate::confidence))
                .orElse(null);
        if (best == null) {
            return new Decision(rawQuery, false, rawRetrieved, null, "no_candidate", "rewrite candidate missing");
        }

        if ("rewrite_always".equals(mode)) {
            return new Decision(best.query(), true, best.retrieved(), best.rewriteCandidateId(), "forced", null);
        }

        double delta = best.confidence() - rawConfidence;
        if (delta >= threshold) {
            return new Decision(best.query(), true, best.retrieved(), best.rewriteCandidateId(), "delta_above_threshold", null);
        }
        return new Decision(rawQuery, false, rawRetrieved, null, "delta_below_threshold", "best-candidate delta below threshold");
    }

    private double confidence(List<RagRepository.RetrievalDoc> docs, double denseScore) {
        if (docs.isEmpty()) {
            return 0.0;
        }
        double r1 = normalizeScore(docs.getFirst().score());
        int topN = Math.min(3, docs.size());
        double r3 = 0.0;
        for (int index = 0; index < topN; index++) {
            r3 += normalizeScore(docs.get(index).score());
        }
        r3 /= topN;
        double dense = normalizeScore(denseScore);
        return (0.6 * r1) + (0.3 * r3) + (0.1 * dense);
    }

    private JsonNode scoreBreakdown(List<RagRepository.RetrievalDoc> retrieved, List<RagRepository.MemoryCandidate> memories) {
        ObjectNode node = objectMapper.createObjectNode();
        double dense = memories.isEmpty() ? 0.0 : memories.getFirst().similarity();
        node.put("r1", retrieved.isEmpty() ? 0.0 : normalizeScore(retrieved.getFirst().score()));
        node.put(
                "r3",
                retrieved.isEmpty()
                        ? 0.0
                        : retrieved.stream().limit(3).mapToDouble(item -> normalizeScore(item.score())).average().orElse(0.0)
        );
        node.put("dense", normalizeScore(dense));
        return node;
    }

    private JsonNode memorySourceIds(List<RagRepository.MemoryCandidate> memories) {
        ArrayNode array = objectMapper.createArrayNode();
        for (RagRepository.MemoryCandidate memory : memories) {
            array.add(memory.memoryId().toString());
        }
        return array;
    }

    private JsonNode generationMethodCodes(List<RagRepository.MemoryCandidate> memories) {
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (RagRepository.MemoryCandidate memory : memories) {
            if (memory.generationStrategy() != null && !memory.generationStrategy().isBlank()) {
                unique.add(memory.generationStrategy().trim().toUpperCase(Locale.ROOT));
            }
        }
        ArrayNode array = objectMapper.createArrayNode();
        unique.forEach(array::add);
        return array;
    }

    private JsonNode generationBatchIds(List<RagRepository.MemoryCandidate> memories) {
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (RagRepository.MemoryCandidate memory : memories) {
            if (memory.generationBatchId() != null) {
                unique.add(memory.generationBatchId().toString());
            }
        }
        ArrayNode array = objectMapper.createArrayNode();
        unique.forEach(array::add);
        return array;
    }

    private ObjectNode runtimeMetadata(ChatRuntimeDtos.ChatRuntimeConfigResponse config) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("domain_id", config.domainId().toString());
        node.put("domain_key", config.domainKey());
        node.put("domain_display_name", config.displayName());
        node.put("mode", config.mode());
        node.put("gating_preset", config.gatingPreset());
        node.set("generation_strategies", objectMapper.valueToTree(config.generationStrategies()));
        putNullableUuid(node, "source_gating_batch_id", config.sourceGatingBatchId());
        putNullableUuid(node, "source_gating_run_id", config.sourceGatingRunId());
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

    private ObjectNode rewriteLogMetadata(
            ChatRuntimeDtos.ChatRuntimeConfigResponse config,
            String rewriteQueryProfile,
            int retrievalTopK,
            int rerankTopN,
            int memoryTopN,
            int candidateCount,
            double threshold,
            Map<String, Long> latencyBreakdown,
            RetrievalRuntime retrievalRuntime
    ) {
        ObjectNode node = runtimeMetadata(config);
        node.put("rewrite_query_profile", rewriteQueryProfile);
        node.put("retrieval_top_k", retrievalTopK);
        node.put("rerank_top_n", rerankTopN);
        node.put("memory_top_n", memoryTopN);
        node.put("rewrite_candidate_count", candidateCount);
        node.put("rewrite_threshold", threshold);
        node.set("retrieval_runtime", retrievalMetadata(retrievalRuntime));
        node.set("latency_breakdown", objectMapper.valueToTree(latencyBreakdown));
        return node;
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

    private void putNullableUuid(ObjectNode node, String fieldName, UUID value) {
        if (value == null) {
            node.putNull(fieldName);
        } else {
            node.put(fieldName, value.toString());
        }
    }

    private AnswerDraft buildAnswer(List<RagRepository.RetrievalDoc> reranked) {
        StringBuilder builder = new StringBuilder();
        Set<String> docs = new HashSet<>();
        Set<String> chunks = new HashSet<>();
        int used = 0;
        for (RagRepository.RetrievalDoc row : reranked) {
            if (used >= 2) {
                break;
            }
            String snippet = preview(row.chunkText());
            if (!snippet.isBlank()) {
                if (builder.length() > 0) {
                    builder.append(" ");
                }
                builder.append(snippet);
                docs.add(row.documentId());
                chunks.add(row.chunkId());
                used++;
            }
        }
        if (builder.length() == 0) {
            builder.append("관련 문서를 찾았지만 질문에 직접 대응하는 근거를 충분히 찾지 못했습니다.");
        }
        return new AnswerDraft(builder.toString(), List.copyOf(docs), List.copyOf(chunks));
    }

    private String normalizedMode(String mode) {
        if (mode == null || mode.isBlank()) {
            return "selective_rewrite";
        }
        return mode.trim().toLowerCase(Locale.ROOT);
    }

    private String normalizedPreset(String preset) {
        if (preset == null || preset.isBlank()) {
            return "full_gating";
        }
        return preset.trim().toLowerCase(Locale.ROOT);
    }

    private String normalizedRewriteProfile(String profile) {
        if (profile == null || profile.isBlank()) {
            return "compact_anchor";
        }
        String normalized = profile.trim().toLowerCase(Locale.ROOT);
        return "detailed_intent".equals(normalized) ? normalized : "compact_anchor";
    }

    private int normalizedPositive(Integer value, int fallback) {
        if (value == null || value <= 0) {
            return fallback;
        }
        return value;
    }

    private String normalizedText(String value) {
        if (value == null) {
            return "";
        }
        return value.trim();
    }

    private JsonNode nullSafeJson(JsonNode node) {
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

    private long elapsedMs(long startedNano) {
        return (System.nanoTime() - startedNano) / 1_000_000L;
    }

    private double normalizeScore(double score) {
        return Math.max(0.0, Math.min(1.0, (score + 1.0) / 2.0));
    }

    private record RetrievalRuntime(
            String retrievalBackend,
            String denseEmbeddingModel,
            String retrieverMode,
            int candidatePoolK,
            double denseWeight,
            double bm25Weight,
            double technicalWeight
    ) {
        String retrieverName() {
            if ("db_ann".equals(retrievalBackend)) {
                return "db-ann:" + retrieverMode + ":" + denseEmbeddingModel;
            }
            return "local:" + retrieverMode + ":hash-embedding-v1";
        }
    }

    private record GeneratedCandidate(
            String label,
            String query,
            List<RagRepository.RetrievalDoc> retrieved,
            double confidence,
            UUID rewriteCandidateId
    ) {
    }

    private record Decision(
            String finalQuery,
            boolean rewriteApplied,
            List<RagRepository.RetrievalDoc> finalRetrieved,
            UUID selectedCandidateId,
            String selectedReason,
            String rejectedReason
    ) {
    }

    private record AnswerDraft(
            String answerText,
            List<String> citedDocumentIds,
            List<String> citedChunkIds
    ) {
    }
}
