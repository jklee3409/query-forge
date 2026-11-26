package io.queryforge.backend.admin.console.runtime.service;

import io.queryforge.backend.admin.console.runtime.dto.AdminRuntimeOptionsDtos.RuntimeCatalog;
import io.queryforge.backend.admin.console.runtime.dto.AdminRuntimeOptionsDtos.RuntimeOptionMetadata;
import io.queryforge.backend.admin.console.runtime.dto.AdminRuntimeOptionsDtos;
import io.queryforge.backend.rag.retrieval.candidate.dto.RetrievalDtos.RetrievalRuntime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static io.queryforge.backend.admin.console.configuration.support.ConsoleParameters.asTrimmedString;
import static io.queryforge.backend.admin.console.configuration.support.ConsoleParameters.blankToNull;
import static io.queryforge.backend.admin.console.configuration.support.ConsoleParameters.firstNonBlank;
import static io.queryforge.backend.rag.retrieval.candidate.dto.RetrievalDtos.DEFAULT_DENSE_EMBEDDING_MODEL;
import static io.queryforge.backend.rag.retrieval.candidate.dto.RetrievalDtos.DEFAULT_DENSE_WEIGHT;
import static io.queryforge.backend.rag.retrieval.candidate.dto.RetrievalDtos.DEFAULT_BM25_WEIGHT;
import static io.queryforge.backend.rag.retrieval.candidate.dto.RetrievalDtos.DEFAULT_TECHNICAL_WEIGHT;

@Component
@RequiredArgsConstructor
public class AdminRetrieverConfiguration {

    private final AdminRuntimeOptionsService catalogService;
    private static final String RETRIEVAL_BACKEND_LOCAL = "local";
    private static final String RETRIEVAL_BACKEND_DB_ANN = "db_ann";
    private static final String RETRIEVER_MODE_BM25_ONLY = "bm25_only";
    private static final String RETRIEVER_MODE_DENSE_ONLY = "dense_only";
    private static final String RETRIEVER_MODE_HYBRID = "hybrid";
    private static final int DEFAULT_RETRIEVER_CANDIDATE_POOL_K = 50;

    public void validateRetrieverSelection(Map<String, Object> retrieverConfig, RuntimeCatalog catalog) {
        String retrieverMode = catalogService.normalizeRetrieverMode(asTrimmedString(retrieverConfig.get("retriever_mode")));
        RuntimeOptionMetadata retrieverModeOption = catalog.findRetrieverMode(retrieverMode);
        if (retrieverModeOption == null) {
            throw new IllegalArgumentException("retriever_mode is not allowed by catalog: " + retrieverMode);
        }
        if (!catalogService.isSelectable(retrieverModeOption)) {
            throw new IllegalArgumentException(
                    "retriever_mode is not selectable by catalog: " + retrieverMode + catalogService.appendReason(retrieverModeOption.reason())
            );
        }
        if (RETRIEVER_MODE_BM25_ONLY.equals(retrieverMode)) {
            return;
        }
        String denseModel = blankToNull(asTrimmedString(retrieverConfig.get("dense_embedding_model")));
        if (denseModel == null) {
            throw new IllegalArgumentException("dense_embedding_model is required for retriever_mode=" + retrieverMode);
        }
        RuntimeOptionMetadata denseOption = catalog.findDenseEmbeddingModel(denseModel);
        if (denseOption == null) {
            throw new IllegalArgumentException("dense_embedding_model is not allowed by catalog: " + denseModel);
        }
        if (!catalogService.isSelectable(denseOption)) {
            throw new IllegalArgumentException(
                    "dense_embedding_model is not selectable by catalog: " + denseModel + catalogService.appendReason(denseOption.reason())
            );
        }
    }

    public void validateRetrievalBackendSelection(String retrievalBackend, RuntimeCatalog catalog) {
        RuntimeOptionMetadata backendOption = catalog.findRetrievalBackend(retrievalBackend);
        if (backendOption == null) {
            throw new IllegalArgumentException("retrieval_backend is not allowed by catalog: " + retrievalBackend);
        }
        if (!catalogService.isSelectable(backendOption)) {
            throw new IllegalArgumentException(
                    "retrieval_backend is not selectable by catalog: " + retrievalBackend + catalogService.appendReason(backendOption.reason())
            );
        }
    }

