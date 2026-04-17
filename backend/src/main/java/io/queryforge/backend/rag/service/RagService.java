package io.queryforge.backend.rag.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RagService {

    private final RagRepository repository;
    private final HashEmbeddingService embeddingService;
    private final CohereRerankService cohereRerankService;
    private final ObjectMapper objectMapper;

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public RagDtos.AskResponse ask(RagDtos.AskRequest request) {
        String rawQuery = normalizedText(request.query());
        if (rawQuery.isBlank()) {
            throw new IllegalArgumentException("query must not be blank");
        }
        String mode = normalizedMode(request.mode());
        int retrievalTopK = normalizedPositive(request.retrievalTopK(), 20);
        int rerankTopN = normalizedPositive(request.rerankTopN(), 5);
        int memoryTopN = normalizedPositive(request.memoryTopN(), 5);
        int candidateCount = normalizedPositive(request.rewriteCandidateCount(), 3);
        double threshold = request.rewriteThreshold() != null ? request.rewriteThreshold() : 0.05d;
        String gatingPreset = request.gatingPreset();
        if (gatingPreset == null || gatingPreset.isBlank()) {
            gatingPreset = "full_gating";
        }

        Instant started = Instant.now();
        long stageStart = System.nanoTime();
        UUID onlineQueryId = repository.createOnlineQuery(
                request.sessionId(),
                rawQuery,
                nullSafeJson(request.sessionContext()),
                mode,
                threshold
        );
        long createQueryLatency = elapsedMs(stageStart);

        stageStart = System.nanoTime();
        String rawEmbeddingLiteral = embeddingService.toHalfvecLiteral(embeddingService.embed(rawQuery));
        List<RagRepository.MemoryCandidate> memoryCandidates = repository.findMemoryTopN(
                rawEmbeddingLiteral,
                memoryTopN,
                switch (mode) {
                    case "memory_only_ungated" -> "ungated";
                    case "memory_only_gated" -> gatingPreset;
                    default -> gatingPreset;
                }
        );
        JsonNode memoryTopNJson = objectMapper.valueToTree(memoryCandidates);
        long memoryLatency = elapsedMs(stageStart);

        stageStart = System.nanoTime();
        List<RagRepository.RetrievalDoc> rawRetrievedLocal = repository.findTopChunksByEmbedding(rawEmbeddingLiteral, retrievalTopK);
        List<RagRepository.RetrievalDoc> rawRetrieved = cohereRerankService.rerank(rawQuery, rawRetrievedLocal, rerankTopN);
        double rawDense = memoryCandidates.isEmpty() ? 0.0 : memoryCandidates.getFirst().similarity();
        double rawConfidence = confidence(rawRetrieved, rawDense);
        repository.insertRetrievalResults(onlineQueryId, null, "raw", rawRetrievedLocal, mode);
        long rawRetrievalLatency = elapsedMs(stageStart);

        stageStart = System.nanoTime();
        List<GeneratedCandidate> generatedCandidates = buildCandidates(
                rawQuery,
                nullSafeJson(request.sessionContext()),
                memoryCandidates,
                candidateCount
        );
        List<GeneratedCandidate> scoredCandidates = new ArrayList<>();
        for (int index = 0; index < generatedCandidates.size(); index++) {
            GeneratedCandidate generated = generatedCandidates.get(index);
            String embeddingLiteral = embeddingService.toHalfvecLiteral(embeddingService.embed(generated.query()));
            List<RagRepository.RetrievalDoc> candidateRetrievedLocal = repository.findTopChunksByEmbedding(embeddingLiteral, retrievalTopK);
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
            repository.insertRetrievalResults(onlineQueryId, candidateId, "rewrite_candidate", candidateRetrievedLocal, mode);
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
                threshold
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
        boolean useSessionContext = "selective_rewrite_with_session".equals(mode);
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
                objectMapper.valueToTree(Map.of(
                        "retrieval_top_k", retrievalTopK,
                        "rerank_top_n", rerankTopN,
                        "memory_top_n", memoryTopN,
                        "rewrite_candidate_count", candidateCount,
                        "rewrite_threshold", threshold,
                        "latency_breakdown", latencyBreakdown
                ))
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
                            "generation_batch_id", memoryCandidate.generationBatchId() == null ? "" : memoryCandidate.generationBatchId().toString()
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
                latencyBreakdown
        );
    }

    public RagDtos.RewritePreviewResponse previewRewrite(RagDtos.RewritePreviewRequest request) {
        String rawQuery = normalizedText(request.rawQuery());
        if (rawQuery.isBlank()) {
            throw new IllegalArgumentException("rawQuery must not be blank");
        }
        int memoryTopN = normalizedPositive(request.memoryTopN(), 5);
        int candidateCount = normalizedPositive(request.candidateCount(), 3);
        String gatingPreset = request.gatingPreset() == null || request.gatingPreset().isBlank()
                ? "full_gating"
                : request.gatingPreset();

        String queryEmbedding = embeddingService.toHalfvecLiteral(embeddingService.embed(rawQuery));
        List<RagRepository.MemoryCandidate> memories = repository.findMemoryTopN(queryEmbedding, memoryTopN, gatingPreset);
        List<GeneratedCandidate> candidates = buildCandidates(
                rawQuery,
                nullSafeJson(request.sessionContext()),
                memories,
                candidateCount
        );
        List<RagDtos.RewriteCandidateDto> previewDtos = new ArrayList<>();
        for (GeneratedCandidate candidate : candidates) {
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

    private List<GeneratedCandidate> buildCandidates(
            String rawQuery,
            JsonNode sessionContext,
            List<RagRepository.MemoryCandidate> memories,
            int candidateCount
    ) {
        String memoryAnchor = memories.isEmpty() ? rawQuery : memories.getFirst().queryText();
        String prevQuestion = sessionContext.path("previous_user_question").asText("");
        String prevSummary = sessionContext.path("previous_assistant_summary").asText("");
        String contextualPrefix = "";
        if (!prevQuestion.isBlank() || !prevSummary.isBlank()) {
            contextualPrefix = (prevQuestion + " " + prevSummary).trim();
        }

        List<GeneratedCandidate> base = List.of(
                new GeneratedCandidate(
                        "explicit_standalone",
                        (contextualPrefix.isBlank() ? rawQuery : contextualPrefix + " 이후 맥락에서 " + rawQuery)
                                + "를 독립 검색 질의로 명확하게 다시 작성",
                        List.of(),
                        0.0,
                        null
                ),
                new GeneratedCandidate(
                        "product_version_anchored",
                        rawQuery + " 관련 제품/설정 키워드 보강 " + memoryAnchor,
                        List.of(),
                        0.0,
                        null
                ),
                new GeneratedCandidate(
                        "error_or_task_focused",
                        rawQuery + " 실패 원인이나 해결 절차 중심으로 재작성",
                        List.of(),
                        0.0,
                        null
                )
        );
        return base.subList(0, Math.min(base.size(), Math.max(1, candidateCount)));
    }

    private Decision decide(
            String mode,
            String rawQuery,
            double rawConfidence,
            List<RagRepository.RetrievalDoc> rawRetrieved,
            List<RagRepository.MemoryCandidate> memoryCandidates,
            List<GeneratedCandidate> candidates,
            double threshold
    ) {
        if ("raw_only".equals(mode)) {
            return new Decision(rawQuery, false, rawRetrieved, null, "raw_only", "mode=raw_only");
        }
        if ("memory_only_ungated".equals(mode) || "memory_only_gated".equals(mode)) {
            if (memoryCandidates.isEmpty()) {
                return new Decision(rawQuery, false, rawRetrieved, null, "memory_empty", "no memory candidate");
            }
            String memoryQuery = memoryCandidates.getFirst().queryText();
            String embedding = embeddingService.toHalfvecLiteral(embeddingService.embed(memoryQuery));
            List<RagRepository.RetrievalDoc> retrievedLocal = repository.findTopChunksByEmbedding(embedding, Math.max(20, rawRetrieved.size()));
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
