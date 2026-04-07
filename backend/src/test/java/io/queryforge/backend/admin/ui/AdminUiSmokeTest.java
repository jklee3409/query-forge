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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
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
    void adminPagesRenderAndLegacyRoutesRedirect() throws Exception {
        mockMvc.perform(get("/admin"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/pipeline"));

        mockMvc.perform(get("/admin/pipeline"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("admin-shell")))
                .andExpect(content().string(containsString("문서 파이프라인 관리")));

        mockMvc.perform(get("/admin/synthetic-queries"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("합성 질의 생성/조회")));

        mockMvc.perform(get("/admin/quality-gating"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("퀄리티 게이팅 관리")));

        mockMvc.perform(get("/admin/rag-tests"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("RAG 성능/품질 테스트")));

        mockMvc.perform(get("/admin/documents"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/pipeline"));

        mockMvc.perform(get("/admin/sources"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/pipeline"));

        mockMvc.perform(get("/admin/ingest-wizard"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/pipeline"));
    }
}

