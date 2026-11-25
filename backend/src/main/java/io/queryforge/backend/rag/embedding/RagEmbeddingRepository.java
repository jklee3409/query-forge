package io.queryforge.backend.rag.embedding;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

import static io.queryforge.backend.rag.persistence.RagJdbc.readUuid;

@Repository
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RagEmbeddingRepository {

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public record ChunkSource(String chunkId, String documentId, String chunkText) {
    }

    public record MemorySource(UUID memoryId, String queryText) {
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
                    384,
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
        if (!"hash-embedding-v1".equalsIgnoreCase(embeddingModel)) {
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
        }

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
                    384,
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

    private RowMapper<ChunkSource> chunkSourceRowMapper() {
        return (rs, rowNum) -> new ChunkSource(
                rs.getString("chunk_id"),
                rs.getString("document_id"),
                rs.getString("chunk_text")
        );
    }
}
