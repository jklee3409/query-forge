package io.queryforge.backend.rag.eval.retrieval.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.queryforge.backend.rag.retrieval.candidate.dto.RetrievalDtos.RetrievalRuntime;
import io.queryforge.backend.rag.retrieval.candidate.service.DomainScopedRetrievalService;
import io.queryforge.backend.rag.retrieval.execution.dto.RagLlmCallCount;
import io.queryforge.backend.rag.retrieval.execution.dto.RetrievalExecutionDtos.ExecutedRewriteCandidate;
import io.queryforge.backend.rag.retrieval.execution.dto.RetrievalExecutionDtos.RawRetrievalRequest;
import io.queryforge.backend.rag.retrieval.execution.dto.RetrievalExecutionDtos.RetrievalMaterial;
import io.queryforge.backend.rag.retrieval.execution.dto.RetrievalExecutionDtos.RewriteExecutionRequest;
import io.queryforge.backend.rag.retrieval.execution.service.RagRetrievalExecutionService;
import io.queryforge.backend.rag.retrieval.execution.service.RagRetrievalExecutionService.ObservedRewrite;
import io.queryforge.backend.rag.retrieval.memory.model.MemoryCandidate;
import io.queryforge.backend.rag.retrieval.rewrite.dto.RewriteDtos.GenerationDiagnostics;
import io.queryforge.backend.rag.retrieval.rewrite.policy.RewriteDecisionPolicy;
import io.queryforge.backend.rag.retrieval.rewrite.policy.RewriteDecisionPolicy.Decision;
import io.queryforge.backend.rag.retrieval.rewrite.service.RewriteDomainContext;
import io.queryforge.backend.rag.runtime.configuration.dto.ChatConfigDtos.ChatRuntimeConfigResponse;
import io.queryforge.backend.rag.runtime.configuration.model.RagExecutionSettings;

import java.util.List;
import java.util.Set;

import static io.queryforge.backend.rag.eval.retrieval.service.EvalRuntimeConfigService.normalizeText;

final class OfficialRetrievalEval {

    private static final Set<String> SUPPORTED_MODES = Set.of("raw_only", "selective_rewrite", "anchor_aware_rewrite");

    private OfficialRetrievalEval() {
    }

    static boolean supports(String mode) {
        return SUPPORTED_MODES.contains(mode);
    }

    static Result execute(
            String mode,
            String query,
            ChatRuntimeConfigResponse config,
            RetrievalRuntime runtime,
            RagExecutionSettings settings,
            boolean includeTrace,
            DomainScopedRetrievalService retrieval,
            RagRetrievalExecutionService execution,
            ObjectMapper mapper
    ) {
        boolean rawOnly = "raw_only".equals(mode);
        boolean useAnchors = !rawOnly && config.rewriteAnchorInjectionEnabled();
        String preset = normalizeText(config.gatingPreset(), "full_gating");
        String embedding = retrieval.embeddingLiteral(query, runtime);
        List<MemoryCandidate> memories = rawOnly ? List.of() : retrieval.findMemoryCandidates(
                query, embedding, settings.memoryTopN(), preset, config, runtime
        );
        double memorySimilarity = memories.isEmpty() ? 0.0d : memories.getFirst().similarity();
        RetrievalMaterial raw = execution.executeRawRetrieval(new RawRetrievalRequest(
                query, embedding, settings.retrievalTopK(), settings.rerankTopN(), config.domainId(), runtime
        ), memorySimilarity);
        ObservedRewrite rewrite = rawOnly ? null : execution.executeObservedRewrite(new RewriteExecutionRequest(
                query, mapper.createObjectNode(), memories, settings.rewriteCandidateCount(),
                normalizeText(config.rewriteQueryProfile(), "compact_anchor"), RewriteDomainContext.create(mapper, config),
                settings.retrievalTopK(), settings.rerankTopN(), config.domainId(), runtime, memorySimilarity, useAnchors
        ));
        List<ExecutedRewriteCandidate> candidates = rewrite == null ? List.of() : rewrite.execution().candidates();
        GenerationDiagnostics generation = rewrite == null ? null : rewrite.generation().diagnostics();
        Decision decision = generation == null || generation.succeeded()
                ? RewriteDecisionPolicy.decide(mode, raw, candidates, settings.rewriteThreshold(), null)
                : new Decision(raw.query(), false, raw.rerankedDocs(), null,
                        generation.attempted() ? "rewrite_failed" : "rewrite_unavailable", generation.failureReason());
        int calls = generation == null ? 0 : generation.attemptCount();
        JsonNode trace = includeTrace
                ? trace(mapper, mode, raw, rewrite, decision, config, preset, useAnchors)
                : null;
        return new Result(decision, trace, new RagLlmCallCount(calls, 0, 0, calls));
    }

