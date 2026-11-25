package io.queryforge.backend.admin.domain.workspace;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class DomainWorkspaceIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres")
    );

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

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void workspaceRoutesReturnIdentityMembershipAndPolicyAfterMutations() throws Exception {
        String domainKey = "workspace-contract-test";
        String sourceId = "workspace-contract-source";
        MvcResult result = mockMvc.perform(post("/api/admin/domains")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "domainKey": "workspace-contract-test",
                                  "displayName": "Workspace Contract",
                                  "primaryLanguage": "ko",
                                  "sourceLanguage": "en",
                                  "metadata": {"category": "technical"}
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.domain.domainKey").value(domainKey))
                .andExpect(jsonPath("$.sources").isEmpty())
                .andExpect(jsonPath("$.methodPolicies").isEmpty())
                .andReturn();
        UUID domainId = UUID.fromString(objectMapper.readTree(result.getResponse().getContentAsString())
                .at("/domain/domainId").asText());

        jdbcTemplate.update("""
                INSERT INTO corpus_sources (source_id, source_type, product_name, source_name, base_url)
                VALUES (?, 'html', 'Workspace Contract', 'Workspace Source', 'https://example.com/docs')
                """, sourceId);
        jdbcTemplate.update("""
                INSERT INTO tech_doc_domain_method_policy
                    (domain_id, method_code, default_query_language, metadata_json)
                VALUES (?, 'A', 'ko', '{"origin":"workspace-test"}'::jsonb)
                """, domainId);

        mockMvc.perform(get("/api/admin/domains"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].domainKey", hasItem(domainKey)));

        mockMvc.perform(patch("/api/admin/domains/{domainRef}", domainKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"displayName": "Updated Workspace"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.domain.displayName").value("Updated Workspace"))
                .andExpect(jsonPath("$.domain.metadata.category").value("technical"))
                .andExpect(jsonPath("$.methodPolicies[0].metadata.origin").value("workspace-test"));

        mockMvc.perform(post("/api/admin/domains/{domainRef}/sources", domainId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"sourceId": "workspace-contract-source"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.domain.sourceCount").value(1))
                .andExpect(jsonPath("$.sources[0].sourceId").value(sourceId))
                .andExpect(jsonPath("$.sources[0].sourceRole").value("primary"))
                .andExpect(jsonPath("$.methodPolicies[0].methodCode").value("A"));
        assertThat(sourceDomain(sourceId)).isEqualTo(domainId);

        mockMvc.perform(get("/api/admin/domains/{domainRef}", domainKey))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.domain.domainId").value(domainId.toString()))
                .andExpect(jsonPath("$.sources[0].active").value(true))
                .andExpect(jsonPath("$.methodPolicies[0].defaultQueryLanguage").value("ko"));

        mockMvc.perform(get("/api/admin/domains/{domainRef}/summary", domainKey))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.domainId").value(domainId.toString()))
                .andExpect(jsonPath("$.sourceCount").value(1));

        mockMvc.perform(delete("/api/admin/domains/{domainRef}/sources/{sourceId}", domainKey, sourceId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.domain.sourceCount").value(0))
                .andExpect(jsonPath("$.sources").isEmpty())
                .andExpect(jsonPath("$.methodPolicies[0].methodCode").value("A"));
        assertThat(sourceDomain(sourceId)).isNull();
    }

    private UUID sourceDomain(String sourceId) {
        return jdbcTemplate.queryForObject(
                "SELECT domain_id FROM corpus_sources WHERE source_id = ?",
                UUID.class,
                sourceId
        );
    }
}
