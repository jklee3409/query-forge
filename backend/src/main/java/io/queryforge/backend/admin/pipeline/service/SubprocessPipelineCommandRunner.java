package io.queryforge.backend.admin.pipeline.service;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;

@Component
public class SubprocessPipelineCommandRunner implements PipelineCommandRunner {

    @Override
    public CommandResult run(CommandRequest request, ProcessObserver observer) throws IOException, InterruptedException {
        Files.createDirectories(request.stdoutPath().getParent());
        Files.createDirectories(request.stderrPath().getParent());

        ProcessBuilder processBuilder = new ProcessBuilder(request.command())
                .directory(request.workingDirectory().toFile())
                .redirectOutput(request.stdoutPath().toFile())
                .redirectError(request.stderrPath().toFile());

        Process process = processBuilder.start();
        observer.onStart(process.toHandle());
        int exitCode = process.waitFor();

        String stdout = Files.exists(request.stdoutPath())
                ? Files.readString(request.stdoutPath(), StandardCharsets.UTF_8)
                : "";
        String stderr = Files.exists(request.stderrPath())
                ? Files.readString(request.stderrPath(), StandardCharsets.UTF_8)
                : "";

        return new CommandResult(
                exitCode,
                request.command(),
                stdout,
                stderr,
                request.stdoutPath(),
                request.stderrPath(),
                process.toHandle(),
                Instant.now()
        );
    }

    @Override
    public boolean cancel(ProcessHandle processHandle) {
        if (processHandle == null || !processHandle.isAlive()) {
            return false;
        }
        processHandle.destroy();
        try {
            processHandle.onExit().join();
        } catch (Exception ignored) {
            processHandle.destroyForcibly();
        }
        return true;
    }
}
