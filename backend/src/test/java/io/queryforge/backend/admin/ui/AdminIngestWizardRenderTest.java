package io.queryforge.backend.admin.ui;

import io.queryforge.backend.admin.ui.controller.AdminUiController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = AdminUiController.class)
class AdminIngestWizardRenderTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void ingestWizardRedirectsToPipeline() throws Exception {
        mockMvc.perform(get("/admin/ingest-wizard"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/pipeline"));
    }
}

