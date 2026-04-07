package io.queryforge.backend.admin.pipeline.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProjectPathResolverTest {

    @TempDir
    Path tempDir;

    @Test
    void findsRepoRootFromNestedBackendDirectory() throws IOException {
        Path repoRoot = createRepoFixture();
        Path backendBuildDir = Files.createDirectories(repoRoot.resolve("backend/build/classes/java/main"));

        Path resolved = ProjectPathResolver.findRepoRoot(backendBuildDir).orElseThrow();

        assertThat(resolved).isEqualTo(repoRoot);
    }

    @Test
    void resolveWithinRepoRejectsPathsOutsideRepo() throws IOException {
        Path repoRoot = createRepoFixture();
        Path outsidePath = tempDir.resolve("outside").resolve("spring_docs_raw.jsonl").toAbsolutePath().normalize();

        assertThatThrownBy(() -> ProjectPathResolver.resolveWithinRepo(repoRoot, outsidePath.toString(), "raw output artifact"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must stay within repo root");
    }

    @Test
    void resolveWithinRepoKeepsRelativeDataPathsUnderRepo() throws IOException {
        Path repoRoot = createRepoFixture();

        Path resolved = ProjectPathResolver.resolveWithinRepo(repoRoot, "data/raw/spring_docs_raw.jsonl", "raw output artifact");

        assertThat(resolved).isEqualTo(repoRoot.resolve("data/raw/spring_docs_raw.jsonl"));
    }

    private Path createRepoFixture() throws IOException {
        Path repoRoot = Files.createDirectories(tempDir.resolve("query-forge"));
        Files.createDirectories(repoRoot.resolve("pipeline"));
        Files.writeString(repoRoot.resolve("pipeline/cli.py"), "print('ok')\n");
        Files.createDirectories(repoRoot.resolve("backend"));
        Files.writeString(repoRoot.resolve("backend/settings.gradle"), "rootProject.name = 'query-forge-backend'\n");
        return repoRoot.toAbsolutePath().normalize();
    }
}
