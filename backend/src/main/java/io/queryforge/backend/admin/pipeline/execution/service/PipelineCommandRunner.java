package io.queryforge.backend.admin.pipeline.execution.service;

import io.queryforge.backend.admin.pipeline.execution.dto.PipelineExecutionDtos.CommandRequest;
import io.queryforge.backend.admin.pipeline.execution.dto.PipelineExecutionDtos.CommandResult;

import java.io.IOException;

public interface PipelineCommandRunner {

    CommandResult run(CommandRequest request, ProcessObserver observer) throws IOException, InterruptedException;

    boolean cancel(ProcessHandle processHandle);

    @FunctionalInterface
    interface ProcessObserver {
        void onStart(ProcessHandle processHandle);
    }
}
