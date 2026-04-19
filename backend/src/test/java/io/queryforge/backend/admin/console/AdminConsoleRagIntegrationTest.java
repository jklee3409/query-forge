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
                    retrieval_results,
                    rerank_results,
                    eval_judgments,
                    online_query_rewrite_log,
                    rag_eval_experiment_record,
                    rag_test_result_detail,
                    rag_test_result_summary,
                    rag_test_run_config,
                    llm_job_item,
                    llm_job,
                    rag_test_run,
                    online_queries,
                    experiment_runs,
                    experiments,
                    eval_samples
                RESTART IDENTITY CASCADE
                """,
        executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD
)
@Sql(
        statements = """
                TRUNCATE TABLE
                    retrieval_results,
                    rerank_results,
                    eval_judgments,
                    online_query_rewrite_log,
                    rag_eval_experiment_record,
                    rag_test_result_detail,
                    rag_test_result_summary,
                    rag_test_run_config,
                    llm_job_item,
                    llm_job,
                    rag_test_run,
                    online_queries,
                    experiment_runs,
                    experiments,
                    eval_samples
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
        UUID retrievalExperimentRunId = UUID.randomUUID();
        UUID answerExperimentRunId = UUID.randomUUID();
        UUID memoryExperimentRunId = UUID.randomUUID();
        UUID unrelatedExperimentRunId = UUID.randomUUID();
        UUID configId = UUID.randomUUID();
        UUID detailId = UUID.randomUUID();
        UUID recordId = UUID.randomUUID();
        UUID onlineQueryId = UUID.randomUUID();
        UUID evalOnlineQueryId = UUID.randomUUID();
        UUID rewriteLogId = UUID.randomUUID();
        UUID llmJobId = UUID.randomUUID();
        UUID llmJobItemId = UUID.randomUUID();
        UUID retrievalResultId = UUID.randomUUID();
        UUID retrievalCascadeResultId = UUID.randomUUID();
        UUID rerankResultId = UUID.randomUUID();
        UUID rerankCascadeResultId = UUID.randomUUID();
        UUID judgmentId = UUID.randomUUID();
        String sampleId = "sample-delete-rag-run";
        String runMetricsJson = String.format(
                "{\"retrieval\":{\"experiment_run_id\":\"%s\"},\"memory\":{\"experiment_run_id\":\"%s\"}}",
                retrievalExperimentRunId,
                memoryExperimentRunId
        );
        String summaryMetricsJson = String.format(
                "{\"answer\":{\"experiment_run_id\":\"%s\"}}",
                answerExperimentRunId
        );
        String jobResultJson = String.format(
                "{\"build-memory\":{\"experiment_run_id\":\"%s\"},\"eval-answer\":{\"experiment_run_id\":\"%s\"}}",
                memoryExperimentRunId,
                answerExperimentRunId
        );

        jdbcTemplate.update(
                """
                INSERT INTO experiment_runs (
                    experiment_run_id,
                    run_label,
                    status,
                    parameters,
                    metrics,
                    started_at,
                    finished_at
                ) VALUES (
                    :experimentRunId,
                    :runLabel,
                    'completed',
                    '{}'::jsonb,
                    '{}'::jsonb,
                    NOW(),
                    NOW()
                )
                """,
                new MapSqlParameterSource()
                        .addValue("experimentRunId", retrievalExperimentRunId)
                        .addValue("runLabel", "retrieval-run")
        );
        jdbcTemplate.update(
                """
                INSERT INTO experiment_runs (
                    experiment_run_id,
                    run_label,
                    status,
                    parameters,
                    metrics,
                    started_at,
                    finished_at
                ) VALUES (
                    :experimentRunId,
                    :runLabel,
                    'completed',
                    '{}'::jsonb,
                    '{}'::jsonb,
                    NOW(),
                    NOW()
                )
                """,
                new MapSqlParameterSource()
                        .addValue("experimentRunId", answerExperimentRunId)
                        .addValue("runLabel", "answer-run")
        );
        jdbcTemplate.update(
                """
                INSERT INTO experiment_runs (
                    experiment_run_id,
                    run_label,
                    status,
                    parameters,
                    metrics,
                    started_at,
                    finished_at
                ) VALUES (
                    :experimentRunId,
                    :runLabel,
                    'completed',
                    '{}'::jsonb,
                    '{}'::jsonb,
                    NOW(),
                    NOW()
                )
                """,
                new MapSqlParameterSource()
                        .addValue("experimentRunId", memoryExperimentRunId)
                        .addValue("runLabel", "memory-run")
        );
        jdbcTemplate.update(
                """
                INSERT INTO experiment_runs (
                    experiment_run_id,
                    run_label,
                    status,
                    parameters,
                    metrics,
                    started_at,
                    finished_at
                ) VALUES (
                    :experimentRunId,
                    :runLabel,
                    'completed',
                    '{}'::jsonb,
                    '{}'::jsonb,
                    NOW(),
                    NOW()
                )
                """,
                new MapSqlParameterSource()
                        .addValue("experimentRunId", unrelatedExperimentRunId)
                        .addValue("runLabel", "unrelated-run")
        );

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
                    source_experiment_run_id,
                    metrics_json,
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
                    :sourceExperimentRunId,
                    CAST(:metricsJson AS jsonb),
                    '{}'::jsonb,
                    'test-admin'
                )
                """,
                new MapSqlParameterSource()
                        .addValue("runId", runId)
                        .addValue("sourceExperimentRunId", retrievalExperimentRunId)
                        .addValue("metricsJson", runMetricsJson)
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
                    CAST(:metricsJson AS jsonb)
                )
                """,
                new MapSqlParameterSource()
                        .addValue("runId", runId)
                        .addValue("metricsJson", summaryMetricsJson)
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
                INSERT INTO llm_job (
                    job_id,
                    job_type,
                    command_name,
                    rag_test_run_id,
                    command_args,
                    total_items,
                    max_retries,
                    result_json
                ) VALUES (
                    :jobId,
                    'RUN_RAG_TEST',
                    'rag-pipeline',
                    :runId,
                    '{}'::jsonb,
                    3,
                    1,
                    CAST(:resultJson AS jsonb)
                )
                """,
                new MapSqlParameterSource()
                        .addValue("jobId", llmJobId)
                        .addValue("runId", runId)
                        .addValue("resultJson", jobResultJson)
        );
        jdbcTemplate.update(
                """
                INSERT INTO llm_job_item (
                    job_item_id,
                    job_id,
                    item_order,
                    item_type,
                    payload_json
                ) VALUES (
                    :jobItemId,
                    :jobId,
                    1,
                    'build-memory',
                    '{}'::jsonb
                )
                """,
                new MapSqlParameterSource()
                        .addValue("jobItemId", llmJobItemId)
                        .addValue("jobId", llmJobId)
        );
        jdbcTemplate.update(
                """
                INSERT INTO eval_samples (
                    sample_id,
                    split,
                    user_query_ko,
                    query_category
                ) VALUES (
                    :sampleId,
                    'test',
                    'delete verification query',
                    'factoid'
                )
                """,
                new MapSqlParameterSource("sampleId", sampleId)
        );
        jdbcTemplate.update(
                """
                INSERT INTO eval_judgments (
                    judgment_id,
                    sample_id,
                    experiment_run_id,
                    evaluator_type,
                    metrics,
                    notes
                ) VALUES (
                    :judgmentId,
                    :sampleId,
                    :experimentRunId,
                    'rule',
                    '{}'::jsonb,
                    'answer_eval:test'
                )
                """,
                new MapSqlParameterSource()
                        .addValue("judgmentId", judgmentId)
                        .addValue("sampleId", sampleId)
                        .addValue("experimentRunId", answerExperimentRunId)
        );
        jdbcTemplate.update(
                """
                INSERT INTO online_queries (
                    online_query_id,
                    experiment_run_id,
                    raw_query
                ) VALUES (
                    :onlineQueryId,
                    :experimentRunId,
                    'query for experiment cascade'
                )
                """,
                new MapSqlParameterSource()
                        .addValue("onlineQueryId", evalOnlineQueryId)
                        .addValue("experimentRunId", memoryExperimentRunId)
        );
        jdbcTemplate.update(
                """
                INSERT INTO retrieval_results (
                    retrieval_result_id,
                    eval_sample_id,
                    result_scope,
                    rank,
                    retriever_name,
                    score,
                    metadata
                ) VALUES (
                    :resultId,
                    :sampleId,
                    'eval',
                    1,
                    'pgvector',
                    0.77,
                    CAST(:metadataJson AS jsonb)
                )
                """,
                new MapSqlParameterSource()
                        .addValue("resultId", retrievalResultId)
                        .addValue("sampleId", sampleId)
                        .addValue("metadataJson", String.format("{\"experiment_run_id\":\"%s\"}", retrievalExperimentRunId))
        );
        jdbcTemplate.update(
                """
                INSERT INTO retrieval_results (
                    retrieval_result_id,
                    online_query_id,
                    eval_sample_id,
                    result_scope,
                    rank,
                    retriever_name,
                    score,
                    metadata
                ) VALUES (
                    :resultId,
                    :onlineQueryId,
                    :sampleId,
                    'eval',
                    2,
                    'pgvector',
                    0.66,
                    '{}'::jsonb
                )
                """,
                new MapSqlParameterSource()
                        .addValue("resultId", retrievalCascadeResultId)
                        .addValue("onlineQueryId", evalOnlineQueryId)
                        .addValue("sampleId", sampleId)
        );
        jdbcTemplate.update(
                """
                INSERT INTO rerank_results (
                    rerank_result_id,
                    eval_sample_id,
                    rank,
                    model_name,
                    relevance_score,
                    metadata
                ) VALUES (
                    :resultId,
                    :sampleId,
                    1,
                    'cohere-rerank',
                    0.91,
                    CAST(:metadataJson AS jsonb)
                )
                """,
                new MapSqlParameterSource()
                        .addValue("resultId", rerankResultId)
                        .addValue("sampleId", sampleId)
                        .addValue("metadataJson", String.format("{\"experiment_run_id\":\"%s\"}", answerExperimentRunId))
        );
        jdbcTemplate.update(
                """
                INSERT INTO rerank_results (
                    rerank_result_id,
                    online_query_id,
                    eval_sample_id,
                    rank,
                    model_name,
                    relevance_score,
                    metadata
                ) VALUES (
                    :resultId,
                    :onlineQueryId,
                    :sampleId,
                    2,
                    'cohere-rerank',
                    0.83,
                    '{}'::jsonb
                )
                """,
                new MapSqlParameterSource()
                        .addValue("resultId", rerankCascadeResultId)
                        .addValue("onlineQueryId", evalOnlineQueryId)
                        .addValue("sampleId", sampleId)
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
        assertThat(countRows("SELECT COUNT(*) FROM llm_job WHERE rag_test_run_id = :runId", runId)).isZero();
        assertThat(countRows("SELECT COUNT(*) FROM llm_job_item WHERE job_id = :jobId", "jobId", llmJobId)).isZero();
        assertThat(countRows("SELECT COUNT(*) FROM experiment_runs WHERE experiment_run_id = :experimentRunId", "experimentRunId", retrievalExperimentRunId)).isZero();
        assertThat(countRows("SELECT COUNT(*) FROM experiment_runs WHERE experiment_run_id = :experimentRunId", "experimentRunId", answerExperimentRunId)).isZero();
        assertThat(countRows("SELECT COUNT(*) FROM experiment_runs WHERE experiment_run_id = :experimentRunId", "experimentRunId", memoryExperimentRunId)).isZero();
        assertThat(countRows("SELECT COUNT(*) FROM eval_judgments WHERE experiment_run_id = :experimentRunId", "experimentRunId", answerExperimentRunId)).isZero();
        assertThat(countRows("SELECT COUNT(*) FROM retrieval_results WHERE retrieval_result_id = :resultId", "resultId", retrievalResultId)).isZero();
        assertThat(countRows("SELECT COUNT(*) FROM retrieval_results WHERE retrieval_result_id = :resultId", "resultId", retrievalCascadeResultId)).isZero();
        assertThat(countRows("SELECT COUNT(*) FROM rerank_results WHERE rerank_result_id = :resultId", "resultId", rerankResultId)).isZero();
        assertThat(countRows("SELECT COUNT(*) FROM rerank_results WHERE rerank_result_id = :resultId", "resultId", rerankCascadeResultId)).isZero();
        assertThat(countRows("SELECT COUNT(*) FROM online_queries WHERE online_query_id = :onlineQueryId", "onlineQueryId", evalOnlineQueryId)).isZero();
        assertThat(countRows("SELECT COUNT(*) FROM experiment_runs WHERE experiment_run_id = :experimentRunId", "experimentRunId", unrelatedExperimentRunId)).isEqualTo(1L);
    }

    @Test
    void deleteRagTestReturnsBadRequestWhenRunMissing() throws Exception {
        UUID missingRunId = UUID.randomUUID();
        mockMvc.perform(delete("/api/admin/console/rag/tests/{runId}", missingRunId))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("rag test run not found: " + missingRunId));
    }

    private long countRows(String sql, UUID runId) {
        return countRows(sql, "runId", runId);
    }

    private long countRows(String sql, String key, Object paramValue) {
        Long rowCount = jdbcTemplate.queryForObject(
                sql,
                new MapSqlParameterSource(key, paramValue),
                Long.class
        );
        return rowCount == null ? 0L : rowCount;
    }
}
