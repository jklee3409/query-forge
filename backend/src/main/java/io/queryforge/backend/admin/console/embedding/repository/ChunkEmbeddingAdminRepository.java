package io.queryforge.backend.admin.console.embedding.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

@Repository
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ChunkEmbeddingAdminRepository {

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public long countCorpusChunks() {
        Long value = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM corpus_chunks",
                new MapSqlParameterSource(),
                Long.class
        );
        return value == null ? 0L : value;
    }

    public long countMaterializedChunkEmbeddings(String embeddingModel) {
        Long value = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(DISTINCT chunk_id)
                FROM chunk_embeddings
                WHERE embedding_model = :embeddingModel
                """,
                new MapSqlParameterSource("embeddingModel", embeddingModel),
                Long.class
        );
        return value == null ? 0L : value;
    }

    public Instant findLatestChunkEmbeddingUpdatedAt(String embeddingModel) {
        List<Instant> rows = jdbcTemplate.query(
                """
                SELECT MAX(updated_at) AS latest_updated_at
                FROM chunk_embeddings
                WHERE embedding_model = :embeddingModel
                """,
                new MapSqlParameterSource("embeddingModel", embeddingModel),
                (rs, rowNum) -> {
                    Timestamp timestamp = rs.getTimestamp("latest_updated_at");
                    return timestamp == null ? null : timestamp.toInstant();
                }
        );
        return rows.stream().filter(value -> value != null).findFirst().orElse(null);
    }
}
