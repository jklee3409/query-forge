package io.queryforge.backend.admin.console.dashboard;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ConsoleDashboardRepository {

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public ConsoleDashboardDtos.AdminDashboardStats findAdminDashboardStats() {
        long sourceCount = queryLong("SELECT COUNT(*) FROM corpus_sources");
        long activeDocumentCount = queryLong("SELECT COUNT(*) FROM corpus_documents WHERE is_active = TRUE");
        long chunkCount = queryLong(
                """
                SELECT COUNT(*)
                FROM corpus_chunks c
                JOIN corpus_documents d ON d.document_id = c.document_id
                WHERE d.is_active = TRUE
                """
        );
        long glossaryCount = queryLong("SELECT COUNT(*) FROM corpus_glossary_terms WHERE is_active = TRUE");
        long syntheticCount = queryLong("SELECT COUNT(*) FROM synthetic_queries_raw_all");
        long gatedAcceptedCount = queryLong("SELECT COUNT(*) FROM synthetic_queries_gated WHERE final_decision = TRUE");
        long memoryCount = queryLong("SELECT COUNT(*) FROM memory_entries");
        long ragRunCount = queryLong("SELECT COUNT(*) FROM rag_test_run");
        return new ConsoleDashboardDtos.AdminDashboardStats(
                sourceCount,
                activeDocumentCount,
                chunkCount,
                glossaryCount,
                syntheticCount,
                gatedAcceptedCount,
                memoryCount,
                ragRunCount
        );
    }

    private long queryLong(String sql) {
        Long value = jdbcTemplate.queryForObject(sql, new MapSqlParameterSource(), Long.class);
        return value == null ? 0L : value;
    }
}
