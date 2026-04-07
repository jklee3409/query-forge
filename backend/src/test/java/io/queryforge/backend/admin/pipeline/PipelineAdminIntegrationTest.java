package io.queryforge.backend.admin.pipeline;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.queryforge.backend.admin.pipeline.service.PipelineCommandRunner;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
@Sql(scripts = "/sql/corpus_cleanup.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
@Sql(scripts = "/sql/corpus_cleanup.sql", executionPhase = Sql.ExecutionPhase.AFTER_TEST_METHOD)
class PipelineAdminIntegrationTest {

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
    private ObjectMapper objectMapper;

    @Test
    void fullIngestRunCreatesAndCompletesSteps() throws Exception {
        MvcResult startResult = mockMvc.perform(post("/api/admin/pipeline/full-ingest")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "sourceIds": ["spring-framework-reference"],
                                  "createdBy": "test-admin"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.runStatus").value("queued"))
                .andReturn();

        JsonNode startPayload = objectMapper.readTree(startResult.getResponse().getContentAsString());
        String runId = startPayload.path("runId").asText();

        JsonNode runDetail = awaitRunSuccess(runId);
        assertThat(runDetail.path("steps")).hasSize(5);
        assertThat(runDetail.at("/steps/0/stepStatus").asText()).isEqualTo("success");
        assertThat(runDetail.at("/steps/4/stepStatus").asText()).isEqualTo("success");
        String repoRoot = detectRepoRoot().toString();
        assertThat(runDetail.at("/steps/0/stdoutLogPath").asText())
                .startsWith(repoRoot)
                .contains("collect.stdout.log");
        assertThat(runDetail.at("/steps/0/outputArtifactPath").asText()).startsWith(repoRoot);

        mockMvc.perform(get("/api/admin/pipeline/runs/{runId}/logs", runId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.steps.length()").value(5))
                .andExpect(jsonPath("$.steps[0].stdout").value(org.hamcrest.Matchers.containsString("collect-docs")));
    }

    private JsonNode awaitRunSuccess(String runId) throws Exception {
        for (int attempt = 0; attempt < 40; attempt++) {
            MvcResult result = mockMvc.perform(get("/api/admin/pipeline/runs/{runId}", runId))
                    .andExpect(status().isOk())
                    .andReturn();
            JsonNode payload = objectMapper.readTree(result.getResponse().getContentAsString());
            String runStatus = payload.path("run").path("runStatus").asText();
            if ("success".equals(runStatus)) {
                return payload;
            }
            Thread.sleep(150L);
        }
        throw new AssertionError("Run did not complete successfully in time.");
    }

    private Path detectRepoRoot() {
        Path current = Path.of("").toAbsolutePath().normalize();
        if (Files.exists(current.resolve("pipeline/cli.py"))) {
            return current;
        }
        Path parent = current.getParent();
        if (parent != null && Files.exists(parent.resolve("pipeline/cli.py"))) {
            return parent;
        }
        throw new AssertionError("Failed to detect query-forge repo root for integration test.");
    }

    @TestConfiguration
    static class StubPipelineRunnerConfiguration {

        @Bean
        @Primary
        PipelineCommandRunner pipelineCommandRunner() {
            return new PipelineCommandRunner() {
                @Override
                public CommandResult run(CommandRequest request, ProcessObserver observer) throws java.io.IOException {
                    observer.onStart(ProcessHandle.current());
                    Files.createDirectories(request.stdoutPath().getParent());
                    Files.createDirectories(request.stderrPath().getParent());
                    String subCommand = request.command().size() > 2 ? request.command().get(2) : "unknown";
                    Files.writeString(
                            request.stdoutPath(),
                            new ObjectMapper().writeValueAsString(Map.of(
                                    "step", subCommand,
                                    "output_path", request.stdoutPath().toString()
                            )),
                            StandardCharsets.UTF_8
                    );
                    Files.writeString(request.stderrPath(), "", StandardCharsets.UTF_8);
                    return new CommandResult(
                            0,
                            request.command(),
                            Files.readString(request.stdoutPath(), StandardCharsets.UTF_8),
                            "",
                            request.stdoutPath(),
                            request.stderrPath(),
                            ProcessHandle.current(),
                            Instant.now()
                    );
                }

                @Override
                public boolean cancel(ProcessHandle processHandle) {
                    return true;
                }
            };
        }
    }
}
