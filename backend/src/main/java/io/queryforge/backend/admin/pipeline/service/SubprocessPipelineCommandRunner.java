package io.queryforge.backend.admin.pipeline.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;

@Component
public class SubprocessPipelineCommandRunner implements PipelineCommandRunner {

    private static final Logger LOGGER = LoggerFactory.getLogger(SubprocessPipelineCommandRunner.class);

    @Override
    public CommandResult run(CommandRequest request, ProcessObserver observer) throws IOException, InterruptedException {
        Files.createDirectories(request.stdoutPath().getParent());
        Files.createDirectories(request.stderrPath().getParent());
        Files.writeString(request.stdoutPath(), "", StandardCharsets.UTF_8);
        Files.writeString(request.stderrPath(), "", StandardCharsets.UTF_8);

        ProcessBuilder processBuilder = new ProcessBuilder(request.command())
                .directory(request.workingDirectory().toFile());

        Process process = processBuilder.start();
        observer.onStart(process.toHandle());
        String runId = request.stdoutPath().getParent() != null
                ? request.stdoutPath().getParent().getFileName().toString()
                : "unknown-run";
        String stepName = request.stdoutPath().getFileName().toString().replace(".stdout.log", "");
        String logPrefix = "[run=" + runId + " step=" + stepName + "]";
        LOGGER.info("{} command started: {}", logPrefix, String.join(" ", request.command()));

        CompletableFuture<Void> stdoutPump = pumpStream(
                process.getInputStream(),
                request.stdoutPath(),
                "stdout",
                logPrefix
        );
        CompletableFuture<Void> stderrPump = pumpStream(
                process.getErrorStream(),
                request.stderrPath(),
                "stderr",
                logPrefix
        );

        int exitCode = process.waitFor();
        waitPump(stdoutPump);
        waitPump(stderrPump);
        LOGGER.info("{} command finished with exitCode={}", logPrefix, exitCode);

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

    private CompletableFuture<Void> pumpStream(
            InputStream stream,
            java.nio.file.Path targetPath,
            String channel,
            String logPrefix
    ) {
        return CompletableFuture.runAsync(() -> {
            try (
                    BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8));
                    BufferedWriter writer = Files.newBufferedWriter(targetPath, StandardCharsets.UTF_8)
            ) {
                String line;
                while ((line = reader.readLine()) != null) {
                    writer.write(line);
                    writer.newLine();
                    writer.flush();
                    if (line.isBlank()) {
                        continue;
                    }
                    if ("stderr".equals(channel)) {
                        LOGGER.warn("{} [{}] {}", logPrefix, channel, line);
                    } else {
                        LOGGER.info("{} [{}] {}", logPrefix, channel, line);
                    }
                }
            } catch (IOException exception) {
                LOGGER.warn("{} failed to stream {}: {}", logPrefix, channel, exception.getMessage());
            }
        });
    }

    private void waitPump(CompletableFuture<Void> future) throws InterruptedException {
        try {
            future.join();
        } catch (Exception exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof InterruptedException interruptedException) {
                throw interruptedException;
            }
        }
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
