package io.queryforge.backend.admin.console.runtime;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;

public final class AdminRuntimeOptionsDtos {

    private AdminRuntimeOptionsDtos() {
    }

    public record RetrieverConfigRequest(
            String retrieverMode,
            String denseEmbeddingModel,
            Boolean denseEmbeddingRequired,
            Boolean denseFallbackEnabled,
            Boolean rerankEnabled,
            Integer candidatePoolK,
            Double denseWeight,
            Double bm25Weight,
            Double technicalWeight
    ) {
    }

    public record RuntimeOptionsResponse(
            List<String> llmModels,
            String defaultLlmModel,
            List<String> denseEmbeddingModels,
            String defaultDenseEmbeddingModel,
            List<String> retrievalBackends,
            String defaultRetrievalBackend,
            List<String> retrieverModes,
            List<String> rewriteFailurePolicies,
            List<String> rewriteQueryProfiles,
            String defaultRewriteQueryProfile,
            List<RuntimeOption> llmProviderOptions,
            List<RuntimeOption> llmModelOptions,
            List<RuntimeOption> denseEmbeddingModelOptions,
            List<RuntimeOption> retrievalBackendOptions,
            List<RuntimeOption> retrieverModeOptions,
            List<RuntimeOption> rewriteFailurePolicyOptions,
            List<RuntimeOption> rewriteQueryProfileOptions,
            Map<String, RuntimeParameterRange> defaultParameterRanges,
            String defaultRetrieverMode,
            JsonNode retrieverModeDefaults
    ) {
    }

    public record RuntimeOption(
            String code,
            String label,
            String provider,
            String status,
            String availability,
            String reason,
            Boolean defaultSelected
    ) {
    }

    public record RuntimeParameterRange(
            Double min,
            Double max,
            Double defaultValue
    ) {
    }
}
