package io.queryforge.backend.admin.corpus;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
@Sql(scripts = "/sql/corpus_cleanup.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
@Sql(scripts = "/sql/corpus_admin_fixture.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
@Sql(scripts = "/sql/corpus_cleanup.sql", executionPhase = Sql.ExecutionPhase.AFTER_TEST_METHOD)
class CorpusAdminMutationIntegrationTest {

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
    private JdbcTemplate jdbcTemplate;

    @Test
    void glossaryPatchAndAliasCrudWork() throws Exception {
        mockMvc.perform(patch("/api/admin/corpus/glossary/22222222-2222-2222-2222-222222222222")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "keepInEnglish": false,
                                  "active": false,
                                  "descriptionShort": "Manually reviewed term."
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.term.keepInEnglish").value(false))
                .andExpect(jsonPath("$.term.active").value(false))
                .andExpect(jsonPath("$.term.descriptionShort").value("Manually reviewed term."));

        mockMvc.perform(post("/api/admin/corpus/glossary/22222222-2222-2222-2222-222222222222/aliases")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "aliasText": "value-placeholder",
                                  "aliasLanguage": "en",
                                  "aliasType": "kebab"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.aliases.length()").value(3));

        mockMvc.perform(delete("/api/admin/corpus/glossary/aliases/44444444-4444-4444-4444-444444444442"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/admin/corpus/glossary/22222222-2222-2222-2222-222222222222/aliases")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "aliasText": "value-placeholder-2",
                                  "aliasLanguage": "en",
                                  "aliasType": "kebab"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.aliases.length()").value(3));
    }

    @Test
    void sourceEnableToggleWorks() throws Exception {
        mockMvc.perform(patch("/api/admin/corpus/sources/spring-framework-reference")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "enabled": false
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false));
    }

    @Test
    void anchorReExtractionEndpointWorksForScopedChunk() throws Exception {
        mockMvc.perform(post("/api/admin/corpus/anchors/extract")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "chunkIds": ["chk_fixture_2"]
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.targetChunkCount").value(1))
                .andExpect(jsonPath("$.deletedEvidenceCount").value(2))
                .andExpect(jsonPath("$.insertedEvidenceCount").value(greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$.updatedTermCount").value(greaterThanOrEqualTo(1)));
    }

    @Test
    void anchorReExtractionWithDocumentScopeRemovesDocumentAnchorsFirst() throws Exception {
        jdbcTemplate.update("""
                INSERT INTO corpus_glossary_evidence (
                    evidence_id, term_id, document_id, chunk_id, matched_text, line_or_offset_info, import_run_id
                ) VALUES (
                    '55555555-5555-5555-5555-555555555553',
                    '22222222-2222-2222-2222-222222222222',
                    'doc_fixture_1',
                    'chk_fixture_1',
                    '@Value',
                    '{"manual":true}'::jsonb,
                    '11111111-1111-1111-1111-111111111111'
                )
                """);

        mockMvc.perform(post("/api/admin/corpus/anchors/extract")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "documentIds": ["doc_fixture_1"],
                                  "chunkIds": ["chk_fixture_2"]
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.targetChunkCount").value(2))
                .andExpect(jsonPath("$.deletedEvidenceCount").value(3))
                .andExpect(jsonPath("$.insertedEvidenceCount").value(greaterThanOrEqualTo(1)));
    }

    @Test
    void anchorNormalizationDryRunEndpointCreatesReviewRun() throws Exception {
        mockMvc.perform(post("/api/admin/corpus/anchors/normalization-runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "keyword": "Value",
                                  "activeOnly": true,
                                  "limit": 10
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("pending_review"))
                .andExpect(jsonPath("$.candidateCount").value(1))
                .andExpect(jsonPath("$.unchangedCount").value(1))
                .andExpect(jsonPath("$.sourceScopeJson.keyword").value("Value"));
    }

    @Test
    void anchorNormalizationDryRunWithoutLimitTargetsAllActiveAnchors() throws Exception {
        jdbcTemplate.update("""
                INSERT INTO corpus_glossary_terms (
                    term_id, canonical_form, normalized_form, term_type, keep_in_english, description_short,
                    source_confidence, first_seen_document_id, first_seen_chunk_id, evidence_count, is_active,
                    import_run_id, metadata_json
                ) VALUES
                (
                    '66666666-6666-6666-6666-666666666671',
                    '@Bean',
                    '@bean',
                    'annotation',
                    TRUE,
                    'Additional active annotation.',
                    0.9,
                    'doc_fixture_1',
                    'chk_fixture_2',
                    1,
                    TRUE,
                    '11111111-1111-1111-1111-111111111111',
                    '{}'::jsonb
                ),
                (
                    '66666666-6666-6666-6666-666666666672',
                    'WebClient',
                    'webclient',
                    'class',
                    TRUE,
                    'Additional active class.',
                    0.9,
                    'doc_fixture_2',
                    'chk_fixture_3',
                    1,
                    TRUE,
                    '11111111-1111-1111-1111-111111111111',
                    '{}'::jsonb
                ),
                (
                    '66666666-6666-6666-6666-666666666673',
                    'InactiveAnchor',
                    'inactiveanchor',
                    'class',
                    TRUE,
                    'Inactive class should not be included.',
                    0.9,
                    'doc_fixture_2',
                    'chk_fixture_3',
                    1,
                    FALSE,
                    '11111111-1111-1111-1111-111111111111',
                    '{}'::jsonb
                )
                """);

        Integer activeAnchorCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM corpus_glossary_terms WHERE is_active = TRUE",
                Integer.class
        );

        mockMvc.perform(post("/api/admin/corpus/anchors/normalization-runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "activeOnly": true
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.candidateCount").value(activeAnchorCount))
                .andExpect(jsonPath("$.sourceScopeJson.document_id").value(nullValue()))
                .andExpect(jsonPath("$.sourceScopeJson.chunk_id").value(nullValue()))
                .andExpect(jsonPath("$.sourceScopeJson.keyword").value(nullValue()))
                .andExpect(jsonPath("$.sourceScopeJson.limit").value(nullValue()));
    }

    @Test
    void anchorNormalizationRunDeleteRemovesHistoryAndCandidates() throws Exception {
        mockMvc.perform(post("/api/admin/corpus/anchors/normalization-runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "keyword": "Value",
                                  "activeOnly": true,
                                  "limit": 10
                                }
                                """))
                .andExpect(status().isOk());

        UUID runId = jdbcTemplate.queryForObject("""
                SELECT run_id
                FROM anchor_normalization_run
                ORDER BY created_at DESC
                LIMIT 1
                """, UUID.class);
        Integer candidateCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM anchor_normalization_candidate WHERE run_id = ?",
                Integer.class,
                runId
        );
        assertEquals(1, candidateCount);

        mockMvc.perform(delete("/api/admin/corpus/anchors/normalization-runs/{runId}", runId))
                .andExpect(status().isOk());

        Integer remainingRuns = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM anchor_normalization_run WHERE run_id = ?",
                Integer.class,
                runId
        );
        Integer remainingCandidates = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM anchor_normalization_candidate WHERE run_id = ?",
                Integer.class,
                runId
        );
        assertEquals(0, remainingRuns);
        assertEquals(0, remainingCandidates);
    }

    @Test
    void anchorNormalizationCandidateReviewCanSkipConflictBeforeRunApproval() throws Exception {
        jdbcTemplate.update("""
                INSERT INTO corpus_glossary_terms (
                    term_id, canonical_form, normalized_form, term_type, keep_in_english, description_short,
                    source_confidence, first_seen_document_id, first_seen_chunk_id, evidence_count, is_active,
                    import_run_id, metadata_json
                ) VALUES (
                    '66666666-6666-6666-6666-666666666661',
                    'Value}',
                    'value}',
                    'annotation',
                    TRUE,
                    'Conflicting punctuation variant.',
                    0.8,
                    'doc_fixture_1',
                    'chk_fixture_2',
                    1,
                    TRUE,
                    '11111111-1111-1111-1111-111111111111',
                    '{}'::jsonb
                )
                """);

        mockMvc.perform(post("/api/admin/corpus/anchors/normalization-runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "keyword": "Value",
                                  "activeOnly": true,
                                  "limit": 10
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.conflictCount").value(1))
                .andExpect(jsonPath("$.reviewPendingCount").value(1));

        UUID runId = jdbcTemplate.queryForObject("""
                SELECT run_id
                FROM anchor_normalization_run
                ORDER BY created_at DESC
                LIMIT 1
                """, UUID.class);
        UUID conflictCandidateId = jdbcTemplate.queryForObject("""
                SELECT candidate_id
                FROM anchor_normalization_candidate
                WHERE run_id = ?
                  AND resolution_status = 'conflict'
                """, UUID.class, runId);

        mockMvc.perform(post("/api/admin/corpus/anchors/normalization-runs/{runId}/candidate-reviews", runId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "reviewedBy": "test-admin",
                                  "decisions": [
                                    {
                                      "candidateId": "%s",
                                      "decision": "skip",
                                      "note": "duplicate with canonical @Value"
                                    }
                                  ]
                                }
                                """.formatted(conflictCandidateId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.run.reviewSkippedCount").value(1))
                .andExpect(jsonPath("$.run.reviewPendingCount").value(0));

        mockMvc.perform(post("/api/admin/corpus/anchors/normalization-runs/{runId}/approve", runId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "reviewedBy": "test-admin",
                                  "note": "approved after skipping conflict"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("approved"))
                .andExpect(jsonPath("$.appliedUpdateCount").value(0));
    }

    @Test
    void anchorNormalizationCandidateReviewAppliesApprovedWouldUpdateOnly() throws Exception {
        jdbcTemplate.update("""
                INSERT INTO corpus_glossary_terms (
                    term_id, canonical_form, normalized_form, term_type, keep_in_english, description_short,
                    source_confidence, first_seen_document_id, first_seen_chunk_id, evidence_count, is_active,
                    import_run_id, metadata_json
                ) VALUES (
                    '66666666-6666-6666-6666-666666666662',
                    'http {',
                    'http {',
                    'cli',
                    TRUE,
                    'CLI punctuation variant.',
                    0.8,
                    'doc_fixture_1',
                    'chk_fixture_2',
                    1,
                    TRUE,
                    '11111111-1111-1111-1111-111111111111',
                    '{}'::jsonb
                )
                """);

        mockMvc.perform(post("/api/admin/corpus/anchors/normalization-runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "keyword": "http",
                                  "activeOnly": true,
                                  "limit": 10
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.changedCount").value(1))
                .andExpect(jsonPath("$.reviewPendingCount").value(1));

        UUID runId = jdbcTemplate.queryForObject("""
                SELECT run_id
                FROM anchor_normalization_run
                ORDER BY created_at DESC
                LIMIT 1
                """, UUID.class);
        UUID candidateId = jdbcTemplate.queryForObject("""
                SELECT candidate_id
                FROM anchor_normalization_candidate
                WHERE run_id = ?
                  AND resolution_status = 'would_update'
                """, UUID.class, runId);

        mockMvc.perform(post("/api/admin/corpus/anchors/normalization-runs/{runId}/candidate-reviews", runId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "reviewedBy": "test-admin",
                                  "decisions": [
                                    {
                                      "candidateId": "%s",
                                      "decision": "approve"
                                    }
                                  ]
                                }
                                """.formatted(candidateId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.run.reviewApprovedCount").value(1))
                .andExpect(jsonPath("$.run.reviewPendingCount").value(0));

        mockMvc.perform(post("/api/admin/corpus/anchors/normalization-runs/{runId}/approve", runId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "reviewedBy": "test-admin"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("approved"))
                .andExpect(jsonPath("$.appliedUpdateCount").value(1));

        String canonicalForm = jdbcTemplate.queryForObject("""
                SELECT canonical_form
                FROM corpus_glossary_terms
                WHERE term_id = '66666666-6666-6666-6666-666666666662'
                """, String.class);
        assertEquals("http", canonicalForm);
    }

    @Test
    void sourceAutoRegisterFromUrlWorks() throws Exception {
        mockMvc.perform(post("/api/admin/corpus/sources/auto-register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "url": "https://docs.spring.io/spring-framework/reference/integration/rest-clients.html"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sourceId").value("docs-spring-io-spring-framework-reference"))
                .andExpect(jsonPath("$.productName").value("spring-framework"))
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.includePatterns[0]").value("https://docs.spring.io/spring-framework/reference/"));
    }
}
