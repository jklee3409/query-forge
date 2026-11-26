package io.queryforge.backend.admin.console.jobs.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.queryforge.backend.admin.console.jobs.dto.LlmJobDtos;
import io.queryforge.backend.admin.console.jobs.model.LlmJobType;
import io.queryforge.backend.admin.console.jobs.repository.LlmJobRepository;
import io.queryforge.backend.admin.console.jobs.result.service.LlmJobResultService;
import io.queryforge.backend.admin.console.jobs.service.LlmJobService;
import io.queryforge.backend.rag.experiment.command.dto.ExperimentCommandDtos;
import io.queryforge.backend.rag.experiment.command.service.ExperimentPipelineService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class LlmJobFinalizationIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres")
    );

    @TempDir
    static Path repoRoot;

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("query-forge.admin.pipeline.repo-root", () -> repoRoot.toString());
    }

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private LlmJobRepository jobs;

    @Autowired
    private LlmJobService worker;

    @Autowired
    private LlmJobResultService resultService;

    @MockitoBean(name = "llmJobExecutor")
    private ExecutorService executor;

    @MockitoBean
    private ExperimentPipelineService pipeline;

    @BeforeEach
    void createReportDirectories() throws IOException {
        Files.createDirectories(repoRoot.resolve("pipeline"));
        Files.writeString(repoRoot.resolve("pipeline/cli.py"), "");
        Files.createDirectories(repoRoot.resolve("data/reports"));
    }

    @ParameterizedTest
    @EnumSource(FailurePoint.class)
    void rollsBackRagResultsAndRetriesWithoutRunningPython(FailurePoint failurePoint) throws Exception {
        RagRun run = createRun();
        writeReport(run);
        jobs.markJobFailed(run.jobId(), "previous failure", objectMapper.createObjectNode());

        String table = failurePoint == FailurePoint.ANCHOR_INSERT ? "rag_rewrite_anchor_eval" : "llm_job";
        String check = failurePoint == FailurePoint.ANCHOR_INSERT
                ? "rag_test_run_id <> '%s'::uuid OR anchor_text <> 'Transactional'".formatted(run.runId())
                : "job_id <> '%s'::uuid OR job_status <> 'completed'".formatted(run.jobId());
        jdbc.execute("ALTER TABLE " + table + " ADD CONSTRAINT reject_finalization CHECK (" + check + ")");
        try {
            retryAndRunWorker(run.jobId());
            LlmJobDtos.LlmJobRow failedJob = job(run);
            assertThat(failedJob.jobStatus()).isEqualTo("failed");
            assertThat(failedJob.errorMessage()).contains("reject_finalization");
            assertThat(runStatus(run)).isEqualTo("failed");
            assertPreviousResults(run);
            assertThat(jobs.findJobItems(run.jobId())).extracting(LlmJobDtos.LlmJobItemRow::itemStatus)
                    .containsExactly("completed");
        } finally {
            jdbc.execute("ALTER TABLE " + table + " DROP CONSTRAINT reject_finalization");
        }

        retryAndRunWorker(run.jobId());

        assertThat(job(run).jobStatus()).isEqualTo("completed");
        assertThat(runStatus(run)).isEqualTo("completed");
        assertThat(summaryMrr(run)).isEqualTo(0.75);
        assertThat(detailIds(run)).hasSize(1).doesNotContain(run.originalDetailId());
        assertThat(anchorTexts(run)).containsExactly("Transactional");
        assertThat(jdbc.queryForObject("""
                SELECT rag_test_result_detail_id FROM rag_rewrite_anchor_eval WHERE rag_test_run_id = ?
                """, UUID.class, run.runId())).isEqualTo(detailIds(run).getFirst());
        verifyNoInteractions(pipeline);
    }

    @Test
    void resumesAnswerWithTheCompletedRetrievalRun() throws Exception {
        RagRun run = createRun();
        writeReport(run);
        UUID retrievalRunId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO experiment_runs (experiment_run_id, run_label, status)
                VALUES (?, 'eval-retrieval', 'completed')
                """, retrievalRunId);
        LlmJobDtos.LlmJobItemRow retrieval = jobs.findJobItems(run.jobId()).getFirst();
        jobs.markItemCompleted(retrieval.jobItemId(), objectMapper.createObjectNode()
                .put("eval_policy_version", 2)
                .put("experiment_run_id", retrievalRunId.toString())
                .set("summary", run.results().get("eval-retrieval").get("summary")));
        String experiment = job(run).experimentName();
        jobs.createJobItem(run.jobId(), 2, "eval-answer",
                objectMapper.createObjectNode().put("command", "eval-answer"), 0);
        jobs.markJobFailed(run.jobId(), "previous failure", objectMapper.createObjectNode());
        var request = new ExperimentCommandDtos.ExperimentCommandRequest("eval-answer", experiment);
        when(pipeline.run(request, run.jobId().toString(), retrievalRunId))
                .thenReturn(new ExperimentCommandDtos.ExperimentCommandResponse(
                        "eval-answer", experiment, 0, objectMapper.createObjectNode(), "", ""
                ));

        retryAndRunWorker(run.jobId());

        assertThat(job(run).jobStatus()).isEqualTo("completed");
        verify(pipeline).run(request, run.jobId().toString(), retrievalRunId);
        verifyNoMoreInteractions(pipeline);
    }

    @Test
    void preservesGeneratedQueriesAfterAFailedFinalization() {
        UUID batchId = UUID.randomUUID();
        UUID sourceRunId = UUID.randomUUID();
        String queryId = "query_" + batchId;
        jdbc.update("""
                INSERT INTO experiment_runs (experiment_run_id, run_label, status) VALUES (?, 'generation', 'completed')
                """, sourceRunId);
        jdbc.update("""
                INSERT INTO synthetic_query_generation_batch (batch_id, generation_method_id, version_name)
                SELECT ?, generation_method_id, 'generation' FROM synthetic_query_generation_method WHERE method_code = 'A'
                """, batchId);
        jdbc.update("""
                INSERT INTO synthetic_queries_raw_a (synthetic_query_id, experiment_run_id, generation_batch_id,
                    answerability_type, query_text, query_type, generation_strategy)
                VALUES (?, ?, ?, 'single', 'Transactional 설정 방법', 'standard', 'A')
                """, queryId, sourceRunId, batchId);

        var emptyJson = objectMapper.createObjectNode();
        UUID jobId = jobs.createJob(LlmJobType.GENERATE_SYNTHETIC_QUERY.name(), "generate-queries", "generation",
                emptyJson, batchId, null, null, 1, 0, "test");
        UUID itemId = jobs.createJobItem(jobId, 1, "generate-queries", emptyJson, 0);
        jobs.markItemCompleted(itemId, objectMapper.createObjectNode()
                .put("experiment_run_id", sourceRunId.toString()).put("generated_queries", 1));
        jobs.markJobFailed(jobId, "previous failure", emptyJson);

        jdbc.execute("""
                ALTER TABLE llm_job ADD CONSTRAINT reject_finalization
                CHECK (job_id <> '%s'::uuid OR job_status <> 'completed')
                """.formatted(jobId));
        String sourceLinks = "SELECT count(*) FROM synthetic_query_source_link WHERE synthetic_query_id = ?";
        try {
            retryAndRunWorker(jobId);
            assertThat(jobs.findJob(jobId).orElseThrow().jobStatus()).isEqualTo("failed");
            assertThat(jdbc.queryForObject("SELECT generation_batch_id FROM synthetic_queries_raw_a WHERE synthetic_query_id = ?",
                    UUID.class, queryId)).isEqualTo(batchId);
            assertThat(jdbc.queryForObject(sourceLinks, Long.class, queryId)).isZero();
        } finally {
            jdbc.execute("ALTER TABLE llm_job DROP CONSTRAINT reject_finalization");
        }

        retryAndRunWorker(jobId);

        assertThat(jobs.findJob(jobId).orElseThrow().jobStatus()).isEqualTo("completed");
        assertThat(jdbc.queryForObject("SELECT total_generated_count FROM synthetic_query_generation_batch WHERE batch_id = ?",
                Integer.class, batchId)).isEqualTo(1);
        assertThat(jdbc.queryForObject(sourceLinks, Long.class, queryId)).isEqualTo(1);
        verifyNoInteractions(pipeline);
    }

    @Test
    void failsOnMissingOrInvalidReportAndAcceptsAnEmptyArray() throws Exception {
        RagRun run = createRun();
        assertThat(jobs.tryMarkJobRunning(run.jobId())).isTrue();

        assertThatThrownBy(() -> resultService.finalizeJob(job(run), run.results()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("failed to read rewrite case report");
        Files.writeString(run.report(), "[] []");
        assertThatThrownBy(() -> resultService.finalizeJob(job(run), run.results()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("failed to read rewrite case report");
        Files.writeString(run.report(), "{}");
        assertThatThrownBy(() -> resultService.finalizeJob(job(run), run.results()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("expected a JSON array");
        assertThat(job(run).jobStatus()).isEqualTo("running");
        assertPreviousResults(run);

        Files.writeString(run.report(), "[]");
        assertThat(resultService.finalizeJob(job(run), run.results())).isEqualTo("completed");
        assertThat(detailIds(run)).isEmpty();
        assertThat(anchorTexts(run)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void leavesResultsIntactWhenStopWasRequested(boolean pause) throws Exception {
        RagRun run = createRun();
        writeReport(run);
        assertThat(jobs.tryMarkJobRunning(run.jobId())).isTrue();
        LlmJobDtos.LlmJobRow runningJob = job(run);
        if (pause) {
            jobs.requestPause(run.jobId());
        } else {
            jobs.requestCancel(run.jobId());
        }

        String expectedStatus = pause ? "pause_requested" : "cancel_requested";
        assertThat(resultService.finalizeJob(runningJob, run.results())).isEqualTo(expectedStatus);
        assertThat(job(run).jobStatus()).isEqualTo(expectedStatus);
        assertThat(runStatus(run)).isEqualTo("running");
        assertPreviousResults(run);
    }

    private RagRun createRun() throws IOException {
        UUID runId = UUID.randomUUID();
        UUID detailId = UUID.randomUUID();
        String experimentName = "worker_" + runId;
        String sampleId = "sample_" + runId;
        jdbc.update("""
                INSERT INTO eval_samples (sample_id, split, user_query_ko, query_category)
                VALUES (?, 'test', 'Transactional 설정 방법', 'configuration')
                """, sampleId);
        jdbc.update("""
                INSERT INTO rag_test_run (rag_test_run_id, status, experiment_config_name) VALUES (?, 'running', ?)
                """, runId, experimentName);
        jdbc.update("INSERT INTO rag_test_result_summary (rag_test_run_id, mrr_at_10) VALUES (?, 0.25)", runId);
        jdbc.update("""
                INSERT INTO rag_eval_experiment_record (rag_test_run_id, snapshot_id) VALUES (?, 'previous-snapshot')
                """, runId);
        jdbc.update("""
                INSERT INTO rag_test_result_detail (detail_id, rag_test_run_id, sample_id) VALUES (?, ?, ?)
                """, detailId, runId, sampleId);
        jdbc.update("""
                INSERT INTO rag_rewrite_anchor_eval (rag_test_run_id, rag_test_result_detail_id, sample_id,
                    original_query, final_rewrite_query, anchor_text, normalized_anchor_text, anchor_source)
                VALUES (?, ?, ?, 'old query', 'old rewrite', 'old anchor', 'oldanchor', 'preserved_raw')
                """, runId, detailId, sampleId);

        UUID jobId = jobs.createJob(LlmJobType.RUN_RAG_TEST.name(), "rag-pipeline", experimentName,
                objectMapper.createObjectNode(), null, null, runId, 1, 0, "test");
        UUID itemId = jobs.createJobItem(jobId, 1, "eval-retrieval",
                objectMapper.createObjectNode().put("command", "eval-retrieval"), 0);
        JsonNode retrievalResult = objectMapper.readTree("""
                {"summary": [{"mode": "raw_only", "mrr@10": 0.75}]}
                """);
        jobs.markItemCompleted(itemId, retrievalResult);
        Path report = repoRoot.resolve("data/reports/rewrite_cases_" + experimentName + ".json");
        return new RagRun(runId, jobId, detailId, sampleId, report, Map.of("eval-retrieval", retrievalResult));
    }

    private void writeReport(RagRun run) throws IOException {
        Files.writeString(run.report(), """
                [{"sample_id": "%s", "mode": "selective_rewrite", "final_query": "Transactional settings",
                  "rewrite_applied": true, "anchor_candidates": [{"term": "Transactional", "source": "raw_query"}]}]
                """.formatted(run.sampleId()));
    }

    private void retryAndRunWorker(UUID jobId) {
        clearInvocations(executor);
        worker.retryJob(jobId);
        ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
        verify(executor).execute(task.capture());
        // These tests exercise finalization after a due retry, independently of the container clock.
        jdbc.update("UPDATE llm_job SET next_run_at = TIMESTAMPTZ '2000-01-01 00:00:00+00' WHERE job_id = ?", jobId);
        task.getValue().run();
    }

    private void assertPreviousResults(RagRun run) {
        assertThat(summaryMrr(run)).isEqualTo(0.25);
        assertThat(detailIds(run)).containsExactly(run.originalDetailId());
        assertThat(anchorTexts(run)).containsExactly("old anchor");
        assertThat(jdbc.queryForObject("""
                SELECT snapshot_id FROM rag_eval_experiment_record WHERE rag_test_run_id = ?
                """, String.class, run.runId())).isEqualTo("previous-snapshot");
    }

    private LlmJobDtos.LlmJobRow job(RagRun run) {
        return jobs.findJob(run.jobId()).orElseThrow();
    }

    private String runStatus(RagRun run) {
        return jdbc.queryForObject("SELECT status FROM rag_test_run WHERE rag_test_run_id = ?", String.class, run.runId());
    }

    private Double summaryMrr(RagRun run) {
        return jdbc.queryForObject("SELECT mrr_at_10 FROM rag_test_result_summary WHERE rag_test_run_id = ?",
                Double.class, run.runId());
    }

    private List<UUID> detailIds(RagRun run) {
        return jdbc.queryForList("SELECT detail_id FROM rag_test_result_detail WHERE rag_test_run_id = ?",
                UUID.class, run.runId());
    }

    private List<String> anchorTexts(RagRun run) {
        return jdbc.queryForList("SELECT anchor_text FROM rag_rewrite_anchor_eval WHERE rag_test_run_id = ?",
                String.class, run.runId());
    }

    enum FailurePoint {
        ANCHOR_INSERT,
        JOB_COMPLETION
    }

    private record RagRun(UUID runId, UUID jobId, UUID originalDetailId, String sampleId, Path report,
                          Map<String, JsonNode> results) {
    }
}
