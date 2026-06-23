package io.queryforge.backend.rag.service;

import io.queryforge.backend.rag.model.ChatRuntimeDtos;
import io.queryforge.backend.rag.repository.RagRepository;
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

@Service
@RequiredArgsConstructor
public class DomainScopedRetrievalService {

    private static final Pattern SEARCH_TOKEN_PATTERN = Pattern.compile("[@A-Za-z0-9_./:$-]{2,}|\\p{InHangulSyllables}{2,}");
    private static final Pattern TECHNICAL_TOKEN_PATTERN = Pattern.compile("[@A-Za-z_][A-Za-z0-9_./:$-]{1,}");

    private final RagRepository repository;
    private final HashEmbeddingService embeddingService;
    private final DenseEmbeddingService denseEmbeddingService;

    public RetrievalRuntime retrievalRuntime(ChatRuntimeDtos.ChatRuntimeConfigResponse config) {
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

    public List<RagRepository.RetrievalDoc> retrieveChunks(
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

    public List<RagRepository.MemoryCandidate> findMemoryCandidates(
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
            return repository.findMemoryTopN(
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
        List<RagRepository.MemoryCandidate> candidates = new ArrayList<>();
        if (!"bm25_only".equals(runtime.retrieverMode())) {
            candidates.addAll(repository.findMemoryDensePool(
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
            candidates.addAll(repository.findMemoryTextPool(
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

    private double clampWeight(double value) {
        return Math.max(0.0d, Math.min(1.0d, value));
    }

    private double normalizeScore(double score) {
        return Math.max(0.0, Math.min(1.0, (score + 1.0) / 2.0));
    }

    public record RetrievalRuntime(
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
}
