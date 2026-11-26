package io.queryforge.backend.rag.retrieval.candidate.service;

import io.queryforge.backend.rag.embedding.service.DenseEmbeddingService;
import io.queryforge.backend.rag.embedding.service.HashEmbeddingService;
import io.queryforge.backend.rag.retrieval.candidate.dto.RetrievalDtos.RetrievalRuntime;
import io.queryforge.backend.rag.retrieval.candidate.model.RetrievalDoc;
import io.queryforge.backend.rag.retrieval.candidate.repository.ChunkRetrievalRepository;
import io.queryforge.backend.rag.retrieval.memory.model.MemoryCandidate;
import io.queryforge.backend.rag.retrieval.memory.repository.RagMemoryRepository;
import io.queryforge.backend.rag.runtime.configuration.dto.ChatConfigDtos;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.queryforge.backend.rag.retrieval.candidate.dto.RetrievalDtos.DEFAULT_DENSE_WEIGHT;
import static io.queryforge.backend.rag.retrieval.candidate.dto.RetrievalDtos.DEFAULT_BM25_WEIGHT;
import static io.queryforge.backend.rag.retrieval.candidate.dto.RetrievalDtos.DEFAULT_TECHNICAL_WEIGHT;

@Service
@RequiredArgsConstructor
public class DomainScopedRetrievalService {

    private static final Pattern SEARCH_TOKEN_PATTERN = Pattern.compile("[@A-Za-z0-9_./:$-]{2,}|\\p{InHangulSyllables}{2,}");
    private static final Pattern TECHNICAL_TOKEN_PATTERN = Pattern.compile("[@A-Za-z_][A-Za-z0-9_./:$-]{1,}");
    private final ChunkRetrievalRepository chunkRepository;
    private final RagMemoryRepository memoryRepository;
    private final HashEmbeddingService embeddingService;
    private final DenseEmbeddingService denseEmbeddingService;