    public Map<String, Object> resolveRetrieverConfig(AdminRuntimeOptionsDtos.RetrieverConfigRequest request) {
        return resolveRetrieverConfig(request, false, catalogService.loadRuntimeCatalog());
    }

    public Map<String, Object> resolveRetrieverConfig(
            AdminRuntimeOptionsDtos.RetrieverConfigRequest request,
            boolean fixedModePreset
    ) {
        return resolveRetrieverConfig(request, fixedModePreset, catalogService.loadRuntimeCatalog());
    }

    public Map<String, Object> resolveRetrieverConfig(
            AdminRuntimeOptionsDtos.RetrieverConfigRequest request,
            boolean fixedModePreset,
            RuntimeCatalog runtimeCatalog
    ) {
        String defaultMode = firstNonBlank(runtimeCatalog.defaultRetrieverMode(), RETRIEVER_MODE_HYBRID);
        String mode = catalogService.normalizeRetrieverMode(request == null ? defaultMode : firstNonBlank(request.retrieverMode(), defaultMode));
        Map<String, Object> modeDefaults = runtimeCatalog.retrieverModeDefault(mode);
        String denseModel;
        if (fixedModePreset || request == null || request.denseEmbeddingModel() == null || request.denseEmbeddingModel().isBlank()) {
            denseModel = firstNonBlank(runtimeCatalog.defaultDenseEmbeddingModel(), DEFAULT_DENSE_EMBEDDING_MODEL);
        } else {
            denseModel = request.denseEmbeddingModel().trim();
        }
        boolean denseRequired;
        if (fixedModePreset || request == null || request.denseEmbeddingRequired() == null) {
            denseRequired = catalogService.parseBooleanOrDefault(
                    modeDefaults.get("dense_embedding_required"),
                    !RETRIEVER_MODE_BM25_ONLY.equals(mode)
            );
        } else {
            denseRequired = request.denseEmbeddingRequired();
        }
        if (RETRIEVER_MODE_BM25_ONLY.equals(mode)) {
            denseRequired = false;
        }
        boolean denseFallbackEnabled = fixedModePreset
                ? false
                : request == null || request.denseFallbackEnabled() == null
                ? catalogService.parseBooleanOrDefault(modeDefaults.get("dense_fallback_enabled"), false)
                : request.denseFallbackEnabled();
        boolean rerankEnabled = fixedModePreset
                ? false
                : request == null || request.rerankEnabled() == null
                ? catalogService.parseBooleanOrDefault(modeDefaults.get("rerank_enabled"), false)
                : request.rerankEnabled();
        int candidatePoolK = fixedModePreset || request == null || request.candidatePoolK() == null
                ? catalogService.intDefault(
                modeDefaults.get("retriever_candidate_pool_k"),
                catalogService.runtimeDefaultInt(runtimeCatalog, "retriever_candidate_pool_k", DEFAULT_RETRIEVER_CANDIDATE_POOL_K)
        )
                : RetrievalRuntime.validateCandidatePoolK(request.candidatePoolK(), "retriever_candidate_pool_k");

        Map<?, ?> defaultWeights = modeDefaults.get("retriever_fusion_weights") instanceof Map<?, ?> weights
                ? weights
                : Map.of();
        double defaultDenseWeight = catalogService.doubleDefault(defaultWeights.get("dense"), DEFAULT_DENSE_WEIGHT);
        double defaultBm25Weight = catalogService.doubleDefault(defaultWeights.get("bm25"), DEFAULT_BM25_WEIGHT);
        double defaultTechnicalWeight = catalogService.doubleDefault(defaultWeights.get("technical"), DEFAULT_TECHNICAL_WEIGHT);
        double denseWeight = fixedModePreset || request == null || request.denseWeight() == null
                ? defaultDenseWeight
                : RetrievalRuntime.validateFusionWeight(request.denseWeight(), "retriever_dense_weight");
        double bm25Weight = fixedModePreset || request == null || request.bm25Weight() == null
                ? defaultBm25Weight
                : RetrievalRuntime.validateFusionWeight(request.bm25Weight(), "retriever_bm25_weight");
        double technicalWeight = fixedModePreset || request == null || request.technicalWeight() == null
                ? defaultTechnicalWeight
                : RetrievalRuntime.validateFusionWeight(request.technicalWeight(), "retriever_technical_weight");
        if (RETRIEVER_MODE_BM25_ONLY.equals(mode)) {
            denseWeight = 0.0d;
            bm25Weight = 1.0d;
            technicalWeight = 0.0d;
        } else if (RETRIEVER_MODE_DENSE_ONLY.equals(mode)) {
            denseWeight = 1.0d;
            bm25Weight = 0.0d;
            technicalWeight = 0.0d;
        } else {
            double sum = denseWeight + bm25Weight + technicalWeight;
            if (sum <= 0.0d) {
                denseWeight = defaultDenseWeight;
                bm25Weight = defaultBm25Weight;
                technicalWeight = defaultTechnicalWeight;
            } else {
                denseWeight = denseWeight / sum;
                bm25Weight = bm25Weight / sum;
                technicalWeight = technicalWeight / sum;
            }
        }

        Map<String, Double> fusionWeights = new LinkedHashMap<>();
        fusionWeights.put("dense", denseWeight);
        fusionWeights.put("bm25", bm25Weight);
        fusionWeights.put("technical", technicalWeight);

        Map<String, Object> retrieverConfig = new LinkedHashMap<>();
        retrieverConfig.put("retriever_mode", mode);
        retrieverConfig.put("dense_embedding_model", denseModel);
        retrieverConfig.put("dense_embedding_required", denseRequired);
        retrieverConfig.put("dense_fallback_enabled", denseFallbackEnabled);
        retrieverConfig.put("dense_embedding_device", "cpu");
        retrieverConfig.put("dense_embedding_batch_size", 32);
        retrieverConfig.put("rerank_enabled", rerankEnabled);
        retrieverConfig.put("retriever_candidate_pool_k", candidatePoolK);
        retrieverConfig.put("candidate_pool_k", candidatePoolK);
        retrieverConfig.put("retriever_fusion_weights", fusionWeights);
        return retrieverConfig;
    }

