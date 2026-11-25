package io.queryforge.backend.admin.console.runtime.dto;

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

    public record RuntimeCatalog(
            List<RuntimeOptionMetadata> llmProviderOptions,
            List<RuntimeOptionMetadata> llmModelOptions,
            List<RuntimeOptionMetadata> denseEmbeddingModelOptions,
            List<RuntimeOptionMetadata> retrievalBackendOptions,
            List<RuntimeOptionMetadata> retrieverModeOptions,
            List<RuntimeOptionMetadata> rewriteFailurePolicyOptions,
            List<RuntimeOptionMetadata> rewriteQueryProfileOptions,
            Map<String, AdminRuntimeOptionsDtos.RuntimeParameterRange> defaultParameterRanges,
            Map<String, Map<String, Object>> retrieverModeDefaults
    ) {
        public List<String> availableLlmModels() {
            return llmModelOptions.stream()
                    .filter(this::isSelectable)
                    .map(RuntimeOptionMetadata::code)
                    .toList();
        }

        public List<String> availableDenseEmbeddingModels() {
            return denseEmbeddingModelOptions.stream()
                    .filter(this::isSelectable)
                    .map(RuntimeOptionMetadata::code)
                    .toList();
        }

        public List<String> availableRetrievalBackends() {
            return retrievalBackendOptions.stream()
                    .filter(this::isSelectable)
                    .map(RuntimeOptionMetadata::code)
                    .toList();
        }

        public List<String> availableRetrieverModes() {
            return retrieverModeOptions.stream()
                    .filter(this::isSelectable)
                    .map(RuntimeOptionMetadata::code)
                    .toList();
        }

        public List<String> availableRewriteFailurePolicies() {
            return rewriteFailurePolicyOptions.stream()
                    .filter(this::isSelectable)
                    .map(RuntimeOptionMetadata::code)
                    .toList();
        }

        public List<String> availableRewriteQueryProfiles() {
            return rewriteQueryProfileOptions.stream()
                    .filter(this::isSelectable)
                    .map(RuntimeOptionMetadata::code)
                    .toList();
        }

        public String defaultLlmModel() {
            return defaultCode(llmModelOptions);
        }

        public String defaultDenseEmbeddingModel() {
            return defaultCode(denseEmbeddingModelOptions);
        }

        public String defaultRetrievalBackend() {
            return defaultCode(retrievalBackendOptions);
        }

        public String defaultRetrieverMode() {
            return defaultCode(retrieverModeOptions);
        }

        public String defaultRewriteQueryProfile() {
            return defaultCode(rewriteQueryProfileOptions);
        }

        public Map<String, Object> retrieverModeDefault(String mode) {
            if (mode == null || mode.isBlank()) {
                return Map.of();
            }
            String normalized = mode.trim().toLowerCase().replace("-", "_");
            return retrieverModeDefaults.getOrDefault(normalized, Map.of());
        }

        public RuntimeOptionMetadata findLlmProvider(String provider) {
            return findExact(llmProviderOptions, provider);
        }

        public RuntimeOptionMetadata findLlmModel(String model) {
            return findExact(llmModelOptions, model);
        }

        public RuntimeOptionMetadata findDenseEmbeddingModel(String model) {
            return findExact(denseEmbeddingModelOptions, model);
        }

        public RuntimeOptionMetadata findRetrievalBackend(String backend) {
            return findNormalized(retrievalBackendOptions, backend);
        }

        public RuntimeOptionMetadata findRetrieverMode(String mode) {
            return findNormalized(retrieverModeOptions, mode);
        }

        public RuntimeOptionMetadata findRewriteFailurePolicy(String policy) {
            return findNormalized(rewriteFailurePolicyOptions, policy);
        }

        public RuntimeOptionMetadata findRewriteQueryProfile(String profile) {
            return findNormalized(rewriteQueryProfileOptions, profile);
        }

        private String defaultCode(List<RuntimeOptionMetadata> options) {
            RuntimeOptionMetadata explicit = options.stream()
                    .filter(option -> option.defaultSelected() && isSelectable(option))
                    .findFirst()
                    .orElse(null);
            if (explicit != null) {
                return explicit.code();
            }
            RuntimeOptionMetadata fallback = options.stream().filter(this::isSelectable).findFirst().orElse(null);
            return fallback == null ? "" : fallback.code();
        }

        private RuntimeOptionMetadata findExact(List<RuntimeOptionMetadata> options, String code) {
            if (code == null || code.isBlank()) {
                return null;
            }
            return options.stream()
                    .filter(option -> code.equals(option.code()))
                    .findFirst()
                    .orElse(null);
        }

        private RuntimeOptionMetadata findNormalized(List<RuntimeOptionMetadata> options, String code) {
            if (code == null || code.isBlank()) {
                return null;
            }
            String normalized = code.trim().toLowerCase().replace("-", "_");
            return options.stream()
                    .filter(option -> normalized.equals(option.code().toLowerCase().replace("-", "_")))
                    .findFirst()
                    .orElse(null);
        }

        private boolean isSelectable(RuntimeOptionMetadata option) {
            String availability = option.availability() == null ? "available" : option.availability().toLowerCase();
            String status = option.status() == null ? "active" : option.status().toLowerCase();
            if (List.of("unavailable", "disabled", "blocked").contains(availability)) {
                return false;
            }
            return !List.of("disabled", "blocked").contains(status);
        }
    }

    public record RuntimeOptionMetadata(
            String code,
            String label,
            String provider,
            String status,
            String availability,
            String reason,
            boolean defaultSelected
    ) {
    }
}
