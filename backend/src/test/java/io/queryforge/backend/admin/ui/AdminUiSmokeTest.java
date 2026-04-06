package io.queryforge.backend.admin.ui;

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

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
@Sql(scripts = "/sql/corpus_cleanup.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
@Sql(scripts = "/sql/corpus_admin_fixture.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
@Sql(scripts = "/sql/corpus_cleanup.sql", executionPhase = Sql.ExecutionPhase.AFTER_TEST_METHOD)
class AdminUiSmokeTest {

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
    void majorAdminPagesRenderSharedLayout() throws Exception {
        mockMvc.perform(get("/admin"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("admin-shell")))
                .andExpect(content().string(containsString("admin-sidebar")))
                .andExpect(content().string(containsString("대시보드 Dashboard")));

        mockMvc.perform(get("/admin/documents"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("문서 Documents")))
                .andExpect(content().string(containsString("filter-bar")));

        mockMvc.perform(get("/admin/documents/doc_fixture_1"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Raw vs Cleaned")))
                .andExpect(content().string(containsString("Sections & Chunks")))
                .andExpect(content().string(containsString("Run History")));

        mockMvc.perform(get("/admin/chunks/chk_fixture_1"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Prev / Current / Next")))
                .andExpect(content().string(containsString("Relation List")));

        mockMvc.perform(get("/admin/runs/11111111-1111-1111-1111-111111111111"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Step 상태 / 소요시간")))
                .andExpect(content().string(containsString("Config Snapshot")));

        mockMvc.perform(get("/admin/glossary/22222222-2222-2222-2222-222222222222"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Term Policy")))
                .andExpect(content().string(containsString("Evidence Snippets")));

        mockMvc.perform(get("/admin/ingest-wizard"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("실행 마법사 Ingest Wizard")))
                .andExpect(content().string(containsString("전체 실행 Full Ingest")));
    }
}
