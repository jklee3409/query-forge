package io.queryforge.backend.admin.ui;

import io.queryforge.backend.admin.corpus.service.CorpusAdminService;
import io.queryforge.backend.admin.pipeline.config.AdminPipelineProperties;
import io.queryforge.backend.admin.pipeline.service.PipelineAdminService;
import io.queryforge.backend.admin.ui.controller.AdminUiController;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = AdminUiController.class)
@Import(AdminIngestWizardRenderTest.TestConfig.class)
class AdminIngestWizardRenderTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private PipelineAdminService pipelineAdminService;

    @MockBean
    private CorpusAdminService corpusAdminService;

    @BeforeEach
    void setUp() {
        when(corpusAdminService.listSources()).thenReturn(List.of());
    }

    @Test
    void ingestWizardRendersWithoutServerError() throws Exception {
        mockMvc.perform(get("/admin/ingest-wizard"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("실행 안내")))
                .andExpect(content().string(containsString("문서 분류/저장 구조")))
                .andExpect(content().string(containsString("파이프라인 로그 경로")));
    }

    @TestConfiguration
    static class TestConfig {
        @Bean
        AdminPipelineProperties adminPipelineProperties() {
            return new AdminPipelineProperties(
                    ".",
                    "python",
                    "data/logs/admin-pipeline",
                    "data/artifacts/corpus-docs",
                    "configs/app/sources",
                    "configs/app/chunking.yml",
                    "data/raw/spring_docs_raw.jsonl",
                    "data/processed/spring_docs_sections.jsonl",
                    "data/processed/chunks.jsonl",
                    "data/processed/glossary_terms.jsonl",
                    "data/processed/chunk_neighbors.sql",
                    "data/processed/chunking_visualization.md",
                    12000
            );
        }
    }
}
