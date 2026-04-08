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
}
