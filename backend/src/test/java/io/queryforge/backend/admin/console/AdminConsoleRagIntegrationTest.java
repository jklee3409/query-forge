package io.queryforge.backend.admin.console;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
@Sql(
        statements = """
                TRUNCATE TABLE
                    online_query_rewrite_log,
                    rag_eval_experiment_record,
                    rag_test_result_detail,
                    rag_test_result_summary,
                    rag_test_run_config,
                    llm_job_item,
                    llm_job,
                    rag_test_run,
                    online_queries
                RESTART IDENTITY CASCADE
                """,
        executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD
)
@Sql(
        statements = """
                TRUNCATE TABLE
                    online_query_rewrite_log,
                    rag_eval_experiment_record,
                    rag_test_result_detail,
                    rag_test_result_summary,
                    rag_test_run_config,
                    llm_job_item,
                    llm_job,
                    rag_test_run,
                    online_queries
                RESTART IDENTITY CASCADE
                """,
        executionPhase = Sql.ExecutionPhase.AFTER_TEST_METHOD
)
class AdminConsoleRagIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres")
    )
            .withDatabaseName("query_forge_test")
            .withUsername("query_forge")
            .withPassword("query_forge");

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private NamedParameterJdbcTemplate jdbcTemplate;

    @Test
    void deleteRagTestRemovesRunWithRelatedResults() throws Exception {
        UUID runId = UUID.randomUUID();
        UUID configId = UUID.randomUUID();
        UUID detailId = UUID.randomUUID();
        UUID recordId = UUID.randomUUID();
        UUID onlineQueryId = UUID.randomUUID();
        UUID rewriteLogId = UUID.randomUUID();

        jdbcTemplate.update(
                """
                INSERT INTO rag_test_run (
                    rag_test_run_id,
                    status,
                    generation_method_codes,
                    generation_batch_ids,
                    gating_applied,
                    rewrite_enabled,
                    selective_rewrite,
                    use_session_context,
                    metadata,
                    created_by
                ) VALUES (
                    :runId,
                    'completed',
                    '["A"]'::jsonb,
                    '[]'::jsonb,
                    TRUE,
                    TRUE,
                    TRUE,
                    FALSE,
                    '{}'::jsonb,
                    'test-admin'
                )
                """,
                new MapSqlParameterSource("runId", runId)
        );
        jdbcTemplate.update(
                """
                INSERT INTO rag_test_run_config (
                    config_id,
                    rag_test_run_id,
                    config_json
                ) VALUES (
                    :configId,
                    :runId,
                    '{}'::jsonb
                )
                """,
                new MapSqlParameterSource()
                        .addValue("configId", configId)
                        .addValue("runId", runId)
        );
        jdbcTemplate.update(
                """
                INSERT INTO rag_test_result_summary (
                    rag_test_run_id,
                    recall_at_5,
                    hit_at_5,
                    mrr_at_10,
                    ndcg_at_10,
                    answer_metrics,
                    metrics_json
                ) VALUES (
                    :runId,
                    0.5,
                    1.0,
                    0.7,
                    0.8,
                    '{}'::jsonb,
                    '{}'::jsonb
                )
                """,
                new MapSqlParameterSource("runId", runId)
        );
        jdbcTemplate.update(
                """
                INSERT INTO rag_test_result_detail (
                    detail_id,
                    rag_test_run_id,
                    query_category,
                    raw_query,
                    rewrite_query,
                    rewrite_applied,
                    memory_candidates,
                    rewrite_candidates,
                    retrieved_chunks,
                    metric_contribution,
                    hit_target
                ) VALUES (
                    :detailId,
                    :runId,
                    'factoid',
                    'raw',
                    'rewrite',
                    TRUE,
                    '[]'::jsonb,
                    '[]'::jsonb,
                    '[]'::jsonb,
                    '{}'::jsonb,
                    TRUE
                )
                """,
                new MapSqlParameterSource()
                        .addValue("detailId", detailId)
                        .addValue("runId", runId)
        );
        jdbcTemplate.update(
                """
                INSERT INTO rag_eval_experiment_record (
                    record_id,
                    rag_test_run_id,
                    snapshot_id,
                    generation_strategy,
                    gating_config,
                    memory_size,
                    retrieval_config,
                    rewrite_config,
                    metrics
                ) VALUES (
                    :recordId,
                    :runId,
                    'snapshot-1',
                    '["A"]'::jsonb,
                    '{}'::jsonb,
                    1,
                    '{}'::jsonb,
                    '{}'::jsonb,
                    '{}'::jsonb
                )
                """,
                new MapSqlParameterSource()
                        .addValue("recordId", recordId)
                        .addValue("runId", runId)
        );
        jdbcTemplate.update(
                """
                INSERT INTO online_queries (
                    online_query_id,
                    raw_query
                ) VALUES (
                    :onlineQueryId,
                    'raw query for delete test'
                )
                """,
                new MapSqlParameterSource("onlineQueryId", onlineQueryId)
        );
        jdbcTemplate.update(
                """
                INSERT INTO online_query_rewrite_log (
                    rewrite_log_id,
                    online_query_id,
                    run_id,
                    raw_query,
                    generation_method_codes,
                    generation_batch_ids
                ) VALUES (
                    :rewriteLogId,
                    :onlineQueryId,
                    :runId,
                    'raw query for delete test',
                    '["A"]'::jsonb,
                    '[]'::jsonb
                )
                """,
                new MapSqlParameterSource()
                        .addValue("rewriteLogId", rewriteLogId)
                        .addValue("onlineQueryId", onlineQueryId)
                        .addValue("runId", runId)
        );

        mockMvc.perform(delete("/api/admin/console/rag/tests/{runId}", runId))
                .andExpect(status().isOk());

        assertThat(countRows("SELECT COUNT(*) FROM rag_test_run WHERE rag_test_run_id = :runId", runId)).isZero();
        assertThat(countRows("SELECT COUNT(*) FROM rag_test_run_config WHERE rag_test_run_id = :runId", runId)).isZero();
        assertThat(countRows("SELECT COUNT(*) FROM rag_test_result_summary WHERE rag_test_run_id = :runId", runId)).isZero();
        assertThat(countRows("SELECT COUNT(*) FROM rag_test_result_detail WHERE rag_test_run_id = :runId", runId)).isZero();
        assertThat(countRows("SELECT COUNT(*) FROM rag_eval_experiment_record WHERE rag_test_run_id = :runId", runId)).isZero();
        assertThat(countRows("SELECT COUNT(*) FROM online_query_rewrite_log WHERE run_id = :runId", runId)).isZero();
    }

    @Test
    void deleteRagTestReturnsBadRequestWhenRunMissing() throws Exception {
        UUID missingRunId = UUID.randomUUID();
        mockMvc.perform(delete("/api/admin/console/rag/tests/{runId}", missingRunId))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("rag test run not found: " + missingRunId));
    }

    private long countRows(String sql, UUID runId) {
        Long value = jdbcTemplate.queryForObject(
                sql,
                new MapSqlParameterSource("runId", runId),
                Long.class
        );
        return value == null ? 0L : value;
    }
}