    public String normalizeRetrievalBackend(String value) {
        return normalizeRetrievalBackend(value, catalogService.loadRuntimeCatalog());
    }

    public String normalizeRetrievalBackend(String value, RuntimeCatalog runtimeCatalog) {
        if (value == null || value.isBlank()) {
            return firstNonBlank(runtimeCatalog.defaultRetrievalBackend(), RETRIEVAL_BACKEND_LOCAL);
        }
        String normalized = value.trim().toLowerCase().replace("-", "_");
        if (!List.of(RETRIEVAL_BACKEND_LOCAL, RETRIEVAL_BACKEND_DB_ANN).contains(normalized)) {
            throw new IllegalArgumentException("unsupported retrieval_backend: " + value);
        }
        return normalized;
    }

    public void attachRetrieverConfig(Map<String, Object> config, Map<String, Object> retrieverConfig) {
        config.put("retriever_config", retrieverConfig);
        config.put("retriever_mode", retrieverConfig.get("retriever_mode"));
        config.put("dense_embedding_model", retrieverConfig.get("dense_embedding_model"));
        config.put("dense_embedding_required", retrieverConfig.get("dense_embedding_required"));
        config.put("dense_fallback_enabled", retrieverConfig.get("dense_fallback_enabled"));
        config.put("dense_embedding_device", retrieverConfig.get("dense_embedding_device"));
        config.put("dense_embedding_batch_size", retrieverConfig.get("dense_embedding_batch_size"));
        config.put("rerank_enabled", retrieverConfig.get("rerank_enabled"));
        config.put("retriever_candidate_pool_k", retrieverConfig.get("retriever_candidate_pool_k"));
        config.put("retriever_fusion_weights", retrieverConfig.get("retriever_fusion_weights"));
    }
}
