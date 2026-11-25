package io.queryforge.backend.rag.trace.write;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.queryforge.backend.rag.retrieval.candidate.RetrievalDoc;
import io.queryforge.backend.rag.retrieval.memory.MemoryCandidate;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RagTraceRepository {

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    @Transactional
    public UUID createOnlineQuery(
            UUID domainId,
            String sessionId,
            String rawQuery,
            JsonNode sessionContextSnapshot,
            String rewriteStrategy,
            double threshold,
            JsonNode metadata
    ) {
        UUID onlineQueryId = UUID.randomUUID();
        String sql = """
                INSERT INTO online_queries (
                    online_query_id,
                    domain_id,
                    session_id,
                    raw_query,
                    rewrite_strategy,
                    session_context_snapshot,
                    threshold,
                    metadata
                ) VALUES (
                    :onlineQueryId,
                    :domainId,
                    :sessionId,
                    :rawQuery,
                    :rewriteStrategy,
                    CAST(:sessionContext AS jsonb),
                    :threshold,
                    CAST(:metadata AS jsonb)
                )
                """;
        jdbcTemplate.update(
                sql,
                new MapSqlParameterSource()
                        .addValue("onlineQueryId", onlineQueryId)
                        .addValue("domainId", domainId)
                        .addValue("sessionId", sessionId)
                        .addValue("rawQuery", rawQuery)
                        .addValue("rewriteStrategy", rewriteStrategy)
                        .addValue("sessionContext", Objects.requireNonNullElse(sessionContextSnapshot, objectMapper.createObjectNode()).toString())
                        .addValue("threshold", threshold)
                        .addValue("metadata", Objects.requireNonNullElse(metadata, objectMapper.createObjectNode()).toString())
        );
        return onlineQueryId;
    }

    @Transactional
    public UUID createRewriteCandidate(
            UUID onlineQueryId,
            int rank,
            String label,
            String query,
            JsonNode memorySourceIds,
            JsonNode retrievalTopKDocs,
            double confidenceScore,
            JsonNode scoreBreakdown
    ) {
        UUID rewriteCandidateId = UUID.randomUUID();
        String sql = """
                INSERT INTO rewrite_candidates (
                    rewrite_candidate_id,
                    online_query_id,
                    candidate_rank,
                    candidate_label,
                    candidate_query,
                    memory_source_ids,
                    retrieval_top_k_docs,
                    confidence_score,
                    adopted,
                    score_breakdown
                ) VALUES (
                    :rewriteCandidateId,
                    :onlineQueryId,
                    :rank,
                    :label,
                    :query,
                    CAST(:memorySourceIds AS jsonb),
                    CAST(:retrievalTopKDocs AS jsonb),
                    :confidenceScore,
                    FALSE,
                    CAST(:scoreBreakdown AS jsonb)
                )
                """;
        jdbcTemplate.update(
                sql,
                new MapSqlParameterSource()
                        .addValue("rewriteCandidateId", rewriteCandidateId)
                        .addValue("onlineQueryId", onlineQueryId)
                        .addValue("rank", rank)
                        .addValue("label", label)
                        .addValue("query", query)
                        .addValue("memorySourceIds", memorySourceIds.toString())
                        .addValue("retrievalTopKDocs", retrievalTopKDocs.toString())
                        .addValue("confidenceScore", confidenceScore)
                        .addValue("scoreBreakdown", scoreBreakdown.toString())
        );
        return rewriteCandidateId;
    }

    @Transactional
    public void markRewriteCandidateAdopted(UUID rewriteCandidateId, boolean adopted, String rejectedReason) {
        String sql = """
                UPDATE rewrite_candidates
                SET adopted = :adopted,
                    rejected_reason = :rejectedReason
                WHERE rewrite_candidate_id = :rewriteCandidateId
                """;
        jdbcTemplate.update(
                sql,
                new MapSqlParameterSource()
                        .addValue("rewriteCandidateId", rewriteCandidateId)
                        .addValue("adopted", adopted)
                        .addValue("rejectedReason", rejectedReason)
        );
    }

    @Transactional
    public void insertRetrievalResults(
            UUID onlineQueryId,
            UUID rewriteCandidateId,
            String scope,
            List<RetrievalDoc> docs,
            String mode
    ) {
        insertRetrievalResults(onlineQueryId, rewriteCandidateId, scope, docs, mode, "pgvector-hash-embedding", objectMapper.createObjectNode());
    }

    @Transactional
    public void insertRetrievalResults(
            UUID onlineQueryId,
            UUID rewriteCandidateId,
            String scope,
            List<RetrievalDoc> docs,
            String mode,
            String retrieverName,
            JsonNode metadata
    ) {
        String sql = """
                INSERT INTO retrieval_results (
                    online_query_id,
                    rewrite_candidate_id,
                    result_scope,
                    rank,
                    document_id,
                    chunk_id,
                    retriever_name,
                    score,
                    metadata
                ) VALUES (
                    :onlineQueryId,
                    :rewriteCandidateId,
                    :scope,
                    :rank,
                    :documentId,
                    :chunkId,
                    :retrieverName,
                    :score,
                    CAST(:metadata AS jsonb)
                )
                """;
        for (int index = 0; index < docs.size(); index++) {
            RetrievalDoc doc = docs.get(index);
            jdbcTemplate.update(
                    sql,
                    new MapSqlParameterSource()
                            .addValue("onlineQueryId", onlineQueryId)
                            .addValue("rewriteCandidateId", rewriteCandidateId)
                            .addValue("scope", scope)
                            .addValue("rank", index + 1)
                            .addValue("documentId", doc.documentId())
                            .addValue("chunkId", doc.chunkId())
                            .addValue("retrieverName", retrieverName)
                            .addValue("score", doc.score())
                            .addValue("metadata", retrievalMetadata(mode, metadata).toString())
            );
        }
    }

    @Transactional
    public void insertRerankResults(UUID onlineQueryId, UUID rewriteCandidateId, List<RetrievalDoc> docs, String modelName) {
        String sql = """
                INSERT INTO rerank_results (
                    online_query_id,
                    rewrite_candidate_id,
                    rank,
                    document_id,
                    chunk_id,
                    model_name,
                    relevance_score,
                    metadata
                ) VALUES (
                    :onlineQueryId,
                    :rewriteCandidateId,
                    :rank,
                    :documentId,
                    :chunkId,
                    :modelName,
                    :relevanceScore,
                    CAST(:metadata AS jsonb)
                )
                """;
        for (int index = 0; index < docs.size(); index++) {
            RetrievalDoc doc = docs.get(index);
            jdbcTemplate.update(
                    sql,
                    new MapSqlParameterSource()
                            .addValue("onlineQueryId", onlineQueryId)
                            .addValue("rewriteCandidateId", rewriteCandidateId)
                            .addValue("rank", index + 1)
                            .addValue("documentId", doc.documentId())
                            .addValue("chunkId", doc.chunkId())
                            .addValue("modelName", modelName)
                            .addValue("relevanceScore", doc.score())
                            .addValue("metadata", "{\"source\":\"backend\"}")
            );
        }
    }

    @Transactional
    public void upsertOnlineQueryDecision(
            UUID onlineQueryId,
            String finalQueryUsed,
            boolean rewriteApplied,
            JsonNode memoryTopN,
            Double rawScore,
            UUID selectedRewriteCandidateId,
            String selectedReason,
            String rejectedReason,
            JsonNode latencyBreakdown
    ) {
        String sql = """
                UPDATE online_queries
                SET final_query_used = :finalQueryUsed,
                    rewrite_applied = :rewriteApplied,
                    memory_top_n = CAST(:memoryTopN AS jsonb),
                    raw_score = :rawScore,
                    selected_rewrite_candidate_id = :selectedRewriteCandidateId,
                    selected_reason = :selectedReason,
                    rejected_reason = :rejectedReason,
                    latency_breakdown = CAST(:latencyBreakdown AS jsonb)
                WHERE online_query_id = :onlineQueryId
                """;
        jdbcTemplate.update(
                sql,
                new MapSqlParameterSource()
                        .addValue("onlineQueryId", onlineQueryId)
                        .addValue("finalQueryUsed", finalQueryUsed)
                        .addValue("rewriteApplied", rewriteApplied)
                        .addValue("memoryTopN", memoryTopN.toString())
                        .addValue("rawScore", rawScore)
                        .addValue("selectedRewriteCandidateId", selectedRewriteCandidateId)
                        .addValue("selectedReason", selectedReason)
                        .addValue("rejectedReason", rejectedReason)
                        .addValue("latencyBreakdown", latencyBreakdown.toString())
        );
    }

    @Transactional
    public void mergeOnlineQueryMetadata(UUID onlineQueryId, JsonNode metadata) {
        String sql = """
                UPDATE online_queries
                SET metadata = COALESCE(metadata, '{}'::jsonb) || CAST(:metadata AS jsonb)
                WHERE online_query_id = :onlineQueryId
                """;
        jdbcTemplate.update(
                sql,
                new MapSqlParameterSource()
                        .addValue("onlineQueryId", onlineQueryId)
                        .addValue("metadata", Objects.requireNonNullElse(metadata, objectMapper.createObjectNode()).toString())
        );
    }

    @Transactional
    public UUID createOnlineRewriteLog(
            UUID onlineQueryId,
            UUID runId,
            String rawQuery,
            String finalQuery,
            String rewriteStrategy,
            JsonNode generationMethodCodes,
            JsonNode generationBatchIds,
            boolean gatingApplied,
            String gatingPreset,
            boolean rewriteApplied,
            Boolean selectiveRewrite,
            Boolean useSessionContext,
            Double rawConfidence,
            Double selectedConfidence,
            Double confidenceDelta,
            String decisionReason,
            String rejectionReason,
            JsonNode metadata
    ) {
        UUID rewriteLogId = UUID.randomUUID();
        String sql = """
                INSERT INTO online_query_rewrite_log (
                    rewrite_log_id,
                    online_query_id,
                    run_id,
                    raw_query,
                    final_query,
                    rewrite_strategy,
                    generation_method_codes,
                    generation_batch_ids,
                    gating_applied,
                    gating_preset,
                    rewrite_applied,
                    selective_rewrite,
                    use_session_context,
                    raw_confidence,
                    selected_confidence,
                    confidence_delta,
                    decision_reason,
                    rejection_reason,
                    metadata_json
                ) VALUES (
                    :rewriteLogId,
                    :onlineQueryId,
                    :runId,
                    :rawQuery,
                    :finalQuery,
                    :rewriteStrategy,
                    CAST(:generationMethodCodes AS jsonb),
                    CAST(:generationBatchIds AS jsonb),
                    :gatingApplied,
                    :gatingPreset,
                    :rewriteApplied,
                    :selectiveRewrite,
                    :useSessionContext,
                    :rawConfidence,
                    :selectedConfidence,
                    :confidenceDelta,
                    :decisionReason,
                    :rejectionReason,
                    CAST(:metadata AS jsonb)
                )
                """;
        jdbcTemplate.update(
                sql,
                new MapSqlParameterSource()
                        .addValue("rewriteLogId", rewriteLogId)
                        .addValue("onlineQueryId", onlineQueryId)
                        .addValue("runId", runId)
                        .addValue("rawQuery", rawQuery)
                        .addValue("finalQuery", finalQuery)
                        .addValue("rewriteStrategy", rewriteStrategy)
                        .addValue("generationMethodCodes", Objects.requireNonNullElse(generationMethodCodes, objectMapper.createArrayNode()).toString())
                        .addValue("generationBatchIds", Objects.requireNonNullElse(generationBatchIds, objectMapper.createArrayNode()).toString())
                        .addValue("gatingApplied", gatingApplied)
                        .addValue("gatingPreset", gatingPreset)
                        .addValue("rewriteApplied", rewriteApplied)
                        .addValue("selectiveRewrite", selectiveRewrite)
                        .addValue("useSessionContext", useSessionContext)
                        .addValue("rawConfidence", rawConfidence)
                        .addValue("selectedConfidence", selectedConfidence)
                        .addValue("confidenceDelta", confidenceDelta)
                        .addValue("decisionReason", decisionReason)
                        .addValue("rejectionReason", rejectionReason)
                        .addValue("metadata", Objects.requireNonNullElse(metadata, objectMapper.createObjectNode()).toString())
        );
        return rewriteLogId;
    }

    @Transactional
    public void insertRewriteCandidateLog(
            UUID rewriteLogId,
            UUID onlineQueryId,
            UUID rewriteCandidateId,
            int candidateRank,
            String candidateLabel,
            String candidateQuery,
            Double confidenceScore,
            boolean selected,
            String rejectionReason,
            JsonNode retrievalTopKDocs,
            JsonNode scoreBreakdown,
            JsonNode metadata
    ) {
        String sql = """
                INSERT INTO rewrite_candidate_log (
                    rewrite_log_id,
                    online_query_id,
                    rewrite_candidate_id,
                    candidate_rank,
                    candidate_label,
                    candidate_query,
                    confidence_score,
                    selected,
                    rejection_reason,
                    retrieval_top_k_docs,
                    score_breakdown,
                    metadata_json
                ) VALUES (
                    :rewriteLogId,
                    :onlineQueryId,
                    :rewriteCandidateId,
                    :candidateRank,
                    :candidateLabel,
                    :candidateQuery,
                    :confidenceScore,
                    :selected,
                    :rejectionReason,
                    CAST(:retrievalTopKDocs AS jsonb),
                    CAST(:scoreBreakdown AS jsonb),
                    CAST(:metadata AS jsonb)
                )
                """;
        jdbcTemplate.update(
                sql,
                new MapSqlParameterSource()
                        .addValue("rewriteLogId", rewriteLogId)
                        .addValue("onlineQueryId", onlineQueryId)
                        .addValue("rewriteCandidateId", rewriteCandidateId)
                        .addValue("candidateRank", candidateRank)
                        .addValue("candidateLabel", candidateLabel)
                        .addValue("candidateQuery", candidateQuery)
                        .addValue("confidenceScore", confidenceScore)
                        .addValue("selected", selected)
                        .addValue("rejectionReason", rejectionReason)
                        .addValue("retrievalTopKDocs", Objects.requireNonNullElse(retrievalTopKDocs, objectMapper.createArrayNode()).toString())
                        .addValue("scoreBreakdown", Objects.requireNonNullElse(scoreBreakdown, objectMapper.createObjectNode()).toString())
                        .addValue("metadata", Objects.requireNonNullElse(metadata, objectMapper.createObjectNode()).toString())
        );
    }

    @Transactional
    public void insertMemoryRetrievalLog(
            UUID rewriteLogId,
            UUID onlineQueryId,
            int retrievalRank,
            MemoryCandidate candidate,
            JsonNode metadata
    ) {
        String sql = """
                INSERT INTO memory_retrieval_log (
                    rewrite_log_id,
                    online_query_id,
                    memory_id,
                    retrieval_rank,
                    similarity,
                    query_text,
                    target_doc_id,
                    target_chunk_ids,
                    generation_strategy,
                    metadata_json
                ) VALUES (
                    :rewriteLogId,
                    :onlineQueryId,
                    :memoryId,
                    :retrievalRank,
                    :similarity,
                    :queryText,
                    :targetDocId,
                    CAST(:targetChunkIds AS jsonb),
                    :generationStrategy,
                    CAST(:metadata AS jsonb)
                )
                """;
        jdbcTemplate.update(
                sql,
                new MapSqlParameterSource()
                        .addValue("rewriteLogId", rewriteLogId)
                        .addValue("onlineQueryId", onlineQueryId)
                        .addValue("memoryId", candidate.memoryId())
                        .addValue("retrievalRank", retrievalRank)
                        .addValue("similarity", candidate.similarity())
                        .addValue("queryText", candidate.queryText())
                        .addValue("targetDocId", candidate.targetDocId())
                        .addValue("targetChunkIds", Objects.requireNonNullElse(candidate.targetChunkIds(), objectMapper.createArrayNode()).toString())
                        .addValue("generationStrategy", candidate.generationStrategy())
                        .addValue("metadata", Objects.requireNonNullElse(metadata, objectMapper.createObjectNode()).toString())
        );
    }

    @Transactional
    public void insertAnswer(
            UUID onlineQueryId,
            String answer,
            JsonNode citedDocumentIds,
            JsonNode citedChunkIds,
            String generationModel,
            JsonNode metadata
    ) {
        String sql = """
                INSERT INTO answers (
                    online_query_id,
                    answer_text,
                    cited_document_ids,
                    cited_chunk_ids,
                    generation_model,
                    metadata
                ) VALUES (
                    :onlineQueryId,
                    :answer,
                    CAST(:citedDocumentIds AS jsonb),
                    CAST(:citedChunkIds AS jsonb),
                    :generationModel,
                    CAST(:metadata AS jsonb)
                )
                ON CONFLICT (online_query_id) DO UPDATE
                SET answer_text = EXCLUDED.answer_text,
                    cited_document_ids = EXCLUDED.cited_document_ids,
                    cited_chunk_ids = EXCLUDED.cited_chunk_ids,
                    generation_model = EXCLUDED.generation_model,
                    metadata = EXCLUDED.metadata
                """;
        jdbcTemplate.update(
                sql,
                new MapSqlParameterSource()
                        .addValue("onlineQueryId", onlineQueryId)
                        .addValue("answer", answer)
                        .addValue("citedDocumentIds", citedDocumentIds.toString())
                        .addValue("citedChunkIds", citedChunkIds.toString())
                        .addValue("generationModel", generationModel == null || generationModel.isBlank() ? "gemini-2.5-flash-lite" : generationModel)
                        .addValue("metadata", Objects.requireNonNullElse(metadata, objectMapper.createObjectNode()).toString())
        );
    }

    private JsonNode retrievalMetadata(String mode, JsonNode metadata) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("mode", mode);
        if (metadata != null && metadata.isObject()) {
            node.setAll((ObjectNode) metadata);
        }
        return node;
    }
}
