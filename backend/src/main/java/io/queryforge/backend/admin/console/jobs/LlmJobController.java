package io.queryforge.backend.admin.console.jobs;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/admin/console")
@RequiredArgsConstructor
public class LlmJobController {

    private final LlmJobService llmJobService;

    @GetMapping("/llm-jobs")
    public List<LlmJobDtos.LlmJobRow> llmJobs(
            @RequestParam(name = "limit", required = false) Integer limit
    ) {
        return llmJobService.listJobs(limit);
    }

    @GetMapping("/llm-jobs/{jobId}")
    public LlmJobDtos.LlmJobRow llmJob(@PathVariable UUID jobId) {
        return llmJobService.getJob(jobId);
    }

    @GetMapping("/llm-jobs/{jobId}/items")
    public List<LlmJobDtos.LlmJobItemRow> llmJobItems(@PathVariable UUID jobId) {
        return llmJobService.listJobItems(jobId);
    }

    @PostMapping("/llm-jobs/{jobId}/pause")
    public void pauseLlmJob(@PathVariable UUID jobId) {
        llmJobService.pauseJob(jobId);
    }

    @PostMapping("/llm-jobs/{jobId}/resume")
    public void resumeLlmJob(@PathVariable UUID jobId) {
        llmJobService.resumeJob(jobId);
    }

    @PostMapping("/llm-jobs/{jobId}/cancel")
    public void cancelLlmJob(@PathVariable UUID jobId) {
        llmJobService.cancelJob(jobId);
    }

    @PostMapping("/llm-jobs/{jobId}/retry")
    public void retryLlmJob(@PathVariable UUID jobId) {
        llmJobService.retryJob(jobId);
    }
}
