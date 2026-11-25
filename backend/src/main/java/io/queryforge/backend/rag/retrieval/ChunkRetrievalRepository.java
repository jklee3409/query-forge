package io.queryforge.backend.rag.retrieval;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ChunkRetrievalRepository {

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public List<RetrievalDoc> findTopChunksByEmbedding(String queryEmbeddingLiteral, int topK) {
        return findTopChunksByEmbedding(queryEmbeddingLiteral, topK, null);
    }

    public List<RetrievalDoc> findTopChunksByEmbedding(String queryEmbeddingLiteral, int topK, UUID domainId) {
        String sql = """
                SELECT c.document_id,
                       c.chunk_id,
                       c.chunk_text,
                       1 - (qe.embedding <=> CAST(:embedding AS halfvec)) AS score
                FROM query_embeddings qe
                JOIN corpus_chunks c ON c.chunk_id = qe.owner_id
                WHERE qe.owner_type = 'chunk'
                  AND qe.embedding_model = 'hash-embedding-v1'
                  AND (:domainId IS NULL OR c.domain_id = :domainId)
                ORDER BY qe.embedding <=> CAST(:embedding AS halfvec)
                LIMIT :topK
                """;
        return jdbcTemplate.query(
                sql,
                new MapSqlParameterSource()
                        .addValue("embedding", queryEmbeddingLiteral)
                        .addValue("topK", topK)
                        .addValue("domainId", domainId),
                (rs, rowNum) -> new RetrievalDoc(
                        rs.getString("document_id"),
                        rs.getString("chunk_id"),
                        rs.getString("chunk_text"),
                        rs.getDouble("score")
                )
        );
    }

    public List<RetrievalDoc> findDbAnnChunkDensePool(
            String queryEmbeddingLiteral,
            String embeddingModel,
            int limit,
            UUID domainId
    ) {
        String sql = """
                SELECT c.document_id,
                       c.chunk_id,
                       c.chunk_text,
                       1 - (ce.embedding <=> CAST(:embedding AS halfvec)) AS score
                FROM chunk_embeddings ce
                JOIN corpus_chunks c ON c.chunk_id = ce.chunk_id
                WHERE ce.embedding_model = :embeddingModel
                  AND (:domainId IS NULL OR c.domain_id = :domainId)
                ORDER BY ce.embedding <=> CAST(:embedding AS halfvec), c.chunk_id
                LIMIT :limit
                """;
        return jdbcTemplate.query(
                sql,
                new MapSqlParameterSource()
                        .addValue("embedding", queryEmbeddingLiteral)
                        .addValue("embeddingModel", embeddingModel)
                        .addValue("limit", Math.max(1, limit))
                        .addValue("domainId", domainId),
                retrievalDocRowMapper()
        );
    }

    public List<RetrievalDoc> findDbAnnChunkTextPool(
            String queryEmbeddingLiteral,
            String embeddingModel,
            List<String> patterns,
            int limit,
            UUID domainId
    ) {
        if (patterns == null || patterns.isEmpty()) {
            return List.of();
        }
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("embedding", queryEmbeddingLiteral)
                .addValue("embeddingModel", embeddingModel)
                .addValue("limit", Math.max(1, limit))
                .addValue("domainId", domainId);
        String patternFilter = chunkPatternFilter(patterns, parameters, "chunkPattern");
        String sql = """
                SELECT c.document_id,
                       c.chunk_id,
                       c.chunk_text,
                       1 - (ce.embedding <=> CAST(:embedding AS halfvec)) AS score
                FROM chunk_embeddings ce
                JOIN corpus_chunks c ON c.chunk_id = ce.chunk_id
                LEFT JOIN corpus_documents d ON d.document_id = c.document_id
                WHERE ce.embedding_model = :embeddingModel
                  AND (:domainId IS NULL OR c.domain_id = :domainId)
                  AND (%s)
                ORDER BY c.chunk_id
                LIMIT :limit
                """.formatted(patternFilter);
        return jdbcTemplate.query(sql, parameters, retrievalDocRowMapper());
    }

    public List<RetrievalDoc> findChunkTextPool(List<String> patterns, int limit, UUID domainId) {
        if (patterns == null || patterns.isEmpty()) {
            return List.of();
        }
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("limit", Math.max(1, limit))
                .addValue("domainId", domainId);
        String patternFilter = chunkPatternFilter(patterns, parameters, "localChunkPattern");
        String sql = """
                SELECT c.document_id,
                       c.chunk_id,
                       c.chunk_text,
                       0.0 AS score
                FROM corpus_chunks c
                LEFT JOIN corpus_documents d ON d.document_id = c.document_id
                WHERE (:domainId IS NULL OR c.domain_id = :domainId)
                  AND (%s)
                ORDER BY c.chunk_id
                LIMIT :limit
                """.formatted(patternFilter);
        return jdbcTemplate.query(sql, parameters, retrievalDocRowMapper());
    }

    private RowMapper<RetrievalDoc> retrievalDocRowMapper() {
        return (rs, rowNum) -> new RetrievalDoc(
                rs.getString("document_id"),
                rs.getString("chunk_id"),
                rs.getString("chunk_text"),
                rs.getDouble("score")
        );
    }

    private String chunkPatternFilter(List<String> patterns, MapSqlParameterSource parameters, String prefix) {
        List<String> clauses = new ArrayList<>();
        for (int index = 0; index < patterns.size(); index++) {
            String name = prefix + index;
            parameters.addValue(name, "%" + patterns.get(index).toLowerCase() + "%");
            clauses.add("""
                    LOWER(COALESCE(c.chunk_text, '')) LIKE :%1$s
                    OR LOWER(COALESCE(c.section_path_text, '')) LIKE :%1$s
                    OR LOWER(COALESCE(d.title, '')) LIKE :%1$s
                    """.formatted(name));
        }
        return String.join(" OR ", clauses);
    }
}
