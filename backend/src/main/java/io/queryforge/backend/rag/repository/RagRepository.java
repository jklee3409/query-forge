package io.queryforge.backend.rag.repository;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RagRepository {

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public record ChunkSource(String chunkId, String documentId, String chunkText) {
    }

    public record RetrievalDoc(String documentId, String chunkId, String chunkText, double score) {
    }

    public record MemoryCandidate(
            UUID memoryId,
            String queryText,
            String targetDocId,
            JsonNode targetChunkIds,
            double similarity,
            String generationStrategy,
            UUID generationBatchId
    ) {
    }

    public record MemorySource(UUID memoryId, String queryText) {
    }

    public record RewriteCandidateRow(
            UUID rewriteCandidateId,
            int candidateRank,
            String candidateLabel,
            String candidateQuery,
            JsonNode retrievalTopKDocs,
            Double confidenceScore,
            Boolean adopted,
            String rejectedReason,
            JsonNode scoreBreakdown
    ) {
    }

    public record OnlineQueryRow(
            UUID onlineQueryId,
            String rawQuery,
            String finalQueryUsed,
            Boolean rewriteApplied,
            String rewriteStrategy,
            JsonNode sessionContextSnapshot,
            JsonNode memoryTopN,
            Double rawScore,
            UUID selectedRewriteCandidateId,
            String selectedReason,
            String rejectedReason,
            Double threshold,
            JsonNode latencyBreakdown
    ) {
    }

    public record ExperimentRunSummary(
            UUID experimentRunId,
            String experimentKey,
            String status,
            Instant startedAt,
            Instant finishedAt,
            JsonNode parameters,
            JsonNode metrics,
            String notes
    ) {
    }

    public List<ChunkSource> findAllChunksForEmbedding() {
        String sql = """
                SELECT chunk_id, document_id, chunk_text
                FROM corpus_chunks
                ORDER BY document_id, chunk_index_in_document
                """;
        return jdbcTemplate.query(sql, chunkSourceRowMapper());
    }

    @Transactional
    public void upsertChunkEmbedding(String chunkId, String embeddingLiteral, String embeddingModel) {
        String sql = """
                INSERT INTO query_embeddings (
                    owner_type,
                    owner_id,
                    embedding_model,
                    embedding_dim,
                    embedding,
                    metadata
                ) VALUES (
                    'chunk',
                    :chunkId,
                    :embeddingModel,
                    3072,
                    CAST(:embedding AS halfvec),
                    CAST(:metadata AS jsonb)
                )
                ON CONFLICT (owner_type, owner_id, embedding_model) DO UPDATE
                SET embedding = EXCLUDED.embedding,
                    metadata = EXCLUDED.metadata
                """;
        jdbcTemplate.update(
                sql,
                new MapSqlParameterSource()
                        .addValue("chunkId", chunkId)
                        .addValue("embeddingModel", embeddingModel)
                        .addValue("embedding", embeddingLiteral)
                        .addValue("metadata", "{\"source\":\"api-admin-reindex\"}")
        );
    }

    public List<MemoryCandidate> findMemoryTopN(String queryEmbeddingLiteral, int topN, String gatingPreset) {
        String sql = """
                SELECT m.memory_id,
                       m.query_text,
                       m.target_doc_id,
                       m.target_chunk_ids::text AS target_chunk_ids,
                       1 - (m.query_embedding <=> CAST(:embedding AS halfvec)) AS similarity,
                       m.generation_strategy,
                       r.generation_batch_id
                FROM memory_entries m
                JOIN synthetic_queries_gated g ON g.gated_query_id = m.source_gated_query_id
                LEFT JOIN synthetic_queries_raw_all r ON r.synthetic_query_id = g.synthetic_query_id
                WHERE m.query_embedding IS NOT NULL
                  AND (:gatingPreset IS NULL OR g.gating_preset = :gatingPreset)
                ORDER BY m.query_embedding <=> CAST(:embedding AS halfvec)
                LIMIT :topN
                """;
        return jdbcTemplate.query(
                sql,
                new MapSqlParameterSource()
                        .addValue("embedding", queryEmbeddingLiteral)
                        .addValue("topN", topN)
                        .addValue("gatingPreset", gatingPreset),
                (rs, rowNum) -> new MemoryCandidate(
                        readUuid(rs, "memory_id"),
                        rs.getString("query_text"),
                        rs.getString("target_doc_id"),
                        readJson(rs, "target_chunk_ids"),
                        rs.getDouble("similarity"),
                        rs.getString("generation_strategy"),
                        readUuid(rs, "generation_batch_id")
                )
        );
    }

    public List<MemorySource> findAllMemorySources() {
        String sql = """
                SELECT memory_id, query_text
                FROM memory_entries
                ORDER BY created_at DESC
                """;
        return jdbcTemplate.query(
                sql,
                (rs, rowNum) -> new MemorySource(
                        readUuid(rs, "memory_id"),
                        rs.getString("query_text")
                )
        );
    }

    @Transactional
    public void updateMemoryEmbedding(UUID memoryId, String embeddingLiteral, String embeddingModel) {
        String updateMemorySql = """
                UPDATE memory_entries
                SET query_embedding = CAST(:embedding AS halfvec)
                WHERE memory_id = :memoryId
                """;
        jdbcTemplate.update(
                updateMemorySql,
                new MapSqlParameterSource()
                        .addValue("memoryId", memoryId)
                        .addValue("embedding", embeddingLiteral)
        );

        String upsertEmbeddingSql = """
                INSERT INTO query_embeddings (
                    owner_type,
                    owner_id,
                    embedding_model,
                    embedding_dim,
                    embedding,
                    metadata
                ) VALUES (
                    'memory',
                    :memoryId,
                    :embeddingModel,
                    3072,
                    CAST(:embedding AS halfvec),
                    CAST(:metadata AS jsonb)
                )
                ON CONFLICT (owner_type, owner_id, embedding_model) DO UPDATE
                SET embedding = EXCLUDED.embedding,
                    metadata = EXCLUDED.metadata
                """;
        jdbcTemplate.update(
                upsertEmbeddingSql,
                new MapSqlParameterSource()
                        .addValue("memoryId", memoryId.toString())
                        .addValue("embeddingModel", embeddingModel)
                        .addValue("embedding", embeddingLiteral)
                        .addValue("metadata", "{\"source\":\"api-admin-reindex\"}")
        );
    }

    public List<RetrievalDoc> findTopChunksByEmbedding(String queryEmbeddingLiteral, int topK) {
        String sql = """
                SELECT c.document_id,
                       c.chunk_id,
                       c.chunk_text,
                       1 - (qe.embedding <=> CAST(:embedding AS halfvec)) AS score
                FROM query_embeddings qe
                JOIN corpus_chunks c ON c.chunk_id = qe.owner_id
                WHERE qe.owner_type = 'chunk'
                  AND qe.embedding_model = 'hash-embedding-v1'
                ORDER BY qe.embedding <=> CAST(:embedding AS halfvec)
                LIMIT :topK
                """;
        return jdbcTemplate.query(
                sql,
                new MapSqlParameterSource()
                        .addValue("embedding", queryEmbeddingLiteral)
                        .addValue("topK", topK),
                (rs, rowNum) -> new RetrievalDoc(
                        rs.getString("document_id"),
                        rs.getString("chunk_id"),
                        rs.getString("chunk_text"),
                        rs.getDouble("score")
                )
        );
    }

    @Transactional
    public UUID createOnlineQuery(
            String sessionId,
            String rawQuery,
            JsonNode sessionContextSnapshot,
            String rewriteStrategy,
            double threshold
    ) {
        UUID onlineQueryId = UUID.randomUUID();
        String sql = """
                INSERT INTO online_queries (
                    online_query_id,
                    session_id,
                    raw_query,
                    rewrite_strategy,
                    session_context_snapshot,
                    threshold
                ) VALUES (
                    :onlineQueryId,
                    :sessionId,
                    :rawQuery,
                    :rewriteStrategy,
                    CAST(:sessionContext AS jsonb),
                    :threshold
                )
                """;
        jdbcTemplate.update(
                sql,
                new MapSqlParameterSource()
                        .addValue("onlineQueryId", onlineQueryId)
                        .addValue("sessionId", sessionId)
                        .addValue("rawQuery", rawQuery)
                        .addValue("rewriteStrategy", rewriteStrategy)
                        .addValue("sessionContext", Objects.requireNonNullElse(sessionContextSnapshot, objectMapper.createObjectNode()).toString())
                        .addValue("threshold", threshold)
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
                            .addValue("retrieverName", "pgvector-hash-embedding")
                            .addValue("score", doc.score())
                            .addValue("metadata", "{\"mode\":\"" + mode + "\"}")
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
    public void insertAnswer(UUID onlineQueryId, String answer, JsonNode citedDocumentIds, JsonNode citedChunkIds) {
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
                        .addValue("generationModel", "extractive-answer-simulated")
                        .addValue("metadata", "{\"source\":\"backend\"}")
        );
    }

    public Optional<OnlineQueryRow> findOnlineQuery(UUID onlineQueryId) {
        String sql = """
                SELECT online_query_id,
                       raw_query,
                       final_query_used,
                       rewrite_applied,
                       rewrite_strategy,
                       session_context_snapshot::text AS session_context_snapshot,
                       memory_top_n::text AS memory_top_n,
                       raw_score,
                       selected_rewrite_candidate_id,
                       selected_reason,
                       rejected_reason,
                       threshold,
                       latency_breakdown::text AS latency_breakdown
                FROM online_queries
                WHERE online_query_id = :onlineQueryId
                """;
        List<OnlineQueryRow> rows = jdbcTemplate.query(
                sql,
                new MapSqlParameterSource("onlineQueryId", onlineQueryId),
                (rs, rowNum) -> new OnlineQueryRow(
                        readUuid(rs, "online_query_id"),
                        rs.getString("raw_query"),
                        rs.getString("final_query_used"),
                        rs.getBoolean("rewrite_applied"),
                        rs.getString("rewrite_strategy"),
                        readJson(rs, "session_context_snapshot"),
                        readJson(rs, "memory_top_n"),
                        rs.getObject("raw_score", Double.class),
                        readUuid(rs, "selected_rewrite_candidate_id"),
                        rs.getString("selected_reason"),
                        rs.getString("rejected_reason"),
                        rs.getObject("threshold", Double.class),
                        readJson(rs, "latency_breakdown")
                )
        );
        return rows.stream().findFirst();
    }

    public List<RewriteCandidateRow> findRewriteCandidates(UUID onlineQueryId) {
        String sql = """
                SELECT rewrite_candidate_id,
                       candidate_rank,
                       candidate_label,
                       candidate_query,
                       retrieval_top_k_docs::text AS retrieval_top_k_docs,
                       confidence_score,
                       adopted,
                       rejected_reason,
                       score_breakdown::text AS score_breakdown
                FROM rewrite_candidates
                WHERE online_query_id = :onlineQueryId
                ORDER BY candidate_rank
                """;
        return jdbcTemplate.query(
                sql,
                new MapSqlParameterSource("onlineQueryId", onlineQueryId),
                (rs, rowNum) -> new RewriteCandidateRow(
                        readUuid(rs, "rewrite_candidate_id"),
                        rs.getInt("candidate_rank"),
                        rs.getString("candidate_label"),
                        rs.getString("candidate_query"),
                        readJson(rs, "retrieval_top_k_docs"),
                        rs.getObject("confidence_score", Double.class),
                        rs.getBoolean("adopted"),
                        rs.getString("rejected_reason"),
                        readJson(rs, "score_breakdown")
                )
        );
    }

    public JsonNode findRetrievalResults(UUID onlineQueryId) {
        String sql = """
                SELECT COALESCE(
                    jsonb_agg(
                        jsonb_build_object(
                            'retrieval_result_id', retrieval_result_id,
                            'rewrite_candidate_id', rewrite_candidate_id,
                            'result_scope', result_scope,
                            'rank', rank,
                            'document_id', document_id,
                            'chunk_id', chunk_id,
                            'score', score,
                            'metadata', metadata
                        )
                        ORDER BY created_at, rank
                    ),
                    '[]'::jsonb
                )::text AS payload
                FROM retrieval_results
                WHERE online_query_id = :onlineQueryId
                """;
        String payload = jdbcTemplate.queryForObject(sql, new MapSqlParameterSource("onlineQueryId", onlineQueryId), String.class);
        return readJson(payload);
    }

    public JsonNode findRerankResults(UUID onlineQueryId) {
        String sql = """
                SELECT COALESCE(
                    jsonb_agg(
                        jsonb_build_object(
                            'rerank_result_id', rerank_result_id,
                            'rewrite_candidate_id', rewrite_candidate_id,
                            'rank', rank,
                            'document_id', document_id,
                            'chunk_id', chunk_id,
                            'relevance_score', relevance_score,
                            'metadata', metadata
                        )
                        ORDER BY created_at, rank
                    ),
                    '[]'::jsonb
                )::text AS payload
                FROM rerank_results
                WHERE online_query_id = :onlineQueryId
                """;
        String payload = jdbcTemplate.queryForObject(sql, new MapSqlParameterSource("onlineQueryId", onlineQueryId), String.class);
        return readJson(payload);
    }

    public JsonNode findAnswer(UUID onlineQueryId) {
        String sql = """
                SELECT COALESCE(
                    jsonb_build_object(
                        'answer_id', answer_id,
                        'answer_text', answer_text,
                        'cited_document_ids', cited_document_ids,
                        'cited_chunk_ids', cited_chunk_ids,
                        'generation_model', generation_model,
                        'metadata', metadata,
                        'created_at', created_at
                    ),
                    '{}'::jsonb
                )::text AS payload
                FROM answers
                WHERE online_query_id = :onlineQueryId
                """;
        List<String> rows = jdbcTemplate.query(
                sql,
                new MapSqlParameterSource("onlineQueryId", onlineQueryId),
                (rs, rowNum) -> rs.getString("payload")
        );
        if (rows.isEmpty()) {
            return objectMapper.createObjectNode();
        }
        return readJson(rows.getFirst());
    }

    public Optional<ExperimentRunSummary> findExperimentRunSummary(UUID experimentRunId) {
        String sql = """
                SELECT er.experiment_run_id,
                       e.experiment_key,
                       er.status,
                       er.started_at,
                       er.finished_at,
                       er.parameters::text AS parameters,
                       er.metrics::text AS metrics,
                       er.notes
                FROM experiment_runs er
                JOIN experiments e ON e.experiment_id = er.experiment_id
                WHERE er.experiment_run_id = :experimentRunId
                """;
        List<ExperimentRunSummary> rows = jdbcTemplate.query(
                sql,
                new MapSqlParameterSource("experimentRunId", experimentRunId),
                (rs, rowNum) -> new ExperimentRunSummary(
                        readUuid(rs, "experiment_run_id"),
                        rs.getString("experiment_key"),
                        rs.getString("status"),
                        readInstant(rs, "started_at"),
                        readInstant(rs, "finished_at"),
                        readJson(rs, "parameters"),
                        readJson(rs, "metrics"),
                        rs.getString("notes")
                )
        );
        return rows.stream().findFirst();
    }

    public List<ExperimentRunSummary> listRecentExperimentRuns(int limit) {
        String sql = """
                SELECT er.experiment_run_id,
                       e.experiment_key,
                       er.status,
                       er.started_at,
                       er.finished_at,
                       er.parameters::text AS parameters,
                       er.metrics::text AS metrics,
                       er.notes
                FROM experiment_runs er
                JOIN experiments e ON e.experiment_id = er.experiment_id
                ORDER BY er.created_at DESC
                LIMIT :limit
                """;
        return jdbcTemplate.query(
                sql,
                new MapSqlParameterSource("limit", Math.max(1, Math.min(limit, 100))),
                (rs, rowNum) -> new ExperimentRunSummary(
                        readUuid(rs, "experiment_run_id"),
                        rs.getString("experiment_key"),
                        rs.getString("status"),
                        readInstant(rs, "started_at"),
                        readInstant(rs, "finished_at"),
                        readJson(rs, "parameters"),
                        readJson(rs, "metrics"),
                        rs.getString("notes")
                )
        );
    }

    private RowMapper<ChunkSource> chunkSourceRowMapper() {
        return (rs, rowNum) -> new ChunkSource(
                rs.getString("chunk_id"),
                rs.getString("document_id"),
                rs.getString("chunk_text")
        );
    }

    private UUID readUuid(ResultSet rs, String column) throws SQLException {
        Object value = rs.getObject(column);
        if (value == null) {
            return null;
        }
        if (value instanceof UUID uuid) {
            return uuid;
        }
        return UUID.fromString(String.valueOf(value));
    }

    private Instant readInstant(ResultSet rs, String column) throws SQLException {
        Timestamp timestamp = rs.getTimestamp(column);
        return timestamp != null ? timestamp.toInstant() : null;
    }

    private JsonNode readJson(ResultSet rs, String column) throws SQLException {
        return readJson(rs.getString(column));
    }

    private JsonNode readJson(String raw) {
        if (raw == null || raw.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            return objectMapper.readTree(raw);
        } catch (Exception exception) {
            ObjectNode fallback = objectMapper.createObjectNode();
            fallback.put("raw", raw);
            return fallback;
        }
    }

    public JsonNode asArrayNode(List<?> rows) {
        ArrayNode arrayNode = objectMapper.createArrayNode();
        for (Object row : rows) {
            arrayNode.add(objectMapper.valueToTree(row));
        }
        return arrayNode;
    }

    public List<RetrievalDoc> rerankByLexicalBoost(String query, List<RetrievalDoc> docs, int topN) {
        List<RetrievalDoc> sorted = new ArrayList<>(docs);
        sorted.sort((left, right) -> {
            double leftScore = lexicalBoost(query, left.chunkText()) * 0.3 + left.score() * 0.7;
            double rightScore = lexicalBoost(query, right.chunkText()) * 0.3 + right.score() * 0.7;
            return Double.compare(rightScore, leftScore);
        });
        return sorted.subList(0, Math.min(sorted.size(), Math.max(1, topN)));
    }

    private double lexicalBoost(String query, String text) {
        if (query == null || text == null || query.isBlank() || text.isBlank()) {
            return 0.0;
        }
        String[] queryTokens = query.toLowerCase().split("\\s+");
        String loweredText = text.toLowerCase();
        int hits = 0;
        for (String queryToken : queryTokens) {
            if (!queryToken.isBlank() && loweredText.contains(queryToken)) {
                hits++;
            }
        }
        return (double) hits / queryTokens.length;
    }
}