    public RetrievalRuntime retrievalRuntime(ChatConfigDtos.ChatRuntimeConfigResponse config) {
        String backend = RetrievalRuntime.normalizeBackend(config.retrievalBackend());
        String mode = RetrievalRuntime.normalizeMode(config.retrieverMode());
        String denseModel = RetrievalRuntime.normalizeDenseModel(config.denseEmbeddingModel());
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
                denseWeight = DEFAULT_DENSE_WEIGHT;
                bm25Weight = DEFAULT_BM25_WEIGHT;
                technicalWeight = DEFAULT_TECHNICAL_WEIGHT;
                sum = denseWeight + bm25Weight + technicalWeight;
            }
            denseWeight = denseWeight / sum;
            bm25Weight = bm25Weight / sum;
            technicalWeight = technicalWeight / sum;
        }
        return new RetrievalRuntime(backend, denseModel, mode, candidatePoolK, denseWeight, bm25Weight, technicalWeight);
    }

    public String embeddingLiteral(String query, RetrievalRuntime runtime) {
        if ("db_ann".equals(runtime.retrievalBackend())) {
            return embeddingService.toHalfvecLiteral(denseEmbeddingService.embedQuery(
                    query,
                    runtime.retrieverMode(),
                    runtime.denseEmbeddingModel()
            ));
        }
        return embeddingService.toHalfvecLiteral(embeddingService.embed(query));
    }

    public List<RetrievalDoc> retrieveChunks(
            String query,
            String queryEmbeddingLiteral,
            int topK,
            UUID domainId,
            RetrievalRuntime runtime
    ) {
        int poolSize = Math.max(topK, runtime.candidatePoolK());
        List<RetrievalDoc> candidates = new ArrayList<>();
        List<String> patterns = searchPatterns(query);
        if ("db_ann".equals(runtime.retrievalBackend())) {
            if (!"bm25_only".equals(runtime.retrieverMode())) {
                candidates.addAll(chunkRepository.findDbAnnChunkDensePool(
                        queryEmbeddingLiteral,
                        runtime.denseEmbeddingModel(),
                        poolSize,
                        domainId
                ));
            }
            if (!"dense_only".equals(runtime.retrieverMode())) {
                candidates.addAll(chunkRepository.findDbAnnChunkTextPool(
                        queryEmbeddingLiteral,
                        runtime.denseEmbeddingModel(),
                        patterns,
                        poolSize,
                        domainId
                ));
            }
        } else {
            if (!"bm25_only".equals(runtime.retrieverMode())) {
                candidates.addAll(chunkRepository.findTopChunksByEmbedding(queryEmbeddingLiteral, poolSize, domainId));
            }
            if (!"dense_only".equals(runtime.retrieverMode())) {
                candidates.addAll(chunkRepository.findChunkTextPool(patterns, poolSize, domainId));
            }
        }
        if (candidates.isEmpty()) {
            return List.of();
        }
        return rankDocs(query, mergeDocs(candidates), topK, runtime);
    }

    public List<MemoryCandidate> findMemoryCandidates(
            String query,
            String queryEmbeddingLiteral,
            int topN,
            String gatingPreset,
            UUID domainId,
            List<String> generationStrategies,
            List<UUID> sourceGatingRunIds,
            List<UUID> sourceGatingBatchIds,
            RetrievalRuntime runtime
    ) {
        if (!"db_ann".equals(runtime.retrievalBackend())) {
            return memoryRepository.findMemoryTopN(
                    queryEmbeddingLiteral,
                    topN,
                    gatingPreset,
                    domainId,
                    generationStrategies,
                    sourceGatingRunIds,
                    sourceGatingBatchIds
            );
        }
        int poolSize = Math.max(topN, runtime.candidatePoolK());
        List<MemoryCandidate> candidates = new ArrayList<>();
        if (!"bm25_only".equals(runtime.retrieverMode())) {
            candidates.addAll(memoryRepository.findMemoryDensePool(
                    queryEmbeddingLiteral,
                    runtime.denseEmbeddingModel(),
                    poolSize,
                    gatingPreset,
                    domainId,
                    generationStrategies,
                    sourceGatingRunIds,
                    sourceGatingBatchIds
            ));
        }
        if (!"dense_only".equals(runtime.retrieverMode())) {
            candidates.addAll(memoryRepository.findMemoryTextPool(
                    queryEmbeddingLiteral,
                    runtime.denseEmbeddingModel(),
                    searchPatterns(query),
                    poolSize,
                    gatingPreset,
                    domainId,
                    generationStrategies,
                    sourceGatingRunIds,
                    sourceGatingBatchIds
            ));
        }
        return rankMemories(query, mergeMemories(candidates), topN, runtime);
    }

    private List<RetrievalDoc> mergeDocs(List<RetrievalDoc> docs) {
        Map<String, RetrievalDoc> merged = new LinkedHashMap<>();
        for (RetrievalDoc doc : docs) {
            RetrievalDoc current = merged.get(doc.chunkId());
            if (current == null || doc.score() > current.score()) {
                merged.put(doc.chunkId(), doc);
            }
        }
        return new ArrayList<>(merged.values());
    }

    private List<MemoryCandidate> mergeMemories(List<MemoryCandidate> memories) {
        Map<UUID, MemoryCandidate> merged = new LinkedHashMap<>();
        for (MemoryCandidate memory : memories) {
            MemoryCandidate current = merged.get(memory.memoryId());
            if (current == null || memory.similarity() > current.similarity()) {
                merged.put(memory.memoryId(), memory);
            }
        }
        return new ArrayList<>(merged.values());
    }

    private List<RetrievalDoc> rankDocs(
            String query,
            List<RetrievalDoc> candidates,
            int topK,
            RetrievalRuntime runtime
    ) {
        return candidates.stream()
                .map(doc -> new RetrievalDoc(
                        doc.documentId(),
                        doc.chunkId(),
                        doc.chunkText(),
                        fusedScore(query, doc.chunkText(), doc.score(), runtime)
                ))
                .sorted(Comparator
                        .comparingDouble(RetrievalDoc::score)
                        .reversed()
                        .thenComparing(RetrievalDoc::chunkId))
                .limit(Math.max(1, topK))
                .toList();
    }

    private List<MemoryCandidate> rankMemories(
            String query,
            List<MemoryCandidate> candidates,
            int topN,
            RetrievalRuntime runtime
    ) {
        return candidates.stream()
                .map(candidate -> withSimilarity(
                        candidate,
                        fusedScore(query, memoryLexicalText(candidate), candidate.similarity(), runtime)
                ))
                .sorted(Comparator
                        .comparingDouble(MemoryCandidate::similarity)
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

    private MemoryCandidate withSimilarity(MemoryCandidate source, double similarity) {
        return new MemoryCandidate(
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

    private String memoryLexicalText(MemoryCandidate candidate) {
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

    private double clampWeight(double value) {
        return Math.max(0.0d, Math.min(1.0d, value));
    }

    private double normalizeScore(double score) {
        return Math.max(0.0, Math.min(1.0, (score + 1.0) / 2.0));
    }

}
