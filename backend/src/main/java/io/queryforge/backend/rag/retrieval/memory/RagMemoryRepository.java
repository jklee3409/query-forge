package io.queryforge.backend.rag.retrieval.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;

import static io.queryforge.backend.rag.persistence.RagJdbc.readJson;
import static io.queryforge.backend.rag.persistence.RagJdbc.readUuid;

@Repository
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RagMemoryRepository {

    private final ObjectMapper objectMapper;
    private final NamedParameterJdbcTemplate jdbcTemplate;

    public List<MemoryCandidate> findMemoryTopN(
            String queryEmbeddingLiteral,
            int topN,
            String gatingPreset,
            UUID domainId,
            List<String> generationStrategies,
            List<UUID> sourceGatingRunIds,
            List<UUID> sourceGatingBatchIds
    ) {
        boolean strategyFilterEmpty = generationStrategies == null || generationStrategies.isEmpty();
        List<String> runIds = uuidTextArray(sourceGatingRunIds);
        List<String> batchIds = uuidTextArray(sourceGatingBatchIds);
        boolean sourceGatingRunFilterEmpty = runIds.isEmpty();
        boolean sourceGatingBatchFilterEmpty = batchIds.isEmpty();
        String sql = """
                SELECT m.memory_id,
                       m.query_text,
                       m.target_doc_id,
                       m.target_chunk_ids::text AS target_chunk_ids,
                       m.glossary_terms::text AS glossary_terms,
                       m.metadata::text AS metadata_json,
                       1 - (qe.embedding <=> CAST(:embedding AS halfvec)) AS similarity,
                       m.generation_strategy,
                       r.generation_batch_id,
                       m.domain_id,
                       m.source_gated_query_id,
                       m.metadata ->> 'source_gate_run_id' AS source_gate_run_id,
                       m.metadata ->> 'source_gating_batch_id' AS source_gating_batch_id
                FROM query_embeddings qe
                JOIN memory_entries m
                  ON qe.owner_type = 'memory'
                 AND qe.owner_id = m.memory_id::text
                 AND qe.embedding_model = 'hash-embedding-v1'
                JOIN synthetic_queries_gated g ON g.gated_query_id = m.source_gated_query_id
                LEFT JOIN synthetic_queries_raw_all r ON r.synthetic_query_id = g.synthetic_query_id
                WHERE qe.embedding IS NOT NULL
                  AND (:gatingPreset IS NULL OR g.gating_preset = :gatingPreset)
                  AND (:domainId IS NULL OR m.domain_id = :domainId)
                  AND (:strategyFilterEmpty IS TRUE OR m.generation_strategy IN (:generationStrategies))
                  AND (:sourceGatingRunFilterEmpty IS TRUE OR m.metadata ->> 'source_gate_run_id' IN (:sourceGatingRunIds))
                  AND (:sourceGatingBatchFilterEmpty IS TRUE OR m.metadata ->> 'source_gating_batch_id' IN (:sourceGatingBatchIds))
                ORDER BY qe.embedding <=> CAST(:embedding AS halfvec)
                LIMIT :topN
                """;
        return jdbcTemplate.query(
                sql,
                new MapSqlParameterSource()
                        .addValue("embedding", queryEmbeddingLiteral)
                        .addValue("topN", topN)
                        .addValue("gatingPreset", gatingPreset)
                        .addValue("domainId", domainId)
                        .addValue("strategyFilterEmpty", strategyFilterEmpty)
                        .addValue("generationStrategies", strategyFilterEmpty ? List.of("") : generationStrategies)
                        .addValue("sourceGatingRunFilterEmpty", sourceGatingRunFilterEmpty)
                        .addValue("sourceGatingRunIds", sourceGatingRunFilterEmpty ? List.of("") : runIds)
                        .addValue("sourceGatingBatchFilterEmpty", sourceGatingBatchFilterEmpty)
                        .addValue("sourceGatingBatchIds", sourceGatingBatchFilterEmpty ? List.of("") : batchIds),
                (rs, rowNum) -> new MemoryCandidate(
                        readUuid(rs, "memory_id"),
                        rs.getString("query_text"),
                        rs.getString("target_doc_id"),
                        readJson(objectMapper, rs, "target_chunk_ids"),
                        readJson(objectMapper, rs, "glossary_terms"),
                        readJson(objectMapper, rs, "metadata_json"),
                        rs.getDouble("similarity"),
                        rs.getString("generation_strategy"),
                        readUuid(rs, "generation_batch_id"),
                        readUuid(rs, "domain_id"),
                        rs.getString("source_gated_query_id"),
                        rs.getString("source_gate_run_id"),
                        rs.getString("source_gating_batch_id")
                )
        );
    }

    public List<MemoryCandidate> findMemoryDensePool(
            String queryEmbeddingLiteral,
            String embeddingModel,
            int limit,
            String gatingPreset,
            UUID domainId,
            List<String> generationStrategies,
            List<UUID> sourceGatingRunIds,
            List<UUID> sourceGatingBatchIds
    ) {
        boolean strategyFilterEmpty = generationStrategies == null || generationStrategies.isEmpty();
        String sql = """
                SELECT m.memory_id,
                       m.query_text,
                       m.target_doc_id,
                       m.target_chunk_ids::text AS target_chunk_ids,
                       m.glossary_terms::text AS glossary_terms,
                       m.metadata::text AS metadata_json,
                       1 - (m.query_embedding <=> CAST(:embedding AS halfvec)) AS similarity,
                       m.generation_strategy,
                       r.generation_batch_id,
                       m.domain_id,
                       m.source_gated_query_id,
                       m.metadata ->> 'source_gate_run_id' AS source_gate_run_id,
                       m.metadata ->> 'source_gating_batch_id' AS source_gating_batch_id
                FROM memory_entries m
                JOIN synthetic_queries_gated g ON g.gated_query_id = m.source_gated_query_id
                LEFT JOIN synthetic_queries_raw_all r ON r.synthetic_query_id = g.synthetic_query_id
                WHERE m.query_embedding IS NOT NULL
                  AND m.metadata ->> 'embedding_model' = :embeddingModel
                  AND (:gatingPreset IS NULL OR g.gating_preset = :gatingPreset)
                  AND (:domainId IS NULL OR m.domain_id = :domainId)
                  AND (:strategyFilterEmpty IS TRUE OR m.generation_strategy IN (:generationStrategies))
                  AND (:sourceGatingRunFilterEmpty IS TRUE OR m.metadata ->> 'source_gate_run_id' IN (:sourceGatingRunIds))
                  AND (:sourceGatingBatchFilterEmpty IS TRUE OR m.metadata ->> 'source_gating_batch_id' IN (:sourceGatingBatchIds))
                ORDER BY m.query_embedding <=> CAST(:embedding AS halfvec), m.memory_id
                LIMIT :limit
                """;
        return jdbcTemplate.query(
                sql,
                memoryQueryParameters(
                        queryEmbeddingLiteral,
                        embeddingModel,
                        limit,
                        gatingPreset,
                        domainId,
                        generationStrategies,
                        sourceGatingRunIds,
                        sourceGatingBatchIds,
                        strategyFilterEmpty
                ),
                memoryCandidateRowMapper()
        );
    }

    public List<MemoryCandidate> findMemoryTextPool(
            String queryEmbeddingLiteral,
            String embeddingModel,
            List<String> patterns,
            int limit,
            String gatingPreset,
            UUID domainId,
            List<String> generationStrategies,
            List<UUID> sourceGatingRunIds,
            List<UUID> sourceGatingBatchIds
    ) {
        if (patterns == null || patterns.isEmpty()) {
            return List.of();
        }
        boolean strategyFilterEmpty = generationStrategies == null || generationStrategies.isEmpty();
        MapSqlParameterSource parameters = memoryQueryParameters(
                queryEmbeddingLiteral,
                embeddingModel,
                limit,
                gatingPreset,
                domainId,
                generationStrategies,
                sourceGatingRunIds,
                sourceGatingBatchIds,
                strategyFilterEmpty
        );
        String patternFilter = memoryPatternFilter(patterns, parameters, "memoryPattern");
        String sql = """
                SELECT m.memory_id,
                       m.query_text,
                       m.target_doc_id,
                       m.target_chunk_ids::text AS target_chunk_ids,
                       m.glossary_terms::text AS glossary_terms,
                       m.metadata::text AS metadata_json,
                       1 - (m.query_embedding <=> CAST(:embedding AS halfvec)) AS similarity,
                       m.generation_strategy,
                       r.generation_batch_id,
                       m.domain_id,
                       m.source_gated_query_id,
                       m.metadata ->> 'source_gate_run_id' AS source_gate_run_id,
                       m.metadata ->> 'source_gating_batch_id' AS source_gating_batch_id
                FROM memory_entries m
                JOIN synthetic_queries_gated g ON g.gated_query_id = m.source_gated_query_id
                LEFT JOIN synthetic_queries_raw_all r ON r.synthetic_query_id = g.synthetic_query_id
                WHERE m.query_embedding IS NOT NULL
                  AND m.metadata ->> 'embedding_model' = :embeddingModel
                  AND (:gatingPreset IS NULL OR g.gating_preset = :gatingPreset)
                  AND (:domainId IS NULL OR m.domain_id = :domainId)
                  AND (:strategyFilterEmpty IS TRUE OR m.generation_strategy IN (:generationStrategies))
                  AND (:sourceGatingRunFilterEmpty IS TRUE OR m.metadata ->> 'source_gate_run_id' IN (:sourceGatingRunIds))
                  AND (:sourceGatingBatchFilterEmpty IS TRUE OR m.metadata ->> 'source_gating_batch_id' IN (:sourceGatingBatchIds))
                  AND (%s)
                ORDER BY m.memory_id
                LIMIT :limit
                """.formatted(patternFilter);
        return jdbcTemplate.query(sql, parameters, memoryCandidateRowMapper());
    }

    private RowMapper<MemoryCandidate> memoryCandidateRowMapper() {
        return (rs, rowNum) -> new MemoryCandidate(
                readUuid(rs, "memory_id"),
                rs.getString("query_text"),
                rs.getString("target_doc_id"),
                readJson(objectMapper, rs, "target_chunk_ids"),
                readJson(objectMapper, rs, "glossary_terms"),
                readJson(objectMapper, rs, "metadata_json"),
                rs.getDouble("similarity"),
                rs.getString("generation_strategy"),
                readUuid(rs, "generation_batch_id"),
                readUuid(rs, "domain_id"),
                rs.getString("source_gated_query_id"),
                rs.getString("source_gate_run_id"),
                rs.getString("source_gating_batch_id")
        );
    }

    private MapSqlParameterSource memoryQueryParameters(
            String queryEmbeddingLiteral,
            String embeddingModel,
            int limit,
            String gatingPreset,
            UUID domainId,
            List<String> generationStrategies,
            List<UUID> sourceGatingRunIds,
            List<UUID> sourceGatingBatchIds,
            boolean strategyFilterEmpty
    ) {
        List<String> runIds = uuidTextArray(sourceGatingRunIds);
        List<String> batchIds = uuidTextArray(sourceGatingBatchIds);
        boolean sourceGatingRunFilterEmpty = runIds.isEmpty();
        boolean sourceGatingBatchFilterEmpty = batchIds.isEmpty();
        return new MapSqlParameterSource()
                .addValue("embedding", queryEmbeddingLiteral)
                .addValue("embeddingModel", embeddingModel)
                .addValue("limit", Math.max(1, limit))
                .addValue("gatingPreset", gatingPreset)
                .addValue("domainId", domainId)
                .addValue("strategyFilterEmpty", strategyFilterEmpty)
                .addValue("generationStrategies", strategyFilterEmpty ? List.of("") : generationStrategies)
                .addValue("sourceGatingRunFilterEmpty", sourceGatingRunFilterEmpty)
                .addValue("sourceGatingRunIds", sourceGatingRunFilterEmpty ? List.of("") : runIds)
                .addValue("sourceGatingBatchFilterEmpty", sourceGatingBatchFilterEmpty)
                .addValue("sourceGatingBatchIds", sourceGatingBatchFilterEmpty ? List.of("") : batchIds);
    }

    private List<String> uuidTextArray(List<UUID> rawValues) {
        if (rawValues == null || rawValues.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> values = new LinkedHashSet<>();
        for (UUID value : rawValues) {
            if (value != null) {
                values.add(value.toString());
            }
        }
        return List.copyOf(values);
    }

    private String memoryPatternFilter(List<String> patterns, MapSqlParameterSource parameters, String prefix) {
        List<String> clauses = new ArrayList<>();
        for (int index = 0; index < patterns.size(); index++) {
            String name = prefix + index;
            parameters.addValue(name, "%" + patterns.get(index).toLowerCase() + "%");
            clauses.add("""
                    LOWER(COALESCE(m.query_text, '')) LIKE :%1$s
                    OR LOWER(COALESCE(m.glossary_terms::text, '')) LIKE :%1$s
                    OR LOWER(COALESCE((m.metadata -> 'canonical_anchors')::text, '')) LIKE :%1$s
                    """.formatted(name));
        }
        return String.join(" OR ", clauses);
    }
}
