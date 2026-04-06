package io.queryforge.backend.admin.pipeline.service;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

public interface PipelineCommandRunner {

    CommandResult run(CommandRequest request, ProcessObserver observer) throws IOException, InterruptedException;

    boolean cancel(ProcessHandle processHandle);

    record CommandRequest(
            List<String> command,
            Path workingDirectory,
            Path stdoutPath,
            Path stderrPath
    ) {
    }

    record CommandResult(
            int exitCode,
            List<String> command,
            String stdout,
            String stderr,
            Path stdoutPath,
            Path stderrPath,
            ProcessHandle processHandle,
            Instant finishedAt
    ) {
    }

    @FunctionalInterface
    interface ProcessObserver {
        void onStart(ProcessHandle processHandle);
    }
}