    private static JsonNode trace(
            ObjectMapper mapper,
            String mode,
            RetrievalMaterial raw,
            ObservedRewrite rewrite,
            Decision decision,
            ChatRuntimeConfigResponse config,
            String preset,
            boolean anchorsEnabled
    ) {
        ObjectNode trace = mapper.createObjectNode();
        trace.put("evalPolicyVersion", 2);
        trace.put("selectedMode", mode);
        trace.put("rewriteApplied", decision.rewriteApplied());
        trace.put("decisionReason", decision.selectedReason());
        trace.put("rejectedReason", decision.rejectedReason());
        trace.put("rawConfidence", raw.confidence());
        trace.put("rawLatencyMs", raw.latencyMs());
        trace.put("anchorInjectionEnabled", anchorsEnabled);
        trace.set("rawRetrievedChunkIds", mapper.valueToTree(raw.rerankedChunkIds()));
        ObjectNode snapshot = trace.putObject("snapshot");
        snapshot.put("gatingPreset", preset);
        snapshot.set("sourceGatingBatchIds", mapper.valueToTree(config.sourceGatingBatchIds()));
        snapshot.set("sourceGatingRunIds", mapper.valueToTree(config.sourceGatingRunIds()));
        snapshot.set("generationStrategies", mapper.valueToTree(config.generationStrategies()));
        ArrayNode candidates = trace.putArray("rewriteCandidates");
        trace.putNull("selectedCandidate");
        trace.putNull("bestCandidateConfidence");
        trace.putNull("generation");
        trace.putNull("rewriteLatencyMs");
        trace.putArray("memoryCandidates");
        ArrayNode anchors = trace.putArray("anchorCandidates");
        if (rewrite == null) {
            return trace;
        }
        trace.set("generation", mapper.valueToTree(rewrite.generation().diagnostics()));
        trace.put("rewriteLatencyMs", rewrite.execution().latencyMs());
        for (ExecutedRewriteCandidate candidate : rewrite.execution().candidates()) {
            ObjectNode row = candidates.addObject();
            row.put("index", candidate.index());
            row.put("label", candidate.label());
            row.put("query", candidate.query());
            row.put("confidence", candidate.confidence());
            row.put("latencyMs", candidate.retrieval().latencyMs());
            row.set("retrievedChunkIds", mapper.valueToTree(candidate.rerankedChunkIds()));
            if (candidate == decision.selectedCandidate()) {
                trace.set("selectedCandidate", row);
            }
        }
        rewrite.execution().candidates().stream().mapToDouble(ExecutedRewriteCandidate::confidence).max()
                .ifPresent(score -> trace.put("bestCandidateConfidence", score));
        JsonNode memories = rewrite.generation().promptMemories();
        if (memories != null && memories.isArray()) {
            trace.set("memoryCandidates", memories);
            collectAnchors(memories, anchors);
        }
        return trace;
    }

    private static void collectAnchors(JsonNode memories, ArrayNode anchors) {
        for (int index = 0; index < memories.size(); index++) {
            JsonNode memory = memories.get(index);
            for (JsonNode term : memory.path("glossary_terms")) {
                if (term.isTextual() && !term.textValue().isBlank()) {
                    addAnchor(anchors, term.textValue(), "glossary", null, index + 1);
                }
            }
            for (JsonNode term : memory.path("canonical_anchors")) {
                String form = term.path("canonical_form").asText("");
                String alias = term.path("display_alias").asText(form);
                if (!alias.isBlank()) {
                    addAnchor(anchors, alias, "canonical", form, index + 1);
                }
            }
        }
    }

    private static void addAnchor(ArrayNode anchors, String anchor, String source, String canonical, int memoryIndex) {
        ObjectNode row = anchors.addObject();
        row.put("anchor", anchor);
        row.put("source", source);
        row.put("canonical_form", canonical);
        row.put("source_memory_index", memoryIndex);
    }

    record Result(Decision decision, JsonNode trace, RagLlmCallCount llmCallCount) {
    }
}
