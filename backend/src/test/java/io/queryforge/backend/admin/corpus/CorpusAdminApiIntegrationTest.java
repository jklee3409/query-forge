package io.queryforge.backend.admin.corpus;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
@Sql(scripts = "/sql/corpus_cleanup.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
@Sql(scripts = "/sql/corpus_admin_fixture.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
@Sql(scripts = "/sql/corpus_cleanup.sql", executionPhase = Sql.ExecutionPhase.AFTER_TEST_METHOD)
class CorpusAdminApiIntegrationTest {

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

    @Test
    void documentHierarchyAndPreviewEndpointsWork() throws Exception {
        mockMvc.perform(get("/api/admin/corpus/documents")
                        .param("document_id", "doc_fixture_1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].documentId").value("doc_fixture_1"))
                .andExpect(jsonPath("$[0].chunkCount").value(2));

        mockMvc.perform(get("/api/admin/corpus/documents/doc_fixture_1/sections"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[1].headingText").value("Bean Definitions"));

        mockMvc.perform(get("/api/admin/corpus/documents/doc_fixture_1/chunks"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[1].chunkId").value("chk_fixture_2"));

        mockMvc.perform(get("/api/admin/corpus/documents/doc_fixture_1/preview/raw-vs-cleaned"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.documentId").value("doc_fixture_1"))
                .andExpect(jsonPath("$.removedBoilerplateExcerpt").exists());

        mockMvc.perform(get("/api/admin/corpus/documents/doc_fixture_1/preview/chunk-boundaries"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.boundaries.length()").value(2))
                .andExpect(jsonPath("$.boundaries[0].startChar").value(0));

        mockMvc.perform(get("/api/admin/corpus/chunks/chk_fixture_1/neighbors"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].targetChunkId").value("chk_fixture_2"));
    }

    @Test
    void glossaryAliasEvidenceAndRunQueriesWork() throws Exception {
        mockMvc.perform(get("/api/admin/corpus/glossary/22222222-2222-2222-2222-222222222222"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.term.termId").value("22222222-2222-2222-2222-222222222222"))
                .andExpect(jsonPath("$.aliases.length()").value(2))
                .andExpect(jsonPath("$.aliases[0].aliasText").value("@value"));

        mockMvc.perform(get("/api/admin/corpus/glossary/22222222-2222-2222-2222-222222222222/evidence"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].chunkId").value("chk_fixture_2"));

        mockMvc.perform(get("/api/admin/corpus/glossary/preview/top-terms"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].canonicalForm").value("@Value"))
                .andExpect(jsonPath("$[0].provenanceSnippets.length()").value(2));

        mockMvc.perform(get("/api/admin/corpus/runs/11111111-1111-1111-1111-111111111111"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.run.runId").value("11111111-1111-1111-1111-111111111111"))
                .andExpect(jsonPath("$.steps.length()").value(2));
    }
}
