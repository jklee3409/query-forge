package io.queryforge.backend.admin.console;

import io.queryforge.backend.admin.console.repository.AdminConsoleRepository;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
@Sql(
        statements = """
                TRUNCATE TABLE
                    quality_gating_stage_result,
                    synthetic_query_gating_history,
                    synthetic_query_gating_result,
                    quality_gating_batch,
                    synthetic_query_generation_batch
                RESTART IDENTITY CASCADE
                """,
        executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD
)
@Sql(
        statements = """
                TRUNCATE TABLE
                    quality_gating_stage_result,
                    synthetic_query_gating_history,
                    synthetic_query_gating_result,
                    quality_gating_batch,
                    synthetic_query_generation_batch
                RESTART IDENTITY CASCADE
                """,
        executionPhase = Sql.ExecutionPhase.AFTER_TEST_METHOD
)
class AdminConsoleGatingIntegrationTest {

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

    @Autowired
    private AdminConsoleRepository adminConsoleRepository;

    @Test
    void gatingFunnelReturnsZeroCountsWhenStageResultRowsAreMissing() throws Exception {
        UUID methodId = jdbcTemplate.getJdbcTemplate().queryForObject(
                "SELECT generation_method_id FROM synthetic_query_generation_method WHERE method_code = 'A'",
                UUID.class
        );
        assertThat(methodId).isNotNull();

        UUID gatingBatchId = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO quality_gating_batch (
                    gating_batch_id,
                    gating_preset,
                    generation_method_id,
                    stage_config_json,
                    status,
                    created_by
                ) VALUES (
                    :gatingBatchId,
                    'full_gating',
                    :generationMethodId,
                    '{}'::jsonb,
                    'running',
                    'test-admin'
                )
                """,
                new MapSqlParameterSource()
                        .addValue("gatingBatchId", gatingBatchId)
                        .addValue("generationMethodId", methodId)
        );

        mockMvc.perform(get("/api/admin/console/gating/batches/{gatingBatchId}/funnel", gatingBatchId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.gatingBatchId").value(gatingBatchId.toString()))
                .andExpect(jsonPath("$.methodCode").value("A"))
                .andExpect(jsonPath("$.gatingPreset").value("full_gating"))
                .andExpect(jsonPath("$.generatedTotal").value(0))
                .andExpect(jsonPath("$.passedRule").value(0))
                .andExpect(jsonPath("$.passedLlm").value(0))
                .andExpect(jsonPath("$.passedUtility").value(0))
                .andExpect(jsonPath("$.passedDiversity").value(0))
                .andExpect(jsonPath("$.finalAccepted").value(0));
    }

    @Test
    void gatingResultsSupportsMethodFilterAndPagination() throws Exception {
        UUID methodA = jdbcTemplate.getJdbcTemplate().queryForObject(
                "SELECT generation_method_id FROM synthetic_query_generation_method WHERE method_code = 'A'",
                UUID.class
        );
        assertThat(methodA).isNotNull();

        UUID gatingBatchId = UUID.randomUUID();
        insertGatingBatch(gatingBatchId, methodA, "completed");

        insertRegistryQuery("sq_a_older", "A");
        insertRegistryQuery("sq_b_mid", "B");
        insertRegistryQuery("sq_a_newer", "A");

        insertGatingResult(gatingBatchId, "sq_a_older", "A", "A older", "2026-04-13T10:00:00Z");
        insertGatingResult(gatingBatchId, "sq_b_mid", "B", "B mid", "2026-04-13T10:00:01Z");
        insertGatingResult(gatingBatchId, "sq_a_newer", "A", "A newer", "2026-04-13T10:00:02Z");

        mockMvc.perform(get("/api/admin/console/gating/batches/{gatingBatchId}/results", gatingBatchId)
                        .param("method_code", "A")
                        .param("limit", "1")
                        .param("offset", "0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].syntheticQueryId").value("sq_a_newer"))
                .andExpect(jsonPath("$[0].generationStrategy").value("A"));

        mockMvc.perform(get("/api/admin/console/gating/batches/{gatingBatchId}/results", gatingBatchId)
                        .param("method_code", "A")
                        .param("limit", "1")
                        .param("offset", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].syntheticQueryId").value("sq_a_older"))
                .andExpect(jsonPath("$[0].generationStrategy").value("A"));
    }

    @Test
    void clearCompletedGatingResultsRemovesOnlyCompletedRowsForTargetMethod() {
        UUID methodA = jdbcTemplate.getJdbcTemplate().queryForObject(
                "SELECT generation_method_id FROM synthetic_query_generation_method WHERE method_code = 'A'",
                UUID.class
        );
        UUID methodB = jdbcTemplate.getJdbcTemplate().queryForObject(
                "SELECT generation_method_id FROM synthetic_query_generation_method WHERE method_code = 'B'",
                UUID.class
        );
        assertThat(methodA).isNotNull();
        assertThat(methodB).isNotNull();

        UUID completedBatchA = UUID.randomUUID();
        UUID runningBatchA = UUID.randomUUID();
        UUID completedBatchB = UUID.randomUUID();
        insertGatingBatch(completedBatchA, methodA, "completed");
        insertGatingBatch(runningBatchA, methodA, "running");
        insertGatingBatch(completedBatchB, methodB, "completed");

        insertRegistryQuery("sq_a_completed", "A");
        insertRegistryQuery("sq_a_running", "A");
        insertRegistryQuery("sq_b_completed", "B");

        insertGatedRow("gated_a_completed", "sq_a_completed", completedBatchA);
        insertGatedRow("gated_a_running", "sq_a_running", runningBatchA);
        insertGatedRow("gated_b_completed", "sq_b_completed", completedBatchB);

        insertBatchArtifacts(completedBatchA, "sq_a_completed");
        insertBatchArtifacts(completedBatchB, "sq_b_completed");

        int removed = adminConsoleRepository.clearCompletedGatingResults(methodA, null);
        assertThat(removed).isEqualTo(1);

        assertThat(countRows("SELECT COUNT(*) FROM quality_gating_batch WHERE gating_batch_id = :id", completedBatchA)).isZero();
        assertThat(countRows("SELECT COUNT(*) FROM quality_gating_batch WHERE gating_batch_id = :id", runningBatchA)).isEqualTo(1);
        assertThat(countRows("SELECT COUNT(*) FROM quality_gating_batch WHERE gating_batch_id = :id", completedBatchB)).isEqualTo(1);

        assertThat(countRows("SELECT COUNT(*) FROM synthetic_query_gating_result WHERE gating_batch_id = :id", completedBatchA)).isZero();
        assertThat(countRows("SELECT COUNT(*) FROM synthetic_query_gating_history WHERE gating_batch_id = :id", completedBatchA)).isZero();
        assertThat(countRows("SELECT COUNT(*) FROM quality_gating_stage_result WHERE gating_batch_id = :id", completedBatchA)).isZero();

        UUID completedBatchRef = jdbcTemplate.queryForObject(
                "SELECT gating_batch_id FROM synthetic_queries_gated WHERE gated_query_id = 'gated_a_completed'",
                new MapSqlParameterSource(),
                UUID.class
        );
        UUID runningBatchRef = jdbcTemplate.queryForObject(
                "SELECT gating_batch_id FROM synthetic_queries_gated WHERE gated_query_id = 'gated_a_running'",
                new MapSqlParameterSource(),
                UUID.class
        );
        UUID methodBBatchRef = jdbcTemplate.queryForObject(
                "SELECT gating_batch_id FROM synthetic_queries_gated WHERE gated_query_id = 'gated_b_completed'",
                new MapSqlParameterSource(),
                UUID.class
        );
        assertThat(completedBatchRef).isNull();
        assertThat(runningBatchRef).isEqualTo(runningBatchA);
        assertThat(methodBBatchRef).isEqualTo(completedBatchB);
    }

    private void insertGatingBatch(UUID gatingBatchId, UUID methodId, String status) {
        jdbcTemplate.update(
                """
                INSERT INTO quality_gating_batch (
                    gating_batch_id,
                    gating_preset,
                    generation_method_id,
                    stage_config_json,
                    status,
                    created_by
                ) VALUES (
                    :gatingBatchId,
                    'full_gating',
                    :generationMethodId,
                    '{}'::jsonb,
                    :status,
                    'test-admin'
                )
                """,
                new MapSqlParameterSource()
                        .addValue("gatingBatchId", gatingBatchId)
                        .addValue("generationMethodId", methodId)
                        .addValue("status", status)
        );
    }

    private void insertRegistryQuery(String syntheticQueryId, String strategy) {
        jdbcTemplate.update(
                """
                INSERT INTO synthetic_query_registry (
                    synthetic_query_id,
                    generation_strategy
                ) VALUES (
                    :syntheticQueryId,
                    :strategy
                )
                """,
                new MapSqlParameterSource()
                        .addValue("syntheticQueryId", syntheticQueryId)
                        .addValue("strategy", strategy)
        );
    }

    private void insertGatedRow(String gatedQueryId, String syntheticQueryId, UUID gatingBatchId) {
        jdbcTemplate.update(
                """
                INSERT INTO synthetic_queries_gated (
                    gated_query_id,
                    synthetic_query_id,
                    gating_batch_id,
                    gating_preset,
                    final_decision
                ) VALUES (
                    :gatedQueryId,
                    :syntheticQueryId,
                    :gatingBatchId,
                    'full_gating',
                    TRUE
                )
                """,
                new MapSqlParameterSource()
                        .addValue("gatedQueryId", gatedQueryId)
                        .addValue("syntheticQueryId", syntheticQueryId)
                        .addValue("gatingBatchId", gatingBatchId)
        );
    }

    private void insertBatchArtifacts(UUID gatingBatchId, String syntheticQueryId) {
        jdbcTemplate.update(
                """
                INSERT INTO synthetic_query_gating_result (
                    gating_batch_id,
                    synthetic_query_id,
                    query_text,
                    accepted
                ) VALUES (
                    :gatingBatchId,
                    :syntheticQueryId,
                    :queryText,
                    TRUE
                )
                """,
                new MapSqlParameterSource()
                        .addValue("gatingBatchId", gatingBatchId)
                        .addValue("syntheticQueryId", syntheticQueryId)
                        .addValue("queryText", syntheticQueryId + " text")
        );
        jdbcTemplate.update(
                """
                INSERT INTO synthetic_query_gating_history (
                    gating_batch_id,
                    synthetic_query_id,
                    stage_name,
                    stage_order
                ) VALUES (
                    :gatingBatchId,
                    :syntheticQueryId,
                    'rule_filter',
                    1
                )
                """,
                new MapSqlParameterSource()
                        .addValue("gatingBatchId", gatingBatchId)
                        .addValue("syntheticQueryId", syntheticQueryId)
        );
        jdbcTemplate.update(
                """
                INSERT INTO quality_gating_stage_result (
                    gating_batch_id,
                    stage_name,
                    stage_order,
                    input_count,
                    passed_count,
                    rejected_count
                ) VALUES (
                    :gatingBatchId,
                    'generated',
                    0,
                    1,
                    1,
                    0
                )
                """,
                new MapSqlParameterSource("gatingBatchId", gatingBatchId)
        );
    }

    private void insertGatingResult(UUID gatingBatchId, String syntheticQueryId, String strategy, String queryText, String createdAtIsoUtc) {
        jdbcTemplate.update(
                """
                INSERT INTO synthetic_query_gating_result (
                    gating_batch_id,
                    synthetic_query_id,
                    query_text,
                    generation_strategy,
                    accepted,
                    created_at
                ) VALUES (
                    :gatingBatchId,
                    :syntheticQueryId,
                    :queryText,
                    :strategy,
                    TRUE,
                    CAST(:createdAtIsoUtc AS timestamptz)
                )
                """,
                new MapSqlParameterSource()
                        .addValue("gatingBatchId", gatingBatchId)
                        .addValue("syntheticQueryId", syntheticQueryId)
                        .addValue("queryText", queryText)
                        .addValue("strategy", strategy)
                        .addValue("createdAtIsoUtc", createdAtIsoUtc)
        );
    }

    private long countRows(String sql, UUID id) {
        Long value = jdbcTemplate.queryForObject(
                sql,
                new MapSqlParameterSource("id", id),
                Long.class
        );
        return value == null ? 0L : value;
    }
}
