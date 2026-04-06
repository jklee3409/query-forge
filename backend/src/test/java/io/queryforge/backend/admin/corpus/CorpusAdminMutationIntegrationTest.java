package io.queryforge.backend.admin.corpus;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

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
}
