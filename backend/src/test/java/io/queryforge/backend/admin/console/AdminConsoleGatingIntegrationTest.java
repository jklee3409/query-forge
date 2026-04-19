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

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
    void gatingFunnelSupportsMethodFilter() throws Exception {
        UUID methodA = jdbcTemplate.getJdbcTemplate().queryForObject(
                "SELECT generation_method_id FROM synthetic_query_generation_method WHERE method_code = 'A'",
                UUID.class
        );
        assertThat(methodA).isNotNull();

        UUID gatingBatchId = UUID.randomUUID();
        insertGatingBatch(gatingBatchId, methodA, "completed");

        insertRegistryQuery("sq_funnel_a", "A");
        insertRegistryQuery("sq_funnel_b", "B");

        insertGatingResult(gatingBatchId, "sq_funnel_a", "A", "A query", true, true, true, true, true, "2026-04-13T10:00:00Z");
        insertGatingResult(gatingBatchId, "sq_funnel_b", "B", "B query", false, false, false, false, false, "2026-04-13T10:00:01Z");

        mockMvc.perform(get("/api/admin/console/gating/batches/{gatingBatchId}/funnel", gatingBatchId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.generatedTotal").value(2))
                .andExpect(jsonPath("$.passedRule").value(1))
                .andExpect(jsonPath("$.passedLlm").value(1))
                .andExpect(jsonPath("$.passedUtility").value(1))
                .andExpect(jsonPath("$.passedDiversity").value(1))
                .andExpect(jsonPath("$.finalAccepted").value(1));

        mockMvc.perform(get("/api/admin/console/gating/batches/{gatingBatchId}/funnel", gatingBatchId)
                        .param("method_code", "A"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.methodCode").value("A"))
                .andExpect(jsonPath("$.generatedTotal").value(1))
                .andExpect(jsonPath("$.passedRule").value(1))
                .andExpect(jsonPath("$.passedLlm").value(1))
                .andExpect(jsonPath("$.passedUtility").value(1))
                .andExpect(jsonPath("$.passedDiversity").value(1))
                .andExpect(jsonPath("$.finalAccepted").value(1));

        mockMvc.perform(get("/api/admin/console/gating/batches/{gatingBatchId}/funnel", gatingBatchId)
                        .param("method_code", "B"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.methodCode").value("B"))
                .andExpect(jsonPath("$.generatedTotal").value(1))
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
    void gatingResultsSupportsPassStageFilter() throws Exception {
        UUID methodA = jdbcTemplate.getJdbcTemplate().queryForObject(
                "SELECT generation_method_id FROM synthetic_query_generation_method WHERE method_code = 'A'",
                UUID.class
        );
        assertThat(methodA).isNotNull();

        UUID gatingBatchId = UUID.randomUUID();
        insertGatingBatch(gatingBatchId, methodA, "completed");

        insertRegistryQuery("sq_stage_rule_fail", "A");
        insertRegistryQuery("sq_stage_rule_pass_llm_fail", "A");
        insertRegistryQuery("sq_stage_llm_pass_utility_fail", "A");
        insertRegistryQuery("sq_stage_utility_pass_diversity_fail", "A");
        insertRegistryQuery("sq_stage_diversity_pass_final_fail", "A");
        insertRegistryQuery("sq_stage_all_pass", "A");

        insertGatingResult(gatingBatchId, "sq_stage_rule_fail", "A", "Rule failed", false, false, false, false, false, "2026-04-13T10:00:00Z");
        insertGatingResult(gatingBatchId, "sq_stage_rule_pass_llm_fail", "A", "Rule pass LLM fail", true, false, false, false, false, "2026-04-13T10:00:01Z");
        insertGatingResult(gatingBatchId, "sq_stage_llm_pass_utility_fail", "A", "LLM pass Utility fail", true, true, false, false, false, "2026-04-13T10:00:02Z");
        insertGatingResult(gatingBatchId, "sq_stage_utility_pass_diversity_fail", "A", "Utility pass Diversity fail", true, true, true, false, false, "2026-04-13T10:00:03Z");
        insertGatingResult(gatingBatchId, "sq_stage_diversity_pass_final_fail", "A", "Diversity pass Final fail", true, true, true, true, false, "2026-04-13T10:00:04Z");
        insertGatingResult(gatingBatchId, "sq_stage_all_pass", "A", "All pass", true, true, true, true, true, "2026-04-13T10:00:05Z");

        mockMvc.perform(get("/api/admin/console/gating/batches/{gatingBatchId}/results", gatingBatchId)
                        .param("pass_stage", "failed_rule"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].syntheticQueryId").value("sq_stage_rule_fail"));

        mockMvc.perform(get("/api/admin/console/gating/batches/{gatingBatchId}/results", gatingBatchId)
                        .param("pass_stage", "rejected"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].syntheticQueryId").value("sq_stage_rule_fail"));

        mockMvc.perform(get("/api/admin/console/gating/batches/{gatingBatchId}/results", gatingBatchId)
                        .param("pass_stage", "passed_rule"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].syntheticQueryId").value("sq_stage_rule_pass_llm_fail"));

        mockMvc.perform(get("/api/admin/console/gating/batches/{gatingBatchId}/results", gatingBatchId)
                        .param("pass_stage", "passed_llm"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].syntheticQueryId").value("sq_stage_llm_pass_utility_fail"));

        mockMvc.perform(get("/api/admin/console/gating/batches/{gatingBatchId}/results", gatingBatchId)
                        .param("pass_stage", "passed_utility"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].syntheticQueryId").value("sq_stage_utility_pass_diversity_fail"));

        mockMvc.perform(get("/api/admin/console/gating/batches/{gatingBatchId}/results", gatingBatchId)
                        .param("pass_stage", "passed_diversity"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].syntheticQueryId").value("sq_stage_diversity_pass_final_fail"));

        mockMvc.perform(get("/api/admin/console/gating/batches/{gatingBatchId}/results", gatingBatchId)
                        .param("pass_stage", "passed_all"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].syntheticQueryId").value("sq_stage_all_pass"));
    }

    @Test
    void gatingResultsRejectsUnsupportedPassStage() throws Exception {
        UUID methodA = jdbcTemplate.getJdbcTemplate().queryForObject(
                "SELECT generation_method_id FROM synthetic_query_generation_method WHERE method_code = 'A'",
                UUID.class
        );
        assertThat(methodA).isNotNull();

        UUID gatingBatchId = UUID.randomUUID();
        insertGatingBatch(gatingBatchId, methodA, "completed");

        mockMvc.perform(get("/api/admin/console/gating/batches/{gatingBatchId}/results", gatingBatchId)
                        .param("pass_stage", "unknown"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("unsupported pass_stage: unknown"));
    }

    @Test
    void runGatingRequiresGenerationBatchSelection() throws Exception {
        mockMvc.perform(post("/api/admin/console/gating/batches/run")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {
                                  "methodCode": "A",
                                  "gatingPreset": "full_gating"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("generation_batch_id is required"));
    }

    @Test
    void runGatingAppliesNestedWeightsAndWritesExperimentConfig() throws Exception {
        UUID methodA = jdbcTemplate.getJdbcTemplate().queryForObject(
                "SELECT generation_method_id FROM synthetic_query_generation_method WHERE method_code = 'A'",
                UUID.class
        );
        assertThat(methodA).isNotNull();
        UUID sourceRunId = UUID.randomUUID();
        UUID generationBatchId = insertGenerationBatch(methodA, "a-batch-with-source", sourceRunId);

        String response = mockMvc.perform(post("/api/admin/console/gating/batches/run")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {
                                  "methodCode": "A",
                                  "generationBatchId": "%s",
                                  "gatingPreset": "full_gating",
                                  "config": {
                                    "stageFlags": {
                                      "enableRuleFilter": true,
                                      "enableLlmSelfEval": true,
                                      "enableRetrievalUtility": true,
                                      "enableDiversity": true
                                    },
                                    "ruleConfig": {
                                      "minLengthShort": 5,
                                      "maxLengthShort": 61,
                                      "minLengthLong": 9,
                                      "maxLengthLong": 101,
                                      "minTokens": 3,
                                      "maxTokens": 31,
                                      "minKoreanRatio": 0.31
                                    },
                                    "gatingWeights": {
                                      "llmWeight": 0.25,
                                      "utilityWeight": 0.55,
                                      "diversityWeight": 0.20
                                    },
                                    "utilityScoreWeights": {
                                      "targetTop1Score": 0.99,
                                      "targetTop3Score": 0.88,
                                      "targetTop5Score": 0.77,
                                      "targetTop10Score": 0.58,
                                      "sameDocTop3Score": 0.54,
                                      "sameDocTop5Score": 0.42,
                                      "outsideTop5Score": 0.11,
                                      "multiPartialBonus": 0.06,
                                      "multiFullBonus": 0.13
                                    },
                                    "thresholds": {
                                      "utilityThreshold": 0.66,
                                      "diversityThresholdSameChunk": 0.91,
                                      "diversityThresholdSameDoc": 0.95,
                                      "finalScoreThreshold": 0.74
                                    }
                                  }
                                }
                                """.formatted(generationBatchId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stageConfig.utility_score_weights.target_top10").value(0.58))
                .andExpect(jsonPath("$.stageConfig.utility_threshold").value(0.66))
                .andReturn()
                .getResponse()
                .getContentAsString();

        UUID gatingBatchId = UUID.fromString(objectMapper().readTree(response).path("gatingBatchId").asText());
        String experimentName = jdbcTemplate.queryForObject(
                """
                SELECT experiment_name
                FROM llm_job
                WHERE gating_batch_id = :gatingBatchId
                ORDER BY created_at DESC
                LIMIT 1
                """,
                new MapSqlParameterSource("gatingBatchId", gatingBatchId),
                String.class
        );
        assertThat(experimentName).isNotBlank();

        Path configPath = resolveExperimentConfigPath(experimentName);
        assertThat(Files.exists(configPath)).isTrue();
        String yaml = Files.readString(configPath, StandardCharsets.UTF_8);
        assertThat(yaml).contains("target_top10: 0.58");
        assertThat(yaml).contains("utility_threshold: 0.66");
        assertThat(yaml).contains("final_score_threshold: 0.74");

        Files.deleteIfExists(configPath);
    }

    @Test
    void clearCompletedGatingResultsRemovesOnlyCompletedRowsForTargetMethodAndGenerationBatch() {
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

        UUID generationBatchA1 = insertGenerationBatch(methodA, "a-batch-1");
        UUID generationBatchA2 = insertGenerationBatch(methodA, "a-batch-2");
        UUID generationBatchB1 = insertGenerationBatch(methodB, "b-batch-1");

        UUID completedBatchA1 = UUID.randomUUID();
        UUID runningBatchA1 = UUID.randomUUID();
        UUID completedBatchA2 = UUID.randomUUID();
        UUID completedBatchB1 = UUID.randomUUID();
        insertGatingBatch(completedBatchA1, methodA, generationBatchA1, "completed");
        insertGatingBatch(runningBatchA1, methodA, generationBatchA1, "running");
        insertGatingBatch(completedBatchA2, methodA, generationBatchA2, "completed");
        insertGatingBatch(completedBatchB1, methodB, generationBatchB1, "completed");

        insertRegistryQuery("sq_a1_completed", "A");
        insertRegistryQuery("sq_a1_running", "A");
        insertRegistryQuery("sq_a2_completed", "A");
        insertRegistryQuery("sq_b1_completed", "B");

        insertGatedRow("gated_a1_completed", "sq_a1_completed", completedBatchA1);
        insertGatedRow("gated_a1_running", "sq_a1_running", runningBatchA1);
        insertGatedRow("gated_a2_completed", "sq_a2_completed", completedBatchA2);
        insertGatedRow("gated_b1_completed", "sq_b1_completed", completedBatchB1);

        insertBatchArtifacts(completedBatchA1, "sq_a1_completed");
        insertBatchArtifacts(completedBatchA2, "sq_a2_completed");
        insertBatchArtifacts(completedBatchB1, "sq_b1_completed");

        int removed = adminConsoleRepository.clearCompletedGatingResults(methodA, generationBatchA1);
        assertThat(removed).isEqualTo(1);

        assertThat(countRows("SELECT COUNT(*) FROM quality_gating_batch WHERE gating_batch_id = :id", completedBatchA1)).isZero();
        assertThat(countRows("SELECT COUNT(*) FROM quality_gating_batch WHERE gating_batch_id = :id", runningBatchA1)).isEqualTo(1);
        assertThat(countRows("SELECT COUNT(*) FROM quality_gating_batch WHERE gating_batch_id = :id", completedBatchA2)).isEqualTo(1);
        assertThat(countRows("SELECT COUNT(*) FROM quality_gating_batch WHERE gating_batch_id = :id", completedBatchB1)).isEqualTo(1);

        assertThat(countRows("SELECT COUNT(*) FROM synthetic_query_gating_result WHERE gating_batch_id = :id", completedBatchA1)).isZero();
        assertThat(countRows("SELECT COUNT(*) FROM synthetic_query_gating_history WHERE gating_batch_id = :id", completedBatchA1)).isZero();
        assertThat(countRows("SELECT COUNT(*) FROM quality_gating_stage_result WHERE gating_batch_id = :id", completedBatchA1)).isZero();

        UUID completedBatchA1Ref = jdbcTemplate.queryForObject(
                "SELECT gating_batch_id FROM synthetic_queries_gated WHERE gated_query_id = 'gated_a1_completed'",
                new MapSqlParameterSource(),
                UUID.class
        );
        UUID runningBatchA1Ref = jdbcTemplate.queryForObject(
                "SELECT gating_batch_id FROM synthetic_queries_gated WHERE gated_query_id = 'gated_a1_running'",
                new MapSqlParameterSource(),
                UUID.class
        );
        UUID completedBatchA2Ref = jdbcTemplate.queryForObject(
                "SELECT gating_batch_id FROM synthetic_queries_gated WHERE gated_query_id = 'gated_a2_completed'",
                new MapSqlParameterSource(),
                UUID.class
        );
        UUID methodBBatchRef = jdbcTemplate.queryForObject(
                "SELECT gating_batch_id FROM synthetic_queries_gated WHERE gated_query_id = 'gated_b1_completed'",
                new MapSqlParameterSource(),
                UUID.class
        );
        assertThat(completedBatchA1Ref).isNull();
        assertThat(runningBatchA1Ref).isEqualTo(runningBatchA1);
        assertThat(completedBatchA2Ref).isEqualTo(completedBatchA2);
        assertThat(methodBBatchRef).isEqualTo(completedBatchB1);
    }

    private UUID insertGenerationBatch(UUID methodId, String versionName) {
        return insertGenerationBatch(methodId, versionName, null);
    }

    private UUID insertGenerationBatch(UUID methodId, String versionName, UUID sourceGenerationRunId) {
        UUID generationBatchId = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO synthetic_query_generation_batch (
                    batch_id,
                    generation_method_id,
                    version_name,
                    source_generation_run_id,
                    status,
                    created_by
                ) VALUES (
                    :batchId,
                    :generationMethodId,
                    :versionName,
                    :sourceGenerationRunId,
                    'completed',
                    'test-admin'
                )
                """,
                new MapSqlParameterSource()
                        .addValue("batchId", generationBatchId)
                        .addValue("generationMethodId", methodId)
                        .addValue("versionName", versionName)
                        .addValue("sourceGenerationRunId", sourceGenerationRunId)
        );
        return generationBatchId;
    }

    private void insertGatingBatch(UUID gatingBatchId, UUID methodId, String status) {
        insertGatingBatch(gatingBatchId, methodId, null, status);
    }

    private void insertGatingBatch(UUID gatingBatchId, UUID methodId, UUID generationBatchId, String status) {
        jdbcTemplate.update(
                """
                INSERT INTO quality_gating_batch (
                    gating_batch_id,
                    gating_preset,
                    generation_method_id,
                    generation_batch_id,
                    stage_config_json,
                    status,
                    created_by
                ) VALUES (
                    :gatingBatchId,
                    'full_gating',
                    :generationMethodId,
                    :generationBatchId,
                    '{}'::jsonb,
                    :status,
                    'test-admin'
                )
                """,
                new MapSqlParameterSource()
                        .addValue("gatingBatchId", gatingBatchId)
                        .addValue("generationMethodId", methodId)
                        .addValue("generationBatchId", generationBatchId)
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
        insertGatingResult(gatingBatchId, syntheticQueryId, strategy, queryText, true, true, true, true, true, createdAtIsoUtc);
    }

    private void insertGatingResult(
            UUID gatingBatchId,
            String syntheticQueryId,
            String strategy,
            String queryText,
            boolean rulePass,
            boolean llmPass,
            boolean utilityPass,
            boolean diversityPass,
            boolean accepted,
            String createdAtIsoUtc
    ) {
        jdbcTemplate.update(
                """
                INSERT INTO synthetic_query_gating_result (
                    gating_batch_id,
                    synthetic_query_id,
                    query_text,
                    generation_strategy,
                    rule_pass,
                    stage_payload_json,
                    diversity_pass,
                    accepted,
                    created_at
                ) VALUES (
                    :gatingBatchId,
                    :syntheticQueryId,
                    :queryText,
                    :strategy,
                    :rulePass,
                    CAST(:stagePayloadJson AS jsonb),
                    :diversityPass,
                    :accepted,
                    CAST(:createdAtIsoUtc AS timestamptz)
                )
                """,
                new MapSqlParameterSource()
                        .addValue("gatingBatchId", gatingBatchId)
                        .addValue("syntheticQueryId", syntheticQueryId)
                        .addValue("queryText", queryText)
                        .addValue("strategy", strategy)
                        .addValue("rulePass", rulePass)
                        .addValue("stagePayloadJson", "{\"passed_llm_self_eval\":" + llmPass + ",\"passed_retrieval_utility\":" + utilityPass + "}")
                        .addValue("diversityPass", diversityPass)
                        .addValue("accepted", accepted)
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

    private Path resolveExperimentConfigPath(String experimentName) {
        Path inRepoRoot = Path.of("configs", "experiments", experimentName + ".yaml").toAbsolutePath().normalize();
        if (Files.exists(inRepoRoot)) {
            return inRepoRoot;
        }
        Path inParent = Path.of("..", "configs", "experiments", experimentName + ".yaml").toAbsolutePath().normalize();
        if (Files.exists(inParent)) {
            return inParent;
        }
        return inRepoRoot;
    }

    private com.fasterxml.jackson.databind.ObjectMapper objectMapper() {
        return new com.fasterxml.jackson.databind.ObjectMapper();
    }
}
